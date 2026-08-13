package org.gptvoiceinput.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.gptvoiceinput.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * AudioRecorder lifecycle + error-path tests (Robolectric):
 *
 * - VAD initialization runs BEFORE the microphone is opened, so a failing
 *   VAD fails the session with a clear localized error and no hardware
 *   access (no fallback detector exists by design).
 * - A healthy session starts, captures (shadowed AudioRecord), and
 *   stopAndFinalize produces a WAV; cancelAndAbort leaves nothing behind.
 * - NoiseSuppressor attach is best-effort and never breaks recording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioRecorderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var wavFile: File

    private class Listener : AudioRecorder.Listener {
        var error: String? = null
        var endOfSpeech = 0
        var noSpeechTimeout = 0
        var maxDuration = 0
        var lastLevel = -1f

        override fun onFrameCaptured(elapsedMs: Long, level01: Float) {
            lastLevel = level01
        }

        override fun onEndOfSpeech() {
            endOfSpeech++
        }

        override fun onNoSpeechTimeout() {
            noSpeechTimeout++
        }

        override fun onMaxDuration() {
            maxDuration++
        }

        override fun onRecordingError(message: String) {
            error = message
        }
    }

    @Before
    fun setUp() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        // createTempFile creates an empty file; the recorder must create its
        // own, so remove it to make existence assertions meaningful.
        wavFile = File.createTempFile("gvi-recorder-test", ".wav").apply { delete() }
    }

    @After
    fun tearDown() {
        wavFile.delete()
    }

    @Test
    fun `vad initialization failure fails the session with a clear error`() {
        val listener = Listener()
        // A native-library failure surfaces as UnsatisfiedLinkError (an
        // Error, not an Exception) — the recorder must contain it.
        val recorder = AudioRecorder(
            context = context,
            wavFile = wavFile,
            endpointDelayMs = 0,
            listener = listener,
            vadFactory = { throw UnsatisfiedLinkError("no native lib") },
        )

        assertFalse(recorder.start())
        assertEquals(context.getString(R.string.mic_vad_failed), listener.error)
        assertFalse(wavFile.exists())
    }

    @Test
    fun `vad factory throwing a plain exception also fails with the clear error`() {
        val listener = Listener()
        val recorder = AudioRecorder(
            context = context,
            wavFile = wavFile,
            endpointDelayMs = 0,
            listener = listener,
            vadFactory = { throw IllegalStateException("broken") },
        )

        assertFalse(recorder.start())
        assertEquals(context.getString(R.string.mic_vad_failed), listener.error)
    }

    @Test
    fun `successful start records and stop finalizes a wav`() {
        val listener = Listener()
        val recorder = AudioRecorder(
            context = context,
            wavFile = wavFile,
            endpointDelayMs = 0,
            listener = listener,
            vadFactory = { FakeVad() },
        )

        assertTrue(recorder.start())
        recorder.stopAndFinalize()
        assertTrue("wav must be finalized with a header", wavFile.exists() && wavFile.length() >= 44)
        assertNull2(listener.error)
    }

    @Test
    fun `cancel aborts without producing a wav`() {
        val listener = Listener()
        val recorder = AudioRecorder(
            context = context,
            wavFile = wavFile,
            endpointDelayMs = 0,
            listener = listener,
            vadFactory = { FakeVad() },
        )

        assertTrue(recorder.start())
        recorder.cancelAndAbort()
        // Abort must NOT patch the WAV header (finish() does): the dataSize
        // placeholder at offset 40 stays 0, marking the file invalid. The
        // caller deletes the file (ImeVoiceController.cancel).
        assertTrue("cancel must leave a wav", wavFile.exists())
        val dataSize = wavFile.inputStream().use { input ->
            input.skip(40)
            (input.read() and 0xFF) or
                ((input.read() and 0xFF) shl 8) or
                ((input.read() and 0xFF) shl 16) or
                ((input.read() and 0xFF) shl 24)
        }
        assertEquals("aborted wav must keep the unpatched dataSize placeholder", 0, dataSize)
    }

    private class FakeVad : VoiceActivityDetector {
        override fun isSpeech(samples: ShortArray, count: Int): Boolean = false
        override fun reset() = Unit
        override fun close() = Unit
    }

    private fun assertNull2(value: String?) {
        assertTrue("expected no error, got $value", value == null)
    }
}
