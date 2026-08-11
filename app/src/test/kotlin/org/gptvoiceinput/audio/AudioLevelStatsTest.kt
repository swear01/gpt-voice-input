package org.gptvoiceinput.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class AudioLevelStatsTest {

    private fun toneFrame(amplitude: Int, count: Int = 320): ShortArray {
        val samples = ShortArray(count)
        for (i in 0 until count) {
            samples[i] = (amplitude * sin(2.0 * PI * 440.0 * i / 16000)).toInt().toShort()
        }
        return samples
    }

    @Test
    fun `silence reads as very low dbFS`() {
        val stats = AudioLevelStats.computePcm(ShortArray(320), 320)
        assertTrue(stats.peakDb < -100f)
        assertTrue(stats.rmsDb < -100f)
    }

    @Test
    fun `tone amplitude maps to expected dbFS`() {
        // Peak 8000 -> 20*log10(8000/32768) ≈ -12.2 dBFS.
        val stats = AudioLevelStats.computePcm(toneFrame(8000), 320)
        assertEquals(-12.25f, stats.peakDb, 0.3f)
        // RMS of a sine ≈ peak/sqrt(2) -> ≈ -15.3 dBFS.
        assertEquals(-15.3f, stats.rmsDb, 0.5f)
    }

    @Test
    fun `full-scale frame is bounded near zero dbFS`() {
        val stats = AudioLevelStats.computePcm(ShortArray(320) { Short.MAX_VALUE }, 320)
        assertTrue(stats.peakDb <= 0f)
        assertTrue(stats.peakDb > -0.01f)
    }

    @Test
    fun `fromWav parses a writer-produced wav`() {
        val file = File.createTempFile("gvi-stats", ".wav")
        try {
            WavWriter(file, 16000).use { writer ->
                writer.appendPcm(toneFrame(8000), 320)
            }
            val stats = AudioLevelStats.fromWav(file)
            assertNotNull(stats)
            assertEquals(-12.25f, stats!!.peakDb, 0.3f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `fromWav returns null for invalid files`() {
        val file = File.createTempFile("gvi-bad", ".wav")
        try {
            file.writeBytes(ByteArray(10))
            assertNull(AudioLevelStats.fromWav(file))
        } finally {
            file.delete()
        }
    }

    private inline fun WavWriter.use(block: (WavWriter) -> Unit) {
        try {
            block(this)
            finish()
        } finally {
        }
    }
}
