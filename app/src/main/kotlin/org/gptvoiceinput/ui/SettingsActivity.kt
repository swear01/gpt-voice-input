package org.gptvoiceinput.ui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.gptvoiceinput.R
import org.gptvoiceinput.config.AppConfig
import org.gptvoiceinput.config.BackupException
import org.gptvoiceinput.config.ImportedProfileStore
import org.gptvoiceinput.config.SettingsBackup
import org.gptvoiceinput.config.SettingsBundle
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.security.SecureApiKeyStore
import java.io.IOException

/**
 * Settings reached only through the gear button in the recognition panel.
 *
 * - OpenAI API key (runtime-entered, Keystore-backed, never displayed back)
 * - auto-stop slider
 * - Advanced: custom terms, effective configuration, and a non-secret
 *   profile/settings import & export (SAF-backed, no filesystem permission)
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var secureStore: SecureApiKeyStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var importedProfileStore: ImportedProfileStore

    private lateinit var apiKeyEdit: EditText
    private lateinit var autoStopSeekBar: SeekBar
    private lateinit var autoStopValue: TextView
    private lateinit var advancedHeader: TextView
    private lateinit var advancedPanel: View
    private lateinit var customTermsEdit: EditText
    private lateinit var effectiveConfigText: TextView

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportTo(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        secureStore = SecureApiKeyStore(this)
        settingsStore = SettingsStore(this)
        importedProfileStore = ImportedProfileStore(this)

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

        // Restore saved runtime custom terms (regression: v0.1.0 left this blank).
        customTermsEdit.setText(settingsStore.customTerms().joinToString("\n"))

        advancedHeader.setOnClickListener {
            val show = advancedPanel.visibility != View.VISIBLE
            advancedPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) renderEffectiveConfig()
        }

        findViewById<View>(R.id.save_button).setOnClickListener { save() }
        findViewById<View>(R.id.import_button).setOnClickListener { startImport() }
        findViewById<View>(R.id.export_button).setOnClickListener { startExport() }
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

    private fun effectiveProfile() =
        AppConfig.load(this, importedProfileStore.load(), parseTerms())

    private fun renderEffectiveConfig() {
        val profile = effectiveProfile()
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

    // ---------------------------------------------------------------- save

    private fun save() {
        val key = apiKeyEdit.text?.toString()?.trim().orEmpty()
        if (key.isNotEmpty()) {
            secureStore.save(key)
            apiKeyEdit.text?.clear()
            apiKeyEdit.hint = getString(R.string.api_key_hint_set)
        }
        // An empty API-key field preserves the already stored key (no-op).
        settingsStore.autoStopSeconds = SettingsStore.AUTO_STOP_OPTIONS[autoStopSeekBar.progress]
        settingsStore.setCustomTerms(parseTerms())
        renderEffectiveConfig()
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------- import

    private fun startImport() {
        importLauncher.launch(arrayOf("application/json"))
    }

    private fun importFrom(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            toastImportError("Could not read the selected file")
            return
        }
        val bundle = try {
            SettingsBackup.parse(text)
        } catch (e: BackupException) {
            toastImportError(e.message ?: "Invalid settings file")
            return
        }
        showImportConfirm(bundle)
    }

    private fun showImportConfirm(bundle: SettingsBundle) {
        val profile = bundle.profile
        val summary = buildString {
            appendLine(
                getString(
                    R.string.import_confirm_languages,
                    profile.expectedLanguages.joinToString(", ").ifEmpty { "—" },
                ),
            )
            appendLine(getString(R.string.import_confirm_keywords, profile.keywords.size))
            appendLine(
                getString(
                    R.string.import_confirm_auto_stop,
                    if (bundle.autoStopSeconds == SettingsStore.AUTO_STOP_OFF) {
                        getString(R.string.auto_stop_off)
                    } else {
                        getString(
                            R.string.auto_stop_seconds,
                            "%.1f".format(bundle.autoStopSeconds),
                        )
                    },
                ),
            )
            append(getString(R.string.import_confirm_custom_terms, bundle.customTerms.size))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(summary)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.import_confirm_button) { _, _ -> applyImport(bundle) }
            .show()
    }

    /** Commit only after confirmation; invalid files never reach this point. */
    private fun applyImport(bundle: SettingsBundle) {
        importedProfileStore.save(bundle.profile)
        settingsStore.autoStopSeconds = bundle.autoStopSeconds
        settingsStore.setCustomTerms(bundle.customTerms)

        // Refresh every field from the stores so the UI mirrors persisted state.
        val options = SettingsStore.AUTO_STOP_OPTIONS
        autoStopSeekBar.progress = indexOfCurrent(options)
        renderAutoStop(options)
        customTermsEdit.setText(bundle.customTerms.joinToString("\n"))
        renderEffectiveConfig()

        Toast.makeText(this, R.string.settings_imported, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------- export

    private fun startExport() {
        exportLauncher.launch(DEFAULT_EXPORT_FILENAME)
    }

    private fun exportTo(uri: Uri) {
        try {
            val bundle = SettingsBundle(
                profile = effectiveProfile(),
                autoStopSeconds = settingsStore.autoStopSeconds,
                customTerms = settingsStore.customTerms(),
            )
            val bytes = SettingsBackup.serialize(bundle).toByteArray(Charsets.UTF_8)
            val stream = contentResolver.openOutputStream(uri)
                ?: throw IOException("Cannot open output stream")
            stream.use { it.write(bytes) }
            Toast.makeText(this, R.string.settings_exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            toastExportError(e.message ?: "Unknown error")
        }
    }

    private fun toastImportError(message: String) {
        Toast.makeText(
            this,
            getString(R.string.settings_import_failed, message),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun toastExportError(message: String) {
        Toast.makeText(
            this,
            getString(R.string.settings_export_failed, message),
            Toast.LENGTH_LONG,
        ).show()
    }

    companion object {
        private const val DEFAULT_EXPORT_FILENAME = "gpt-voice-input-settings.json"
    }
}
