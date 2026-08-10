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

    @Test
    fun `default alone is used when no overlay`() {
        val profile = TranscriptionProfile.merge(defaultJson, null, customTerms = emptyList())
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
        val profile = TranscriptionProfile.merge(defaultJson, overlay, customTerms = emptyList())
        assertEquals("Personal context.", profile.transcriptionContext)
        // languages untouched by overlay
        assertEquals(listOf("zh-tw", "en"), profile.expectedLanguages)
        // union, deduped, default-first
        assertEquals(listOf("Zed", "HAPI"), profile.keywords)
    }

    @Test
    fun `overlay replaces languages when present`() {
        val overlay = JSONObject("""{"expectedLanguages": ["yue", "cmn"]}""")
        val profile = TranscriptionProfile.merge(defaultJson, overlay, customTerms = emptyList())
        assertEquals(listOf("yue", "cmn"), profile.expectedLanguages)
    }

    @Test
    fun `custom terms merge into keywords without duplicates`() {
        val profile = TranscriptionProfile.merge(
            defaultJson,
            null,
            customTerms = listOf("HAPI", "zed", "Pi Agent"),
        )
        assertEquals(listOf("Zed", "HAPI", "zed", "Pi Agent"), profile.keywords)
    }

    @Test
    fun `unknown overlay keys are ignored`() {
        val overlay = JSONObject("""{"_comment": "nope", "apiKey": "sk-secret"}""")
        val profile = TranscriptionProfile.merge(defaultJson, overlay, customTerms = emptyList())
        assertEquals("Neutral default context.", profile.transcriptionContext)
    }

    @Test
    fun `invalid language codes are dropped`() {
        val overlay = JSONObject("""{"expectedLanguages": ["en", "!!!bad", ""]}""")
        val profile = TranscriptionProfile.merge(defaultJson, overlay, customTerms = emptyList())
        assertEquals(listOf("en"), profile.expectedLanguages)
    }

    @Test
    fun `keywords are sanitized of characters the API rejects`() {
        val profile = TranscriptionProfile.merge(
            defaultJson,
            null,
            customTerms = listOf("a<b", "c>d", "e\r\nf", "  spaced  "),
        )
        assertEquals(listOf("Zed", "a b", "c d", "e f", "spaced"), profile.keywords)
    }

    @Test
    fun `regional zh codes survive validation`() {
        val overlay = JSONObject("""{"expectedLanguages": ["zh-tw", "zh-hk", "zh-cn"]}""")
        val profile = TranscriptionProfile.merge(defaultJson, overlay, customTerms = emptyList())
        assertEquals(listOf("zh-tw", "zh-hk", "zh-cn"), profile.expectedLanguages)
    }
}
