package org.avventomedia.app.telefyna.player

import android.content.Context
import android.os.Handler
import androidx.annotation.Nullable
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
class TelefynaCodecRender @OptIn(UnstableApi::class) constructor
    (
    context: Context,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler? = null,
    eventListener: VideoRendererEventListener? = null,
    maxDroppedFramesToNotify: Int
) : MediaCodecVideoRenderer(context, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, maxDroppedFramesToNotify) {

    private val workAroundDecoders: List<String> = listOf("OMX.rk.video_decoder.avc")

    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean {
        // https://github.com/google/ExoPlayer/issues/3939
        return workAroundDecoders.contains(name)
    }
}