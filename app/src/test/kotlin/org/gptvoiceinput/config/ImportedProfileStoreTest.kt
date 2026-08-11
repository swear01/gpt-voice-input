package org.gptvoiceinput.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ImportedProfileStore persistence: the runtime imported profile lives in
 * app-private SharedPreferences, fully independent of APK assets — a package
 * update (same package, same certificate) preserves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedProfileStoreTest {

    private fun store() = ImportedProfileStore(
        ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun `save load clear round trip`() {
        val s = store()
        s.clear()
        assertNull(s.load())

        val profile = TranscriptionProfile(
            expectedLanguages = listOf("zh-tw", "en"),
            transcriptionContext = "Imported context.",
            keywords = listOf("ACME_TERM", "ExampleTool"),
        )
        s.save(profile)

        val loaded = s.load()
        assertEquals(profile.expectedLanguages, loaded!!.expectedLanguages)
        assertEquals(profile.transcriptionContext, loaded.transcriptionContext)
        assertEquals(profile.keywords, loaded.keywords)

        s.clear()
        assertNull(s.load())
    }

    @Test
    fun `empty profile round trip`() {
        val s = store()
        s.save(TranscriptionProfile(emptyList(), "", emptyList()))
        val loaded = s.load()!!
        assertTrue(loaded.expectedLanguages.isEmpty())
        assertEquals("", loaded.transcriptionContext)
        assertTrue(loaded.keywords.isEmpty())
        s.clear()
    }

    @Test
    fun `overwrite replaces the previous profile`() {
        val s = store()
        s.save(TranscriptionProfile(listOf("en"), "old", listOf("A")))
        s.save(TranscriptionProfile(listOf("yue"), "new", listOf("B")))
        val loaded = s.load()!!
        assertEquals(listOf("yue"), loaded.expectedLanguages)
        assertEquals("new", loaded.transcriptionContext)
        assertEquals(listOf("B"), loaded.keywords)
        s.clear()
    }
}
