package org.avventomedia.app.telefyna.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Passes audio through untouched and, while a [sink] is set, hands a copy converted to 44.1 kHz stereo to it
 * (the audio stream). Sits after loudness levelling so listeners hear the levelled programme.
 */
@UnstableApi
class AudioTapProcessor : BaseAudioProcessor() {

    /** Receives interleaved stereo 16-bit samples at [PcmConverter.TARGET_RATE] and the sample count. */
    @Volatile var sink: ((ShortArray, Int) -> Unit)? = null

    private var converter = PcmConverter()
    // Reused across buffers: this runs on the playback thread dozens of times a second
    private var samples = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET // float output or passthrough: nothing to tap
        }
        converter = PcmConverter()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val target = sink
        if (target != null) {
            val shorts = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            val count = shorts.remaining()
            if (samples.size < count) samples = ShortArray(count)
            shorts.get(samples, 0, count)
            val channels = inputAudioFormat.channelCount
            val produced = converter.convert(samples, count / channels, channels, inputAudioFormat.sampleRate)
            if (produced > 0) target(converter.output, produced)
        }
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }
}

/**
 * Converts interleaved 16-bit PCM of any channel count and rate to stereo at [TARGET_RATE] (linear interpolation).
 * Keeps its position across calls so consecutive buffers join without clicks.
 */
class PcmConverter {
    companion object {
        const val TARGET_RATE = 44_100
    }

    private var phase = 0.0 // position in input frames, relative to the first frame of the next buffer
    private var prevLeft = 0
    private var prevRight = 0

    /** Converted samples of the last [convert] call (reused, so copy what you keep). */
    var output = ShortArray(0)
        private set

    /** Converts [frames] frames of [input]; returns how many stereo samples were written to [output]. */
    fun convert(input: ShortArray, frames: Int, channels: Int, sampleRate: Int): Int {
        if (frames <= 0 || channels <= 0 || sampleRate <= 0) return 0
        fun left(i: Int) = if (i < 0) prevLeft else input[i * channels].toInt()
        fun right(i: Int) = if (i < 0) prevRight else input[i * channels + if (channels > 1) 1 else 0].toInt()
        val step = sampleRate.toDouble() / TARGET_RATE
        val needed = ((frames / step).toInt() + 2) * 2
        if (output.size < needed) output = ShortArray(needed)
        val out = output
        var n = 0
        while (phase <= frames - 1) {
            val idx = kotlin.math.floor(phase).toInt()
            val frac = phase - idx
            val l = if (frac > 0) left(idx) + (left(idx + 1) - left(idx)) * frac else left(idx).toDouble()
            val r = if (frac > 0) right(idx) + (right(idx + 1) - right(idx)) * frac else right(idx).toDouble()
            out[n++] = l.toInt().toShort()
            out[n++] = r.toInt().toShort()
            phase += step
        }
        phase -= frames
        prevLeft = left(frames - 1)
        prevRight = right(frames - 1)
        return n
    }
}
