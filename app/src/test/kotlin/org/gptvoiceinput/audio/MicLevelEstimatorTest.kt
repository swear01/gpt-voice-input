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

    private val silent = ShortArray(320)

    private fun feed(estimator: MicLevelEstimator, frames: Int, frame: ShortArray) {
        repeat(frames) { estimator.processFrame(frame, frame.size) }
    }

    @Test
    fun `all-zero input stays at floor minimum`() {
        val estimator = MicLevelEstimator()
        feed(estimator, 24, silent)
        assertEquals(0f, estimator.currentLevel(), 0.0001f)
    }

    @Test
    fun `silence and whisper read zero`() {
        val estimator = MicLevelEstimator()
        feed(estimator, 24, silent)
        assertEquals(0f, estimator.currentLevel(), 0.0001f)
        // amplitude 300 -> peak ≈ -40.8 dBFS — below the -40 dBFS floor.
        feed(estimator, 24, sineFrame(amplitude = 300))
        assertEquals("whisper must read zero", 0f, estimator.currentLevel(), 0.0001f)
    }

    @Test
    fun `low amplitude maps to a low visible level`() {
        val estimator = MicLevelEstimator()
        // amplitude 1000 -> peak ≈ -30.3 dBFS -> ≈ 0.48 normalized.
        feed(estimator, 24, sineFrame(amplitude = 1000))
        assertTrue(
            "low level expected, got ${estimator.currentLevel()}",
            estimator.currentLevel() in 0.2f..0.7f,
        )
    }

    @Test
    fun `normal speech level reads full`() {
        val estimator = MicLevelEstimator()
        // amplitude 3000 -> peak ≈ -20.8 dBFS (typical AGC'd speech) -> ≈ 0.96.
        feed(estimator, 24, sineFrame(amplitude = 3000))
        assertTrue(
            "normal speech should read full, got ${estimator.currentLevel()}",
            estimator.currentLevel() > 0.85f,
        )
    }

    @Test
    fun `larger amplitude is strictly higher`() {
        val quiet = MicLevelEstimator().let {
            feed(it, 24, sineFrame(amplitude = 1000)); it.currentLevel()
        }
        val loud = MicLevelEstimator().let {
            feed(it, 24, sineFrame(amplitude = 8000)); it.currentLevel()
        }
        assertTrue("loud must exceed quiet ($loud vs $quiet)", loud > quiet)
    }

    @Test
    fun `clipping max PCM stays bounded at the maximum`() {
        val estimator = MicLevelEstimator()
        feed(estimator, 30, ShortArray(320) { Short.MAX_VALUE })
        val level = estimator.currentLevel()
        assertTrue(level <= 1f)
        assertTrue("near-full-scale should read high, got $level", level > 0.9f)
    }

    @Test
    fun `no NaN or infinity escapes`() {
        val estimator = MicLevelEstimator()
        feed(estimator, 30, sineFrame(amplitude = 3000))
        feed(estimator, 30, silent)
        assertTrue(estimator.currentLevel().isFinite())
        assertTrue(estimator.currentLevel() in 0f..1f)
    }

    @Test
    fun `peak is held then decays in silence - classic meter fall`() {
        val estimator = MicLevelEstimator()
        // Enough loud frames for the display to converge to the held peak
        // (~10 emissions), so the subsequent fall is monotonic.
        feed(estimator, 60, sineFrame(amplitude = 8000))
        val afterSpeech = estimator.currentLevel()
        assertTrue("speech should raise the meter, got $afterSpeech", afterSpeech > 0.9f)

        // Silence: the held peak decays each emission; level must fall
        // monotonically and settle near the floor.
        var previous = afterSpeech
        var monotonic = true
        repeat(14) {
            feed(estimator, 6, silent)
            val now = estimator.currentLevel()
            if (now > previous + 0.0001f) monotonic = false
            previous = now
        }
        assertTrue("meter must not rise in silence", monotonic)
        assertTrue("meter must settle near floor, got $previous", previous < 0.05f)
    }

    @Test
    fun `display smoothing converges toward steady input`() {
        val estimator = MicLevelEstimator()
        var previous = 0f
        var converged = false
        repeat(24) {
            feed(estimator, 6, sineFrame(amplitude = 8000))
            val now = estimator.currentLevel()
            if (kotlin.math.abs(now - previous) < 0.0005f) converged = true
            previous = now
        }
        assertTrue("level should converge", converged)
        assertTrue("steady loud level, got $previous", previous > 0.5f)
    }

    @Test
    fun `reset returns level to zero`() {
        val estimator = MicLevelEstimator()
        feed(estimator, 12, sineFrame(amplitude = 8000))
        assertTrue(estimator.currentLevel() > 0f)
        estimator.reset()
        assertEquals(0f, estimator.currentLevel(), 0.0001f)
    }
}
