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
    private lateinit var meterContainer: View
    private lateinit var meterBars: List<View>

    private var controller: ImeVoiceController? = null
    private var waitingForPermission = false
    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastMeterUiMs = 0L

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.view_voice_panel, null)
        panel = view
        statusText = view.findViewById(R.id.status_text)
        hintText = view.findViewById(R.id.hint_text)
        micIcon = view.findViewById(R.id.mic_icon)
        meterContainer = view.findViewById(R.id.mic_level_container)
        meterBars = (meterContainer as ViewGroup).let { vg ->
            (0 until vg.childCount).map { vg.getChildAt(it) }
        }
        meterBars.forEach { it.alpha = BAR_DIM_ALPHA }

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
            if (waitingForPermission) {
                openSettings()
            } else {
                runCatching { controller?.onPanelTap() }
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

    private fun openSettings() {
        runCatching {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.e(TAG, "open settings failed", it) }
    }

    // ------------------------------------------------------------------ UI

    private fun renderState(state: ImeVoiceController.State) {
        panel ?: return
        when (state) {
            ImeVoiceController.State.IDLE -> {
                micIcon.visibility = View.VISIBLE
                meterContainer.visibility = View.GONE
                statusText.setText(R.string.status_listening)
                hintText.setText(R.string.hint_tap_to_submit)
            }
            ImeVoiceController.State.LISTENING -> {
                micIcon.visibility = View.VISIBLE
                meterContainer.visibility = View.VISIBLE
                statusText.setText(R.string.status_listening)
                hintText.setText(R.string.hint_tap_to_submit)
            }
            ImeVoiceController.State.PROCESSING -> {
                meterContainer.visibility = View.GONE
                statusText.setText(R.string.status_transcribing)
                hintText.setText("")
            }
            ImeVoiceController.State.ERROR -> {
                meterContainer.visibility = View.GONE
                statusText.setText(R.string.status_transcribe_failed)
                hintText.setText(R.string.hint_tap_to_retry)
            }
            ImeVoiceController.State.FINISHED -> {
                meterContainer.visibility = View.GONE
            }
        }
    }

    private fun renderMeter(level01: Float) {
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
            bar.alpha += (target - bar.alpha) * BAR_SMOOTHING
        }
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
        private const val BAR_DIM_ALPHA = 0.12f
        private const val BAR_SMOOTHING = 0.35f
    }
}
