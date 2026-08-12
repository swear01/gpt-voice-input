package org.gptvoiceinput.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointDetectorTest {

    private class Recorder(
        var endOfSpeech: Int = 0,
        var noSpeechTimeout: Int = 0,
        var maxDuration: Int = 0,
    ) : EndpointDetector.Listener {
        override fun onEndOfSpeech() {
            endOfSpeech++
        }

        override fun onNoSpeechTimeout() {
            noSpeechTimeout++
        }

        override fun onMaxDuration() {
            maxDuration++
        }
    }

    private class Driver(private val detector: EndpointDetector) {
        private var elapsedMs = 0L

        /** Feeds `frames` consecutive VAD decisions of [frameMs] each. */
        fun feed(frames: Int, speech: Boolean, frameMs: Long = 20) {
            repeat(frames) {
                elapsedMs += frameMs
                detector.onFrame(speech, elapsedMs)
            }
        }
    }

    @Test
    fun `endpoint fires after configured silence following speech`() {
        val listener = Recorder()
        val detector = EndpointDetector(endpointDelayMs = 1800, listener = listener)
        val driver = Driver(detector)

        driver.feed(50, speech = false) // quiet start
        driver.feed(10, speech = true) // speech begins (sets ~200ms hangover)
        driver.feed(50, speech = false) // 10 frames hangover + 40 silence → ~700ms so far
        assertEquals(0, listener.endOfSpeech)
        driver.feed(60, speech = false) // …+1200ms ≥ 1800ms
        assertEquals(1, listener.endOfSpeech)
        assertEquals(EndpointDetector.State.STOPPED, detector.state)
    }

    @Test
    fun `brief pause inside speech does not endpoint`() {
        val listener = Recorder()
        val detector = EndpointDetector(endpointDelayMs = 1800, listener = listener)
        val driver = Driver(detector)

        driver.feed(10, speech = true)
        driver.feed(25, speech = false) // 500ms pause (200ms hangover + 300ms real)
        assertEquals(0, listener.endOfSpeech)
        driver.feed(10, speech = true) // speech resumes → back IN_SPEECH
        driver.feed(50, speech = false) // 10 hangover + 40 silence → ~700ms
        assertEquals(0, listener.endOfSpeech)
        driver.feed(60, speech = false) // total ≥ 1800ms since pause
        assertEquals(1, listener.endOfSpeech)
    }

    @Test
    fun `micro-gaps inside speech are bridged by the hangover`() {
        val listener = Recorder()
        val detector = EndpointDetector(endpointDelayMs = 1800, listener = listener)
        val driver = Driver(detector)

        driver.feed(10, speech = true)
        // 180ms gap (< 200ms hangover): still fully inside the hangover window.
        driver.feed(9, speech = false)
        assertEquals(EndpointDetector.State.IN_SPEECH, detector.state)
        // Hangover exhausted on the 10th silence frame (still not real silence).
        driver.feed(1, speech = false)
        assertEquals(EndpointDetector.State.IN_SPEECH, detector.state)
        // 80ms of real silence < 100ms debounce: still talking.
        driver.feed(4, speech = false)
        assertEquals(EndpointDetector.State.IN_SPEECH, detector.state)
        // 100ms of true silence → the endpoint clock may start.
        driver.feed(1, speech = false)
        assertEquals(EndpointDetector.State.ENDPOINT_CANDIDATE, detector.state)
        assertEquals(0, listener.endOfSpeech)
    }

    @Test
    fun `silence timer never starts before first speech`() {
        val listener = Recorder()
        val detector = EndpointDetector(endpointDelayMs = 1000, listener = listener)
        val driver = Driver(detector)

        driver.feed(350, speech = false) // 7s of quiet
        assertEquals(0, listener.noSpeechTimeout)
        driver.feed(10, speech = true) // speech at 7.2s — no timeout fired
        assertEquals(0, listener.noSpeechTimeout)
        assertEquals(EndpointDetector.State.IN_SPEECH, detector.state)
    }

    @Test
    fun `no speech at all triggers graceful timeout at 8s`() {
        val listener = Recorder()
        val detector = EndpointDetector(endpointDelayMs = 1000, listener = listener)
        val driver = Driver(detector)

        driver.feed(390, speech = false) // 7.8s
        assertEquals(0, listener.noSpeechTimeout)
        driver.feed(20, speech = false) // 8.2s
        assertEquals(1, listener.noSpeechTimeout)
        assertEquals(EndpointDetector.State.STOPPED, detector.state)
        assertFalse("no auto-submit on the no-speech path", listener.endOfSpeech > 0)
    }

    @Test
    fun `auto-stop OFF never auto-submits`() {
        val listener = Recorder()
        val detector = EndpointDetector(endpointDelayMs = 0, listener = listener)
        val driver = Driver(detector)

        driver.feed(10, speech = true)
        driver.feed(200, speech = false) // 4s of silence
        assertEquals(0, listener.endOfSpeech)
        assertEquals(EndpointDetector.State.ENDPOINT_CANDIDATE, detector.state)
    }

    @Test
    fun `max duration submits what was captured`() {
        val listener = Recorder()
        val detector = EndpointDetector(endpointDelayMs = 0, listener = listener)
        val driver = Driver(detector)

        driver.feed(10, speech = true)
        driver.feed(200, speech = false) // ENDPOINT_CANDIDATE, never auto-submits
        assertEquals(0, listener.maxDuration)
        // 210 frames * 20ms = 4.2s so far; fast-forward by feeding long frames
        driver.feed(1, speech = false, frameMs = 120_000)
        assertEquals(1, listener.maxDuration)
        assertEquals(EndpointDetector.State.STOPPED, detector.state)
        assertTrue("maxDuration is the submit path", listener.endOfSpeech == 0)
    }

    @Test
    fun `first frames are debounced - single speech frame does not flip state`() {
        val listener = Recorder()
        val detector = EndpointDetector(endpointDelayMs = 1000, listener = listener)
        val driver = Driver(detector)

        driver.feed(1, speech = true)
        assertEquals(EndpointDetector.State.WAITING_FOR_SPEECH, detector.state)
        driver.feed(1, speech = true)
        assertEquals(EndpointDetector.State.IN_SPEECH, detector.state)
    }
}
