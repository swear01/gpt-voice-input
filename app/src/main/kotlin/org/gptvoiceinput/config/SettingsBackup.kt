package org.gptvoiceinput.config

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Portable settings file, `schemaVersion` 1. Supports both the safe export
 * (never contains the API key) and the explicit full backup (may include the
 * key in plaintext — the UI warns before writing it).
 *
 * ```json
 * {
 *   "format": "gpt-voice-input-settings",
 *   "schemaVersion": 1,
 *   "secrets":  { "openAiApiKey": "sk-..." },      // optional
 *   "profile":  { "expectedLanguages": [...], ... },// optional, validated when present
 *   "settings": { "autoStopSeconds": 1.8, ... }     // optional
 * }
 * ```
 *
 * Rules: `format` must match; `schemaVersion` must equal 1 (newer versions are
 * rejected); unknown fields are ignored for forward compatibility; malformed
 * files never partially apply — [parse] validates everything up front and the
 * caller commits only after the user confirms.
 */
data class ExportData(
    val profile: TranscriptionProfile,
    val autoStopSeconds: Double,
    val customTerms: List<String>,
    /** Non-null only for full backup; safe export passes null and omits `secrets`. */
    val apiKey: String? = null,
)

data class ParsedSettings(
    /** Null when the file has no `profile` section (existing profile preserved). */
    val profile: TranscriptionProfile?,
    /** Null when the file has no `settings` section (existing values preserved). */
    val autoStopSeconds: Double?,
    /** Null when the file has no `settings` section (existing terms preserved). */
    val customTerms: List<String>?,
    /** Non-null when the file contains a non-blank key (existing key preserved otherwise). */
    val apiKey: String?,
)

sealed class BackupException(message: String) : Exception(message) {
    class Invalid(message: String) : BackupException(message)

    class UnsupportedVersion(val version: Int) :
        BackupException("This settings file was created by a newer unsupported version.")
}

object SettingsBackup {

    const val FORMAT = "gpt-voice-input-settings"
    const val SCHEMA_VERSION = 1

    private val VALID_LANGUAGE =
        Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?", RegexOption.IGNORE_CASE)

    /** Allowed: OFF (exactly 0.0) or one of the supported 0.2 s steps. */
    fun validateAutoStop(value: Double): Boolean = when {
        value.isNaN() || value.isInfinite() -> false
        value == 0.0 -> true // OFF sentinel
        value < 0.0 -> false
        else -> {
            val ms = (value * 1000.0).roundToInt()
            ms in SettingsStore.AUTO_STOP_OPTIONS_MS
        }
    }

    /** Serializes with an optional `secrets` section (apiKey != null = full backup). */
    fun serialize(data: ExportData): String {
        val root = JSONObject()
            .put(KEY_FORMAT, FORMAT)
            .put(KEY_SCHEMA, SCHEMA_VERSION)
            .put(
                KEY_PROFILE,
                JSONObject()
                    .put("expectedLanguages", JSONArray(data.profile.expectedLanguages))
                    .put("transcriptionContext", data.profile.transcriptionContext)
                    .put("keywords", JSONArray(data.profile.keywords)),
            )
            .put(
                KEY_SETTINGS,
                JSONObject()
                    .put("autoStopSeconds", data.autoStopSeconds)
                    .put("customTerms", JSONArray(data.customTerms)),
            )
        data.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            root.put(KEY_SECRETS, JSONObject().put("openAiApiKey", key))
        }
        return root.toString(2)
    }

    /**
     * Parses and fully validates a settings file. Throws [BackupException] on
     * any problem; never partially succeeds and never mutates anything.
     */
    fun parse(json: String): ParsedSettings {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupException.Invalid("This is not a valid JSON settings file.")
        }

        val format = root.optString(KEY_FORMAT)
        if (format != FORMAT) {
            throw BackupException.Invalid("This is not a GPT Voice Input settings file.")
        }

        val version = root.optInt(KEY_SCHEMA, -1)
        if (version < 0) {
            throw BackupException.Invalid("Missing schemaVersion.")
        }
        if (version > SCHEMA_VERSION) {
            throw BackupException.UnsupportedVersion(version)
        }
        if (version < SCHEMA_VERSION) {
            throw BackupException.Invalid("Unsupported schemaVersion $version.")
        }

        // secrets (optional)
        val apiKey = root.optJSONObject(KEY_SECRETS)
            ?.optString("openAiApiKey", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (apiKey != null && isPlaceholder(apiKey)) {
            throw BackupException.Invalid(
                "The file contains a placeholder API key (" +
                    "REPLACE_WITH_YOUR_OPENAI_API_KEY). Fill in a real key first.",
            )
        }

        // profile (optional, validated when present)
        val profile = root.optJSONObject(KEY_PROFILE)?.let { p ->
            val languages = requireStringList(p, "expectedLanguages")
            languages.firstOrNull { !VALID_LANGUAGE.matches(it) }?.let { bad ->
                throw BackupException.Invalid("Invalid language code: \"$bad\"")
            }
            val ctxValue = p.opt("transcriptionContext")
            val context = when {
                ctxValue == null || ctxValue == JSONObject.NULL -> ""
                ctxValue is String -> ctxValue.trim()
                else -> throw BackupException.Invalid("transcriptionContext must be a string")
            }
            TranscriptionProfile(
                expectedLanguages = languages,
                transcriptionContext = context,
                keywords = sanitizeAll(requireStringList(p, "keywords")),
            )
        }

        // settings (optional, validated when present)
        val settingsObj = root.optJSONObject(KEY_SETTINGS)
        val autoStopSeconds = settingsObj?.opt("autoStopSeconds")?.let { v ->
            if (v !is Number) {
                throw BackupException.Invalid("autoStopSeconds must be a number")
            }
            val value = v.toDouble()
            if (!validateAutoStop(value)) {
                throw BackupException.Invalid(
                    "Invalid auto-stop value. Use 1.0–3.0 s in 0.2 steps or OFF (0).",
                )
            }
            value
        }
        val customTerms = settingsObj?.let { sanitizeAll(requireStringList(it, "customTerms")) }

        return ParsedSettings(
            profile = profile,
            autoStopSeconds = autoStopSeconds,
            customTerms = customTerms,
            apiKey = apiKey,
        )
    }

    /** Language-code validation shared by import parsing and the Settings UI. */
    fun isValidLanguageCode(code: String): Boolean = VALID_LANGUAGE.matches(code)

    /**
     * Merges a parsed settings file into the current runtime profile, producing
     * the single unified profile that will be persisted.
     *
     * - languages/context override when the file supplies them, else kept
     * - keywords: the file's profile keywords + legacy custom terms are
     *   unioned into ONE keyword list (deduplicated, order-preserving)
     * - when the file has neither keywords nor custom terms, current keywords
     *   are kept
     */
    fun mergeIntoCurrent(
        current: TranscriptionProfile,
        parsed: ParsedSettings,
    ): TranscriptionProfile {
        val languages = parsed.profile?.expectedLanguages
            ?.takeIf { it.isNotEmpty() }
            ?: current.expectedLanguages
        val context = parsed.profile?.transcriptionContext
            ?.takeIf { it.isNotBlank() }
            ?: current.transcriptionContext
        val keywords = if (parsed.profile != null || parsed.customTerms != null) {
            (parsed.profile?.keywords.orEmpty() + parsed.customTerms.orEmpty()).distinct()
        } else {
            current.keywords
        }
        return TranscriptionProfile(languages, context, keywords)
    }

    /** Same edge sanitization as the rest of the app (API rejects <,>,CR,LF). */
    fun sanitizeKeyword(raw: String): String =
        raw.replace(Regex("[<>\\r\\n]+"), " ").trim()

    /**
     * Placeholder detection for template keys. Conservative by design: only
     * rejects obvious template markers, never an exact real-key regex, so
     * future legitimate OpenAI key formats are not blocked.
     */
    fun isPlaceholder(key: String): Boolean {
        val k = key.trim()
        return PLACEHOLDER_PATTERNS.any { it.containsMatchIn(k) }
    }

    private val PLACEHOLDER_PATTERNS = listOf(
        Regex("REPLACE_WITH_YOUR_OPENAI_API_KEY", RegexOption.IGNORE_CASE),
        Regex("YOUR_OPENAI_API_KEY", RegexOption.IGNORE_CASE),
        Regex("YOUR_API_KEY", RegexOption.IGNORE_CASE),
        Regex("OPENAI_API_KEY", RegexOption.IGNORE_CASE),
        Regex("sk-\\.\\.\\.", RegexOption.IGNORE_CASE), // literal "sk-..."
        Regex("REPLACE_.*KEY", RegexOption.IGNORE_CASE), // generic template marker
    )

    private fun sanitizeAll(list: List<String>): List<String> =
        list.map { sanitizeKeyword(it) }.filter { it.isNotEmpty() }.distinct()

    /** Arrays must contain only strings; anything else is a malformed file. */
    private fun requireStringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { i ->
            val v = arr.get(i)
            if (v !is String) {
                throw BackupException.Invalid("Field \"$key\" must be an array of strings")
            }
            v
        }
    }

    private const val KEY_FORMAT = "format"
    private const val KEY_SCHEMA = "schemaVersion"
    private const val KEY_SECRETS = "secrets"
    private const val KEY_PROFILE = "profile"
    private const val KEY_SETTINGS = "settings"
}
