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
import org.avventomedia.app.telefyna.Utils
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.modal.Playlist
import java.util.Calendar

class Maintenance {
    companion object {
        private const val MIN_PLAUSIBLE_YEAR = 2025
    }

    private var startedSlotsToday: MutableMap<String, CurrentPlaylist> = HashMap()
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
            startedSlotsToday = HashMap()
            pendingIntents = HashMap()
            Logger.log(AuditLog.Event.MAINTENANCE)
            schedule()
            // Metrics do network calls; keep them off the main thread and out of the scheduling path
            Thread {
                Logger.log(AuditLog.Event.METRICS, Metrics.retrieve())
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
        val config = Monitor.instance?.configuration

        config?.let {
            val basePlaylists = it.playlists ?: emptyArray()
            val schedules = it.schedules ?: emptyArray()
            // start time -> name of the slot holding it and whether that slot is booked for today's date
            val starts = mutableMapOf<String, Pair<String, Boolean>>()

            // Build a map of playlists by ID for O(1) lookup, and store their index for state saving
            val playlistMap = mutableMapOf<String, Pair<Int, Playlist>>()
            
            val newPlaylists = mutableListOf<Playlist>()
            var monitorIndex = 0
            // Base playlists are also added to Monitor's list (for default fallback etc)
            basePlaylists.forEachIndexed { index, playlist ->
                if (playlist.id != null) {
                    playlistMap[playlist.id!!] = Pair(monitorIndex, playlist)
                }
                newPlaylists.add(playlist)
                monitorIndex++
            }

            schedules.forEachIndexed { index, schedule ->
                val (baseIndex, basePlaylist) = playlistMap[schedule.playlistId] ?: return@forEachIndexed
                // Create an in-memory Playlist instance to represent this schedule
                val scheduledPlaylist = basePlaylist.copy().apply {
                    this.start = schedule.start
                    this.days = schedule.days
                    this.dates = schedule.dates
                    this.active = this.active && schedule.active
                    this.schedule = baseIndex // Link to base playlist index for state saving
                    this.id = "sched_${index}"
                    if (schedule.name != null) this.name = schedule.name
                    if (schedule.type != null) this.type = schedule.type!!
                    if (schedule.color != null) this.color = schedule.color
                    if (schedule.graphics != null) this.graphics = schedule.graphics
                    if (schedule.emptyReplacer != null) this.emptyReplacer = schedule.emptyReplacer
                    if (schedule.seekTo != null) this.seekTo = schedule.seekTo!!
                }
                
                newPlaylists.add(scheduledPlaylist)

                if (scheduledPlaylist.scheduledToday()) {
                    schedulePlaylistAtStart(scheduledPlaylist, monitorIndex, starts)
                }
                monitorIndex++
            }
            Monitor.instance?.replacePlaylists(newPlaylists)
            playCurrentSlot()
        }
    }

    @OptIn(UnstableApi::class)
    fun retrievePrograms(playlist: Playlist?): List<MediaItem> {
        val programs = mutableListOf<MediaItem>()
        playlist?.let {
            if (it.type == Playlist.Type.ONLINE) {
                // HLS, DASH, RTSP, RTMP and SRT are all resolved by the player's SchemeRoutingDataSource
                it.urlOrFolder?.trim()?.let { url -> programs.add(MediaItem.fromUri(url)) }
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

    @OptIn(UnstableApi::class)
    private fun schedulePlaylistAtStart(playlist: Playlist, index: Int, starts: MutableMap<String, Pair<String, Boolean>>) {
        val start = playlist.start
        val dated = playlist.scheduledByDateToday()
        val existing = start?.let { starts[it] }
        if (start != null && existing != null) {
            // Two slots at the same time: one booked for today's date (special event) beats a weekly/daily one,
            // whatever their order in the config; otherwise the later entry replaces the earlier one
            val (existingName, existingDated) = existing
            if (existingDated && !dated) {
                Logger.log(AuditLog.Event.SCHEDULE_CLASH, start, existingName, playlist.name ?: "")
                return
            }
            Logger.log(AuditLog.Event.SCHEDULE_CLASH, start, playlist.name ?: "", existingName)
            pendingIntents[start]?.let { operation ->
                Monitor.instance?.alarmManager?.cancel(operation)
            }
            startedSlotsToday.remove(start)
        }
        schedulePlayList(playlist, index)
        if (start != null) {
            starts[start] = Pair(playlist.name ?: "", dated)
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    private fun playCurrentSlot() {
        if (startedSlotsToday.isNotEmpty()) {
            // Latest started slot wins; compare as minutes ("9:00" sorts after "10:00" as a string)
            val slots = startedSlotsToday.keys.toList().sortedByDescending { startMinutes(it) }
            val currentPlaylist = startedSlotsToday[slots[0]]
            // isCurrentSlot should only be true here
            Monitor.instance?.switchNow(currentPlaylist?.index ?: 0, true, Monitor.instance!!)
        } else if (Monitor.instance?.isPlayingScheduledSlot() == true) {
            // Midnight maintenance before today's first slot: let yesterday's programme run to its end
            // (finite playlists hand over to fillers on completion) instead of cutting it at 00:00
            Logger.log(AuditLog.Event.PLAYLIST_CARRY_OVER, Monitor.instance!!.getNowPlayingLabel())
        } else { // Play first default
            Monitor.instance?.let { Monitor.instance!!.switchNow(it.getFirstDefaultIndex(), false, Monitor.instance!!) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun schedulePlayList(playlist: Playlist, index: Int) {
        val start = playlist.start ?: "default_start"
        if (playlist.isStarted()) {
            startedSlotsToday[start] = CurrentPlaylist(index, playlist)
        } else {
            val intent = Intent(Monitor.instance, PlaylistScheduler::class.java).apply {
                putExtra(PlaylistScheduler.PLAYLIST_INDEX, index)
            }
            playlist.start?.let { schedule(intent, playlist.getScheduledTime(), it, index) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun schedule(intent: Intent, millis: Long, start: String, index: Int) {
        val alarmPendingIntent = PendingIntent.getBroadcast(Monitor.instance, index, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        pendingIntents[start] = alarmPendingIntent
        Monitor.instance?.alarmManager?.let { Utils.setExactAlarm(it, millis, alarmPendingIntent) }
    }

    private fun startMinutes(start: String): Int {
        val parts = start.split(":")
        return (parts[0].trim().toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
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