package org.gptvoiceinput.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.gptvoiceinput.R
import org.gptvoiceinput.audio.AudioLevelStats
import org.gptvoiceinput.audio.AudioRecorder
import org.gptvoiceinput.config.AppConfig
import org.gptvoiceinput.config.ImportedProfileStore
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.ime.ImeVoiceController
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
    private lateinit var panelView: View
    private lateinit var gearButton: ImageButton
    private lateinit var micIcon: View
    private lateinit var meterContainer: View
    private lateinit var meterBars: List<View>
    private lateinit var processingBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var errorButtons: View
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button
    private lateinit var openSettingsErrorButton: Button
    private lateinit var settingsActionButton: Button

    private var phase: Phase = Phase.NO_KEY
    private var recorder: AudioRecorder? = null
    private var transcribeJob: Job? = null
    private var wavReady = false

    /** Incoming result-delivery route (activity result vs PendingIntent). */
    private var pendingIntent: PendingIntent? = null
    private var pendingIntentBundle: Bundle? = null

    /** Throttles meter updates to ~30 fps without touching layout. */
    @Volatile
    private var lastMeterUiMs = 0L

    /** Capture length (ms) of the current recording, for the min-duration check. */
    @Volatile
    private var lastElapsedMs = 0L

    private val tempWav: File
        get() = File(cacheDir, TEMP_RECORDING_NAME)

    private val recorderListener = object : AudioRecorder.Listener {
        override fun onFrameCaptured(elapsedMs: Long, level01: Float) {
            // Track the capture length on EVERY frame (the meter update below
            // is rate-limited and must not skew the min-duration check).
            lastElapsedMs = elapsedMs
            // Bounded update rate (~30 fps): cheap skip before posting.
            val now = SystemClock.uptimeMillis()
            if (now - lastMeterUiMs < METER_UI_INTERVAL_MS) return
            lastMeterUiMs = now
            runOnUiThread { renderMeter(level01) }
        }

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
        panelView = findViewById(R.id.panel)
        gearButton = findViewById(R.id.gear_button)
        micIcon = findViewById(R.id.mic_icon)
        meterContainer = findViewById(R.id.mic_level_container)
        meterBars = (meterContainer as android.view.ViewGroup).let { vg ->
            (0 until vg.childCount).map { vg.getChildAt(it) }
        }
        processingBar = findViewById(R.id.processing_bar)
        statusText = findViewById(R.id.status_text)
        hintText = findViewById(R.id.hint_text)
        errorButtons = findViewById(R.id.error_buttons)
        retryButton = findViewById(R.id.retry_button)
        cancelButton = findViewById(R.id.cancel_button)
        openSettingsErrorButton = findViewById(R.id.open_settings_error_button)
        settingsActionButton = findViewById(R.id.settings_action_button)

        // Tap anywhere on the panel = stop + submit (primary interaction).
        panelView.setOnClickListener {
            if (phase == Phase.LISTENING) submit()
        }
        // Bars start dim; they light progressively with input level.
        meterBars.forEach { it.alpha = BAR_DIM_ALPHA }

        inspectIncomingIntent(intent)
        val owner = sessionOwner
        if (owner != null && owner !== this) {
            // A recognition session is already running (duplicate launch):
            // refuse cleanly instead of opening a second microphone. The
            // caller of THIS instance gets a cancelled result.
            Log.i(TAG, "session: duplicate launch refused (owner=${owner.hashCode()})")
            finishSession(RESULT_CANCELED, null)
            return
        }
        sessionOwner = this
        gearButton.setOnClickListener { openSettings() }
        retryButton.setOnClickListener { retry() }
        cancelButton.setOnClickListener { cancelSession() }
        openSettingsErrorButton.setOnClickListener { openSettings() }
        settingsActionButton.setOnClickListener { openSettings() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelSession()
        })

        // Secondary safeguard: remove stale recordings from a crashed session.
        tempWav.delete()

        applyPanelMetrics()
        decideNext()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation is handled via configChanges; re-fit the panel height so a
        // landscape/split-screen window still shows a keyboard-like panel.
        applyPanelHeight()
    }

    /**
     * Keyboard-height, bottom-anchored panel. Height is a bounded fraction of
     * the window height (screenHeightDp reflects multi-window bounds), and the
     * gesture/navigation-bar inset becomes padding inside the panel.
     */
    private fun applyPanelMetrics() {
        applyPanelHeight()
        ViewCompat.setOnApplyWindowInsetsListener(panelView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                bars.bottom,
            )
            insets
        }
    }

    private fun applyPanelHeight() {
        val heightDp = resources.configuration.screenHeightDp
        val panelDp = (heightDp * PANEL_HEIGHT_FRACTION)
            .toInt()
            .coerceIn(PANEL_HEIGHT_MIN_DP, PANEL_HEIGHT_MAX_DP)
        val lp = panelView.layoutParams
        lp.height = (panelDp * resources.displayMetrics.density).toInt()
        panelView.layoutParams = lp
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
        if (sessionOwner === this) sessionOwner = null
        super.onDestroy()
    }

    /**
     * Privacy-safe inspection of the incoming ACTION_RECOGNIZE_SPEECH intent:
     * action, flags, result-route extras, and caller. Never logs spoken text,
     * API keys, audio or personal configuration.
     */
    private fun inspectIncomingIntent(intent: Intent) {
        pendingIntent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT)
        }
        pendingIntentBundle = intent.getBundleExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE)
        val caller = callingActivity?.className ?: getReferrer()?.host
        Log.i(
            TAG,
            "incoming action=${intent.action} flags=0x${Integer.toHexString(intent.flags)}" +
                " hasPendingIntent=${pendingIntent != null}" +
                " hasPendingBundle=${pendingIntentBundle != null}" +
                " caller=${caller ?: "unknown"}",
        )
    }

    /** Decides the delivery route: PendingIntent forwarding vs Activity result. */
    internal fun deliveryPlan(intent: Intent): DeliveryPlan {
        val hasPi = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, PendingIntent::class.java) != null
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<PendingIntent>(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT) != null
        }
        return if (hasPi) DeliveryPlan(viaPendingIntent = true, viaActivityResult = false)
        else DeliveryPlan(viaPendingIntent = false, viaActivityResult = true)
    }

    internal data class DeliveryPlan(val viaPendingIntent: Boolean, val viaActivityResult: Boolean)

    /** Test hook: current phase (for diagnosing duplicate-session tests). */
    internal fun phaseForTest(): String = phase.name

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

    private fun startListening(force: Boolean = false) {
        if (!force && (phase == Phase.PROCESSING || phase == Phase.DONE)) return
        tempWav.delete()
        wavReady = false
        lastMeterUiMs = 0L
        lastElapsedMs = 0L
        setPhase(Phase.LISTENING)

        val endpointMs = settingsStore.autoStopMs // 0 = off
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

        val source = recorder?.sourceDescription
        try {
            recorder?.stopAndFinalize()
        } catch (e: Exception) {
            recorder = null
            showRecordingError(getString(R.string.rec_error_save))
            return
        }
        recorder = null
        wavReady = true

        // Accidental tap (no speech yet): discard the capture silently and
        // go back to listening instead of transcribing silence/noise.
        if (lastElapsedMs in 1 until MIN_RECORDING_MS) {
            Log.i(TAG, "record: ${lastElapsedMs}ms < ${MIN_RECORDING_MS}ms; discarding (accidental tap)")
            tempWav.delete()
            wavReady = false
            startListening(force = true)
            return
        }

        // Privacy-safe level diagnostics: numbers only, never audio content.
        // Confirms whether the captured signal is too quiet for transcription.
        val stats = AudioLevelStats.fromWav(tempWav)
        Log.i(
            TAG,
            "record: source=$source peakDb=${stats?.peakDb ?: "n/a"} rmsDb=${stats?.rmsDb ?: "n/a"}",
        )

        if (!tempWav.exists() || tempWav.length() == 0L) {
            showRecordingError(getString(R.string.rec_error_no_audio))
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
                if (!ImeVoiceController.hasMeaningfulText(transcript)) {
                    Log.i(TAG, "transcribe: no meaningful words; NO_WORDS")
                    tempWav.delete()
                    wavReady = false
                    showNoWords()
                    return@launch
                }
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
            // Nothing usable to re-transcribe (e.g. no-words result): record
            // again instead of showing a dead-end error.
            startListening(force = true)
            return
        }
        setPhase(Phase.PROCESSING)
        transcribe()
    }

    internal fun deliverResult(transcript: String) {
        phase = Phase.DONE
        tempWav.delete()
        wavReady = false

        Log.i(TAG, "deliver: transcription ok; result payload created")
        val result = Intent()
            .putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS,
                arrayListOf(transcript),
            )
        finishSession(RESULT_OK, result)
    }

    /**
     * Combines the caller-provided EXTRA_RESULTS_PENDINGINTENT_BUNDLE with the
     * recognizer result for the PendingIntent route. Our extras win on
     * conflicts (e.g. EXTRA_RESULTS).
     */
    internal fun buildForwardedIntent(result: Intent?, bundle: Bundle?): Intent {
        val combined = Intent()
        bundle?.let { combined.putExtras(it) }
        result?.let { combined.putExtras(it) }
        return combined
    }

    /**
     * Standard recognizer handoff. If the caller supplied
     * EXTRA_RESULTS_PENDINGINTENT, the result is forwarded through it (with
     * EXTRA_RESULTS_PENDINGINTENT_BUNDLE merged in); otherwise the classic
     * setResult(RESULT_OK, EXTRA_RESULTS) path is used. In both cases the
     * Activity finishes immediately so SwiftKey can return and commit text.
     */
    private fun finishSession(resultCode: Int, result: Intent?) {
        val plan = deliveryPlan(intent)
        val pi = pendingIntent
        if (plan.viaPendingIntent && pi != null) {
            val forwarded = result?.let { buildForwardedIntent(it, pendingIntentBundle) }
            try {
                pi.send(this, resultCode, forwarded)
                Log.i(TAG, "deliver: forwarded via EXTRA_RESULTS_PENDINGINTENT code=$resultCode")
            } catch (e: Exception) {
                Log.e(TAG, "deliver: PendingIntent send failed; falling back to activity result", e)
                setResult(resultCode, result)
            }
        } else {
            setResult(resultCode, result)
            Log.i(TAG, "deliver: setResult code=$resultCode via activity result")
        }
        finish()
        Log.i(TAG, "deliver: finished")
    }

    /** User back / system dismissal: no API request made where possible. */
    private fun cancelSession() {
        if (phase == Phase.DONE) return
        recorder?.cancelAndAbort()
        recorder = null
        transcribeJob?.cancel()
        phase = Phase.DONE
        tempWav.delete()
        finishSession(RESULT_CANCELED, null)
    }

    /** No-speech timeout: graceful cancel without an API request. */
    private fun cancelGracefully() {
        recorder?.cancelAndAbort()
        recorder = null
        phase = Phase.DONE
        tempWav.delete()
        finishSession(RESULT_CANCELED, null)
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

    /** The recording transcribed to nothing usable: offer a fresh recording. */
    private fun showNoWords() {
        setPhase(Phase.ERROR)
        statusText.setText(R.string.status_no_words)
        hintText.setText(R.string.hint_tap_to_retry)
        errorButtons.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE // retry = record again
        openSettingsErrorButton.visibility = View.GONE
    }

    private fun showError(error: TranscriptionException) {
        setPhase(Phase.ERROR)
        statusText.setText(R.string.status_transcribe_failed)
        if (error is TranscriptionException.Unauthorized) {
            // Deterministic auth failure: retrying with the same key cannot
            // help, and we never surface server-provided key fragments/URLs.
            hintText.setText(R.string.error_unauthorized)
            retryButton.visibility = View.GONE
            openSettingsErrorButton.visibility = View.VISIBLE
        } else {
            hintText.text = errorText(error)
            retryButton.visibility = if (wavReady && tempWav.exists()) View.VISIBLE else View.GONE
            openSettingsErrorButton.visibility = View.GONE
        }
        errorButtons.visibility = View.VISIBLE
    }

    /** Maps structured errors to stable, localized, app-owned text. */
    private fun errorText(error: TranscriptionException): String = when (error) {
        is TranscriptionException.Unauthorized -> getString(R.string.error_unauthorized)
        is TranscriptionException.RateLimited -> getString(R.string.error_rate_limited)
        is TranscriptionException.ServerError -> getString(R.string.error_server)
        is TranscriptionException.ApiError -> getString(R.string.error_api, error.code)
        is TranscriptionException.Timeout -> getString(R.string.error_timeout)
        is TranscriptionException.Network -> getString(R.string.error_network)
        is TranscriptionException.Protocol -> getString(R.string.hint_error_generic)
    }

    /**
     * Live microphone input meter (LISTENING only). Visualizes the analysis-side
     * level as a segmented bar meter (WebRTC-style peak estimate); never
     * touches the upload path.
     */
    internal fun setMeterVisible(visible: Boolean) {
        meterContainer.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            meterBars.forEach { it.alpha = BAR_DIM_ALPHA }
            meterContainer.contentDescription = getString(R.string.mic_level_desc)
        }
    }

    internal fun renderMeter(level01: Float) {
        // The meter is only visible in LISTENING (setPhase drives visibility).
        if (meterContainer.visibility != View.VISIBLE) return
        val level = if (level01.isNaN()) 0f else level01.coerceIn(0f, 1f)
        val n = meterBars.size
        val lit = (level * n).toInt().coerceIn(0, n)
        val frac = (level * n - lit).coerceIn(0f, 1f)
        for ((idx, bar) in meterBars.withIndex()) {
            val target = when {
                idx < lit -> 1f
                idx == lit && idx < n -> BAR_DIM_ALPHA + frac * (1f - BAR_DIM_ALPHA)
                else -> BAR_DIM_ALPHA
            }
            // Per-frame lerp gives a smooth bobbing animation without layout.
            bar.alpha += (target - bar.alpha) * BAR_SMOOTHING
        }
        meterContainer.contentDescription = getString(
            R.string.mic_level_desc,
            getString(
                when {
                    level < 0.15f -> R.string.mic_level_quiet
                    level < 0.6f -> R.string.mic_level_moderate
                    else -> R.string.mic_level_loud
                },
            ),
        )
    }

    private fun setPhase(newPhase: Phase) {
        phase = newPhase
        micIcon.visibility = if (newPhase == Phase.PROCESSING) View.GONE else View.VISIBLE
        processingBar.visibility = if (newPhase == Phase.PROCESSING) View.VISIBLE else View.GONE
        setMeterVisible(newPhase == Phase.LISTENING)
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

        /**
         * Duplicate-launch guard (standard launch mode): only one recognition
         * session per process; extra instances cancel themselves cleanly
         * instead of opening a second microphone. The owner reference is
         * cleared by the owning instance on destroy.
         */
        @Volatile
        private var sessionOwner: RecognitionActivity? = null

        /** Test hook: clears the process-wide session guard between tests. */
        internal fun resetSessionGuardForTest() {
            sessionOwner = null
        }

        /** Panel ≈ 38% of window height, bounded to keyboard-like 180–340dp. */
        private const val PANEL_HEIGHT_FRACTION = 0.38f
        private const val PANEL_HEIGHT_MIN_DP = 180
        private const val PANEL_HEIGHT_MAX_DP = 340

        /** ~30 fps meter updates (33 ms); frames arrive every 20 ms. */
        private const val METER_UI_INTERVAL_MS = 33L

        /** Unlit bar alpha and the per-frame animation smoothing. */
        private const val BAR_DIM_ALPHA = 0.12f
        private const val BAR_SMOOTHING = 0.35f

        /** Captures shorter than this are accidental taps: discarded silently. */
        private const val MIN_RECORDING_MS = 500L
    }
}
