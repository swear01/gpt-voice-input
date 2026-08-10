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
 *  1. generic `default.json` asset — neutral public defaults, always shipped
 *  2. runtime imported profile      — imported via Settings → Advanced →
 *                                     Profile & backup (ImportedProfileStore,
 *                                     non-secret, survives APK updates)
 *  3. runtime custom terms          — Settings → Advanced → Custom terms
 *
 * Precedence semantics (explicit, per spec):
 * - expectedLanguages:     imported replaces the generic value when supplied
 * - transcriptionContext:  imported replaces the generic value when supplied
 * - keywords:              imported keywords REPLACE the generic keyword list;
 *                          runtime custom terms are merged after and
 *                          deduplicated. No automatic merge of old build-time
 *                          personal keywords.
 *
 * No API keys ever live here; keys are runtime-entered/imported and stored by
 * [org.gptvoiceinput.security.SecureApiKeyStore].
 */
data class TranscriptionProfile(
    val expectedLanguages: List<String>,
    val transcriptionContext: String,
    val keywords: List<String>,
) {
    companion object {
        private val VALID_LANGUAGE =
            Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?", RegexOption.IGNORE_CASE)

        /** Pure merge; unit-testable without an Android Context. */
        fun merge(
            base: JSONObject,
            imported: TranscriptionProfile?,
            customTerms: List<String>,
        ): TranscriptionProfile {
            val defaultLanguages = base
                .optJSONArray("expectedLanguages")
                ?.let { arr -> List(arr.length()) { arr.getString(it) } }
                .orEmpty()
                .filter { VALID_LANGUAGE.matches(it) }
            val defaultContext = base.optString("transcriptionContext").trim()
            val defaultKeywords = base
                .optJSONArray("keywords")
                ?.let { arr -> List(arr.length()) { arr.getString(it) } }
                .orEmpty()
                .map { sanitizeKeyword(it) }

            val languages =
                imported?.expectedLanguages?.takeIf { it.isNotEmpty() } ?: defaultLanguages
            val context =
                imported?.transcriptionContext?.takeIf { it.isNotBlank() } ?: defaultContext
            // Imported keywords replace the default list entirely.
            val profileKeywords = imported?.keywords ?: defaultKeywords

            val keywords = buildList {
                addAll(profileKeywords.map { sanitizeKeyword(it) })
                customTerms.forEach { add(sanitizeKeyword(it)) }
            }.distinct().filter { it.isNotEmpty() }

            return TranscriptionProfile(languages, context, keywords)
        }

        /**
         * The API rejects `<`, `>`, CR and LF inside prompt/keywords and
         * rejects the whole request. Strip them defensively at the edge.
         */
        fun sanitizeKeyword(raw: String): String =
            raw.replace(Regex("[<>\\r\\n]+"), " ").trim()
    }
}

object AppConfig {
    private const val ASSET_DEFAULT = "default.json"
    private const val TAG = "AppConfig"

    /** Effective profile: generic default asset + imported profile + custom terms. */
    fun load(
        context: Context,
        imported: TranscriptionProfile?,
        customTerms: List<String>,
    ): TranscriptionProfile {
        val defaultJson = readAsset(context, ASSET_DEFAULT) ?: run {
            // The asset is always shipped; fail loudly rather than silently
            // transcribing with an empty profile.
            throw IllegalStateException("Missing required asset: $ASSET_DEFAULT")
        }
        return TranscriptionProfile.merge(defaultJson, imported, customTerms)
    }

    /** The generic default profile (no imported layer, no custom terms). */
    fun loadDefault(context: Context): TranscriptionProfile =
        load(context, imported = null, customTerms = emptyList())

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
