package org.avventomedia.app.telefyna.player

import android.content.Context
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * Buffers an upcoming live stream during the slot pre-roll, so the switch starts from data already downloaded
 * instead of connecting at the exact second (5-8 s of a held picture on a live start).
 *
 * Uses Media3's preload manager on the player's own playback thread and buffer pool: it loads the playlist and the
 * first seconds of media without opening a decoder, so it costs a few MB and some download, never a second player.
 */
@UnstableApi
class LivePreloader(
    context: Context,
    renderersFactory: RenderersFactory,
    mediaSourceFactory: MediaSource.Factory,
    val loadControl: DefaultLoadControl,
    val bandwidthMeter: DefaultBandwidthMeter,
    val playbackLooper: Looper
) {
    companion object {
        // Enough to start without a stall, small enough to stay close to the live edge
        private const val PRELOAD_MS = 6_000L

        fun playbackThread(): HandlerThread = HandlerThread("telefyna-playback").apply { start() }
    }

    private val trackSelector = DefaultTrackSelector(context)
    private var wanted: MediaItem? = null
    // Handed to the player: forgotten only once it is no longer on air, so a playing source is never released
    private var handedOver: MediaItem? = null

    private val manager = DefaultPreloadManager(
        TargetPreloadStatusControl<Int> {
            DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_LOADED_FOR_DURATION_MS, PRELOAD_MS)
        },
        mediaSourceFactory,
        trackSelector,
        bandwidthMeter,
        DefaultRendererCapabilitiesList.Factory(renderersFactory),
        loadControl.allocator,
        playbackLooper
    ).also { trackSelector.init({}, bandwidthMeter) }

    /** Starts buffering [item] (replacing any earlier preload); [onAirUri] is what the player is airing now. */
    fun preload(item: MediaItem, onAirUri: String?) {
        clear()
        handedOver?.takeIf { it.localConfiguration?.uri?.toString() != onAirUri }?.let {
            manager.remove(it)
            handedOver = null
        }
        wanted = item
        manager.add(item, 0)
        manager.invalidate()
    }

    /** The buffered source for [item], if it is the one being preloaded; it is handed over and forgotten. */
    fun take(item: MediaItem): MediaSource? {
        val current = wanted ?: return null
        if (current.localConfiguration?.uri != item.localConfiguration?.uri) return null
        val source = manager.getMediaSource(current)
        if (source != null) handedOver = current
        wanted = null
        return source
    }

    fun clear() {
        wanted?.let { manager.remove(it) }
        wanted = null
    }

    fun release() {
        wanted = null
        handedOver = null
        manager.release()
        trackSelector.release()
    }
}
