package org.gptvoiceinput.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.gptvoiceinput.R
import org.gptvoiceinput.ui.SettingsActivity

/**
 * Voice input method (v1.0.5).
 *
 * A normal, visible IME in the keyboard cycle whose input view is a
 * voice-only panel (no keys). The panel shows the mic level meter, Listening…
 * / Transcribing… / error states, and a small gear. Tapping anywhere on the
 * panel submits the current recording (or retries after an error); after the
 * transcript is committed the IME returns to the previous keyboard
 * automatically. Back key cancels and returns as well.
 *
 * The IME window layer (TYPE_INPUT_METHOD) is above chat-head overlays, so
 * dictation works in Messenger bubbles etc. System-bar / language-bar insets
 * are applied to the input view (whisperIME-style).
 */
class GptVoiceIme : InputMethodService() {

    private var panel: View? = null
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var micIcon: View
    private lateinit var voiceRing: View
    private lateinit var processingBar: View

    private var controller: ImeVoiceController? = null
    private var waitingForPermission = false
    private var currentError: ImeVoiceController.ImeError? = null
    private var navBarPainted = false
    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastMeterUiMs = 0L

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.view_voice_panel, null)
        panel = view
        paintNavigationBarLikeKeyboard()
        statusText = view.findViewById(R.id.status_text)
        hintText = view.findViewById(R.id.hint_text)
        micIcon = view.findViewById(R.id.mic_icon)
        voiceRing = view.findViewById(R.id.voice_ring)
        processingBar = view.findViewById(R.id.processing_bar)
        // Scale from the center (Google-voice style ring). The view has no
        // size at inflate time, so fix the pivot on the first real layout —
        // otherwise the ring expands from its top-left corner, never
        // concentric with the mic.
        voiceRing.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0) {
                v.pivotX = w / 2f
                v.pivotY = h / 2f
            }
        }

        // Keep content clear of the system bars / language bar (whisperIME-style).
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val mlp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (mlp != null) {
                mlp.leftMargin = insets.left
                mlp.bottomMargin = insets.bottom
                mlp.rightMargin = insets.right
                v.layoutParams = mlp
            }
            WindowInsetsCompat.CONSUMED
        }

        // Tap anywhere = submit (listening) / retry (error) / settings
        // (when waiting for the mic permission).
        view.setOnClickListener {
            when {
                waitingForPermission -> openSettings()
                currentError != null -> handleErrorTap()
                else -> runCatching { controller?.onPanelTap() }
                    .onFailure { Log.e(TAG, "panel tap failed", it) }
            }
        }
        view.findViewById<ImageButton>(R.id.gear_button).setOnClickListener { openSettings() }

        if (controller == null) {
            controller = ImeVoiceController.create(this, scope, imeCallbacks)
        }
        renderState(ImeVoiceController.State.IDLE)
        return view
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastMeterUiMs = 0L
        waitingForPermission = false
        currentError = null
        if (hasMicPermission()) {
            controller?.start()
        } else {
            // Services cannot request permissions directly; guide the user to
            // Settings (which requests RECORD_AUDIO on open).
            waitingForPermission = true
            renderState(ImeVoiceController.State.ERROR)
            hintText.setText(R.string.hint_no_permission)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private val imeCallbacks = object : ImeVoiceController.Callbacks {
        override fun onStateChanged(state: ImeVoiceController.State) {
            runCatching { renderState(state) }
                .onFailure { Log.e(TAG, "renderState failed", it) }
            when (state) {
                ImeVoiceController.State.FINISHED -> {
                    // Session ended (cancel/back): leave and return to the
                    // previous keyboard.
                    runCatching {
                        requestHideSelf(0)
                        switchToPreviousIme()
                    }.onFailure { Log.e(TAG, "return to previous IME failed", it) }
                }
                else -> Unit
            }
        }

        override fun onMeterLevel(level01: Float) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastMeterUiMs < METER_UI_INTERVAL_MS) return
            lastMeterUiMs = now
            mainHandler.post { runCatching { renderMeter(level01) } }
        }

        override fun onError(error: ImeVoiceController.ImeError) {
            currentError = error
            mainHandler.post { runCatching { renderState(ImeVoiceController.State.ERROR) } }
        }

        override fun onTranscript(transcript: String) {
            Log.i(TAG, "deliver: committing text via InputConnection")
            val ic: InputConnection? = currentInputConnection
            if (ic != null) {
                ic.commitText(transcript, 1)
            } else {
                // Editor focus lost at the last moment — retry once shortly.
                Log.w(TAG, "deliver: InputConnection null, retrying")
                mainHandler.postDelayed({
                    currentInputConnection?.commitText(transcript, 1)
                    finishAndReturn()
                }, 300)
                return
            }
            finishAndReturn()
        }
    }

    private fun finishAndReturn() {
        runCatching {
            requestHideSelf(0)
            switchToPreviousIme()
        }.onFailure { Log.e(TAG, "finishAndReturn failed", it) }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Never keep an invisible recording session alive.
        controller?.cancel()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        controller?.cancel()
    }

    override fun onDestroy() {
        controller?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.repeatCount == 0) {
            controller?.cancel()
            runCatching { switchToPreviousIme() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Match the gesture/nav bar to the keyboard panel so no white bar shows. */
    private fun paintNavigationBarLikeKeyboard() {
        if (navBarPainted) return
        navBarPainted = true
        runCatching {
            val w = getWindow()?.getWindow() ?: return
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.setNavigationBarColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.recognition_bg),
            )
            w.decorView.systemUiVisibility =
                w.decorView.systemUiVisibility and
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }.onFailure { Log.e(TAG, "nav bar paint failed", it) }
    }

    private fun openSettings() {
        runCatching {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.e(TAG, "open settings failed", it) }
    }

    // ------------------------------------------------------------------ UI

    private fun handleErrorTap() {
        val error = currentError ?: return
        when (ImeVoiceController.actionForError(error)) {
            ImeVoiceController.PanelAction.OPEN_SETTINGS -> openSettings()
            ImeVoiceController.PanelAction.RETRY -> {
                currentError = null
                runCatching { controller?.onPanelTap() }
                    .onFailure { Log.e(TAG, "panel tap failed", it) }
            }
        }
    }

    private fun renderState(state: ImeVoiceController.State) {
        panel ?: return
        when (state) {
            ImeVoiceController.State.ERROR -> {
                micIcon.visibility = View.VISIBLE
                voiceRing.visibility = View.INVISIBLE
                processingBar.visibility = View.GONE
                statusText.setText(R.string.status_transcribe_failed)
                hintText.setText(errorHint())
            }
            ImeVoiceController.State.IDLE -> {
                micIcon.visibility = View.VISIBLE
                voiceRing.visibility = View.INVISIBLE
                processingBar.visibility = View.GONE
                statusText.setText(R.string.status_listening)
                hintText.setText(R.string.hint_tap_to_submit)
            }
            ImeVoiceController.State.LISTENING -> {
                micIcon.visibility = View.VISIBLE
                voiceRing.visibility = View.VISIBLE
                processingBar.visibility = View.GONE
                statusText.setText(R.string.status_listening)
                hintText.setText(R.string.hint_tap_to_submit)
            }
            ImeVoiceController.State.PROCESSING -> {
                // Clear signal that the recording is done and transcription
                // is in flight: spinner + explicit status (Google-style).
                micIcon.visibility = View.INVISIBLE
                voiceRing.visibility = View.INVISIBLE
                processingBar.visibility = View.VISIBLE
                statusText.setText(R.string.status_transcribing)
                hintText.setText("")
            }
            ImeVoiceController.State.FINISHED -> {
                voiceRing.visibility = View.INVISIBLE
                processingBar.visibility = View.GONE
            }
        }
    }

    /** Localized, actionable hint for the current error. */
    private fun errorHint(): String = when (currentError) {
        ImeVoiceController.ImeError.NO_API_KEY -> getString(R.string.hint_no_key)
        ImeVoiceController.ImeError.RECORDING_FAILED -> getString(R.string.hint_tap_to_retry)
        ImeVoiceController.ImeError.AUTH -> getString(R.string.error_unauthorized)
        ImeVoiceController.ImeError.RATE_LIMITED -> getString(R.string.error_rate_limited)
        ImeVoiceController.ImeError.SERVER -> getString(R.string.error_server)
        ImeVoiceController.ImeError.API_ERROR -> getString(R.string.error_api_generic)
        ImeVoiceController.ImeError.TIMEOUT -> getString(R.string.error_timeout)
        ImeVoiceController.ImeError.NETWORK -> getString(R.string.error_network)
        ImeVoiceController.ImeError.PROTOCOL -> getString(R.string.hint_error_generic)
        null -> getString(R.string.hint_tap_to_retry)
    }

    /**
     * Google-voice-style ring: invisible when silent, expands and brightens
     * with the input level; normal speech (level ~1) reaches full size.
     */
    private fun renderMeter(level01: Float) {
        if (voiceRing.visibility != View.VISIBLE) return
        val level = if (level01.isNaN()) 0f else level01.coerceIn(0f, 1f)
        val targetScale = RING_MIN_SCALE + level * (RING_MAX_SCALE - RING_MIN_SCALE)
        val targetAlpha = RING_MIN_ALPHA + level * (RING_MAX_ALPHA - RING_MIN_ALPHA)
        // Smooth the ring so it reads cleanly (no per-frame jitter).
        voiceRing.scaleX += (targetScale - voiceRing.scaleX) * RING_SMOOTHING
        voiceRing.scaleY += (targetScale - voiceRing.scaleY) * RING_SMOOTHING
        voiceRing.alpha += (targetAlpha - voiceRing.alpha) * RING_SMOOTHING
    }

    // ------------------------------------------- IME switching helpers

    private fun switchToNextIme(): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("NewApi")
            return switchToNextInputMethod(false)
        }
        val token = imeWindowToken() ?: return false
        @Suppress("DEPRECATION")
        return getSystemService(InputMethodManager::class.java)
            .switchToNextInputMethod(token, false)
    }

    private fun switchToPreviousIme(): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("NewApi")
            return switchToPreviousInputMethod()
        }
        val token = imeWindowToken() ?: return false
        @Suppress("DEPRECATION")
        // Pre-28 API is named switchToLastInputMethod (deprecated in 28).
        return getSystemService(InputMethodManager::class.java)
            .switchToLastInputMethod(token)
    }

    /** Null-safe: the IMS window may be gone during dismissal. */
    private fun imeWindowToken(): android.os.IBinder? =
        getWindow()?.getWindow()?.getAttributes()?.token

    companion object {
        private const val TAG = "GptVoiceIme"
        private const val METER_UI_INTERVAL_MS = 33L
        private const val RING_MIN_SCALE = 0.45f
        private const val RING_MAX_SCALE = 1.0f
        private const val RING_MIN_ALPHA = 0.15f
        private const val RING_MAX_ALPHA = 0.9f
        private const val RING_SMOOTHING = 0.35f
    }
}
