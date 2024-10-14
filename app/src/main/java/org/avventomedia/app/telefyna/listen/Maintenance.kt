package org.avventomedia.app.telefyna.listen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.avventomedia.app.telefyna.Metrics
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.Utils
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.modal.Playlist
import java.util.Calendar

class Maintenance {

    private var startedSlotsToday: MutableMap<String, CurrentPlaylist> = HashMap()
    private var pendingIntents: MutableMap<String, PendingIntent> = HashMap()

    /**
     * Called when Telefyna is launched and every day at midnight
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerMaintenance() {
        cancelPendingIntents()
        Monitor.instance?.initialise()
        // Switch to firstDefault when automation is turned off
        if (Monitor.instance?.configuration?.isAutomationDisabled == true) {
            val defaultIndex = Monitor.instance!!.getFirstDefaultIndex()
            val playlist = Monitor.instance?.configuration?.playlists?.get(defaultIndex)
            playlist?.let { Monitor.instance!!.addPlayListByIndex(it) }
            Monitor.instance!!.switchNow(defaultIndex, false)
        } else {
            startedSlotsToday = HashMap()
            pendingIntents = HashMap()
            Logger.log(AuditLog.Event.METRICS, Metrics.retrieve())
            Logger.log(AuditLog.Event.MAINTENANCE)
            schedule()
        }
    }

    fun cancelPendingIntents() {
        if (pendingIntents.isNotEmpty()) {
            for (intent in pendingIntents.values) { // TODO test
                Monitor.instance?.alarmManager?.cancel(intent)
            }
        }
    }

    val maintenanceRunnable = object : Runnable {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun run() {
            triggerMaintenance() // Your method call
            Monitor.instance?.maintenanceHandler?.postDelayed(this, getMillisToMaintenanceTime()) // Schedule the next execution
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    fun run() {
        // This may cause a null pointer exception if config is not available and restarts it
        triggerMaintenance()
        Logger.log(AuditLog.Event.HEARTBEAT, "ON")
        Monitor.instance?.maintenanceHandler?.removeCallbacksAndMessages(null)
        // Start the maintenance process
        Monitor.instance?.maintenanceHandler?.postDelayed(maintenanceRunnable, getMillisToMaintenanceTime())
    }

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

    fun retrievePrograms(playlist: Playlist?): List<MediaItem> {
        val programs = mutableListOf<MediaItem>()
        playlist?.let {
            if (it.type == Playlist.Type.ONLINE) {
//                if
//                        (it.urlOrFolder?.contains("rtmp//") == true) {
//                    val mediaSource = getRtmpSource(Uri.parse(it.urlOrFolder))
//                    programs.add(mediaSource.mediaItem)
//                } else
//                {
                    it.urlOrFolder?.let { it1 -> MediaItem.fromUri(it1) }
                        ?.let { it2 -> programs.add(it2) }
//                }
            } else {
                it.urlOrFolder?.split("#")?.forEachIndexed { i, _ ->
                    val pgms = mutableListOf<MediaItem>()
                    val localPlaylistFolder = Monitor.instance?.getDirectoryFromPlaylist(it, i)
                    if (localPlaylistFolder != null) {
                        if (localPlaylistFolder.exists() && localPlaylistFolder.listFiles()?.isNotEmpty() == true) {
                            var addedFirstItem = false
                            Utils.setupLocalPrograms(pgms, localPlaylistFolder, addedFirstItem, it)
                            programs.addAll(pgms)
                        }
                    }
                }
            }
        }
        return programs
    }

//    // Support Rtmp stream "rtmp//server:port" or "rtmp//server"
//    private fun getRtmpSource(uri: Uri): MediaSource {
//        val rtmpDataSourceFactory = object : RtmpDataSource.Factory() {
//            override fun createDataSource(): DataSource? {
//                return null // You should provide an implementation here
//            }
//        }
//        // This is the MediaSource representing the media to be played.
//        return ProgressiveMediaSource.Factory(rtmpDataSourceFactory)
//            .createMediaSource(MediaItem.fromUri(uri))
//    }

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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun playCurrentSlot() {
        if (startedSlotsToday.isNotEmpty()) {
            val slots = startedSlotsToday.keys.toList().sortedDescending()
            val currentPlaylist = startedSlotsToday[slots[0]]
            // isCurrentSlot should only be true here
            Monitor.instance?.switchNow(currentPlaylist?.index ?: 0, true)
        } else { // Play first default
            Monitor.instance?.let { Monitor.instance!!.switchNow(it.getFirstDefaultIndex(), false) }
        }
    }

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

    private fun schedule(intent: Intent, millis: Long, start: String, index: Int) {
        val alarmPendingIntent = PendingIntent.getBroadcast(Monitor.instance, index, intent, PendingIntent.FLAG_CANCEL_CURRENT)
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