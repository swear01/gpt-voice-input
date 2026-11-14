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
 *   stopAndFinalize finishes the upload sink; cancelAndAbort aborts it.
 * - NoiseSuppressor attach is best-effort and never breaks recording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioRecorderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var audioFile: File

    private class Listener : AudioRecorder.Listener {
        @Volatile var error: String? = null
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
        audioFile = File.createTempFile("gvi-recorder-test", ".ogg").apply { delete() }
    }

    @After
    fun tearDown() {
        audioFile.delete()
    }

    @Test
    fun `vad initialization failure fails the session with a clear error`() {
        val listener = Listener()
        // A native-library failure surfaces as UnsatisfiedLinkError (an
        // Error, not an Exception) — the recorder must contain it.
        val recorder = AudioRecorder(
            context = context,
            audioFile = audioFile,
            endpointDelayMs = 0,
            listener = listener,
            vadFactory = { throw UnsatisfiedLinkError("no native lib") },
        )

        assertFalse(recorder.start())
        assertEquals(context.getString(R.string.mic_vad_failed), listener.error)
        assertFalse(audioFile.exists())
    }

    @Test
    fun `vad factory throwing a plain exception also fails with the clear error`() {
        val listener = Listener()
        val recorder = AudioRecorder(
            context = context,
            audioFile = audioFile,
            endpointDelayMs = 0,
            listener = listener,
            vadFactory = { throw IllegalStateException("broken") },
        )

        assertFalse(recorder.start())
        assertEquals(context.getString(R.string.mic_vad_failed), listener.error)
    }

    @Test
    fun `successful start records and stop finishes the sink`() {
        val listener = Listener()
        val sink = FakeSink()
        val recorder = AudioRecorder(
            context = context,
            audioFile = audioFile,
            endpointDelayMs = 0,
            listener = listener,
            vadFactory = { FakeVad() },
            sinkFactory = { file, rate -> sink.also { it.file = file; it.rate = rate } },
        )

        assertTrue(recorder.start())
        recorder.stopAndFinalize()
        assertEquals(audioFile, sink.file)
        assertTrue("capture rate must be an Opus rate", sink.rate in intArrayOf(48_000, 16_000, 8_000))
        assertTrue(sink.finished)
        assertFalse(sink.aborted)
        assertNull2(listener.error)
    }

    @Test
    fun `cancel aborts the sink without finishing it`() {
        val listener = Listener()
        val sink = FakeSink()
        val recorder = AudioRecorder(
            context = context,
            audioFile = audioFile,
            endpointDelayMs = 0,
            listener = listener,
            vadFactory = { FakeVad() },
            sinkFactory = { _, _ -> sink },
        )

        assertTrue(recorder.start())
        recorder.cancelAndAbort()
        assertTrue(sink.aborted)
        assertFalse(sink.finished)
    }

    @Test
    fun `encoder failure stops capture with an error instead of dying silently`() {
        val listener = Listener()
        val sink = FakeSink(failAppend = true)
        val recorder = AudioRecorder(
            context = context,
            audioFile = audioFile,
            endpointDelayMs = 0,
            listener = listener,
            vadFactory = { FakeVad() },
            sinkFactory = { _, _ -> sink },
        )

        assertTrue(recorder.start())
        val deadline = System.currentTimeMillis() + 5_000
        while (listener.error == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(context.getString(R.string.mic_read_failed), listener.error)
        recorder.cancelAndAbort()
        assertTrue(sink.aborted)
    }

    private class FakeSink(private val failAppend: Boolean = false) : PcmSink {
        var file: File? = null
        var rate = 0
        var finished = false
        var aborted = false
        override fun appendPcm(samples: ShortArray, count: Int) {
            if (failAppend) throw IllegalStateException("encoder broke")
        }
        override fun finish() {
            finished = true
        }
        override fun abort() {
            aborted = true
        }
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
