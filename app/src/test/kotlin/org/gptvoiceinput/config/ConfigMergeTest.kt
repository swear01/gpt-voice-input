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
          "keywords": ["Zed"]
        }
        """.trimIndent(),
    )

    private fun merge(
        base: JSONObject = defaultJson,
        overlay: JSONObject? = null,
        imported: TranscriptionProfile? = null,
        customTerms: List<String> = emptyList(),
    ) = TranscriptionProfile.merge(base, overlay, imported, customTerms)

    @Test
    fun `default alone is used when no overlay`() {
        val profile = merge()
        assertEquals(listOf("zh-tw", "en"), profile.expectedLanguages)
        assertEquals("Neutral default context.", profile.transcriptionContext)
        assertEquals(listOf("Zed"), profile.keywords)
    }

    @Test
    fun `overlay replaces strings and unions keywords`() {
        val overlay = JSONObject(
            """
            {
              "transcriptionContext": "Personal context.",
              "keywords": ["HAPI", "Zed"]
            }
            """.trimIndent(),
        )
        val profile = merge(overlay = overlay)
        assertEquals("Personal context.", profile.transcriptionContext)
        // languages untouched by overlay
        assertEquals(listOf("zh-tw", "en"), profile.expectedLanguages)
        // union, deduped, default-first
        assertEquals(listOf("Zed", "HAPI"), profile.keywords)
    }

    @Test
    fun `overlay replaces languages when present`() {
        val overlay = JSONObject("""{"expectedLanguages": ["yue", "cmn"]}""")
        val profile = merge(overlay = overlay)
        assertEquals(listOf("yue", "cmn"), profile.expectedLanguages)
    }

    @Test
    fun `custom terms merge into keywords without duplicates`() {
        val profile = merge(customTerms = listOf("HAPI", "zed", "Pi Agent"))
        assertEquals(listOf("Zed", "HAPI", "zed", "Pi Agent"), profile.keywords)
    }

    @Test
    fun `unknown overlay keys are ignored`() {
        val overlay = JSONObject("""{"_comment": "nope", "apiKey": "sk-secret"}""")
        val profile = merge(overlay = overlay)
        assertEquals("Neutral default context.", profile.transcriptionContext)
    }

    @Test
    fun `invalid language codes are dropped`() {
        val overlay = JSONObject("""{"expectedLanguages": ["en", "!!!bad", ""]}""")
        val profile = merge(overlay = overlay)
        assertEquals(listOf("en"), profile.expectedLanguages)
    }

    @Test
    fun `keywords are sanitized of characters the API rejects`() {
        val profile = merge(customTerms = listOf("a<b", "c>d", "e\r\nf", "  spaced  "))
        assertEquals(listOf("Zed", "a b", "c d", "e f", "spaced"), profile.keywords)
    }

    @Test
    fun `regional zh codes survive validation`() {
        val overlay = JSONObject("""{"expectedLanguages": ["zh-tw", "zh-hk", "zh-cn"]}""")
        val profile = merge(overlay = overlay)
        assertEquals(listOf("zh-tw", "zh-hk", "zh-cn"), profile.expectedLanguages)
    }

    // ---------------------------------------------------------- imported layer

    private fun imported(
        languages: List<String> = emptyList(),
        context: String = "",
        keywords: List<String> = emptyList(),
    ) = TranscriptionProfile(languages, context, keywords)

    @Test
    fun `imported languages override deployment when provided`() {
        val overlay = JSONObject("""{"expectedLanguages": ["zh-tw", "en"]}""")
        val profile = merge(overlay = overlay, imported = imported(languages = listOf("yue", "cmn")))
        assertEquals(listOf("yue", "cmn"), profile.expectedLanguages)
    }

    @Test
    fun `empty imported languages keep deployment languages`() {
        val overlay = JSONObject("""{"expectedLanguages": ["zh-tw", "en"]}""")
        val profile = merge(overlay = overlay, imported = imported())
        assertEquals(listOf("zh-tw", "en"), profile.expectedLanguages)
    }

    @Test
    fun `imported context overrides deployment when non-blank`() {
        val overlay = JSONObject("""{"transcriptionContext": "Deployment context."}""")
        val profile = merge(overlay = overlay, imported = imported(context = "Imported context."))
        assertEquals("Imported context.", profile.transcriptionContext)
    }

    @Test
    fun `blank imported context keeps deployment context`() {
        val overlay = JSONObject("""{"transcriptionContext": "Deployment context."}""")
        val profile = merge(overlay = overlay, imported = imported())
        assertEquals("Deployment context.", profile.transcriptionContext)
    }

    @Test
    fun `imported keywords merge after deployment keywords`() {
        val overlay = JSONObject("""{"keywords": ["HAPI", "Synopsys"]}""")
        val profile = merge(
            overlay = overlay,
            imported = imported(keywords = listOf("HAPI", "BTOR2")),
            customTerms = listOf("MathSAT"),
        )
        // deployment + imported + custom terms, deduped, order-preserving
        assertEquals(
            listOf("Zed", "HAPI", "Synopsys", "BTOR2", "MathSAT"),
            profile.keywords,
        )
    }

    @Test
    fun `full layering default - deployment - imported - custom terms`() {
        val overlay = JSONObject(
            """
            {
              "expectedLanguages": ["zh-tw", "en"],
              "transcriptionContext": "Deployment context.",
              "keywords": ["Zed", "ACP"]
            }
            """.trimIndent(),
        )
        val profile = merge(
            overlay = overlay,
            imported = imported(
                languages = listOf("yue"),
                context = "Imported context.",
                keywords = listOf("HAPI", "ACP"),
            ),
            customTerms = listOf("MCP"),
        )
        assertEquals(listOf("yue"), profile.expectedLanguages)
        assertEquals("Imported context.", profile.transcriptionContext)
        assertEquals(listOf("Zed", "ACP", "HAPI", "MCP"), profile.keywords)
    }
}
