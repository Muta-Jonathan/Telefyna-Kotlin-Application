package org.avventomedia.app.telefyna

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.avventomedia.app.telefyna.modal.Playlist
import org.avventomedia.app.telefyna.player.LoudnessLevelingAudioProcessor

/**
 * Process-wide playout: the player and what it is airing. It outlives the Monitor Activity, so when the screen is
 * destroyed and re-created the broadcast keeps playing and the new screen reattaches instead of restarting it.
 * Owned by TelefynaForegroundService, which releases it when it stops.
 */
@OptIn(UnstableApi::class)
object Playout {
    var player: ExoPlayer? = null
    val loudnessLeveling = LoudnessLevelingAudioProcessor()

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

    fun release() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
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
