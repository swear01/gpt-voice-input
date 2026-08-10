package org.gptvoiceinput.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigMergeTest {

    private val defaultJson = JSONObject(
        """
        {
          "expectedLanguages": ["zh-tw", "en"],
          "transcriptionContext": "Neutral default context.",
          "keywords": []
        }
        """.trimIndent(),
    )

    private fun merge(
        base: JSONObject = defaultJson,
        imported: TranscriptionProfile? = null,
        customTerms: List<String> = emptyList(),
    ) = TranscriptionProfile.merge(base, imported, customTerms)

    private fun imported(
        languages: List<String> = emptyList(),
        context: String = "",
        keywords: List<String> = emptyList(),
    ) = TranscriptionProfile(languages, context, keywords)

    @Test
    fun `default alone is used when no imported profile`() {
        val profile = merge()
        assertEquals(listOf("zh-tw", "en"), profile.expectedLanguages)
        assertEquals("Neutral default context.", profile.transcriptionContext)
        assertEquals(emptyList<String>(), profile.keywords)
    }

    @Test
    fun `default keywords plus custom terms when no imported profile`() {
        val profile = merge(customTerms = listOf("HAPI", "zed", "Pi Agent"))
        assertEquals(listOf("HAPI", "zed", "Pi Agent"), profile.keywords)
    }

    @Test
    fun `imported languages override default when provided`() {
        val profile = merge(imported = imported(languages = listOf("yue", "cmn")))
        assertEquals(listOf("yue", "cmn"), profile.expectedLanguages)
    }

    @Test
    fun `empty imported languages keep default languages`() {
        val profile = merge(imported = imported())
        assertEquals(listOf("zh-tw", "en"), profile.expectedLanguages)
    }

    @Test
    fun `imported context overrides default when non-blank`() {
        val profile = merge(imported = imported(context = "Imported context."))
        assertEquals("Imported context.", profile.transcriptionContext)
    }

    @Test
    fun `blank imported context keeps default context`() {
        val profile = merge(imported = imported())
        assertEquals("Neutral default context.", profile.transcriptionContext)
    }

    @Test
    fun `imported keywords replace the default keyword list`() {
        val profile = merge(imported = imported(keywords = listOf("HAPI", "BTOR2")))
        assertEquals(listOf("HAPI", "BTOR2"), profile.keywords)
    }

    @Test
    fun `effective keywords are imported keywords plus custom terms deduped`() {
        val profile = merge(
            imported = imported(keywords = listOf("HAPI", "ACP")),
            customTerms = listOf("HAPI", "MathSAT"),
        )
        assertEquals(listOf("HAPI", "ACP", "MathSAT"), profile.keywords)
    }

    @Test
    fun `keywords are sanitized of characters the API rejects`() {
        val profile = merge(customTerms = listOf("a<b", "c>d", "e\r\nf", "  spaced  "))
        assertEquals(listOf("a b", "c d", "e f", "spaced"), profile.keywords)
    }

    @Test
    fun `regional zh codes survive validation`() {
        val profile = merge(imported = imported(languages = listOf("zh-tw", "zh-hk", "zh-cn")))
        assertEquals(listOf("zh-tw", "zh-hk", "zh-cn"), profile.expectedLanguages)
    }

    // ------------------------------------------------------- update persistence

    /**
     * Critical invariant: an APK update that changes default.json must NOT
     * erase the runtime imported profile. The imported layer keeps overriding
     * the new defaults after the update.
     */
    @Test
    fun `imported profile overrides a changed default - survives update`() {
        val imported = imported(
            languages = listOf("yue", "cmn"),
            context = "Imported context that must survive updates.",
            keywords = listOf("HAPI"),
        )
        val defaultV1 = JSONObject(
            """
            {
              "expectedLanguages": ["zh-tw", "en"],
              "transcriptionContext": "v1 default context.",
              "keywords": []
            }
            """.trimIndent(),
        )
        val effV1 = TranscriptionProfile.merge(defaultV1, imported, emptyList())

        // APK update ships a different default.json.
        val defaultV2 = JSONObject(
            """
            {
              "expectedLanguages": ["en"],
              "transcriptionContext": "v2 default context.",
              "keywords": ["newDefaultKeyword"]
            }
            """.trimIndent(),
        )
        val effV2 = TranscriptionProfile.merge(defaultV2, imported, emptyList())

        assertEquals(effV1.expectedLanguages, effV2.expectedLanguages)
        assertEquals(effV1.transcriptionContext, effV2.transcriptionContext)
        assertEquals(effV1.keywords, effV2.keywords)
        // And the imported values specifically still win over the new default:
        assertEquals(listOf("yue", "cmn"), effV2.expectedLanguages)
        assertEquals("Imported context that must survive updates.", effV2.transcriptionContext)
        assertEquals(listOf("HAPI"), effV2.keywords)
    }
}
