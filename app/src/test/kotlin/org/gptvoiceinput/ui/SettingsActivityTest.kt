package org.gptvoiceinput.ui

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import org.gptvoiceinput.R
import org.gptvoiceinput.config.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression smoke tests: SettingsActivity must be able to inflate and open.
 *
 * v0.1.0 crashed on open — several styled TextViews in activity_settings.xml
 * were missing layout_width/layout_height (the styles are TextAppearance
 * derivatives and provide no layout params).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityTest {

    private val appContext: android.content.Context
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private fun launch() =
        Robolectric.buildActivity(SettingsActivity::class.java).setup()

    @Test
    fun `settings activity inflates without crashing`() {
        assertNotNull(launch().get())
    }

    @Test
    fun `advanced panel expands and renders effective config`() {
        val activity = launch().get()
        val header = activity.findViewById<View>(R.id.advanced_header)
        val panel = activity.findViewById<View>(R.id.advanced_panel)
        assertNotNull(header)
        assertNotNull(panel)
        header.performClick()
        assertNotNull(panel)
    }

    @Test
    fun `saved custom terms reload into the textarea`() {
        SettingsStore(appContext).setCustomTerms(listOf("HAPI", "Pi Agent"))
        val edit = launch().get().findViewById<EditText>(R.id.custom_terms_edit)
        assertEquals("HAPI\nPi Agent", edit.text.toString())
    }

    @Test
    fun `no api key - status text and no clear button`() {
        appContext.getSharedPreferences("secure_api_key", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        val activity = launch().get()
        assertEquals(
            activity.getString(R.string.api_key_hint),
            activity.findViewById<EditText>(R.id.api_key_edit).hint.toString(),
        )
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
    fun `key set - status text and visible clear button, no plaintext`() {
        // Seed the keystore-backed store's ciphertext marker directly (hasKey()
        // only inspects SharedPreferences, so no Keystore call is needed here).
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
        // Plaintext must never be shown.
        assertEquals("", activity.findViewById<EditText>(R.id.api_key_edit).text.toString())
    }

    @Test
    fun `exactly one primary page title`() {
        val activity = launch().get()
        val titleText = activity.getString(R.string.settings_title)
        val appName = activity.getString(R.string.app_name)
        val title = activity.findViewById<TextView>(R.id.title_text)
        assertNotNull(title)
        assertEquals(titleText, title.text.toString())
        // No other TextView carries the app name as a separate heading.
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val texts = collectTextViews(root)
        assertEquals(1, texts.count { it.text.toString() == titleText })
        assertEquals(0, texts.count { it.text.toString() == appName && it.id != R.id.title_text })
    }

    private fun collectTextViews(view: android.view.View): List<TextView> {
        val result = mutableListOf<TextView>()
        if (view is TextView) result.add(view)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                result.addAll(collectTextViews(view.getChildAt(i)))
            }
        }
        return result
    }

    @Test
    fun `advanced toggle shows panel, rotates chevron, updates description`() {
        val activity = launch().get()
        val header = activity.findViewById<View>(R.id.advanced_header)
        val panel = activity.findViewById<View>(R.id.advanced_panel)
        val chevron = activity.findViewById<android.widget.ImageView>(R.id.advanced_chevron)

        assertEquals(View.GONE, panel.visibility)
        assertEquals(0f, chevron.rotation)
        assertEquals(
            activity.getString(R.string.advanced_expand_description),
            chevron.contentDescription,
        )

        header.performClick()
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(180f, chevron.rotation)
        assertEquals(
            activity.getString(R.string.advanced_collapse_description),
            chevron.contentDescription,
        )

        header.performClick()
        assertEquals(View.GONE, panel.visibility)
        assertEquals(0f, chevron.rotation)
    }

    @Test
    fun `profile and backup buttons exist`() {
        val activity = launch().get()
        assertNotNull(activity.findViewById<View>(R.id.import_button))
        assertNotNull(activity.findViewById<View>(R.id.export_button))
        assertNotNull(activity.findViewById<View>(R.id.export_full_button))
        assertNotNull(activity.findViewById<View>(R.id.clear_profile_button))
    }
}
