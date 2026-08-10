package org.gptvoiceinput.config

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private val profile = TranscriptionProfile(
        expectedLanguages = listOf("zh-tw", "en"),
        transcriptionContext = "Some context.\nSecond line.",
        keywords = listOf("HAPI", "Pi Agent", "BTOR2"),
    )
    private val customTerms = listOf("runtime-term", "another one")

    private fun exportData(apiKey: String? = null, autoStop: Double = 1.8) = ExportData(
        profile = profile,
        autoStopSeconds = autoStop,
        customTerms = customTerms,
        apiKey = apiKey,
    )

    /** Builds a settings file JSON with explicit values (no fragile string surgery). */
    private fun fileJson(
        format: String = SettingsBackup.FORMAT,
        schema: Int = 1,
        secrets: JSONObject? = null,
        profile: JSONObject? = JSONObject()
            .put("expectedLanguages", JSONArray(listOf("zh-tw", "en")))
            .put("transcriptionContext", "Some context.\nSecond line.")
            .put("keywords", JSONArray(listOf("HAPI", "Pi Agent", "BTOR2"))),
        settings: JSONObject? = JSONObject()
            .put("autoStopSeconds", 1.8)
            .put("customTerms", JSONArray(listOf("runtime-term", "another one"))),
    ): String {
        val root = JSONObject()
            .put("format", format)
            .put("schemaVersion", schema)
        secrets?.let { root.put("secrets", it) }
        profile?.let { root.put("profile", it) }
        settings?.let { root.put("settings", it) }
        return root.toString()
    }

    // ------------------------------------------------------------- round trip

    @Test
    fun `safe export round trip excludes api key`() {
        val json = SettingsBackup.serialize(exportData(apiKey = null))
        assertFalse("safe export has no secrets section", json.contains("secrets"))
        assertFalse(json.contains("sk-"))

        val parsed = SettingsBackup.parse(json)
        assertNull(parsed.apiKey)
        assertEquals(profile.expectedLanguages, parsed.profile!!.expectedLanguages)
        assertEquals(profile.transcriptionContext, parsed.profile.transcriptionContext)
        assertEquals(profile.keywords, parsed.profile.keywords)
        assertEquals(1.8, parsed.autoStopSeconds!!, 0.0)
        assertEquals(customTerms, parsed.customTerms)
    }

    @Test
    fun `full backup round trip includes api key`() {
        val json = SettingsBackup.serialize(exportData(apiKey = "sk-test-123"))
        assertTrue(json.contains("secrets"))
        assertTrue(json.contains("sk-test-123"))

        val parsed = SettingsBackup.parse(json)
        assertEquals("sk-test-123", parsed.apiKey)
        assertEquals(profile.keywords, parsed.profile!!.keywords)
    }

    @Test
    fun `serialized json has format and schemaVersion 1`() {
        val root = JSONObject(SettingsBackup.serialize(exportData()))
        assertEquals("gpt-voice-input-settings", root.getString("format"))
        assertEquals(1, root.getInt("schemaVersion"))
    }

    @Test
    fun `round trip with off auto-stop`() {
        val parsed = SettingsBackup.parse(SettingsBackup.serialize(exportData(autoStop = 0.0)))
        assertEquals(0.0, parsed.autoStopSeconds!!, 0.0)
    }

    // ------------------------------------------------------------ validation

    @Test
    fun `malformed json is rejected`() {
        assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse("this is { not json")
        }
    }

    @Test
    fun `wrong format is rejected`() {
        val e = assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(format = "some-other-app-settings"))
        }
        assertTrue(e.message.orEmpty().contains("not a GPT Voice Input"))
    }

    @Test
    fun `newer schema version is rejected`() {
        val e = assertThrows(BackupException.UnsupportedVersion::class.java) {
            SettingsBackup.parse(fileJson(schema = 2))
        }
        assertEquals(2, e.version)
        assertEquals("This settings file was created by a newer unsupported version.", e.message)
    }

    @Test
    fun `older schema version is rejected`() {
        assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(schema = 0))
        }
    }

    @Test
    fun `missing schema version is rejected`() {
        val json = JSONObject(fileJson()).apply { remove("schemaVersion") }.toString()
        assertThrows(BackupException.Invalid::class.java) { SettingsBackup.parse(json) }
    }

    @Test
    fun `invalid language code is rejected`() {
        val profile = JSONObject().put("expectedLanguages", JSONArray(listOf("en", "!!!bad")))
        val e = assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(profile = profile))
        }
        assertTrue(e.message.orEmpty().contains("language"))
    }

    @Test
    fun `bad language type is rejected`() {
        val profile = JSONObject().put("expectedLanguages", JSONArray().put(42))
        assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(profile = profile))
        }
    }

    @Test
    fun `bad keyword type is rejected`() {
        val profile = JSONObject()
            .put("keywords", JSONArray().put("HAPI").put(7))
        assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(profile = profile))
        }
    }

    @Test
    fun `bad custom term type is rejected`() {
        val settings = JSONObject()
            .put("autoStopSeconds", 1.8)
            .put("customTerms", JSONArray().put("ok").put(false))
        assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(settings = settings))
        }
    }

    @Test
    fun `non-string transcriptionContext is rejected`() {
        val profile = JSONObject().put("transcriptionContext", 12345)
        assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(profile = profile))
        }
    }

    @Test
    fun `invalid auto-stop is rejected`() {
        val settings = JSONObject()
            .put("autoStopSeconds", 1.5)
            .put("customTerms", JSONArray())
        assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(settings = settings))
        }
    }

    @Test
    fun `non-number auto-stop is rejected`() {
        val settings = JSONObject()
            .put("autoStopSeconds", "1.8")
            .put("customTerms", JSONArray())
        assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(settings = settings))
        }
    }

    @Test
    fun `valid auto-stop values accepted`() {
        for (v in listOf(0.0, 1.0, 1.2, 1.8, 2.4, 3.0)) {
            assertTrue("$v should be valid", SettingsBackup.validateAutoStop(v))
        }
        for (v in listOf(1.5, 1.7, 3.1, -1.0, 0.4)) {
            assertFalse("$v should be invalid", SettingsBackup.validateAutoStop(v))
        }
    }

    // ------------------------------------------------------ optional sections

    @Test
    fun `secrets absent means api key preserved (null)`() {
        assertNull(SettingsBackup.parse(fileJson(secrets = null)).apiKey)
    }

    @Test
    fun `secrets with api key are extracted`() {
        val secrets = JSONObject().put("openAiApiKey", "sk-imported")
        assertEquals("sk-imported", SettingsBackup.parse(fileJson(secrets = secrets)).apiKey)
    }

    @Test
    fun `blank api key is treated as absent`() {
        val secrets = JSONObject().put("openAiApiKey", "   ")
        assertNull(SettingsBackup.parse(fileJson(secrets = secrets)).apiKey)
    }

    @Test
    fun `placeholder api keys are rejected on import`() {
        for (placeholder in listOf(
            "REPLACE_WITH_YOUR_OPENAI_API_KEY",
            "YOUR_OPENAI_API_KEY",
            "YOUR_API_KEY",
            "OPENAI_API_KEY",
            "sk-...",
            "replace_with_your_openai_api_key", // case-insensitive
            "  REPLACE_WITH_YOUR_OPENAI_API_KEY  ", // trimmed
            "REPLACE_THE_KEY_HERE", // generic REPLACE_..._KEY marker
        )) {
            val secrets = JSONObject().put("openAiApiKey", placeholder)
            val e = assertThrows(BackupException.Invalid::class.java) {
                SettingsBackup.parse(fileJson(secrets = secrets))
            }
            assertTrue("$placeholder should be rejected", e.message.orEmpty().contains("placeholder"))
        }
    }

    @Test
    fun `valid non-placeholder api key is accepted`() {
        for (valid in listOf(
            "sk-test-123",
            "sk-proj-a1b2c3d4",
            "sess-xyz",
            "a-random-non-template-string",
        )) {
            val secrets = JSONObject().put("openAiApiKey", valid)
            assertEquals(valid, SettingsBackup.parse(fileJson(secrets = secrets)).apiKey)
        }
    }

    @Test
    fun `placeholder import rejects the whole file atomically`() {
        // Even with a valid profile, a placeholder secret fails the entire import.
        val secrets = JSONObject().put("openAiApiKey", "REPLACE_WITH_YOUR_OPENAI_API_KEY")
        assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse(fileJson(secrets = secrets))
        }
    }

    @Test
    fun `profile section absent yields null profile`() {
        val parsed = SettingsBackup.parse(fileJson(profile = null))
        assertNull(parsed.profile)
        assertEquals(1.8, parsed.autoStopSeconds!!, 0.0)
        assertEquals(customTerms, parsed.customTerms)
    }

    @Test
    fun `settings section absent yields null settings`() {
        val parsed = SettingsBackup.parse(fileJson(settings = null))
        assertNull(parsed.autoStopSeconds)
        assertNull(parsed.customTerms)
        assertEquals(profile.keywords, parsed.profile!!.keywords)
    }

    @Test
    fun `unknown extra fields are ignored`() {
        val root = JSONObject(fileJson()).put("futureField", JSONObject().put("nested", true))
        val parsed = SettingsBackup.parse(root.toString())
        assertEquals(profile.keywords, parsed.profile!!.keywords)
        assertEquals(1.8, parsed.autoStopSeconds!!, 0.0)
    }

    @Test
    fun `import sanitizes keywords and custom terms`() {
        val profile = JSONObject()
            .put("expectedLanguages", JSONArray(listOf("zh-tw", "en")))
            .put("transcriptionContext", "ctx")
            .put("keywords", JSONArray(listOf("HA<PI")))
        val settings = JSONObject()
            .put("autoStopSeconds", 1.8)
            .put("customTerms", JSONArray(listOf("run\r\ntime")))
        val parsed = SettingsBackup.parse(fileJson(profile = profile, settings = settings))
        assertTrue(parsed.profile!!.keywords.contains("HA PI"))
        assertTrue(parsed.customTerms!!.contains("run time"))
    }
}
