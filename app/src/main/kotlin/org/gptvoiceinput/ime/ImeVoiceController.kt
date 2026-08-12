package org.gptvoiceinput.ime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.gptvoiceinput.audio.AudioLevelStats
import org.gptvoiceinput.audio.AudioRecorder
import org.gptvoiceinput.audio.SessionRecorder
import org.gptvoiceinput.config.AppConfig
import org.gptvoiceinput.config.ImportedProfileStore
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.config.TranscriptionProfile
import org.gptvoiceinput.net.OpenAITranscriber
import org.gptvoiceinput.net.TranscriptionException
import org.gptvoiceinput.security.SecureApiKeyStore
import java.io.File

/**
 * Voice session controller shared by the IME (and testable without a real
 * microphone or network).
 *
 * Mirrors RecognitionActivity's flow: listen (with the analysis-side VAD /
 * auto-stop / meter) → transcribe via gpt-transcribe → hand the final text to
 * the host ([Callbacks.onTranscript] commits it through the InputConnection).
 */
class ImeVoiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val wavFile: File,
    private val secureStore: SecureApiKeyStore,
    private val settingsStore: SettingsStore,
    private val importedProfileStore: ImportedProfileStore,
    private val callbacks: Callbacks,
    private val recorderFactory: (File, Int, AudioRecorder.Listener) -> SessionRecorder,
    private val transcriberFactory: (String) -> suspend (File, TranscriptionProfile) -> String,
) {

    enum class State { IDLE, LISTENING, PROCESSING, ERROR, FINISHED }

    interface Callbacks {
        fun onStateChanged(state: State)
        fun onMeterLevel(level01: Float)
        fun onTranscript(transcript: String)
    }

    var state: State = State.IDLE
        private set

    private var recorder: SessionRecorder? = null
    private var transcribeJob: Job? = null
    private var wavReady = false

    private val recorderListener = object : AudioRecorder.Listener {
        override fun onFrameCaptured(elapsedMs: Long, level01: Float) {
            callbacks.onMeterLevel(level01)
        }

        override fun onEndOfSpeech() = finishAndTranscribe()

        override fun onNoSpeechTimeout() = cancelQuietly()

        override fun onMaxDuration() = finishAndTranscribe()

        override fun onRecordingError(message: String) {
            setState(State.ERROR)
        }
    }

    /** Starts a listening session. Safe to call when already listening. */
    fun start() {
        if (state == State.LISTENING || state == State.PROCESSING) return
        if (secureStore.load().isNullOrBlank()) {
            setState(State.ERROR)
            return
        }
        wavFile.delete()
        wavReady = false
        setState(State.LISTENING)
        val recorder = recorderFactory(wavFile, settingsStore.autoStopMs, recorderListener)
        this.recorder = recorder
        if (!recorder.start()) {
            // onRecordingError already fired through the listener.
            setState(State.ERROR)
        }
    }

    /** Manual submit (Done button) or auto-stop: stop, transcribe, deliver. */
    fun submit() {
        if (state != State.LISTENING) return
        finishAndTranscribe()
    }

    /** Cancels the session (IME closed / switched away): no API request. */
    fun cancel() {
        if (state == State.FINISHED) return
        recorder?.cancelAndAbort()
        recorder = null
        transcribeJob?.cancel()
        wavFile.delete()
        wavReady = false
        setState(State.FINISHED)
    }

    fun retry() {
        if (state != State.ERROR) return
        if (!wavReady || !wavFile.exists()) {
            setState(State.ERROR)
            return
        }
        setState(State.PROCESSING)
        transcribe()
    }

    private fun finishAndTranscribe() {
        if (state != State.LISTENING) return
        setState(State.PROCESSING)
        try {
            recorder?.stopAndFinalize()
        } catch (e: Exception) {
            recorder = null
            setState(State.ERROR)
            return
        }
        recorder = null
        wavReady = true

        // Privacy-safe level diagnostics: numbers only, never audio content.
        val stats = AudioLevelStats.fromWav(wavFile)
        Log.i(
            TAG,
            "record: source=ime peakDb=${stats?.peakDb ?: "n/a"} rmsDb=${stats?.rmsDb ?: "n/a"}",
        )

        if (!wavFile.exists() || wavFile.length() == 0L) {
            setState(State.ERROR)
            return
        }
        transcribe()
    }

    private fun transcribe() {
        val key = secureStore.load() ?: run {
            setState(State.ERROR)
            return
        }
        val profile = AppConfig.load(context, importedProfileStore.load(), settingsStore.customTerms())
        transcribeJob = scope.launch {
            try {
                val transcript = transcriberFactory(key).invoke(wavFile, profile)
                Log.i(TAG, "transcribe: ok; delivering to InputConnection")
                wavFile.delete()
                wavReady = false
                callbacks.onTranscript(transcript)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranscriptionException) {
                setState(State.ERROR)
            } catch (e: Exception) {
                setState(State.ERROR)
            }
        }
    }

    private fun cancelQuietly() {
        recorder?.cancelAndAbort()
        recorder = null
        wavFile.delete()
        wavReady = false
        setState(State.FINISHED)
    }

    private fun setState(newState: State) {
        if (state == newState) return
        state = newState
        callbacks.onStateChanged(newState)
    }

    companion object {
        private const val TAG = "ImeVoiceController"

        /** Production wiring: real recorder + real OpenAI transcriber. */
        fun create(
            context: Context,
            scope: CoroutineScope,
            callbacks: Callbacks,
        ): ImeVoiceController {
            val secureStore = SecureApiKeyStore(context)
            val settingsStore = SettingsStore(context)
            val importedProfileStore = ImportedProfileStore(context)
            return ImeVoiceController(
                context = context,
                scope = scope,
                wavFile = File(context.cacheDir, "current_recording.wav"),
                secureStore = secureStore,
                settingsStore = settingsStore,
                importedProfileStore = importedProfileStore,
                callbacks = callbacks,
                recorderFactory = { file, endpointMs, listener ->
                    AudioRecorder(context, file, endpointMs, listener)
                },
                transcriberFactory = { key -> { file, profile -> OpenAITranscriber(key).transcribe(file, profile) } },
            )
        }
    }
}
