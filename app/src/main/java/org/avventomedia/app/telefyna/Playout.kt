package org.avventomedia.app.telefyna

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.avventomedia.app.telefyna.modal.Playlist
import android.content.Context
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.modal.AudioStream
import org.avventomedia.app.telefyna.player.AudioTapProcessor
import org.avventomedia.app.telefyna.player.LoudnessLevelingAudioProcessor
import org.avventomedia.app.telefyna.stream.AudioStreamer

/**
 * Process-wide playout: the player and what it is airing. It outlives the Monitor Activity, so when the screen is
 * destroyed and re-created the broadcast keeps playing and the new screen reattaches instead of restarting it.
 * Owned by TelefynaForegroundService, which releases it when it stops.
 */
@OptIn(UnstableApi::class)
object Playout {
    var player: ExoPlayer? = null
    val loudnessLeveling = LoudnessLevelingAudioProcessor()
    val audioTap = AudioTapProcessor()
    private var streamer: AudioStreamer? = null
    var livePreloader: org.avventomedia.app.telefyna.player.LivePreloader? = null
    var playbackThread: android.os.HandlerThread? = null

    // What is on air (formerly Monitor fields)
    var playlistByIndex: MutableList<Playlist> = mutableListOf()
    var nowPlayingIndex: Int? = null
    var currentPlaylist: Playlist? = null
    var programItems: MutableList<MediaItem> = mutableListOf()
    var upcomingProgramItems: MutableList<MediaItem>? = null
    var nowProgramItem: Int? = 0
    var startOnePlayProgramItem: Int? = null
    var failedBecauseOfInternetIndex: Int? = null
    var fillingForLackOfInternet = false

    // The screen currently receiving player events
    private var listener: Player.Listener? = null

    /** True when a player exists and a playlist was put on air: a new screen should reattach, not restart. */
    val isOnAir: Boolean get() = player != null && nowPlayingIndex != null

    fun attach(newListener: Player.Listener) {
        player?.let { p ->
            listener?.let { p.removeListener(it) }
            p.addListener(newListener)
        }
        listener = newListener
    }

    fun detach(oldListener: Player.Listener) {
        if (listener === oldListener) {
            player?.removeListener(oldListener)
            listener = null
        }
    }

    /** Starts, restarts or stops the audio stream to match [settings]; nothing happens when they are unchanged. */
    fun applyAudioStream(context: Context, settings: AudioStream?) {
        if (settings?.enabled == true && !settings.isUsable) {
            Logger.logWithContext(context, AuditLog.Event.ERROR, "audioStream is enabled but its host or password is missing")
        }
        val wanted = settings?.takeIf { it.isUsable }?.copy()
        if (wanted == streamer?.settings) return
        stopAudioStream()
        wanted?.let { s ->
            streamer = AudioStreamer(context.applicationContext, s).also {
                audioTap.sink = it::write
                it.start()
            }
        }
    }

    /** The now playing title for stream listeners. */
    fun streamTitle(title: String) {
        streamer?.nowPlaying(title)
    }

    private fun stopAudioStream() {
        audioTap.sink = null
        streamer?.stop()
        streamer = null
    }

    fun release() {
        stopAudioStream()
        livePreloader?.release()
        livePreloader = null
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
        playbackThread?.quitSafely() // the player's playback thread, created with it
        playbackThread = null
        listener = null
        playlistByIndex = mutableListOf()
        nowPlayingIndex = null
        currentPlaylist = null
        programItems = mutableListOf()
        upcomingProgramItems = null
        nowProgramItem = 0
        startOnePlayProgramItem = null
        failedBecauseOfInternetIndex = null
        fillingForLackOfInternet = false
    }
}
