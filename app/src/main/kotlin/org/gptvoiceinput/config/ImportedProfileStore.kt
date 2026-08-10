package org.gptvoiceinput.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent store for the runtime **imported** transcription profile
 * (Settings → Advanced → Import). Non-secret data only; the OpenAI API key
 * never passes through here.
 *
 * Layering:
 * ```
 * default asset → deployment asset → imported profile → custom terms
 * ```
 */
class ImportedProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the imported profile, or null when none has been imported. */
    fun load(): TranscriptionProfile? {
        val json = prefs.getString(KEY_PROFILE, null) ?: return null
        return try {
            val obj = JSONObject(json)
            TranscriptionProfile(
                expectedLanguages = optStringList(obj, "expectedLanguages"),
                transcriptionContext = obj.optString("transcriptionContext", "").trim(),
                keywords = optStringList(obj, "keywords"),
            )
        } catch (e: Exception) {
            // Corrupted storage: treat as absent.
            clear()
            null
        }
    }

    fun save(profile: TranscriptionProfile) {
        val obj = JSONObject()
            .put("expectedLanguages", JSONArray(profile.expectedLanguages))
            .put("transcriptionContext", profile.transcriptionContext)
            .put("keywords", JSONArray(profile.keywords))
        prefs.edit().putString(KEY_PROFILE, obj.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_PROFILE).apply()
    }

    private fun optStringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
    }

    companion object {
        private const val PREFS_NAME = "imported_profile"
        private const val KEY_PROFILE = "profile_json"
    }
}
