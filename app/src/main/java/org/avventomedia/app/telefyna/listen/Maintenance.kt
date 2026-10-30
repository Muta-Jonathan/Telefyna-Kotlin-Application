package org.avventomedia.app.telefyna.listen

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.avventomedia.app.telefyna.Metrics
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.Playout
import org.avventomedia.app.telefyna.ScheduleEngine
import org.avventomedia.app.telefyna.ScheduledSlot
import org.avventomedia.app.telefyna.Utils
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.modal.Playlist
import java.util.Calendar

class Maintenance {
    companion object {
        private const val MIN_PLAUSIBLE_YEAR = 2025
        // Slots are prepared this long before their start time and switched exactly on it
        const val PREROLL_MS = 15_000L
    }

    private var pendingIntents: MutableMap<String, PendingIntent> = HashMap()

    /**
     * Called when Telefyna is launched and every day at midnight
     */
    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    fun triggerMaintenance() {
        cancelPendingIntents()
        Monitor.instance?.initialise()
        val monitor = Monitor.instance ?: return
        if (monitor.configuration == null) {
            monitor.showSlate("No usable configuration")
            return
        }
        when (monitor.safeMode) {
            Monitor.SafeMode.SLATE_ONLY -> {
                monitor.showSlate("Safe mode: repeated crashes")
                return
            }
            Monitor.SafeMode.FILLERS_ONLY -> {
                // No schedules, alarms or graphics: just keep fillers on air until safe mode expires
                monitor.replacePlaylists(monitor.configuration?.playlists?.toList().orEmpty())
                monitor.switchNow(monitor.getSecondDefaultIndex(), false, monitor)
                return
            }
            Monitor.SafeMode.OFF -> {}
        }
        // Switch to firstDefault when automation is turned off
        if (Monitor.instance?.configuration?.isAutomationDisabled == true) {
            val defaultIndex = Monitor.instance!!.getFirstDefaultIndex()
            val playlist = Monitor.instance?.configuration?.playlists?.get(defaultIndex)
            Monitor.instance!!.replacePlaylists(listOfNotNull(playlist))
            Monitor.instance!!.switchNow(defaultIndex, false, Monitor.instance!!)
        } else {
            pendingIntents = HashMap()
            Logger.log(AuditLog.Event.MAINTENANCE)
            schedule()
            // Metrics do network calls; keep them off the main thread and out of the scheduling path
            Thread {
                Logger.log(AuditLog.Event.METRICS, Metrics.retrieve())
                // Measure new/changed media once, in the background, for slot joins and intelligent sorting
                Monitor.instance?.let { monitor ->
                    monitor.configuration?.playlists?.filter { it.type != Playlist.Type.ONLINE }?.forEach { playlist ->
                        playlist.urlOrFolder?.split(Utils.COMMA_SPLITTER)?.indices?.forEach { i ->
                            org.avventomedia.app.telefyna.DurationCacheManager.indexDirectory(monitor, monitor.getDirectoryFromPlaylist(playlist, i))
                        }
                    }
                }
                // A drive left in the box: import it now (mounting it later is caught by DriveMounted)
                Monitor.instance?.let { DriveImporter.importFromMountedDrives(it) }
                // Reports for the week/month/year that just ended, built before old logs are pruned
                Monitor.instance?.generateReports(org.avventomedia.app.telefyna.audit.ReportPeriod.endedBy(System.currentTimeMillis()), toDate = false)
                // Old daily logs are pruned off the main thread too
                Monitor.instance?.let { monitor ->
                    val keepDays = monitor.configuration?.auditRetentionDays ?: 0
                    val auditDir = java.io.File(monitor.getAuditFilePath(monitor, ""))
                    val deleted = Logger.pruneOldLogs(auditDir, keepDays)
                    if (deleted.isNotEmpty()) {
                        Logger.log(AuditLog.Event.MAINTENANCE_CLEANUP, deleted.size, keepDays)
                    }
                }
            }.start()
        }
    }

    @OptIn(UnstableApi::class)
    fun cancelPendingIntents() {
        Monitor.instance?.cancelPendingSlotCommits()
        if (pendingIntents.isNotEmpty()) {
            for (intent in pendingIntents.values) { // TODO test
                Monitor.instance?.alarmManager?.cancel(intent)
            }
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    fun scheduleNextMaintenance() {
        val millisToMaintenance = getMillisToMaintenanceTime()
        val monitorInstance = Monitor.instance ?: return
        val intent = Intent(monitorInstance, MaintenanceReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            monitorInstance, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        monitorInstance.alarmManager?.let {
            Utils.setExactAlarm(it, System.currentTimeMillis() + millisToMaintenance, pendingIntent)
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    fun run() {
        Monitor.instance?.let {
            DateChange.rememberClock(it)
            if (Calendar.getInstance()[Calendar.YEAR] < MIN_PLAUSIBLE_YEAR) {
                // RTC-less box before NTP sync; DateChange reschedules once the clock is corrected
                Logger.log(AuditLog.Event.ERROR, "Device clock looks unset: ${Calendar.getInstance().time}")
            }
        }
        // This may cause a null pointer exception if config is not available and restarts it
        triggerMaintenance()
        Logger.log(AuditLog.Event.HEARTBEAT, "ON")
        scheduleNextMaintenance()
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    private fun schedule() {
        val monitor = Monitor.instance ?: return
        val config = monitor.configuration ?: return
        val now = Calendar.getInstance()
        val plan = ScheduleEngine.plan(config, now)
        plan.clashes.forEach { Logger.log(AuditLog.Event.SCHEDULE_CLASH, it.start, it.kept, it.dropped) }
        monitor.replacePlaylists(plan.playlists)
        val minutes = ScheduleEngine.minutesOf(now)
        plan.upcoming(minutes).forEach { scheduleSlot(monitor, it) }
        playCurrentSlot(monitor, plan.current(minutes))
    }

    @OptIn(UnstableApi::class)
    fun retrievePrograms(playlist: Playlist?): List<MediaItem> {
        val programs = mutableListOf<MediaItem>()
        playlist?.let {
            if (it.type == Playlist.Type.ONLINE) {
                // HLS, DASH, RTSP, RTMP and SRT are all resolved by the player's SchemeRoutingDataSource
                it.urlOrFolder?.trim()?.let { url -> programs.add(MediaItem.fromUri(url)) }
            } else if (it.type == Playlist.Type.LOCAL_RANDOMIZED_ONE && retrieveOneRandomFolder(it, programs)) {
                // one random sub-folder, in order
            } else {
                it.urlOrFolder?.split(Utils.COMMA_SPLITTER)?.forEachIndexed { i, _ ->
                    val pgms = mutableListOf<MediaItem>()
                    val localPlaylistFolder = Monitor.instance?.getDirectoryFromPlaylist(it, i)
                    if (localPlaylistFolder != null) {
                        if (localPlaylistFolder.exists() && localPlaylistFolder.listFiles()?.isNotEmpty() == true) {
                            val addedFirstItem = false
                            Utils.setupLocalPrograms(pgms, localPlaylistFolder, addedFirstItem, it)
                            programs.addAll(pgms)
                        }
                    }
                }
            }
        }
        return programs
    }

    /**
     * LOCAL_RANDOMIZED_ONE: picks one random sub-folder (not the same as last time when there is a
     * choice) and adds its files in alphabetical order. Returns false when there are no sub-folders with media,
     * so the caller falls back to shuffling all files.
     */
    @OptIn(UnstableApi::class)
    private fun retrieveOneRandomFolder(playlist: Playlist, programs: MutableList<MediaItem>): Boolean {
        val monitor = Monitor.instance ?: return false
        val roots = playlist.urlOrFolder?.split(Utils.COMMA_SPLITTER)?.indices?.map { monitor.getDirectoryFromPlaylist(playlist, it) }.orEmpty()
        val folders = Utils.programFolders(roots)
        val prefs = monitor.getSharedPreferences(Monitor.PREFERENCES, android.content.Context.MODE_PRIVATE)
        val key = "RANDOM_FOLDER_${playlist.urlOrFolder}"
        val picked = Utils.pickFolder(folders, prefs.getString(key, null)) ?: return false
        prefs.edit().putString(key, picked.absolutePath).apply()
        Utils.playableFiles(picked).forEach { file ->
            val uri = android.net.Uri.fromFile(file)
            programs.add(MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build())
        }
        Logger.log(AuditLog.Event.RANDOM_FOLDER_PICKED, playlist.name ?: "", picked.name, folders.size)
        return true
    }

    /**
     * Arms the slot's alarm PREROLL_MS early: Monitor.prepareSlot warms it up and commits the switch at the exact
     * start time, rather than doing all the work at the moment an alarm (which a TV box may deliver late) arrives.
     */
    @OptIn(UnstableApi::class)
    private fun scheduleSlot(monitor: Monitor, slot: ScheduledSlot) {
        val startAt = slot.playlist.getScheduledTime()
        val intent = Intent(monitor, PlaylistScheduler::class.java).apply {
            putExtra(PlaylistScheduler.PLAYLIST_INDEX, slot.index)
            putExtra(PlaylistScheduler.START_AT, startAt)
        }
        val operation = PendingIntent.getBroadcast(monitor, slot.index, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        pendingIntents[slot.start] = operation
        val fireAt = maxOf(startAt - PREROLL_MS, System.currentTimeMillis() + 500)
        monitor.alarmManager?.let { Utils.setExactAlarm(it, fireAt, operation) }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    private fun playCurrentSlot(monitor: Monitor, current: ScheduledSlot?) {
        val reattached = monitor.consumeReattach()
        when {
            current != null -> monitor.switchNow(current.index, true, monitor)
            // A new screen joined a playout that kept running: keep what is on air (only a due slot interrupts it)
            reattached && Playout.isOnAir -> monitor.redrawGraphics()
            // Midnight maintenance before today's first slot: let yesterday's programme run to its end
            // (finite playlists hand over to fillers on completion) instead of cutting it at 00:00
            monitor.isPlayingScheduledSlot() -> Logger.log(AuditLog.Event.PLAYLIST_CARRY_OVER, monitor.getNowPlayingLabel())
            else -> monitor.switchNow(monitor.getFirstDefaultIndex(), false, monitor)
        }
    }

    /*
     * Maintenance time is currently set to midnight
     */
    private fun getMillisToMaintenanceTime(): Long {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis - System.currentTimeMillis()
    }

    // TODO and use fix
    @OptIn(UnstableApi::class)
    private fun isSupportedImageAudioOrVideo(url: String): Boolean {
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
        return MimeTypes.isAudio(type) || MimeTypes.isVideo(type)
    }
}