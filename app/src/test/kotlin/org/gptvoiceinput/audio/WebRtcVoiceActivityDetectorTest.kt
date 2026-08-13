package org.gptvoiceinput.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.sin

/**
 * WebRTC VAD wrapper tests.
 *
 * The real native library is an Android .so and cannot load in the
 * unit-test JVM (construction throws UnsatisfiedLinkError there), so:
 *
 * - The wrapper's ERROR CONTRACT is tested with an injected fake native:
 *   native failures are contained (reported as silence, never a crash),
 *   partial frames are trimmed, reset recreates the instance, close is
 *   idempotent, and constructor failure propagates to the caller (which
 *   AudioRecorder maps to a clear localized error — see AudioRecorderTest).
 * - Real speech classification is asserted only where the native library
 *   loads (device / instrumentation), guarded by [nativeAvailable].
 */
@RunWith(RobolectricTestRunner::class)
class WebRtcVoiceActivityDetectorTest {

    private class FakeNative(
        var behavior: (ShortArray) -> Boolean = { false },
        var received: MutableList<ShortArray> = mutableListOf(),
        var closed: Boolean = false,
    ) : WebRtcNativeVad {
        override fun isSpeech(frame: ShortArray): Boolean {
            received.add(frame.copyOf())
            return behavior(frame)
        }

        override fun close() {
            closed = true
        }
    }

    private fun toneFrame(amplitude: Int, rate: Int = 16000, count: Int = 320): ShortArray {
        val samples = ShortArray(count)
        for (i in 0 until count) {
            samples[i] = (amplitude * sin(2.0 * PI * 440.0 * i / rate)).toInt().toShort()
        }
        return samples
    }

    private val silent = ShortArray(320)
    private val loudTone = toneFrame(8000) // ≈ -15 dBFS

    /** True where the native .so loads (device); false in the unit-test JVM. */
    private fun nativeAvailable(): Boolean = try {
        WebRtcVoiceActivityDetector().close()
        true
    } catch (t: Throwable) {
        // UnsatisfiedLinkError on first load; NoClassDefFoundError after a
        // failed class init — either way the native side is unavailable.
        false
    }

    // ------------------------------------------------ error contract (JVM)

    @Test
    fun `native failure is contained and reported as silence`() {
        val native = FakeNative(behavior = { throw UnsatisfiedLinkError("native gone") })
        val vad = WebRtcVoiceActivityDetector(nativeFactory = { native })

        repeat(5) {
            assertFalse("native failure must read as silence", vad.isSpeech(loudTone, loudTone.size))
        }
        vad.close()
    }

    @Test
    fun `frames shorter than the buffer are trimmed before the native call`() {
        val native = FakeNative()
        val vad = WebRtcVoiceActivityDetector(nativeFactory = { native })

        vad.isSpeech(loudTone, 160)
        assertEquals(1, native.received.size)
        assertEquals(160, native.received[0].size)
        assertArrayEquals(loudTone.copyOf(160), native.received[0])
        vad.close()
    }

    @Test
    fun `full frames are passed through unchanged`() {
        val native = FakeNative()
        val vad = WebRtcVoiceActivityDetector(nativeFactory = { native })

        vad.isSpeech(loudTone, loudTone.size)
        assertArrayEquals(loudTone, native.received[0])
        vad.close()
    }

    @Test
    fun `reset recreates the native instance and closes the old one`() {
        val first = FakeNative()
        val second = FakeNative()
        var count = 0
        val vad = WebRtcVoiceActivityDetector(nativeFactory = {
            count++
            if (count == 1) first else second
        })

        vad.isSpeech(silent, silent.size)
        vad.reset()
        vad.isSpeech(silent, silent.size)

        assertEquals(2, count)
        assertTrue("old native must be closed on reset", first.closed)
        assertFalse(second.closed)
        vad.close()
    }

    @Test
    fun `close is idempotent`() {
        val native = FakeNative()
        val vad = WebRtcVoiceActivityDetector(nativeFactory = { native })
        vad.close()
        vad.close()
        assertTrue(native.closed)
    }

    @Test
    fun `constructor failure propagates to the caller`() {
        // AudioRecorder maps this to a clear localized error (see
        // AudioRecorderTest.vad initialization failure...); the wrapper must
        // not swallow it.
        val threw = try {
            WebRtcVoiceActivityDetector(nativeFactory = { throw UnsatisfiedLinkError("no vad_jni") })
            false
        } catch (t: UnsatisfiedLinkError) {
            true
        }
        assertTrue("constructor must propagate native-load failure", threw)
    }

    // ------------------------------------------- real native (device only)

    @Test
    fun `silence is never speech when the native library is available`() {
        assumeTrue("native VAD unavailable in unit-test JVM", nativeAvailable())
        WebRtcVoiceActivityDetector().use {
            repeat(10) { frame -> assertFalse(it.isSpeech(silent, silent.size)) }
        }
    }

    @Test
    fun `loud sustained tone is speech when the native library is available`() {
        assumeTrue("native VAD unavailable in unit-test JVM", nativeAvailable())
        WebRtcVoiceActivityDetector().use { vad ->
            repeat(3) { vad.isSpeech(silent, silent.size) }
            var detected = false
            repeat(10) {
                detected = detected || vad.isSpeech(loudTone, loudTone.size)
            }
            assertTrue("loud tone must classify as speech", detected)
        }
    }
}
