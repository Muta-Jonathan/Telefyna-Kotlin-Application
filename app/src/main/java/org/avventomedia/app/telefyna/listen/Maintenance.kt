package org.avventomedia.app.telefyna.listen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.avventomedia.app.telefyna.Metrics
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.Utils
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.modal.Playlist
import org.avventomedia.app.telefyna.player.SrtDataSourceFactory
import org.avventomedia.app.telefyna.player.TsOnlyExtractorFactory
import java.util.Calendar

class Maintenance {
    companion object {
        private const val QUERY_PARAM_PASSCODE = "passcode"
    }

    private var startedSlotsToday: MutableMap<String, CurrentPlaylist> = HashMap()
    private var pendingIntents: MutableMap<String, PendingIntent> = HashMap()
    private var maintenanceJob: Job? = null

    /**
     * Called when Telefyna is launched and every day at midnight
     */
    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerMaintenance() {
        cancelPendingIntents()
        Monitor.instance?.initialise()
        // Switch to firstDefault when automation is turned off
        if (Monitor.instance?.configuration?.isAutomationDisabled == true) {
            val defaultIndex = Monitor.instance!!.getFirstDefaultIndex()
            val playlist = Monitor.instance?.configuration?.playlists?.get(defaultIndex)
            playlist?.let { Monitor.instance!!.addPlayListByIndex(it) }
            Monitor.instance!!.switchNow(defaultIndex, false, Monitor.instance!!)
        } else {
            startedSlotsToday = HashMap()
            pendingIntents = HashMap()
            Logger.log(AuditLog.Event.METRICS, Metrics.retrieve())
            Logger.log(AuditLog.Event.MAINTENANCE)
            schedule()
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

    val maintenanceRunnable = object : Runnable {
        @OptIn(UnstableApi::class)
        @RequiresApi(Build.VERSION_CODES.O)
        override fun run() {
            // Cancel any existing maintenance job
            maintenanceJob?.cancel()

            // Start the maintenance process
            maintenanceJob = Monitor.instance?.lifecycleScope?.launch {
                triggerMaintenance()
                // Schedule the next execution
                while (isActive) {  // Continuously run until the coroutine is canceled
                    delay(getMillisToMaintenanceTime())  // Use delay instead of postDelayed
                    triggerMaintenance()
                }
            }
        }
    }


    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    fun run() {
        // Cancel any existing maintenance job
        maintenanceJob?.cancel()

        // Start the maintenance process
        maintenanceJob = Monitor.instance?.lifecycleScope?.launch {
            // This may cause a null pointer exception if config is not available and restarts it
            triggerMaintenance()
            Logger.log(AuditLog.Event.HEARTBEAT, "ON")

            // Schedule the next execution
            while (isActive) {  // Continuously run until the coroutine is canceled
                delay(getMillisToMaintenanceTime())  // Use delay instead of postDelayed
                triggerMaintenance()
            }
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    private fun schedule() {
        val config = Monitor.instance?.configuration

        config?.let {
            val playlists = it.playlists
            val starts = mutableListOf<String>()
            playlists?.forEachIndexed { index, playlist ->
                playlist.schedule?.let { scheduleIndex ->
                    playlist.schedule(playlists[scheduleIndex])
                }

                if (playlist.scheduledToday()) {
                    schedulePlaylistAtStart(playlist, index, starts)
                }
                Monitor.instance?.addPlayListByIndex(playlist)
            }
            playCurrentSlot()
        }
    }

    @OptIn(UnstableApi::class)
    fun retrievePrograms(playlist: Playlist?): List<MediaItem> {
        val programs = mutableListOf<MediaItem>()
        playlist?.let {
            if (it.type == Playlist.Type.ONLINE) {
                when {
                    it.urlOrFolder?.contains("rtmp://") == true -> {
                        // Handle RTMP with custom media source
                        val mediaSource = getRtmpSource(Uri.parse(it.urlOrFolder))
                        programs.add(mediaSource.mediaItem)
                    }
                    it.urlOrFolder?.contains("srt://") == true -> {
                        // Handle SRT
                        val srtUrl = Uri.parse(it.urlOrFolder)
                        val passcode = srtUrl.getQueryParameter(QUERY_PARAM_PASSCODE)

                        // Create MediaItem with custom cache key (passcode if present)
                        val mediaItemBuilder = MediaItem.Builder()
                            .setUri(srtUrl)
                        // Set custom cache key if passcode is present
                        if (!passcode.isNullOrEmpty()) {
                            mediaItemBuilder.setCustomCacheKey(passcode) // Use passcode as custom cache key
                        }

                        val mediaItem = mediaItemBuilder.build()

                        // Create SRT source and add to programs
                        val mediaSource = getSrtSource(mediaItem)
                        programs.add(mediaSource.mediaItem)
                    }
                    else -> {
                        // Handle HLS, RTSP, and Smooth Streaming (default behavior)
                        it.urlOrFolder?.let { url ->
                            MediaItem.fromUri(url).let { mediaItem ->
                                programs.add(mediaItem)
                            }
                        }
                    }
                }
            } else {
                it.urlOrFolder?.split("#")?.forEachIndexed { i, _ ->
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

    // Support Rtmp stream "rtmp//server:port" or "rtmp//server"
    @OptIn(UnstableApi::class)
    private fun getRtmpSource(uri: Uri): MediaSource {
        val rtmpDataSourceFactory = RtmpDataSource.Factory()
        return ProgressiveMediaSource.Factory(rtmpDataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri))
    }

    // Support srt stream "srt//server:port" or "srt//server"
    @OptIn(UnstableApi::class)
    private fun getSrtSource(mediaItem: MediaItem): MediaSource {
       return ProgressiveMediaSource.Factory(SrtDataSourceFactory(), TsOnlyExtractorFactory())
           .createMediaSource(mediaItem)
    }

    @OptIn(UnstableApi::class)
    private fun schedulePlaylistAtStart(playlist: Playlist, index: Int, starts: MutableList<String>) {
        // Was scheduled, remove existing playlist to reschedule a new later one
        val start = playlist.start
        if (starts.contains(start)) {
            pendingIntents[start]?.let { operation ->
                Monitor.instance?.alarmManager?.cancel(operation)
            }
            startedSlotsToday.remove(start)
            starts.remove(start)
        }
        schedulePlayList(playlist, index)
        if (start != null) {
            starts.add(start)
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    private fun playCurrentSlot() {
        if (startedSlotsToday.isNotEmpty()) {
            val slots = startedSlotsToday.keys.toList().sortedDescending()
            val currentPlaylist = startedSlotsToday[slots[0]]
            // isCurrentSlot should only be true here
            Monitor.instance?.switchNow(currentPlaylist?.index ?: 0, true, Monitor.instance!!)
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
        Monitor.instance?.alarmManager?.setExact(AlarmManager.RTC_WAKEUP, millis, alarmPendingIntent)
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