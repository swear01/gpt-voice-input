package org.gptvoiceinput.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import org.gptvoiceinput.R
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.ui.SettingsActivity

/**
 * Voice input method (v1.0.0).
 *
 * A normal, visible IME in the keyboard cycle whose input view is a
 * voice-only panel (no keys). Reached via the globe key or by tapping the
 * panel (switchToNextInputMethod). Speech is transcribed by gpt-transcribe
 * and committed through the InputConnection; the IME then returns to the
 * previous keyboard automatically.
 *
 * The IME window layer (TYPE_INPUT_METHOD) is above chat-head overlays, so
 * dictation works in Messenger bubbles etc.
 */
class GptVoiceIme : InputMethodService() {

    private var panel: View? = null
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var micIcon: View
    private lateinit var meterContainer: View
    private lateinit var meterBars: List<View>
    private lateinit var errorButtons: View
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button
    private lateinit var openSettingsErrorButton: Button
    private lateinit var doneButton: Button

    private var controller: ImeVoiceController? = null
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
        errorButtons = view.findViewById(R.id.error_buttons)
        retryButton = view.findViewById(R.id.retry_button)
        cancelButton = view.findViewById(R.id.cancel_button)
        openSettingsErrorButton = view.findViewById(R.id.open_settings_error_button)
        doneButton = view.findViewById(R.id.done_button)
        meterBars.forEach { it.alpha = BAR_DIM_ALPHA }

        // Tap anywhere on the panel (except controls) = next input method.
        view.setOnClickListener { switchToNextIme() }
        view.findViewById<ImageButton>(R.id.gear_button).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        doneButton.setOnClickListener { controller?.submit() }
        retryButton.setOnClickListener { controller?.retry() }
        cancelButton.setOnClickListener { controller?.cancel(); switchToNextIme() }
        openSettingsErrorButton.setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        if (controller == null) {
            controller = ImeVoiceController.create(this, mainScope(), imeCallbacks)
        }
        renderState(ImeVoiceController.State.IDLE)
        return view
    }

    private val imeCallbacks = object : ImeVoiceController.Callbacks {
        override fun onStateChanged(state: ImeVoiceController.State) {
            renderState(state)
            when (state) {
                ImeVoiceController.State.FINISHED -> {
                    // Cancel/session end: leave the IME and return to the
                    // previous keyboard.
                    requestHideSelf(0)
                    switchToPreviousIme()
                }
                else -> Unit
            }
        }

        override fun onMeterLevel(level01: Float) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastMeterUiMs < METER_UI_INTERVAL_MS) return
            lastMeterUiMs = now
            mainHandler.post { renderMeter(level01) }
        }

        override fun onTranscript(transcript: String) {
            Log.i(TAG, "deliver: committing text via InputConnection")
            val ic: InputConnection? = currentInputConnection
            if (ic != null) {
                ic.commitText(transcript, 1)
            }
            requestHideSelf(0)
            switchToPreviousIme()
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastMeterUiMs = 0L
        controller?.start()
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
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.repeatCount == 0) {
            controller?.cancel()
            switchToNextIme()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------- IME switching helpers

    /** API 28+: InputMethodService.switchToNextInputMethod; older: the
     *  token-based InputMethodManager variant. */
    private fun switchToNextIme(): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("NewApi")
            return switchToNextInputMethod(false)
        }
        @Suppress("DEPRECATION")
        return getSystemService(InputMethodManager::class.java)
            .switchToNextInputMethod(imeWindowToken(), false)
    }

    private fun switchToPreviousIme(): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("NewApi")
            return switchToPreviousInputMethod()
        }
        @Suppress("DEPRECATION")
        // Pre-28 API is named switchToLastInputMethod (deprecated in 28).
        return getSystemService(InputMethodManager::class.java)
            .switchToLastInputMethod(imeWindowToken())
    }

    private fun imeWindowToken(): android.os.IBinder =
        getWindow().getWindow()!!.getAttributes().token

    // ------------------------------------------------------------------ UI

    private fun renderState(state: ImeVoiceController.State) {
        panel ?: return
        when (state) {
            ImeVoiceController.State.IDLE -> {
                statusText.setText(R.string.hint_tap_to_submit)
                hintText.setText(R.string.hint_no_key)
            }
            ImeVoiceController.State.LISTENING -> {
                micIcon.visibility = View.VISIBLE
                meterContainer.visibility = View.VISIBLE
                doneButton.visibility = View.VISIBLE
                errorButtons.visibility = View.GONE
                statusText.setText(R.string.status_listening)
                hintText.setText(R.string.hint_tap_to_submit)
            }
            ImeVoiceController.State.PROCESSING -> {
                micIcon.visibility = View.VISIBLE
                meterContainer.visibility = View.GONE
                doneButton.visibility = View.GONE
                errorButtons.visibility = View.GONE
                statusText.setText(R.string.status_transcribing)
                hintText.setText("")
            }
            ImeVoiceController.State.ERROR -> {
                meterContainer.visibility = View.GONE
                doneButton.visibility = View.GONE
                errorButtons.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
                openSettingsErrorButton.visibility = View.GONE
                statusText.setText(R.string.status_transcribe_failed)
                hintText.setText(R.string.hint_error_generic)
            }
            ImeVoiceController.State.FINISHED -> {
                meterContainer.visibility = View.GONE
                doneButton.visibility = View.GONE
                errorButtons.visibility = View.GONE
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

    private fun mainScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.MainScope()

    companion object {
        private const val TAG = "GptVoiceIme"
        private const val METER_UI_INTERVAL_MS = 33L
        private const val BAR_DIM_ALPHA = 0.12f
        private const val BAR_SMOOTHING = 0.35f
    }
}
