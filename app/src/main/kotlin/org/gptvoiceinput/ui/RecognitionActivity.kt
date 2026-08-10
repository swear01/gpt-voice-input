package org.gptvoiceinput.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.gptvoiceinput.R
import org.gptvoiceinput.audio.AudioRecorder
import org.gptvoiceinput.config.AppConfig
import org.gptvoiceinput.config.ImportedProfileStore
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.net.OpenAITranscriber
import org.gptvoiceinput.net.TranscriptionException
import org.gptvoiceinput.security.SecureApiKeyStore
import java.io.File

/**
 * The one and only entry point: launched through ACTION_RECOGNIZE_SPEECH
 * (e.g. from SwiftKey's microphone button), records speech, transcribes it
 * with OpenAI gpt-transcribe, and returns the final text through
 * RecognizerIntent.EXTRA_RESULTS.
 *
 * The Activity stays open during the API request; it finishes only after the
 * final transcript is available (or the session is cancelled/errored).
 */
class RecognitionActivity : AppCompatActivity() {

    private enum class Phase { NO_KEY, NO_PERMISSION, LISTENING, PROCESSING, ERROR, DONE }

    private lateinit var secureStore: SecureApiKeyStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var importedProfileStore: ImportedProfileStore

    private lateinit var rootView: FrameLayout
    private lateinit var gearButton: ImageButton
    private lateinit var micIcon: View
    private lateinit var processingBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var errorButtons: View
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button
    private lateinit var settingsActionButton: Button

    private var phase: Phase = Phase.NO_KEY
    private var recorder: AudioRecorder? = null
    private var transcribeJob: Job? = null
    private var wavReady = false

    private val tempWav: File
        get() = File(cacheDir, TEMP_RECORDING_NAME)

    private val recorderListener = object : AudioRecorder.Listener {
        override fun onFrameCaptured(elapsedMs: Long) = Unit

        override fun onEndOfSpeech() = runOnUiThread { submit() }

        override fun onNoSpeechTimeout() = runOnUiThread { cancelGracefully() }

        override fun onMaxDuration() = runOnUiThread { submit() }

        override fun onRecordingError(message: String) =
            runOnUiThread { showRecordingError(message) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognition)

        secureStore = SecureApiKeyStore(this)
        settingsStore = SettingsStore(this)
        importedProfileStore = ImportedProfileStore(this)

        rootView = findViewById(R.id.root)
        gearButton = findViewById(R.id.gear_button)
        micIcon = findViewById(R.id.mic_icon)
        processingBar = findViewById(R.id.processing_bar)
        statusText = findViewById(R.id.status_text)
        hintText = findViewById(R.id.hint_text)
        errorButtons = findViewById(R.id.error_buttons)
        retryButton = findViewById(R.id.retry_button)
        cancelButton = findViewById(R.id.cancel_button)
        settingsActionButton = findViewById(R.id.settings_action_button)

        rootView.setOnClickListener {
            if (phase == Phase.LISTENING) submit()
        }
        gearButton.setOnClickListener { openSettings() }
        retryButton.setOnClickListener { retry() }
        cancelButton.setOnClickListener { cancelSession() }
        settingsActionButton.setOnClickListener { openSettings() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelSession()
        })

        // Secondary safeguard: remove stale recordings from a crashed session.
        tempWav.delete()

        decideNext()
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from Settings / system permission screen.
        when (phase) {
            Phase.NO_KEY -> decideNext()
            Phase.NO_PERMISSION -> {
                if (hasMicPermission()) {
                    startListening()
                } else if (
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.RECORD_AUDIO,
                    )
                ) {
                    // Denied once but not permanently: offer the dialog again.
                    requestMicPermission()
                }
                // else: permanently denied — stay on the NO_PERMISSION screen.
            }
            else -> Unit
        }
    }

    override fun onStop() {
        super.onStop()
        // Never keep an invisible recording session alive. App switch, lock,
        // Home or a covering activity all cancel the session like a normal
        // recognizer would.
        if (phase == Phase.LISTENING) cancelSession()
    }

    override fun onDestroy() {
        recorder?.cancelAndAbort()
        recorder = null
        transcribeJob?.cancel()
        if (phase != Phase.DONE) tempWav.delete()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second launch while a session is active is ignored (singleTask).
    }

    // ------------------------------------------------------------------ state

    private fun decideNext() {
        val key = secureStore.load()
        if (key.isNullOrBlank()) {
            showNoKey()
            return
        }
        if (!hasMicPermission()) {
            requestMicPermission()
            return
        }
        startListening()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_MIC_PERMISSION,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            showNoPermission()
        }
    }

    // ------------------------------------------------------------------ flow

    private fun startListening() {
        if (phase == Phase.PROCESSING || phase == Phase.DONE) return
        tempWav.delete()
        wavReady = false
        setPhase(Phase.LISTENING)

        val endpointMs = (settingsStore.autoStopSeconds * 1000.0).toInt() // 0 = off
        recorder = AudioRecorder(
            context = this,
            wavFile = tempWav,
            endpointDelayMs = endpointMs,
            listener = recorderListener,
        )
        if (!recorder!!.start()) {
            // onRecordingError already fired; state is set there.
        }
    }

    /** Stop recording and transcribe what we captured. Shared by tap and auto-stop. */
    private fun submit() {
        if (phase != Phase.LISTENING) return
        setPhase(Phase.PROCESSING)

        try {
            recorder?.stopAndFinalize()
        } catch (e: Exception) {
            recorder = null
            showRecordingError("Could not save the recording")
            return
        }
        recorder = null
        wavReady = true

        if (!tempWav.exists() || tempWav.length() == 0L) {
            showRecordingError("No audio was captured")
            return
        }

        transcribe()
    }

    private fun transcribe() {
        val key = secureStore.load()
        if (key.isNullOrBlank()) {
            showNoKey()
            return
        }

        transcribeJob = lifecycleScope.launch {
            try {
                val profile = AppConfig.load(
                    this@RecognitionActivity,
                    importedProfileStore.load(),
                    settingsStore.customTerms(),
                )
                val transcriber = OpenAITranscriber(key)
                val transcript = transcriber.transcribe(tempWav, profile)
                deliverResult(transcript)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranscriptionException) {
                showError(e)
            } catch (e: Exception) {
                showError(TranscriptionException.Network(e))
            }
        }
    }

    private fun retry() {
        if (phase != Phase.ERROR) return
        if (!wavReady || !tempWav.exists()) {
            showError(TranscriptionException.Protocol("The recording is gone; please record again"))
            return
        }
        setPhase(Phase.PROCESSING)
        transcribe()
    }

    private fun deliverResult(transcript: String) {
        phase = Phase.DONE
        tempWav.delete()
        wavReady = false

        val result = Intent()
            .putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS,
                arrayListOf(transcript),
            )
        setResult(RESULT_OK, result)
        finish()
    }

    /** User back / system dismissal: no API request made where possible. */
    private fun cancelSession() {
        if (phase == Phase.DONE) return
        recorder?.cancelAndAbort()
        recorder = null
        transcribeJob?.cancel()
        phase = Phase.DONE
        tempWav.delete()
        setResult(RESULT_CANCELED)
        finish()
    }

    /** No-speech timeout: graceful cancel without an API request. */
    private fun cancelGracefully() {
        recorder?.cancelAndAbort()
        recorder = null
        phase = Phase.DONE
        tempWav.delete()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ------------------------------------------------------------------ UI

    private fun showNoKey() {
        phase = Phase.NO_KEY
        setPhase(Phase.NO_KEY)
        statusText.setText(R.string.status_no_key)
        hintText.setText(R.string.hint_no_key)
    }

    private fun showNoPermission() {
        phase = Phase.NO_PERMISSION
        setPhase(Phase.NO_PERMISSION)
        statusText.setText(R.string.status_no_permission)
        hintText.setText(R.string.hint_no_permission)
    }

    private fun showRecordingError(message: String) {
        setPhase(Phase.ERROR)
        statusText.setText(R.string.status_recording_failed)
        hintText.text = message
        errorButtons.visibility = View.VISIBLE
        retryButton.visibility = View.GONE // no usable recording to retry
    }

    private fun showError(error: TranscriptionException) {
        setPhase(Phase.ERROR)
        statusText.setText(R.string.status_transcribe_failed)
        hintText.text = error.localizedMessage ?: getString(R.string.hint_error_generic)
        errorButtons.visibility = View.VISIBLE
        retryButton.visibility = if (wavReady && tempWav.exists()) View.VISIBLE else View.GONE
    }

    private fun setPhase(newPhase: Phase) {
        phase = newPhase
        micIcon.visibility = if (newPhase == Phase.PROCESSING) View.GONE else View.VISIBLE
        processingBar.visibility = if (newPhase == Phase.PROCESSING) View.VISIBLE else View.GONE
        errorButtons.visibility = View.GONE
        settingsActionButton.visibility = View.GONE

        when (newPhase) {
            Phase.LISTENING -> {
                statusText.setText(R.string.status_listening)
                hintText.setText(R.string.hint_tap_to_submit)
            }
            Phase.PROCESSING -> {
                statusText.setText(R.string.status_transcribing)
                hintText.text = ""
            }
            Phase.NO_KEY -> {
                statusText.setText(R.string.status_no_key)
                hintText.setText(R.string.hint_no_key)
                settingsActionButton.visibility = View.VISIBLE
            }
            Phase.NO_PERMISSION -> {
                statusText.setText(R.string.status_no_permission)
                hintText.setText(R.string.hint_no_permission)
                settingsActionButton.visibility = View.VISIBLE
            }
            Phase.ERROR, Phase.DONE -> Unit
        }
    }

    companion object {
        private const val TAG = "RecognitionActivity"
        private const val TEMP_RECORDING_NAME = "current_recording.wav"
        private const val REQUEST_MIC_PERMISSION = 1001
    }
}
