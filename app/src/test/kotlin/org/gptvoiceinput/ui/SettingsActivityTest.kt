package org.gptvoiceinput.ui

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.gptvoiceinput.R
import org.gptvoiceinput.config.ImportedProfileStore
import org.gptvoiceinput.config.ParsedSettings
import org.gptvoiceinput.config.SettingsStore
import org.gptvoiceinput.config.TranscriptionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings UI regression tests (synthetic fixtures only — the repository is
 * generic and must not contain owner-specific vocabulary).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityTest {

    private val appContext: android.content.Context
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private fun launch() =
        Robolectric.buildActivity(SettingsActivity::class.java).setup()

    // ---------------------------------------------------------- structure

    @Test
    fun `settings activity inflates without crashing`() {
        assertNotNull(launch().get())
    }

    @Test
    fun `no advanced section and no effective-config dump`() {
        val res = appContext.resources
        for (name in listOf(
            "advanced_header",
            "advanced_panel",
            "advanced_chevron",
            "effective_config_text",
            "custom_terms_edit",
        )) {
            assertEquals(
                "removed id must not exist: $name",
                0,
                res.getIdentifier(name, "id", appContext.packageName),
            )
        }
    }

    @Test
    fun `four top-level sections are visible`() {
        val activity = launch().get()
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val texts = collectTexts(root)
        for (section in listOf(
            activity.getString(R.string.section_openai),
            activity.getString(R.string.section_transcription),
            activity.getString(R.string.section_recording),
            activity.getString(R.string.section_profile),
        )) {
            assertEquals("section missing: $section", 1, texts.count { it == section })
        }
    }

    @Test
    fun `editable transcription fields exist`() {
        val activity = launch().get()
        assertNotNull(activity.findViewById<EditText>(R.id.languages_edit))
        assertNotNull(activity.findViewById<EditText>(R.id.context_edit))
        assertNotNull(activity.findViewById<EditText>(R.id.keywords_edit))
    }

    @Test
    fun `profile and backup rows are present and clickable`() {
        val activity = launch().get()
        for (id in intArrayOf(
            R.id.import_row,
            R.id.export_row,
            R.id.export_full_row,
            R.id.reset_profile_row,
        )) {
            val row = activity.findViewById<View>(id)
            assertNotNull("row missing: $id", row)
            assertTrue("row not clickable: $id", row.isClickable)
        }
    }

    @Test
    fun `exactly one primary page title`() {
        val activity = launch().get()
        val titleText = activity.getString(R.string.settings_title)
        val appName = activity.getString(R.string.app_name)
        val title = activity.findViewById<TextView>(R.id.title_text)
        assertEquals(titleText, title.text.toString())
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val texts = collectTextViews(root)
        assertEquals(1, texts.count { it.text.toString() == titleText })
        assertEquals(0, texts.count { it.text.toString() == appName && it.id != R.id.title_text })
    }

    private fun collectTexts(view: View): List<String> =
        collectTextViews(view).map { it.text.toString() }

    private fun collectTextViews(view: View): List<TextView> {
        val result = mutableListOf<TextView>()
        if (view is TextView) result.add(view)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                result.addAll(collectTextViews(view.getChildAt(i)))
            }
        }
        return result
    }

    // ------------------------------------------------------------ api key

    @Test
    fun `no api key - status text and no clear button`() {
        appContext.getSharedPreferences("secure_api_key", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        val activity = launch().get()
        assertEquals(
            activity.getString(R.string.key_not_configured),
            activity.findViewById<TextView>(R.id.api_key_status).text.toString(),
        )
        assertEquals(
            View.GONE,
            activity.findViewById<Button>(R.id.clear_api_key_button).visibility,
        )
    }

    @Test
    fun `key set - status text, visible clear button, no plaintext`() {
        appContext.getSharedPreferences("secure_api_key", android.content.Context.MODE_PRIVATE)
            .edit().putString("iv", "AAAA").putString("ciphertext", "BBBB").apply()
        val activity = launch().get()
        assertEquals(
            activity.getString(R.string.key_configured),
            activity.findViewById<TextView>(R.id.api_key_status).text.toString(),
        )
        assertEquals(
            View.VISIBLE,
            activity.findViewById<Button>(R.id.clear_api_key_button).visibility,
        )
        assertEquals("", activity.findViewById<EditText>(R.id.api_key_edit).text.toString())
    }

    // ------------------------------------------------------ transcription

    @Test
    fun `generic default is shown in editable fields when no profile exists`() {
        val activity = launch().get()
        assertEquals("zh-tw, en", activity.findViewById<EditText>(R.id.languages_edit).text.toString())
        assertEquals(
            activity.getString(R.string.context_hint),
            activity.findViewById<EditText>(R.id.context_edit).hint.toString(),
        )
        // default.json has no keywords -> empty field
        assertEquals("", activity.findViewById<EditText>(R.id.keywords_edit).text.toString())
    }

    @Test
    fun `imported profile populates the editable fields`() {
        ImportedProfileStore(appContext).save(
            TranscriptionProfile(
                expectedLanguages = listOf("yue", "cmn"),
                transcriptionContext = "UNIQUE_TEST_CONTEXT",
                keywords = listOf("ACME_TERM", "ZXQ-17"),
            ),
        )
        val activity = launch().get()
        assertEquals("yue, cmn", activity.findViewById<EditText>(R.id.languages_edit).text.toString())
        assertEquals("UNIQUE_TEST_CONTEXT", activity.findViewById<EditText>(R.id.context_edit).text.toString())
        assertEquals("ACME_TERM\nZXQ-17", activity.findViewById<EditText>(R.id.keywords_edit).text.toString())
    }

    @Test
    fun `legacy customTerms merge into the unified keyword field, deduplicated`() {
        ImportedProfileStore(appContext).save(
            TranscriptionProfile(
                expectedLanguages = listOf("zh-tw", "en"),
                transcriptionContext = "",
                keywords = listOf("ACME_TERM", "ExampleTool"),
            ),
        )
        SettingsStore(appContext).setCustomTerms(listOf("ZXQ-17", "ACME_TERM"))
        val activity = launch().get()
        assertEquals(
            "ACME_TERM\nExampleTool\nZXQ-17",
            activity.findViewById<EditText>(R.id.keywords_edit).text.toString(),
        )
    }

    @Test
    fun `save persists unified profile and migrates legacy customTerms away`() {
        SettingsStore(appContext).setCustomTerms(listOf("ZXQ-17"))
        val activity = launch().get()
        val languages = activity.findViewById<EditText>(R.id.languages_edit)
        val context = activity.findViewById<EditText>(R.id.context_edit)
        val keywords = activity.findViewById<EditText>(R.id.keywords_edit)

        languages.setText("zh-tw, en")
        context.setText("UNIQUE_TEST_CONTEXT")
        keywords.setText("ACME_TERM\nExampleTool")

        activity.findViewById<View>(R.id.save_button).performClick()

        val saved = ImportedProfileStore(appContext).load()!!
        assertEquals(listOf("zh-tw", "en"), saved.expectedLanguages)
        assertEquals("UNIQUE_TEST_CONTEXT", saved.transcriptionContext)
        assertEquals(listOf("ACME_TERM", "ExampleTool"), saved.keywords)
        // Legacy split state is gone.
        assertEquals(emptyList<String>(), SettingsStore(appContext).customTerms())
    }

    @Test
    fun `save then reopen shows the same values`() {
        val activity = launch().get()
        activity.findViewById<EditText>(R.id.languages_edit).setText("yue, cmn")
        activity.findViewById<EditText>(R.id.context_edit).setText("UNIQUE_TEST_CONTEXT")
        activity.findViewById<EditText>(R.id.keywords_edit).setText("ACME_TERM")
        activity.findViewById<View>(R.id.save_button).performClick()

        val reopened = launch().get()
        assertEquals("yue, cmn", reopened.findViewById<EditText>(R.id.languages_edit).text.toString())
        assertEquals("UNIQUE_TEST_CONTEXT", reopened.findViewById<EditText>(R.id.context_edit).text.toString())
        assertEquals("ACME_TERM", reopened.findViewById<EditText>(R.id.keywords_edit).text.toString())
    }

    @Test
    fun `invalid languages on save modify nothing`() {
        ImportedProfileStore(appContext).clear()
        SettingsStore(appContext).setCustomTerms(listOf("ZXQ-17"))
        val activity = launch().get()
        activity.findViewById<EditText>(R.id.languages_edit).setText("not a lang")
        activity.findViewById<EditText>(R.id.keywords_edit).setText("ACME_TERM")
        activity.findViewById<View>(R.id.save_button).performClick()

        // Nothing persisted: no imported profile, legacy terms untouched.
        assertNull(ImportedProfileStore(appContext).load())
        assertEquals(listOf("ZXQ-17"), SettingsStore(appContext).customTerms())
    }

    // ----------------------------------------------------------- reset

    @Test
    fun `reset restores generic defaults but keeps key and auto-stop`() {
        // Seed key marker + auto-stop + custom profile.
        appContext.getSharedPreferences("secure_api_key", android.content.Context.MODE_PRIVATE)
            .edit().putString("iv", "AAAA").putString("ciphertext", "BBBB").apply()
        SettingsStore(appContext).setAutoStopSeconds(2.4)
        SettingsStore(appContext).setCustomTerms(listOf("ZXQ-17"))
        ImportedProfileStore(appContext).save(
            TranscriptionProfile(
                expectedLanguages = listOf("yue"),
                transcriptionContext = "UNIQUE_TEST_CONTEXT",
                keywords = listOf("ACME_TERM"),
            ),
        )

        val activity = launch().get()
        activity.findViewById<View>(R.id.reset_profile_row).performClick()
        // Confirmation dialog appears.
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull("reset confirmation dialog", dialog)
        // Execute the confirmed reset path (dialog button wiring is thin).
        activity.performResetProfile()

        // Profile gone, defaults shown.
        assertNull(ImportedProfileStore(appContext).load())
        assertEquals("zh-tw, en", activity.findViewById<EditText>(R.id.languages_edit).text.toString())
        assertEquals("", activity.findViewById<EditText>(R.id.keywords_edit).text.toString())
        assertEquals(emptyList<String>(), SettingsStore(appContext).customTerms())
        // Key + auto-stop preserved.
        assertTrue(activity.findViewById<Button>(R.id.clear_api_key_button).visibility == View.VISIBLE)
        assertEquals(2.4, SettingsStore(appContext).autoStopSeconds, 0.001)
    }

    // ----------------------------------------------------------- import

    @Test
    fun `import applies immediately to visible fields`() {
        val activity = launch().get()
        activity.applyImport(
            ParsedSettings(
                profile = TranscriptionProfile(
                    expectedLanguages = listOf("yue", "cmn"),
                    transcriptionContext = "UNIQUE_TEST_CONTEXT",
                    keywords = listOf("ACME_TERM", "ZXQ-17"),
                ),
                autoStopSeconds = 2.0,
                customTerms = listOf("ExampleTool"),
                apiKey = null,
            ),
        )
        assertEquals("yue, cmn", activity.findViewById<EditText>(R.id.languages_edit).text.toString())
        assertEquals("UNIQUE_TEST_CONTEXT", activity.findViewById<EditText>(R.id.context_edit).text.toString())
        // profile keywords + legacy custom terms unified, deduplicated
        assertEquals(
            "ACME_TERM\nZXQ-17\nExampleTool",
            activity.findViewById<EditText>(R.id.keywords_edit).text.toString(),
        )
        assertEquals(2.0, SettingsStore(appContext).autoStopSeconds, 0.0)
        // legacy split state cleared
        assertEquals(emptyList<String>(), SettingsStore(appContext).customTerms())
    }
}
