package org.gptvoiceinput.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private val bundle = SettingsBundle(
        profile = TranscriptionProfile(
            expectedLanguages = listOf("zh-tw", "en"),
            transcriptionContext = "Some context.\nSecond line.",
            keywords = listOf("HAPI", "Pi Agent", "BTOR2"),
        ),
        autoStopSeconds = 1.8,
        customTerms = listOf("runtime-term", "another one"),
    )

    // ------------------------------------------------------------- round trip

    @Test
    fun `export then import reproduces the same bundle`() {
        val json = SettingsBackup.serialize(bundle)
        val parsed = SettingsBackup.parse(json)
        assertEquals(bundle.profile.expectedLanguages, parsed.profile.expectedLanguages)
        assertEquals(bundle.profile.transcriptionContext, parsed.profile.transcriptionContext)
        assertEquals(bundle.profile.keywords, parsed.profile.keywords)
        assertEquals(bundle.autoStopSeconds, parsed.autoStopSeconds, 0.0)
        assertEquals(bundle.customTerms, parsed.customTerms)
    }

    @Test
    fun `serialized json has schemaVersion 1 and no api key`() {
        val json = SettingsBackup.serialize(bundle)
        val root = JSONObject(json)
        assertEquals(1, root.getInt("schemaVersion"))
        assertTrue(root.has("profile"))
        assertTrue(root.has("settings"))
        // The API key must never be exported.
        assertFalse("no apiKey key", root.has("apiKey"))
        assertFalse("no api key string", json.contains("sk-"))
        assertFalse(json.contains("API_KEY"))
    }

    @Test
    fun `round trip with off auto-stop and empty profile parts`() {
        val b = SettingsBundle(
            profile = TranscriptionProfile(emptyList(), "", emptyList()),
            autoStopSeconds = 0.0,
            customTerms = emptyList(),
        )
        val parsed = SettingsBackup.parse(SettingsBackup.serialize(b))
        assertTrue(parsed.profile.expectedLanguages.isEmpty())
        assertEquals("", parsed.profile.transcriptionContext)
        assertTrue(parsed.profile.keywords.isEmpty())
        assertEquals(0.0, parsed.autoStopSeconds, 0.0)
        assertTrue(parsed.customTerms.isEmpty())
    }

    // ------------------------------------------------------------ validation

    @Test
    fun `malformed json is rejected`() {
        val e = assertThrows(BackupException.Invalid::class.java) {
            SettingsBackup.parse("this is { not json")
        }
        assertTrue(e.message.orEmpty().isNotBlank())
    }

    @Test
    fun `newer schema version is rejected with explicit message`() {
        val e = assertThrows(BackupException.UnsupportedVersion::class.java) {
            SettingsBackup.parse(SettingsBackup.serialize(bundle).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        }
        assertEquals(2, e.version)
        assertEquals("This settings file was created by a newer unsupported version.", e.message)
    }

    @Test
    fun `missing schema version is rejected`() {
        val json = SettingsBackup.serialize(bundle).replace("\"schemaVersion\": 1,", "")
        assertThrows(BackupException.Invalid::class.java) { SettingsBackup.parse(json) }
    }

    @Test
    fun `invalid language code is rejected`() {
        val json = SettingsBackup.serialize(bundle).replace("zh-tw", "!!!")
        val e = assertThrows(BackupException.Invalid::class.java) { SettingsBackup.parse(json) }
        assertTrue(e.message.orEmpty().contains("language"))
    }

    @Test
    fun `invalid auto-stop is rejected`() {
        // 1.5 is not on the 0.2 s grid.
        val json = SettingsBackup.serialize(bundle).replace("\"autoStopSeconds\": 1.8", "\"autoStopSeconds\": 1.5")
        assertThrows(BackupException.Invalid::class.java) { SettingsBackup.parse(json) }
        // Out of range.
        val json2 = SettingsBackup.serialize(bundle).replace("\"autoStopSeconds\": 1.8", "\"autoStopSeconds\": 3.5")
        assertThrows(BackupException.Invalid::class.java) { SettingsBackup.parse(json2) }
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

    @Test
    fun `missing sections are rejected`() {
        val noProfile = SettingsBackup.serialize(bundle).replace("\"profile\":", "\"profiles\":")
        assertThrows(BackupException.Invalid::class.java) { SettingsBackup.parse(noProfile) }
        val noSettings = SettingsBackup.serialize(bundle).replace("\"settings\":", "\"setting\":")
        assertThrows(BackupException.Invalid::class.java) { SettingsBackup.parse(noSettings) }
    }

    @Test
    fun `import sanitizes keywords and custom terms`() {
        val json = SettingsBackup.serialize(bundle)
            .replace("HAPI", "HA<PI")
            .replace("runtime-term", "run\\r\\ntime")
        val parsed = SettingsBackup.parse(json)
        assertTrue(parsed.profile.keywords.contains("HA PI"))
        assertTrue(parsed.customTerms.contains("run time"))
    }

    @Test
    fun `unknown extra keys are ignored`() {
        val json = SettingsBackup.serialize(bundle).replace(
            "\"schemaVersion\": 1",
            "\"schemaVersion\": 1,\"futureField\": true",
        )
        val parsed = SettingsBackup.parse(json)
        assertEquals(bundle.profile.keywords, parsed.profile.keywords)
    }
}
