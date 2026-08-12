package org.gptvoiceinput.ime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.gptvoiceinput.audio.AudioRecorder
import org.gptvoiceinput.audio.SessionRecorder
import org.gptvoiceinput.config.ImportedProfileStore
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.config.TranscriptionProfile
import org.gptvoiceinput.net.TranscriptionException
import org.gptvoiceinput.security.SecureApiKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * IME voice session controller tests (v1.0.0): state machine, auto-stop,
 * commit delivery, error and cancel paths — driven with a fake recorder and
 * fake transcriber, no microphone or network needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeVoiceControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeRecorder(
        private val wavFile: File,
    ) : SessionRecorder {
        var listener: AudioRecorder.Listener? = null
        var started = false
        var stopped = false
        var cancelled = false

        override fun start(): Boolean {
            started = true
            return true
        }

        override fun stopAndFinalize() {
            stopped = true
            wavFile.writeBytes(ByteArray(100)) // pretend there is audio
        }

        override fun cancelAndAbort() {
            cancelled = true
        }

        fun fireEndOfSpeech() = listener?.onEndOfSpeech()
        fun fireNoSpeech() = listener?.onNoSpeechTimeout()
    }

    private class Harness {
        val states = mutableListOf<ImeVoiceController.State>()
        var meterLevel = -1f
        var delivered: String? = null
        var recorder: FakeRecorder? = null
        var nextTranscription: (() -> String)? = null
        var nextError: Exception? = null
        val wavFile = File.createTempFile("gvi-ime-test", ".wav")
        lateinit var controller: ImeVoiceController

        fun build(context: Context): Harness {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            controller = ImeVoiceController(
                context = context,
                scope = scope,
                wavFile = wavFile,
                secureStore = SecureApiKeyStore(
                    context,
                    encryptor = object : org.gptvoiceinput.security.SecretEncryptor {
                        override fun encrypt(plaintext: String) =
                            org.gptvoiceinput.security.EncryptedBlob(
                                byteArrayOf(1),
                                plaintext.reversed().toByteArray(),
                            )

                        override fun decrypt(iv: ByteArray, ciphertext: ByteArray) =
                            String(ciphertext).reversed()
                    },
                ),
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
                },
                recorderFactory = { file, endpointMs, listener ->
                    FakeRecorder(file).also {
                        it.listener = listener
                        recorder = it
                    }
                },
                transcriberFactory = { _ ->
                    { _, _ ->
                        nextError?.let { throw it }
                        nextTranscription?.invoke() ?: "UNIQUE_TEST_TEXT"
                    }
                },
            )
            return this
        }
    }

    @Before
    fun setUp() {
        SecureApiKeyStore(
            ApplicationProvider.getApplicationContext<Context>(),
            encryptor = object : org.gptvoiceinput.security.SecretEncryptor {
                override fun encrypt(plaintext: String) =
                    org.gptvoiceinput.security.EncryptedBlob(
                        byteArrayOf(1),
                        plaintext.reversed().toByteArray(),
                    )

                override fun decrypt(iv: ByteArray, ciphertext: ByteArray) =
                    String(ciphertext).reversed()
            },
        ).save("sk-ime-test")
    }

    @After
    fun tearDown() {
        SecureApiKeyStore(
            ApplicationProvider.getApplicationContext<Context>(),
            encryptor = object : org.gptvoiceinput.security.SecretEncryptor {
                override fun encrypt(plaintext: String) =
                    org.gptvoiceinput.security.EncryptedBlob(byteArrayOf(1), byteArrayOf())

                override fun decrypt(iv: ByteArray, ciphertext: ByteArray) = ""
            },
        ).clear()
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun harness(): Harness = Harness().build(context)

    @Test
    fun `start with a key enters listening and starts the recorder`() {
        val h = harness()
        h.controller.start()
        assertEquals(ImeVoiceController.State.LISTENING, h.controller.state)
        assertTrue(h.recorder!!.started)
    }

    @Test
    fun `auto-stop transcribes and delivers the final text`() {
        val h = harness()
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
        assertTrue(h.recorder!!.stopped)
    }

    @Test
    fun `manual submit also transcribes`() {
        val h = harness()
        h.controller.start()
        h.controller.submit()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
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
        h.nextError = TranscriptionException.Network(java.io.IOException("boom"))
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals(ImeVoiceController.State.ERROR, h.controller.state)
        h.nextError = null
        h.controller.onPanelTap()
        assertEquals("UNIQUE_TEST_TEXT", h.delivered)
    }

    @Test
    fun `transcription failure moves to error and retry can recover`() {
        val h = harness()
        h.nextError = TranscriptionException.Network(java.io.IOException("boom"))
        h.controller.start()
        h.recorder!!.fireEndOfSpeech()
        assertEquals(ImeVoiceController.State.ERROR, h.controller.state)

        h.nextError = null
        h.controller.retry()
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
        assertEquals(null, h.delivered)
    }

    @Test
    fun `no-speech timeout cancels quietly`() {
        val h = harness()
        h.controller.start()
        h.recorder!!.fireNoSpeech()
        assertEquals(ImeVoiceController.State.FINISHED, h.controller.state)
        assertEquals(null, h.delivered)
    }

    @Test
    fun `no key means error state`() {
        // Clear the stored key for this test via a fresh store instance.
        SecureApiKeyStore(
            context,
            encryptor = object : org.gptvoiceinput.security.SecretEncryptor {
                override fun encrypt(plaintext: String) =
                    org.gptvoiceinput.security.EncryptedBlob(byteArrayOf(1), byteArrayOf())

                override fun decrypt(iv: ByteArray, ciphertext: ByteArray) = ""
            },
        ).clear()
        val h = harness()
        h.controller.start()
        assertEquals(ImeVoiceController.State.ERROR, h.controller.state)
    }

    @Test
    fun `meter levels flow to the callbacks`() {
        val h = harness()
        h.controller.start()
        h.recorder!!.listener!!.onFrameCaptured(100, 0.5f)
        assertEquals(0.5f, h.meterLevel, 0.001f)
    }
}
