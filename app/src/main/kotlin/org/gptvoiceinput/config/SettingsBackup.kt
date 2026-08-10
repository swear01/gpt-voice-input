package org.gptvoiceinput.config

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Portable, non-secret settings backup (schemaVersion 1).
 *
 * Export shape (never contains the API key):
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "profile": {
 *     "expectedLanguages": ["zh-tw", "en"],
 *     "transcriptionContext": "...",
 *     "keywords": ["HAPI", "Pi Agent"]
 *   },
 *   "settings": {
 *     "autoStopSeconds": 1.8,
 *     "customTerms": ["runtime term"]
 *   }
 * }
 * ```
 *
 * Parse-then-commit: [parse] validates everything up front and never touches
 * any store; the caller applies the bundle only after the user confirms.
 */
data class SettingsBundle(
    val profile: TranscriptionProfile,
    val autoStopSeconds: Double,
    val customTerms: List<String>,
)

sealed class BackupException(message: String) : Exception(message) {
    /** The file is not JSON, or the v1 structure is broken. */
    class Invalid(message: String) : BackupException(message)

    /** schemaVersion is newer than this app supports. */
    class UnsupportedVersion(val version: Int) :
        BackupException("This settings file was created by a newer unsupported version.")
}

object SettingsBackup {

    const val SCHEMA_VERSION = 1

    private val VALID_LANGUAGE =
        Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?", RegexOption.IGNORE_CASE)

    private val AUTO_STOP_STEP = SettingsStore.AUTO_STOP_STEP
    private val AUTO_STOP_MIN = SettingsStore.AUTO_STOP_MIN
    private val AUTO_STOP_MAX = SettingsStore.AUTO_STOP_MAX
    private const val AUTO_STOP_OFF = SettingsStore.AUTO_STOP_OFF

    /** Serializes the effective non-secret configuration for backup. */
    fun serialize(bundle: SettingsBundle): String {
        val profile = bundle.profile
        val json = JSONObject()
            .put(KEY_SCHEMA, SCHEMA_VERSION)
            .put(
                KEY_PROFILE,
                JSONObject()
                    .put("expectedLanguages", JSONArray(profile.expectedLanguages))
                    .put("transcriptionContext", profile.transcriptionContext)
                    .put("keywords", JSONArray(profile.keywords)),
            )
            .put(
                KEY_SETTINGS,
                JSONObject()
                    .put("autoStopSeconds", bundle.autoStopSeconds)
                    .put("customTerms", JSONArray(bundle.customTerms)),
            )
        return json.toString(2)
    }

    /**
     * Parses and fully validates an export file. Throws [BackupException] with
     * a user-presentable message on any problem; never partially succeeds.
     */
    fun parse(json: String): SettingsBundle {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupException.Invalid("This is not a valid JSON settings file.")
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

        val profileObj = root.optJSONObject(KEY_PROFILE)
            ?: throw BackupException.Invalid("Missing profile section.")
        val settingsObj = root.optJSONObject(KEY_SETTINGS)
            ?: throw BackupException.Invalid("Missing settings section.")

        val languages = optStringList(profileObj, "expectedLanguages")
            .also { list ->
                list.firstOrNull { !VALID_LANGUAGE.matches(it) }?.let { bad ->
                    throw BackupException.Invalid("Invalid language code: \"$bad\"")
                }
            }
        val context = profileObj.optString("transcriptionContext", "").trim()
        val keywords = sanitizeAll(optStringList(profileObj, "keywords"))
        val customTerms = sanitizeAll(optStringList(settingsObj, "customTerms"))

        val autoStop = settingsObj.optDouble("autoStopSeconds", Double.NaN)
        if (!validateAutoStop(autoStop)) {
            throw BackupException.Invalid(
                "Invalid auto-stop value. Use 1.0–3.0 s in 0.2 steps or OFF (0).",
            )
        }

        return SettingsBundle(
            profile = TranscriptionProfile(languages, context, keywords),
            autoStopSeconds = autoStop,
            customTerms = customTerms,
        )
    }

    /** Allowed: OFF (0.0) or 1.0–3.0 in 0.2 s increments. */
    fun validateAutoStop(value: Double): Boolean {
        if (value == AUTO_STOP_OFF) return true
        if (value < AUTO_STOP_MIN || value > AUTO_STOP_MAX) return false
        val steps = (value - AUTO_STOP_MIN) / AUTO_STOP_STEP
        return kotlin.math.abs(steps - kotlin.math.round(steps)) < 1e-9
    }

    /** Same edge sanitization as the rest of the app (API rejects <,>,CR,LF). */
    fun sanitizeKeyword(raw: String): String =
        raw.replace(Regex("[<>\\r\\n]+"), " ").trim()

    private fun sanitizeAll(list: List<String>): List<String> =
        list.map { sanitizeKeyword(it) }.filter { it.isNotEmpty() }.distinct()

    private fun optStringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { arr.optString(it) }
    }

    private const val KEY_SCHEMA = "schemaVersion"
    private const val KEY_PROFILE = "profile"
    private const val KEY_SETTINGS = "settings"
}
