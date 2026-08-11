package org.gptvoiceinput.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MicLevelEstimatorTest {

    private fun sineFrame(amplitude: Int, rate: Int = 16000, count: Int = 320): ShortArray {
        val samples = ShortArray(count)
        for (i in 0 until count) {
            samples[i] = (amplitude * sin(2.0 * PI * 440.0 * i / rate)).toInt().toShort()
        }
        return samples
    }

    @Test
    fun `all-zero frame is floor minimum`() {
        val estimator = MicLevelEstimator()
        val level = estimator.processFrame(ShortArray(320), 320)
        assertEquals(0f, level, 0.0001f)
    }

    @Test
    fun `low amplitude maps to a low level`() {
        val estimator = MicLevelEstimator()
        // amplitude 300 → ≈ -43.8 dBFS → ≈ 0.12 normalized (first frame ~0.09)
        val level = estimator.processFrame(sineFrame(amplitude = 300), 320)
        assertTrue("low level expected, got $level", level in 0.01f..0.4f)
    }

    @Test
    fun `larger amplitude is strictly higher`() {
        val quiet = MicLevelEstimator().processFrame(sineFrame(amplitude = 300), 320)
        val loud = MicLevelEstimator().processFrame(sineFrame(amplitude = 8000), 320)
        assertTrue("loud must exceed quiet ($loud vs $quiet)", loud > quiet)
    }

    @Test
    fun `clipping max PCM stays bounded at the maximum`() {
        val estimator = MicLevelEstimator()
        repeat(5) { estimator.processFrame(sineFrame(amplitude = 32767), 320) }
        val level = estimator.processFrame(ShortArray(320) { Short.MAX_VALUE }, 320)
        assertTrue(level <= 1f)
        assertTrue(level > 0.9f)
    }

    @Test
    fun `no NaN or infinity escapes to the UI`() {
        val estimator = MicLevelEstimator()
        estimator.processFrame(ShortArray(0), 0)
        estimator.processFrame(sineFrame(amplitude = 0), 320)
        repeat(10) {
            val v = estimator.processFrame(sineFrame(amplitude = 1000), 320)
            assertTrue("level must be finite: $v", v.isFinite())
            assertTrue(v in 0f..1f)
        }
    }

    @Test
    fun `attack is fast and release is slower`() {
        val estimator = MicLevelEstimator()
        val loud = sineFrame(amplitude = 8000)
        val silent = ShortArray(320)

        // Rise quickly from silence (first loud frame ≈ 0.49).
        val first = estimator.processFrame(loud, 320)
        assertTrue("fast attack expected, got $first", first > 0.4f)

        // Decay should be a fraction of the rise per frame.
        estimator.processFrame(silent, 320)
        val afterOneSilent = estimator.currentLevel()
        assertTrue("slow release expected", afterOneSilent > first * 0.4f)
        estimator.processFrame(silent, 320)
        val afterTwoSilent = estimator.currentLevel()
        assertTrue("release must decay monotonically", afterTwoSilent < afterOneSilent)
        assertTrue("release must stay above floor briefly", afterTwoSilent > 0.1f)
    }

    @Test
    fun `smoothing converges toward steady input`() {
        val estimator = MicLevelEstimator()
        val loud = sineFrame(amplitude = 8000)
        var previous = 0f
        var converged = false
        repeat(30) {
            val v = estimator.processFrame(loud, 320)
            if (kotlin.math.abs(v - previous) < 0.0005f) converged = true
            previous = v
        }
        assertTrue("level should converge", converged)
        // amplitude 8000 → ≈ 0.7 normalized; converges near that.
        assertTrue("steady level below max, got $previous", previous > 0.6f)
    }

    @Test
    fun `reset returns level to zero`() {
        val estimator = MicLevelEstimator()
        estimator.processFrame(sineFrame(amplitude = 8000), 320)
        assertTrue(estimator.currentLevel() > 0f)
        estimator.reset()
        assertEquals(0f, estimator.currentLevel(), 0.0001f)
    }
}
