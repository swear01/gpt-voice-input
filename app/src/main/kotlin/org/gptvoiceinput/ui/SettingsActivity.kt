package org.gptvoiceinput.ui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.gptvoiceinput.R
import org.gptvoiceinput.config.AppConfig
import org.gptvoiceinput.config.BackupException
import org.gptvoiceinput.config.ExportData
import org.gptvoiceinput.config.ImportedProfileStore
import org.gptvoiceinput.config.ParsedSettings
import org.gptvoiceinput.config.SettingsBackup
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.security.SecureApiKeyStore
import java.io.IOException

/**
 * Settings reached only through the gear button in the recognition panel.
 *
 * - OpenAI API key: enter/replace, "key is set" hint, explicit Clear (never
 *   displays plaintext)
 * - auto-stop slider
 * - Advanced: custom terms, Effective Configuration, and Profile & backup:
 *   Import settings / Export settings (no key) / Export full backup (key in
 *   plaintext, warned) / Clear imported profile
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var secureStore: SecureApiKeyStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var importedProfileStore: ImportedProfileStore

    private lateinit var apiKeyEdit: EditText
    private lateinit var apiKeyStatus: TextView
    private lateinit var clearApiKeyButton: Button
    private lateinit var autoStopSeekBar: SeekBar
    private lateinit var autoStopValue: TextView
    private lateinit var advancedHeader: View
    private lateinit var advancedChevron: ImageView
    private lateinit var advancedPanel: View
    private lateinit var customTermsEdit: EditText
    private lateinit var effectiveConfigText: TextView

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportTo(uri, includeApiKey = false)
        }

    private val fullBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportTo(uri, includeApiKey = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        secureStore = SecureApiKeyStore(this)
        settingsStore = SettingsStore(this)
        importedProfileStore = ImportedProfileStore(this)

        apiKeyEdit = findViewById(R.id.api_key_edit)
        apiKeyStatus = findViewById(R.id.api_key_status)
        clearApiKeyButton = findViewById(R.id.clear_api_key_button)
        autoStopSeekBar = findViewById(R.id.auto_stop_seekbar)
        autoStopValue = findViewById(R.id.auto_stop_value)
        advancedHeader = findViewById(R.id.advanced_header)
        advancedChevron = findViewById(R.id.advanced_chevron)
        advancedPanel = findViewById(R.id.advanced_panel)
        customTermsEdit = findViewById(R.id.custom_terms_edit)
        effectiveConfigText = findViewById(R.id.effective_config_text)

        applySystemBarInsets()

        apiKeyEdit.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        updateApiKeyHint()

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

        // Advanced expand/collapse with chevron + accessibility state.
        advancedHeader.setOnClickListener {
            setAdvancedExpanded(advancedPanel.visibility != View.VISIBLE)
        }
        setAdvancedExpanded(savedInstanceState?.getBoolean(KEY_ADVANCED_EXPANDED) == true)

        findViewById<View>(R.id.save_button).setOnClickListener { save() }
        findViewById<View>(R.id.import_button).setOnClickListener { startImport() }
        findViewById<View>(R.id.export_button).setOnClickListener { startExport() }
        findViewById<View>(R.id.export_full_button).setOnClickListener { startFullBackup() }
        findViewById<View>(R.id.clear_profile_button).setOnClickListener { confirmClearProfile() }
        clearApiKeyButton.setOnClickListener { confirmClearApiKey() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ADVANCED_EXPANDED, advancedPanel.visibility == View.VISIBLE)
    }

    /** Edge-to-edge (targetSdk 35): keep content below the status bar / above the nav bar. */
    private fun applySystemBarInsets() {
        val scroll = findViewById<View>(R.id.settings_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun setAdvancedExpanded(expanded: Boolean) {
        advancedPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        advancedChevron.rotation = if (expanded) 180f else 0f
        advancedChevron.contentDescription = getString(
            if (expanded) R.string.advanced_collapse_description
            else R.string.advanced_expand_description,
        )
        if (expanded) renderEffectiveConfig()
    }

    // ---------------------------------------------------------------- key

    private fun updateApiKeyHint() {
        if (secureStore.hasKey()) {
            apiKeyEdit.hint = getString(R.string.api_key_hint)
            apiKeyStatus.setText(R.string.key_configured)
            apiKeyStatus.setTextColor(getColorCompat(R.color.key_configured))
            clearApiKeyButton.visibility = View.VISIBLE
        } else {
            apiKeyEdit.hint = getString(R.string.api_key_hint)
            apiKeyStatus.setText(R.string.key_not_configured)
            apiKeyStatus.setTextColor(getColorCompat(R.color.key_not_configured))
            clearApiKeyButton.visibility = View.GONE
        }
    }

    private fun getColorCompat(res: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, res)

    private fun confirmClearApiKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_api_key_confirm_title)
            .setMessage(R.string.clear_api_key_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_api_key) { _, _ ->
                secureStore.clear()
                updateApiKeyHint()
                Toast.makeText(this, R.string.api_key_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ------------------------------------------------------------- autostop

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

    // ------------------------------------------------------------- config

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

    /** The profile layer for export: imported profile if any, else the generic default. */
    private fun exportProfile() =
        importedProfileStore.load() ?: AppConfig.loadDefault(this)

    private fun exportData(): ExportData = ExportData(
        profile = exportProfile(),
        autoStopSeconds = settingsStore.autoStopSeconds,
        customTerms = settingsStore.customTerms(),
    )

    // ---------------------------------------------------------------- save

    private fun save() {
        val key = apiKeyEdit.text?.toString()?.trim().orEmpty()
        if (key.isNotEmpty()) {
            secureStore.save(key)
            apiKeyEdit.text?.clear()
            updateApiKeyHint()
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
        val parsed = try {
            SettingsBackup.parse(text)
        } catch (e: BackupException) {
            toastImportError(e.message ?: "Invalid settings file")
            return
        }
        showImportConfirm(parsed)
    }

    private fun showImportConfirm(parsed: ParsedSettings) {
        val unchanged = getString(R.string.import_confirm_unchanged)
        val profile = parsed.profile
        val summary = buildString {
            appendLine(
                getString(
                    R.string.import_confirm_languages,
                    profile?.expectedLanguages?.joinToString(", ")?.ifEmpty { "—" } ?: unchanged,
                ),
            )
            appendLine(
                getString(
                    R.string.import_confirm_keywords,
                    profile?.keywords?.size ?: -1,
                ).let {
                    if (profile == null) getString(R.string.import_confirm_keywords_unchanged) else it
                },
            )
            appendLine(
                getString(
                    R.string.import_confirm_auto_stop,
                    formatAutoStop(parsed.autoStopSeconds),
                ),
            )
            appendLine(
                getString(
                    R.string.import_confirm_custom_terms,
                    parsed.customTerms?.size ?: -1,
                ).let {
                    if (parsed.customTerms == null) {
                        getString(R.string.import_confirm_custom_terms_unchanged)
                    } else {
                        it
                    }
                },
            )
            append(
                if (parsed.apiKey != null) {
                    getString(R.string.import_confirm_api_key_included)
                } else {
                    getString(R.string.import_confirm_api_key_excluded)
                },
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(summary)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.import_confirm_button) { _, _ -> applyImport(parsed) }
            .show()
    }

    private fun formatAutoStop(value: Double?): String = when {
        value == null -> getString(R.string.import_confirm_unchanged)
        value == SettingsStore.AUTO_STOP_OFF -> getString(R.string.auto_stop_off)
        else -> getString(R.string.auto_stop_seconds, "%.1f".format(value))
    }

    /** Commit only after confirmation; invalid files never reach this point. */
    private fun applyImport(parsed: ParsedSettings) {
        parsed.apiKey?.let { secureStore.save(it) }
        parsed.profile?.let { importedProfileStore.save(it) }
        parsed.autoStopSeconds?.let { settingsStore.autoStopSeconds = it }
        parsed.customTerms?.let { settingsStore.setCustomTerms(it) }

        // Refresh every field from the stores so the UI mirrors persisted state.
        updateApiKeyHint()
        val options = SettingsStore.AUTO_STOP_OPTIONS
        autoStopSeekBar.progress = indexOfCurrent(options)
        renderAutoStop(options)
        customTermsEdit.setText(settingsStore.customTerms().joinToString("\n"))
        renderEffectiveConfig()

        Toast.makeText(this, R.string.settings_imported, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------- export

    private fun startExport() {
        exportLauncher.launch(SAFE_EXPORT_FILENAME)
    }

    private fun startFullBackup() {
        val hasKey = secureStore.hasKey()
        val message = if (hasKey) {
            getString(R.string.full_backup_warning_message)
        } else {
            getString(R.string.full_backup_no_key_message)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.full_backup_warning_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.full_backup_confirm_button) { _, _ ->
                fullBackupLauncher.launch(FULL_BACKUP_FILENAME)
            }
            .show()
    }

    private fun exportTo(uri: Uri, includeApiKey: Boolean) {
        try {
            val data = if (includeApiKey) {
                exportData().copy(apiKey = secureStore.load())
            } else {
                exportData()
            }
            val bytes = SettingsBackup.serialize(data).toByteArray(Charsets.UTF_8)
            val stream = contentResolver.openOutputStream(uri)
                ?: throw IOException("Cannot open output stream")
            stream.use { it.write(bytes) }
            Toast.makeText(this, R.string.settings_exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            toastExportError(e.message ?: "Unknown error")
        }
    }

    // ---------------------------------------------------- clear profile

    private fun confirmClearProfile() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_profile_confirm_title)
            .setMessage(R.string.clear_profile_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_imported_profile) { _, _ ->
                importedProfileStore.clear()
                renderEffectiveConfig()
                Toast.makeText(this, R.string.imported_profile_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
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
        private const val SAFE_EXPORT_FILENAME = "gpt-voice-input-settings.json"
        private const val FULL_BACKUP_FILENAME = "gpt-voice-input-personal.json"
        private const val KEY_ADVANCED_EXPANDED = "advanced_expanded"
    }
}
