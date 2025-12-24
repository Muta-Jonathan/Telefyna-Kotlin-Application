package org.avventomedia.app.telefyna.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.avventomedia.app.telefyna.PrefsStore
import org.avventomedia.app.telefyna.R
import android.util.Log

/**
 * Foreground playback service that owns a single ExoPlayer + MediaSession.
 * Minimal implementation to enable binding from Activity and survive UI lifecycle.
 */
@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {

    companion object {
        const val ACTION_START = "org.avventomedia.app.telefyna.action.START"
        const val ACTION_MAINTENANCE = "org.avventomedia.app.telefyna.action.MAINTENANCE"
        private const val CHANNEL_ID = "telefyna_media"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, PlayerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var serviceScope: CoroutineScope
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private var prefsStore: PrefsStore? = null
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        prefsStore = PrefsStore(applicationContext)
        ensureNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWatchdog()
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Call super for proper lifecycle handling
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> ensureStarted()
            ACTION_MAINTENANCE -> {
                // In a full implementation, recompute schedule and update playlist
                ensureStarted()
                // Nudge watchdog to run a maintenance pass
                startWatchdog(force = true)
            }
            else -> ensureStarted()
        }
        return START_STICKY
    }

    private fun ensureStarted() {
        if (mediaSession == null || player == null) {
            val p = ExoPlayer.Builder(this)
                .setAudioAttributes(
                    AudioAttributes.Builder().setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA).build(), true
                )
                .build()
            player = p
            mediaSession = MediaSession.Builder(this, p).build()
            startForeground(NOTIFICATION_ID, buildNotification())
            startWatchdog()
            // Load last known playback state (scaffold; actual restore needs media items prepared here)
            serviceScope.launch {
                try {
                    val last = prefsStore?.load()
                    Log.d("PlayerService", "[RESTORE] Loaded state: $last")
                } catch (t: Throwable) {
                    Log.w("PlayerService", "[RESTORE] Failed to load state: ${t.message}")
                }
            }
        } else if (watchdogJob == null) {
            startWatchdog()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        ensureStarted()
        return mediaSession
    }

    private fun startWatchdog(force: Boolean = false) {
        if (watchdogJob != null && !force) return
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                try {
                    Log.d("PlayerService", "[WATCHDOG] tick")
                    // Persist playhead periodically
                    val p = player
                    if (p != null) {
                        val state = PrefsStore.PlaybackState(
                            playlistIndex = 0, // TODO: wire real playlist index when scheduling migrates here
                            mediaItemIndex = p.currentMediaItemIndex.takeIf { it >= 0 } ?: 0,
                            seekPosition = p.currentPosition
                        )
                        prefsStore?.save(state)
                    }
                } catch (t: Throwable) {
                    Log.w("PlayerService", "[WATCHDOG] error: ${t.message}")
                }
                delay(WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Telefyna is playing")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Telefyna Media",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Media playback"
                    }
                )
            }
        }
    }
}
