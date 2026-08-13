package org.gptvoiceinput.ime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.gptvoiceinput.audio.AudioRecorder
import org.gptvoiceinput.audio.SessionRecorder
import org.gptvoiceinput.audio.WavWriter
import org.gptvoiceinput.config.ImportedProfileStore
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.config.TranscriptionProfile
import org.gptvoiceinput.net.OpenAITranscriber
import org.gptvoiceinput.net.TranscriptionException
import org.gptvoiceinput.security.SecureApiKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/**
 * IME voice session controller tests (v1.0.6): state machine, error
 * classification, panel-tap semantics, cancellation races, and a real
 * controller + OpenAITranscriber + MockWebServer integration path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeVoiceControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeRecorder(
        private val wavFile: File,
        val writeAudio: Boolean = true,
        val failStart: Boolean = false,
    ) : SessionRecorder {
        var listener: AudioRecorder.Listener? = null
        var started = false
        var stopped = false
        var cancelled = false

        override fun start(): Boolean {
            if (failStart) return false
            started = true
            return true
        }

        override fun stopAndFinalize() {
            stopped = true
            if (writeAudio) wavFile.writeBytes(ByteArray(100)) // pretend audio
        }

        override fun cancelAndAbort() {
            cancelled = true
        }

        fun fireEndOfSpeech() = listener?.onEndOfSpeech()
        fun fireNoSpeech() = listener?.onNoSpeechTimeout()
    }

    private class Harness(
        private val context: Context,
        private val encryptor: org.gptvoiceinput.security.SecretEncryptor,
    ) {
        val states = mutableListOf<ImeVoiceController.State>()
        val errors = mutableListOf<ImeVoiceController.ImeError>()
        var meterLevel = -1f
        var delivered: String? = null
        var recorder: FakeRecorder? = null
        var nextTranscription: (() -> String)? = null
        var nextError: Exception? = null
        var usedFile: File? = null
        var fakeTranscriber: FakeTranscriber? = null
        val wavFile = File.createTempFile("gvi-ime-test", ".wav")
        lateinit var controller: ImeVoiceController

        fun build(
            recorderFactory: ((File, Int, AudioRecorder.Listener) -> SessionRecorder)? = null,
            transcriberFactory: ((String) -> suspend (File, TranscriptionProfile) -> String)? = null,
        ): Harness {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val defaultRecorder: (File, Int, AudioRecorder.Listener) -> SessionRecorder = { file, _, listener ->
                FakeRecorder(file).also {
                    it.listener = listener
                    recorder = it
                }
            }
            val defaultTranscriber: (String) -> suspend (File, TranscriptionProfile) -> String = { _ ->
                { file, _ ->
                    usedFile = file
                    nextError?.let { throw it }
                    nextTranscription?.invoke() ?: "UNIQUE_TEST_TEXT"
                }
            }
            controller = ImeVoiceController(
                context = context,
                scope = scope,
                wavFile = wavFile,
                secureStore = SecureApiKeyStore(context, encryptor = encryptor),
                settingsStore = SettingsStore(context),
                importedProfileStore = ImportedProfileStore(context),
                callbacks = object : ImeVoiceController.Callbacks {
                    override fun onStateChanged(state: ImeVoiceController.State) {
                        states.add(state)
                    }

                    override fun onMeterLevel(level01: Float) {
                        meterLevel = level01
                    }

                    override fun onTranscript(transcript: String) {
                        delivered = transcript
                    }

                    override fun onError(error: ImeVoiceController.ImeError) {
                        errors.add(error)
                    }
                },
                recorderFactory = recorderFactory ?: defaultRecorder,
                transcriberFactory = transcriberFactory ?: defaultTranscriber,
            )
            return this
        }
    }

    private class FakeTranscriber : (String) -> suspend (File, TranscriptionProfile) -> String {
        var latch: (suspend () -> Unit)? = null
        var result: String = "UNIQUE_TEST_TEXT"
        var error: Exception? = null

        override fun invoke(key: String): suspend (File, TranscriptionProfile) -> String = { file, _ ->
            latch?.invoke() // test can block here to race cancellation
            error?.let { throw it }
            result
        }
    }

    private fun fakeEncryptor() = object : org.gptvoiceinput.security.SecretEncryptor {
        override fun encrypt(plaintext: String) =
            org.gptvoiceinput.security.EncryptedBlob(
                byteArrayOf(1),
                plaintext.reversed().toByteArray(),
            )

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray) = String(ciphertext).reversed()
    }

    private fun store() = SecureApiKeyStore(
        ApplicationProvider.getApplicationContext<Context>(),
        encryptor = fakeEncryptor(),
    )

    @Before
    fun setUp() {
        store().save("sk-ime-test")
    }

    @After
    fun tearDown() {
        store().clear()
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun harness(
        recorderFactory: ((File, Int, AudioRecorder.Listener) -> SessionRecorder)? = null,
        transcriberFactory: ((String) -> suspend (File, TranscriptionProfile) -> String)? = null,
    ): Harness = Harness(context, fakeEncryptor()).build(recorderFactory, transcriberFactory)

    // ------------------------------------------------------------- basics

    @Test
    fun `start with a key enters listening and starts the recorder`() {
        val h = harness()
        h.controller.start()
        assertEquals(ImeVoiceController.State.LISTENING, h.controller.state)
        assertTrue(h.recorder!!.started)
    }

    @Test
    fun `state sequence for the happy path is listen - process - transcript`() {
        val h = harness()
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
        assertEquals(
            listOf(
                ImeVoiceController.State.LISTENING,
                ImeVoiceController.State.PROCESSING,
            ),
            h.states,
        )
    }

    @Test
    fun `manual submit also transcribes`() {
        val h = harness()
        h.controller.start()
        h.controller.submit()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
        assertTrue(h.recorder!!.stopped)
    }

    @Test
    fun `panel tap submits while listening`() {
        val h = harness()
        h.controller.start()
        h.controller.onPanelTap()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
    }

    @Test
    fun `panel tap retries after an error`() {
        val h = harness()
        h.nextError = TranscriptionException.Network(IOException("boom"))
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals(ImeVoiceController.State.ERROR, h.controller.state)
        h.nextError = null
        h.controller.onPanelTap()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
    }

    @Test
    fun `cancel stops the recorder, deletes audio and finishes without a request`() {
        val h = harness()
        h.controller.start()
        h.recorder!!.stopAndFinalize() // pretend audio exists
        h.controller.cancel()
        assertEquals(ImeVoiceController.State.FINISHED, h.controller.state)
        assertTrue(h.recorder!!.cancelled)
        assertFalse(h.wavFile.exists())
        assertNull(h.delivered)
    }

    @Test
    fun `no-speech timeout cancels quietly`() {
        val h = harness()
        h.controller.start()
        h.recorder!!.fireNoSpeech()
        assertEquals(ImeVoiceController.State.FINISHED, h.controller.state)
        assertNull(h.delivered)
    }

    @Test
    fun `meter levels flow to the callbacks`() {
        val h = harness()
        h.controller.start()
        h.recorder!!.listener!!.onFrameCaptured(100, 0.5f)
        assertEquals(0.5f, h.meterLevel, 0.001f)
    }

    // ------------------------------------------------------- error mapping

    @Test
    fun `no key maps to NO_API_KEY`() {
        store().clear()
        val h = harness()
        h.controller.start()
        assertEquals(ImeVoiceController.ImeError.NO_API_KEY, h.controller.lastError)
        assertEquals(ImeVoiceController.State.ERROR, h.controller.state)
    }

    @Test
    fun `recorder start failure maps to RECORDING_FAILED`() {
        val holder = Harness(context, fakeEncryptor())
        val factory: (File, Int, AudioRecorder.Listener) -> SessionRecorder = { file, _, listener ->
            FakeRecorder(file, failStart = true).also { it.listener = listener; holder.recorder = it }
        }
        val h = holder.build(recorderFactory = factory)
        h.controller.start()
        assertEquals(ImeVoiceController.ImeError.RECORDING_FAILED, h.controller.lastError)
    }

    @Test
    fun `empty recording maps to RECORDING_FAILED`() {
        val holder = Harness(context, fakeEncryptor())
        val factory: (File, Int, AudioRecorder.Listener) -> SessionRecorder = { file, _, listener ->
            FakeRecorder(file, writeAudio = false).also { it.listener = listener; holder.recorder = it }
        }
        val h = holder.build(recorderFactory = factory)
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals(ImeVoiceController.ImeError.RECORDING_FAILED, h.controller.lastError)
        assertNull(h.delivered)
    }

    @Test
    fun `every transcription error maps to its classified type`() {
        val cases = listOf(
            TranscriptionException.Unauthorized() to ImeVoiceController.ImeError.AUTH,
            TranscriptionException.RateLimited() to ImeVoiceController.ImeError.RATE_LIMITED,
            TranscriptionException.ServerError(503) to ImeVoiceController.ImeError.SERVER,
            TranscriptionException.ApiError(400) to ImeVoiceController.ImeError.API_ERROR,
            TranscriptionException.Timeout(IOException("t")) to ImeVoiceController.ImeError.TIMEOUT,
            TranscriptionException.Network(IOException("n")) to ImeVoiceController.ImeError.NETWORK,
            TranscriptionException.Protocol("p") to ImeVoiceController.ImeError.PROTOCOL,
        )
        for ((exception, expected) in cases) {
            val h = harness()
            h.nextError = exception
            h.controller.start()
            h.recorder!!.fireEndOfSpeech()
            assertEquals("$exception -> $expected", expected, h.controller.lastError)
            assertEquals(expected, h.errors.last())
        }
    }

    @Test
    fun `action for error routes settings-fixable errors to settings`() {
        assertEquals(
            ImeVoiceController.PanelAction.OPEN_SETTINGS,
            ImeVoiceController.actionForError(ImeVoiceController.ImeError.NO_API_KEY),
        )
        assertEquals(
            ImeVoiceController.PanelAction.OPEN_SETTINGS,
            ImeVoiceController.actionForError(ImeVoiceController.ImeError.AUTH),
        )
        for (e in listOf(
            ImeVoiceController.ImeError.RECORDING_FAILED,
            ImeVoiceController.ImeError.RATE_LIMITED,
            ImeVoiceController.ImeError.SERVER,
            ImeVoiceController.ImeError.API_ERROR,
            ImeVoiceController.ImeError.TIMEOUT,
            ImeVoiceController.ImeError.NETWORK,
            ImeVoiceController.ImeError.PROTOCOL,
        )) {
            assertEquals(ImeVoiceController.PanelAction.RETRY, ImeVoiceController.actionForError(e))
        }
    }

    // ------------------------------------------------------- cancellation race

    @Test
    fun `cancel during an in-flight transcription does not deliver text`() {
        val fake = FakeTranscriber()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.latch = { gate.await() }
        val h = harness(transcriberFactory = fake)

        h.controller.start()
        h.recorder!!.fireEndOfSpeech() // enters PROCESSING, transcriber blocks on gate
        assertEquals(ImeVoiceController.State.PROCESSING, h.controller.state)

        h.controller.cancel()
        gate.complete(Unit) // release the transcriber after the cancel
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(10) }

        assertEquals(ImeVoiceController.State.FINISHED, h.controller.state)
        assertNull(h.delivered)
        assertFalse(h.wavFile.exists())
    }

    // ------------------------------------------------------------ integration

    @Test
    fun `controller plus real OpenAI transcriber against MockWebServer`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody("""{"text":"你好 world","languages":[{"code":"zh"}]}"""),
            )
            val realTranscriber: (String) -> suspend (File, TranscriptionProfile) -> String = { key ->
                { file, profile ->
                    OpenAITranscriber(
                        key,
                        endpoint = server.url("/").toString(),
                    ).transcribe(file, profile)
                }
            }
            val holder = Harness(context, fakeEncryptor())
            val factory: (File, Int, AudioRecorder.Listener) -> SessionRecorder = { file, _, listener ->
                // Real WAV so the transcribe path can read it.
                FakeRecorder(file).also { it.listener = listener; holder.recorder = it }
            }
            val h = holder.build(
                recorderFactory = factory,
                transcriberFactory = realTranscriber,
            )
            h.controller.start()
            h.recorder!!.fireEndOfSpeech()

            // The real transcriber runs on Dispatchers.IO — poll for delivery.
            val deadline = System.currentTimeMillis() + 5000
            while (h.delivered == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals("你好 world", h.delivered)
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest()
            assertEquals("Bearer sk-ime-test", recorded.getHeader("Authorization"))
            assertTrue(recorded.body.readUtf8().contains("name=\"file\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `retry reuses the same recorded wav`() {
        val h = harness()
        h.nextError = TranscriptionException.Network(IOException("boom"))
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        h.nextError = null
        h.controller.retry()
        assertEquals(h.wavFile.absolutePath, h.usedFile!!.absolutePath)
    }

    // ------------------------------------------------------- short taps

    @Test
    fun `recording under 500ms is discarded and a fresh session starts`() {
        val h = harness()
        h.controller.start()
        // 100ms of frames, then an immediate submit = accidental tap.
        h.recorder!!.listener!!.onFrameCaptured(100, 0.3f)
        h.controller.submit()
        assertNull(h.delivered)
        assertTrue(h.errors.isEmpty())
        assertFalse(h.wavFile.exists())
        // Back to listening automatically (no dead-end error UI).
        assertEquals(ImeVoiceController.State.LISTENING, h.controller.state)
        assertTrue(h.recorder!!.started)
    }

    @Test
    fun `recording of at least 500ms is transcribed normally`() {
        val h = harness()
        h.controller.start()
        h.recorder!!.listener!!.onFrameCaptured(500, 0.3f)
        h.controller.submit()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
    }

    // ------------------------------------------------------- no words

    @Test
    fun `empty transcript maps to NO_WORDS and deletes the wav`() {
        val h = harness()
        h.nextTranscription = { "   " }
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals(ImeVoiceController.ImeError.NO_WORDS, h.errors.lastOrNull())
        assertEquals(ImeVoiceController.State.ERROR, h.controller.state)
        assertNull(h.delivered)
        assertFalse(h.wavFile.exists())
    }

    @Test
    fun `punctuation-only transcript maps to NO_WORDS`() {
        val h = harness()
        h.nextTranscription = { "。，！？" }
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals(ImeVoiceController.ImeError.NO_WORDS, h.errors.lastOrNull())
        assertNull(h.delivered)
    }

    @Test
    fun `single-character transcript maps to NO_WORDS`() {
        val h = harness()
        h.nextTranscription = { "嗯" }
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals(ImeVoiceController.ImeError.NO_WORDS, h.errors.lastOrNull())
        assertNull(h.delivered)
    }

    @Test
    fun `controller resets to idle after delivery so the next session starts fresh`() {
        val h = harness()
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
        // Second session: must start listening again, not re-transcribe.
        h.controller.start()
        assertEquals(ImeVoiceController.State.LISTENING, h.controller.state)
        assertTrue(h.recorder!!.started)
        h.recorder!!.fireEndOfSpeech()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
    }

    @Test
    fun `action for NO_WORDS routes to RESTART`() {
        assertEquals(
            ImeVoiceController.PanelAction.RESTART,
            ImeVoiceController.actionForError(ImeVoiceController.ImeError.NO_WORDS),
        )
    }

    @Test
    fun `hasMeaningfulText accepts real words and rejects noise`() {
        assertTrue(ImeVoiceController.hasMeaningfulText("Hello world"))
        assertTrue(ImeVoiceController.hasMeaningfulText("你好世界"))
        assertTrue(ImeVoiceController.hasMeaningfulText("OK"))
        assertFalse(ImeVoiceController.hasMeaningfulText(""))
        assertFalse(ImeVoiceController.hasMeaningfulText("   "))
        assertFalse(ImeVoiceController.hasMeaningfulText("。"))
        assertFalse(ImeVoiceController.hasMeaningfulText("嗯"))
        assertFalse(ImeVoiceController.hasMeaningfulText("...!?"))
    }
}
