package org.gptvoiceinput.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `invalid calibration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MicLevelEstimator(floorDb = -12f, ceilingDb = -12f)
        }
        assertThrows(IllegalArgumentException::class.java) { MicLevelEstimator(attack = 1.1f) }
        assertThrows(IllegalArgumentException::class.java) { MicLevelEstimator(release = -0.1f) }
    }

    @Test
    fun `all-zero input stays at floor minimum`() {
        val estimator = MicLevelEstimator()
        feed(estimator, 24, silent)
        assertEquals(0f, estimator.currentLevel(), 0.0001f)
    }

    @Test
    fun `silence is zero and sub-threshold input stays invisible`() {
        val estimator = MicLevelEstimator()
        feed(estimator, 24, silent)
        assertEquals(0f, estimator.currentLevel(), 0.0001f)
        // amplitude 40 -> RMS ≈ -61.3 dBFS, below the -55 dBFS floor.
        feed(estimator, 24, sineFrame(amplitude = 40))
        assertTrue("very quiet input should stay invisible", estimator.currentLevel() < 0.05f)
    }

    @Test
    fun `quiet speech maps to a clearly visible level`() {
        val estimator = MicLevelEstimator()
        // amplitude 300 -> RMS ≈ -43.8 dBFS -> ≈ 0.45 on the -55/-30 band.
        feed(estimator, 24, sineFrame(amplitude = 300))
        assertTrue(
            "quiet speech should be clearly visible, got ${estimator.currentLevel()}",
            estimator.currentLevel() in 0.3f..0.6f,
        )
    }

    @Test
    fun `normal speech drives the meter near full`() {
        val estimator = MicLevelEstimator()
        // amplitude 1000 -> RMS ≈ -33.3 dBFS -> ≈ 0.87. Reference: Google's
        // AMR-WB VAD treats -26 dBov as nominal speech, so normal speech
        // (-26 to -33 dBFS on device) must read near-full.
        feed(estimator, 24, sineFrame(amplitude = 1000))
        assertTrue(
            "normal speech should be near full, got ${estimator.currentLevel()}",
            estimator.currentLevel() in 0.75f..0.98f,
        )
    }

    @Test
    fun `larger amplitude never reads lower`() {
        val quiet = MicLevelEstimator().let {
            feed(it, 24, sineFrame(amplitude = 300)); it.currentLevel()
        }
        val normal = MicLevelEstimator().let {
            feed(it, 24, sineFrame(amplitude = 1000)); it.currentLevel()
        }
        val loud = MicLevelEstimator().let {
            feed(it, 24, sineFrame(amplitude = 3000)); it.currentLevel()
        }
        val louder = MicLevelEstimator().let {
            feed(it, 24, sineFrame(amplitude = 8000)); it.currentLevel()
        }
        assertTrue("normal must visibly exceed quiet ($normal vs $quiet)", normal - quiet > 0.2f)
        assertTrue("loud must not drop below normal ($loud vs $normal)", loud >= normal)
        assertTrue("louder must saturate at the ceiling ($louder)", louder >= loud)
        assertTrue("loud speech should saturate near full", loud > 0.95f)
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
    fun `speech attacks quickly then releases smoothly in silence`() {
        val estimator = MicLevelEstimator()
        feed(estimator, 3, sineFrame(amplitude = 8000))
        val afterSpeech = estimator.currentLevel()
        assertTrue("speech should raise the meter within 60 ms, got $afterSpeech", afterSpeech > 0.8f)

        var previous = afterSpeech
        var monotonic = true
        repeat(30) {
            feed(estimator, 1, silent)
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
