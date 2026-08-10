package org.gptvoiceinput.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.gptvoiceinput.R
import org.gptvoiceinput.config.AppConfig
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.security.SecureApiKeyStore

/**
 * Settings reached only through the gear button in the recognition panel.
 * Keeps everyday options minimal: API key, auto-stop, and an Advanced section
 * for custom terms (plus a read-only view of the effective config).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var secureStore: SecureApiKeyStore
    private lateinit var settingsStore: SettingsStore

    private lateinit var apiKeyEdit: EditText
    private lateinit var autoStopSeekBar: SeekBar
    private lateinit var autoStopValue: TextView
    private lateinit var advancedHeader: TextView
    private lateinit var advancedPanel: View
    private lateinit var customTermsEdit: EditText
    private lateinit var effectiveConfigText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        secureStore = SecureApiKeyStore(this)
        settingsStore = SettingsStore(this)

        apiKeyEdit = findViewById(R.id.api_key_edit)
        autoStopSeekBar = findViewById(R.id.auto_stop_seekbar)
        autoStopValue = findViewById(R.id.auto_stop_value)
        advancedHeader = findViewById(R.id.advanced_header)
        advancedPanel = findViewById(R.id.advanced_panel)
        customTermsEdit = findViewById(R.id.custom_terms_edit)
        effectiveConfigText = findViewById(R.id.effective_config_text)

        // Never show a stored key — placeholder says it is set, field stays blank.
        if (secureStore.hasKey()) {
            apiKeyEdit.hint = getString(R.string.api_key_hint_set)
        } else {
            apiKeyEdit.hint = getString(R.string.api_key_hint)
        }
        apiKeyEdit.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        // Slider positions: 1.0, 1.2, ..., 3.0, OFF (OFF is the far end).
        val options = SettingsStore.AUTO_STOP_OPTIONS
        autoStopSeekBar.max = options.size - 1
        autoStopSeekBar.progress = indexOfCurrent(options)
        renderAutoStop(options)

        autoStopSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                renderAutoStop(options)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        advancedHeader.setOnClickListener {
            val show = advancedPanel.visibility != View.VISIBLE
            advancedPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) renderEffectiveConfig()
        }

        findViewById<View>(R.id.save_button).setOnClickListener { save() }
    }

    private fun indexOfCurrent(options: List<Double>): Int {
        val current = settingsStore.autoStopSeconds
        val idx = options.indexOf(current)
        return if (idx >= 0) idx else options.indexOfFirst { it == SettingsStore.AUTO_STOP_OFF }
    }

    private fun renderAutoStop(options: List<Double>) {
        val selected = options[autoStopSeekBar.progress]
        autoStopValue.text = if (selected == SettingsStore.AUTO_STOP_OFF) {
            getString(R.string.auto_stop_off)
        } else {
            getString(R.string.auto_stop_seconds, "%.1f".format(selected))
        }
    }

    private fun renderEffectiveConfig() {
        val profile = AppConfig.load(this, parseTerms())
        val languages = profile.expectedLanguages.joinToString(", ")
        effectiveConfigText.text = buildString {
            appendLine(getString(R.string.effective_languages, languages))
            appendLine()
            append(profile.transcriptionContext)
            appendLine()
            appendLine()
            if (profile.keywords.isNotEmpty()) {
                append(getString(R.string.effective_keywords, profile.keywords.joinToString(", ")))
            }
        }
    }

    private fun parseTerms(): List<String> =
        customTermsEdit.text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
            .orEmpty()

    private fun save() {
        val key = apiKeyEdit.text?.toString()?.trim().orEmpty()
        if (key.isNotEmpty()) {
            secureStore.save(key)
            apiKeyEdit.text?.clear()
            apiKeyEdit.hint = getString(R.string.api_key_hint_set)
        }
        settingsStore.autoStopSeconds = SettingsStore.AUTO_STOP_OPTIONS[autoStopSeekBar.progress]
        settingsStore.setCustomTerms(parseTerms())
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }
}
