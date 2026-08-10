package org.gptvoiceinput.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VadProcessorTest {

    private fun toneFrame(
        amplitude: Int,
        freq: Double = 440.0,
        rate: Int = 16000,
        count: Int = 320,
    ): ShortArray {
        val samples = ShortArray(count)
        for (i in 0 until count) {
            samples[i] = (amplitude * sin(2.0 * PI * freq * i / rate)).toInt().toShort()
        }
        return samples
    }

    private val quiet = ShortArray(320)
    private val loudTone = toneFrame(8000) // ≈ -17 dBFS
    private val quietTone = toneFrame(40) // ≈ -63 dBFS
    private val ambient = toneFrame(1200) // ≈ -34 dBFS

    private fun feedQuiet(vad: VadProcessor, frames: Int = 6) {
        repeat(frames) { assertFalse(vad.isSpeech(quiet, quiet.size)) }
    }

    /** Feeds [frame] up to [maxFrames] times; true if speech is ever detected. */
    private fun eventuallySpeech(vad: VadProcessor, frame: ShortArray, maxFrames: Int): Boolean {
        var detected = false
        repeat(maxFrames) {
            detected = detected || vad.isSpeech(frame, frame.size)
        }
        return detected
    }

    @Test
    fun `silence is never speech`() {
        val vad = VadProcessor(sampleRate = 16000)
        feedQuiet(vad)
        repeat(5) { assertFalse(vad.isSpeech(quiet, quiet.size)) }
    }

    @Test
    fun `loud tone is speech after pre-roll`() {
        val vad = VadProcessor(sampleRate = 16000)
        feedQuiet(vad)
        // Level smoothing converges within ~4 frames (trace-verified).
        assertFalse(vad.isSpeech(loudTone, loudTone.size))
        assertFalse(vad.isSpeech(loudTone, loudTone.size))
        assertFalse(vad.isSpeech(loudTone, loudTone.size))
        assertTrue(vad.isSpeech(loudTone, loudTone.size))
        assertTrue(vad.isSpeech(loudTone, loudTone.size))
    }

    @Test
    fun `quiet tone is not speech`() {
        val vad = VadProcessor(sampleRate = 16000)
        feedQuiet(vad)
        repeat(5) { assertFalse(vad.isSpeech(quietTone, quietTone.size)) }
    }

    @Test
    fun `ambient noise does not trip detection but louder speech does`() {
        val vad = VadProcessor(sampleRate = 16000)
        // Pre-roll absorbs the initial ambient level…
        repeat(6) { assertFalse(vad.isSpeech(ambient, ambient.size)) }
        // …and sustained ambient stays below the adapted threshold.
        repeat(30) { assertFalse(vad.isSpeech(ambient, ambient.size)) }
        // Clear speech above the ambient is detected (after the smoothing lag).
        assertTrue(eventuallySpeech(vad, loudTone, 5))
    }

    @Test
    fun `instant loud start cannot silence the detector`() {
        val vad = VadProcessor(sampleRate = 16000)
        // User speaks immediately: the pre-roll (5 frames) forces silence but
        // clamps the noise floor, so detection kicks in right after.
        repeat(5) { assertFalse(vad.isSpeech(loudTone, loudTone.size)) }
        assertTrue(eventuallySpeech(vad, loudTone, 10))
    }

    @Test
    fun `reset clears adaptation`() {
        val vad = VadProcessor(sampleRate = 16000)
        assertTrue(eventuallySpeech(vad, loudTone, 10))
        vad.reset()
        feedQuiet(vad)
        repeat(3) { assertFalse(vad.isSpeech(quiet, quiet.size)) }
    }
}
