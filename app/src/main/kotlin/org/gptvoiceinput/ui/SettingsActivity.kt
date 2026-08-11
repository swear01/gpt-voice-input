package org.gptvoiceinput.ui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import org.gptvoiceinput.config.TranscriptionProfile
import org.gptvoiceinput.security.SecureApiKeyStore
import java.io.IOException

/**
 * Settings reached only through the gear button in the recognition panel.
 *
 * Four top-level sections:
 * - OpenAI: API key (enter/replace/clear, never plaintext)
 * - Transcription: Languages / Context / Keywords — the profile that is
 *   ACTUALLY sent to the transcription API, directly editable
 * - Recording: auto-stop slider
 * - Profile & backup: Import / Export / Export full backup / Reset
 *
 * The editable transcription fields are the single source of truth for the
 * runtime profile (ImportedProfileStore). Legacy `customTerms` from older
 * versions are merged into the unified keyword list on load and cleared on
 * the next save/import/reset.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var secureStore: SecureApiKeyStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var importedProfileStore: ImportedProfileStore

    private lateinit var apiKeyEdit: EditText
    private lateinit var apiKeyStatus: TextView
    private lateinit var clearApiKeyButton: Button
    private lateinit var languagesEdit: EditText
    private lateinit var contextEdit: EditText
    private lateinit var keywordsEdit: EditText
    private lateinit var autoStopSeekBar: SeekBar
    private lateinit var autoStopValue: TextView

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
        languagesEdit = findViewById(R.id.languages_edit)
        contextEdit = findViewById(R.id.context_edit)
        keywordsEdit = findViewById(R.id.keywords_edit)
        autoStopSeekBar = findViewById(R.id.auto_stop_seekbar)
        autoStopValue = findViewById(R.id.auto_stop_value)

        applySystemBarInsets()

        apiKeyEdit.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        updateApiKeyHint()

        // Slider positions: 1.0, 1.2, ..., 3.0, OFF (OFF is the far end).
        val options = SettingsStore.AUTO_STOP_OPTIONS_MS
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

        populateTranscriptionFields()

        findViewById<View>(R.id.save_button).setOnClickListener { save() }
        clearApiKeyButton.setOnClickListener { confirmClearApiKey() }
        findViewById<View>(R.id.import_row).setOnClickListener { startImport() }
        findViewById<View>(R.id.export_row).setOnClickListener { startExport() }
        findViewById<View>(R.id.export_full_row).setOnClickListener { startFullBackup() }
        findViewById<View>(R.id.reset_profile_row).setOnClickListener { confirmResetProfile() }
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

    private fun indexOfCurrent(options: List<Int>): Int {
        val current = settingsStore.autoStopMs
        val idx = options.indexOf(current)
        return if (idx >= 0) idx else options.indexOfFirst { it == SettingsStore.AUTO_STOP_OFF_MS }
    }

    private fun renderAutoStop(options: List<Int>) {
        val selectedMs = options[autoStopSeekBar.progress]
        autoStopValue.text = if (selectedMs == SettingsStore.AUTO_STOP_OFF_MS) {
            getString(R.string.auto_stop_off)
        } else {
            getString(R.string.auto_stop_seconds, "%.1f".format(selectedMs / 1000.0))
        }
    }

    // -------------------------------------------------------- transcription

    /** The persisted runtime profile, or the generic default when none exists. */
    private fun baseProfile(): TranscriptionProfile =
        importedProfileStore.load() ?: AppConfig.loadDefault(this)

    /**
     * Loads the editable transcription fields from the persisted state.
     * Legacy customTerms (pre-unification) are merged into the keyword field
     * for migration display — the field is the single visible concept.
     */
    private fun populateTranscriptionFields() {
        val base = baseProfile()
        val legacyTerms = settingsStore.customTerms()
        val keywords = (base.keywords + legacyTerms).distinct()
        languagesEdit.setText(base.expectedLanguages.joinToString(", "))
        contextEdit.setText(base.transcriptionContext)
        keywordsEdit.setText(keywords.joinToString("\n"))
    }

    /** Splits/trims/deduplicates the languages field; null when invalid. */
    private fun parseLanguages(): List<String>? {
        val raw = languagesEdit.text?.toString().orEmpty()
        val parts = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            toastValidation(getString(R.string.languages_empty))
            return null
        }
        val invalid = parts.firstOrNull { !SettingsBackup.isValidLanguageCode(it) }
        if (invalid != null) {
            toastValidation(getString(R.string.languages_invalid, invalid))
            return null
        }
        return parts.distinct()
    }

    private fun parseKeywords(): List<String> =
        keywordsEdit.text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
            .orEmpty()

    /**
     * Saves all sections atomically. Validate every transcription field first;
     * on validation failure nothing is persisted.
     */
    private fun save() {
        val languages = parseLanguages() ?: return

        val key = apiKeyEdit.text?.toString()?.trim().orEmpty()
        val context = contextEdit.text?.toString()?.trim().orEmpty()
        val keywords = parseKeywords()

        // Commit: key (only when entered; empty preserves), unified profile,
        // auto-stop, and migrate the legacy customTerms away.
        if (key.isNotEmpty()) {
            secureStore.save(key)
            apiKeyEdit.text?.clear()
            updateApiKeyHint()
        }
        importedProfileStore.save(TranscriptionProfile(languages, context, keywords))
        settingsStore.setCustomTerms(emptyList())
        settingsStore.setAutoStopMs(SettingsStore.AUTO_STOP_OPTIONS_MS[autoStopSeekBar.progress])

        populateTranscriptionFields()
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
            toastImportError(getString(R.string.import_error_read))
            return
        }
        val parsed = try {
            SettingsBackup.parse(text)
        } catch (e: BackupException.UnsupportedVersion) {
            toastImportError(getString(R.string.import_error_newer_version))
            return
        } catch (e: BackupException) {
            toastImportError(getString(R.string.import_error_invalid))
            return
        }
        showImportConfirm(parsed)
    }

    private fun showImportConfirm(parsed: ParsedSettings) {
        val unchanged = getString(R.string.import_confirm_unchanged)
        val hasProfile = parsed.profile != null
        val hasTerms = parsed.customTerms != null
        val mergedKeywordCount = (parsed.profile?.keywords.orEmpty() + parsed.customTerms.orEmpty())
            .distinct().size

        val summary = buildString {
            appendLine(
                getString(
                    R.string.import_confirm_languages,
                    parsed.profile?.expectedLanguages?.joinToString(", ")?.ifEmpty { "—" } ?: unchanged,
                ),
            )
            appendLine(
                if (hasProfile || hasTerms) {
                    getString(R.string.import_confirm_keywords, mergedKeywordCount)
                } else {
                    getString(R.string.import_confirm_keywords_unchanged)
                },
            )
            appendLine(
                getString(R.string.import_confirm_auto_stop, formatAutoStop(parsed.autoStopSeconds)),
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
        value <= 0.0 -> getString(R.string.auto_stop_off)
        else -> getString(R.string.auto_stop_seconds, "%.1f".format(value))
    }

    /** Commit only after confirmation; invalid files never reach this point. */
    internal fun applyImport(parsed: ParsedSettings) {
        parsed.apiKey?.let { secureStore.save(it) }
        // Unify: file profile keywords + legacy file custom terms -> one list.
        val merged = SettingsBackup.mergeIntoCurrent(baseProfile(), parsed)
        importedProfileStore.save(merged)
        settingsStore.setCustomTerms(emptyList())
        parsed.autoStopSeconds?.let { settingsStore.setAutoStopSeconds(it) }

        // Refresh every visible field so the user sees the imported state.
        updateApiKeyHint()
        val options = SettingsStore.AUTO_STOP_OPTIONS_MS
        autoStopSeekBar.progress = indexOfCurrent(options)
        renderAutoStop(options)
        populateTranscriptionFields()

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

    /** Unified profile export: keywords carry the full list; no legacy duplicate state. */
    private fun exportData(): ExportData = ExportData(
        profile = baseProfile(),
        autoStopSeconds = settingsStore.autoStopSeconds,
        customTerms = emptyList(),
    )

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
            toastExportError(e.message ?: getString(R.string.hint_error_generic))
        }
    }

    // ------------------------------------------------------- reset profile

    private fun confirmResetProfile() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_profile_confirm_title)
            .setMessage(R.string.reset_profile_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_transcription_profile) { _, _ ->
                performResetProfile()
            }
            .show()
    }

    /** Removes the runtime profile override + migrated legacy terms; keeps key and auto-stop. */
    internal fun performResetProfile() {
        importedProfileStore.clear()
        settingsStore.setCustomTerms(emptyList())
        populateTranscriptionFields()
        Toast.makeText(this, R.string.profile_reset, Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------- helpers

    private fun toastValidation(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
    }
}
