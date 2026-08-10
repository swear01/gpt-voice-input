package org.gptvoiceinput.config

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Effective transcription profile: what gpt-transcribe receives beyond the
 * audio itself.
 *
 * Layer order (later layers win):
 *  1. `config/default.json`   — generic public defaults, always shipped
 *  2. `config/local.json`     — deployment overlay, gitignored, shipped when
 *                                present (personal profile for forks/deployers)
 *  3. runtime custom terms    — user-entered in Settings → Advanced, merged
 *                                into keywords (never overrides them)
 *
 * No API keys ever live here; keys are runtime-entered and stored by
 * [org.gptvoiceinput.security.SecureApiKeyStore].
 */
data class TranscriptionProfile(
    val expectedLanguages: List<String>,
    val transcriptionContext: String,
    val keywords: List<String>,
) {
    companion object {
        /** Keys whose arrays are unioned instead of replaced by overlays. */
        private val UNION_ARRAY_KEYS = setOf("keywords")

        private val VALID_LANGUAGE =
            Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?", RegexOption.IGNORE_CASE)

        /**
         * Merge an optional overlay over a base JSON document, then fold in
         * runtime custom terms. Pure function so it is unit-testable without
         * an Android Context.
         */
        fun merge(
            base: JSONObject,
            overlay: JSONObject?,
            customTerms: List<String>,
        ): TranscriptionProfile {
            val merged = JSONObject(base.toString())

            overlay?.let { o ->
                o.keys().forEach { key ->
                    when {
                        !base.has(key) -> merged.put(key, o.get(key))
                        UNION_ARRAY_KEYS.contains(key) -> {
                            val combined = JSONArray()
                            val baseArr = base.getJSONArray(key)
                            for (i in 0 until baseArr.length()) combined.put(baseArr.get(i))
                            val overlayArr = o.getJSONArray(key)
                            for (i in 0 until overlayArr.length()) combined.put(overlayArr.get(i))
                            merged.put(key, combined)
                        }
                        else -> merged.put(key, o.get(key))
                    }
                }
            }

            val languages = merged
                .optJSONArray("expectedLanguages")
                ?.let { arr -> List(arr.length()) { arr.getString(it) } }
                .orEmpty()
                .filter { VALID_LANGUAGE.matches(it) }

            val context = merged.optString("transcriptionContext").trim()

            val keywords = buildList {
                merged.optJSONArray("keywords")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        add(sanitizeKeyword(arr.getString(i)))
                    }
                }
                customTerms.forEach { add(sanitizeKeyword(it)) }
            }.distinct().filter { it.isNotEmpty() }

            if (languages != (merged.optJSONArray("expectedLanguages")?.let { arr ->
                    List(arr.length()) { arr.getString(it) }
                })) {
                Log.w(TAG, "Some expectedLanguages were dropped (unsupported format)")
            }

            return TranscriptionProfile(languages, context, keywords)
        }

        /**
         * The API rejects `<`, `>`, CR and LF inside prompt/keywords and
         * rejects the whole request. Strip them defensively at the edge.
         */
        private fun sanitizeKeyword(raw: String): String =
            raw.replace(Regex("[<>\\r\\n]+"), " ").trim()

        private const val TAG = "AppConfig"
    }
}

object AppConfig {
    private const val ASSET_DEFAULT = "default.json"
    private const val ASSET_LOCAL = "local.json"
    private const val TAG = "AppConfig"

    /** Loads the effective profile for a given runtime custom-terms list. */
    fun load(context: Context, customTerms: List<String>): TranscriptionProfile {
        val defaultJson = readAsset(context, ASSET_DEFAULT) ?: run {
            // The asset is always shipped; fail loudly rather than silently
            // transcribing with an empty profile.
            throw IllegalStateException("Missing required asset: $ASSET_DEFAULT")
        }
        val localJson = readAsset(context, ASSET_LOCAL)
        return TranscriptionProfile.merge(defaultJson, localJson, customTerms)
    }

    private fun readAsset(context: Context, name: String): JSONObject? =
        try {
            context.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: Exception) {
            if (e is java.io.FileNotFoundException) {
                null
            } else {
                Log.e(TAG, "Failed to read asset $name", e)
                null
            }
        }
}
