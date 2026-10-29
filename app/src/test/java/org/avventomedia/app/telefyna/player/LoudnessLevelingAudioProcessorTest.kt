package org.avventomedia.app.telefyna.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessLevelingAudioProcessorTest {

    private val sampleRate = 48_000
    private val channels = 2

    /** Stereo 1 kHz sine whose RMS is [rmsDbfs]; returns the processed samples. */
    private fun process(processor: LoudnessLevelingAudioProcessor, rmsDbfs: Double, seconds: Int): ShortArray {
        processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
        val amplitude = sqrt(2.0) * 10.0.pow(rmsDbfs / 20) * Short.MAX_VALUE
        val out = mutableListOf<Short>()
        val chunkFrames = 4096
        var frame = 0
        while (frame < sampleRate * seconds) {
            val input = ByteBuffer.allocateDirect(chunkFrames * channels * 2).order(ByteOrder.nativeOrder())
            repeat(chunkFrames) {
                val s = (amplitude * sin(2 * PI * 1000 * (frame + it) / sampleRate)).toInt().toShort()
                repeat(channels) { input.putShort(s) }
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.nativeOrder())
            while (output.remaining() >= 2) out.add(output.short)
            frame += chunkFrames
        }
        return out.toShortArray()
    }

    private fun rmsDbfs(samples: ShortArray, lastSeconds: Int): Double {
        val tail = samples.takeLast(sampleRate * channels * lastSeconds)
        val ms = tail.sumOf { it.toDouble() * it } / tail.size / (Short.MAX_VALUE.toDouble() * Short.MAX_VALUE)
        return 10 * log10(ms)
    }

    @Test
    fun quietAndLoudProgrammesEndUpAtTheTarget() {
        for (inputDb in listOf(-30.0, -8.0)) {
            val out = process(LoudnessLevelingAudioProcessor(enabled = true), inputDb, 15)
            val level = rmsDbfs(out, 3)
            assertTrue("input $inputDb dBFS levelled to $level", abs(level - LoudnessLevelingAudioProcessor.DEFAULT_TARGET_DBFS) < 1.5)
        }
    }

    @Test
    fun programmeRecoversQuicklyAfterALoudBumper() {
        val processor = LoudnessLevelingAudioProcessor(enabled = true)
        process(processor, -10.0, 8) // hot bumper
        val after = process(processor, -30.0, 10) // quiet programme straight after (level estimate carried over)
        // 4-8 s into the programme it must be back on target
        val window = after.copyOfRange(sampleRate * channels * 4, sampleRate * channels * 8)
        val ms = window.sumOf { it.toDouble() * it } / window.size / (Short.MAX_VALUE.toDouble() * Short.MAX_VALUE)
        assertEquals(LoudnessLevelingAudioProcessor.DEFAULT_TARGET_DBFS, 10 * log10(ms), 1.5)
    }

    @Test
    fun boostIsCappedAndNeverClips() {
        val veryQuiet = process(LoudnessLevelingAudioProcessor(enabled = true), -45.0, 15)
        assertEquals(-45.0 + 12.0, rmsDbfs(veryQuiet, 3), 1.0) // max +12 dB
        val veryLoud = process(LoudnessLevelingAudioProcessor(enabled = true, targetDbfs = -3.0), -10.0, 10)
        assertTrue(veryLoud.all { abs(it.toInt()) <= 0.98 * Short.MAX_VALUE + 1 })
    }

    @Test
    fun silenceIsNotBoosted() {
        val out = process(LoudnessLevelingAudioProcessor(enabled = true), -90.0, 5)
        assertTrue(rmsDbfs(out, 2) < -80)
    }

    @Test
    fun disabledIsBitExactPassthrough() {
        val off = process(LoudnessLevelingAudioProcessor(enabled = false), -30.0, 2)
        val reference = process(LoudnessLevelingAudioProcessor(enabled = false), -30.0, 2)
        assertEquals(-30.0, rmsDbfs(off, 1), 0.2)
        assertTrue(off.contentEquals(reference))
    }
}
