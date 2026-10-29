package org.avventomedia.app.telefyna.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Levels loudness between programmes, bumpers and streams: a slow automatic gain control that steers the
 * short-term RMS level towards [targetDbfs], plus an instant peak limiter so boosted audio never clips.
 *
 * Works on 16-bit PCM after decoding, so it applies to every source (files, HLS, RTMP, SRT) without
 * analysing media in advance. Silence is not boosted. Bypassed (bit-exact passthrough) while [enabled] is false,
 * so it can be toggled from config without rebuilding the player.
 */
@UnstableApi
class LoudnessLevelingAudioProcessor(
    @Volatile var enabled: Boolean = false,
    @Volatile var targetDbfs: Double = DEFAULT_TARGET_DBFS
) : BaseAudioProcessor() {

    companion object {
        const val DEFAULT_TARGET_DBFS = -20.0
        private const val MAX_GAIN_DB = 12.0
        private const val MIN_GAIN_DB = -12.0
        // Below this the block is treated as silence/room tone and doesn't move the level estimate
        private const val GATE_DBFS = -50.0
        // Asymmetric like broadcast AGCs: react fast to something louder (a hot bumper), recover from it
        // within a few seconds so the following programme isn't left quiet
        private const val LEVEL_RISE_TIME_CONSTANT_S = 0.5
        private const val LEVEL_FALL_TIME_CONSTANT_S = 1.5
        private const val GAIN_DOWN_TIME_CONSTANT_S = 0.3
        private const val GAIN_UP_TIME_CONSTANT_S = 1.0
        private const val BLOCK_MS = 10
        private const val CEILING = 0.98 * Short.MAX_VALUE
    }

    // Level estimate in dBFS RMS, smoothed in the dB domain so a 20 dB drop settles as fast as a 20 dB rise;
    // NaN until the first audible audio
    private var levelDb = Double.NaN
    private var gainDb = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET // e.g. float output or passthrough: stay out of the way
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        if (!enabled) {
            output.put(inputBuffer).flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val channels = inputAudioFormat.channelCount
        val blockSamples = maxOf(channels, inputAudioFormat.sampleRate * BLOCK_MS / 1000 * channels)
        val block = ShortArray(blockSamples)

        while (input.remaining() >= 2) {
            val count = minOf(blockSamples, input.remaining() / 2)
            var sumSquares = 0.0
            var peak = 0
            for (i in 0 until count) {
                val s = input.short
                block[i] = s
                sumSquares += s.toDouble() * s
                val a = if (s < 0) -s.toInt() else s.toInt()
                if (a > peak) peak = a
            }
            val blockMs = sumSquares / count / (Short.MAX_VALUE.toDouble() * Short.MAX_VALUE)
            updateGain(blockMs, count.toDouble() / channels / inputAudioFormat.sampleRate)

            var gain = 10.0.pow(gainDb / 20.0)
            if (peak * gain > CEILING) {
                gain = CEILING / peak // limiter: never let this block clip
            }
            for (i in 0 until count) {
                output.putShort((block[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        // Odd trailing byte (never expected for PCM16): pass it through untouched
        while (input.hasRemaining()) output.put(input.get())
        output.flip()
    }

    private fun updateGain(blockMeanSquare: Double, blockSeconds: Double) {
        val blockDb = 10 * log10(blockMeanSquare.coerceAtLeast(1e-12))
        if (blockDb > GATE_DBFS) {
            levelDb = if (levelDb.isNaN()) {
                blockDb // first audible audio: start from it instead of ramping up from silence
            } else {
                val tau = if (blockDb > levelDb) LEVEL_RISE_TIME_CONSTANT_S else LEVEL_FALL_TIME_CONSTANT_S
                levelDb + (blockDb - levelDb) * (1 - exp(-blockSeconds / tau))
            }
        }
        if (levelDb.isNaN()) return
        val desired = (targetDbfs - levelDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val tau = if (desired < gainDb) GAIN_DOWN_TIME_CONSTANT_S else GAIN_UP_TIME_CONSTANT_S
        gainDb += (desired - gainDb) * (1 - exp(-blockSeconds / tau))
    }

    override fun onFlush() {
        // Keep the level estimate across seeks/item changes so the next programme is levelled from its first second
    }

    override fun onReset() {
        levelDb = Double.NaN
        gainDb = 0.0
    }

    /** Current applied gain, for logging and tests. */
    fun currentGainDb(): Double = gainDb
}
