package org.gptvoiceinput.ui

import android.view.View
import android.widget.Button
import android.widget.EditText
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
    fun `no api key - hint and no clear button`() {
        appContext.getSharedPreferences("secure_api_key", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        val activity = launch().get()
        assertEquals(
            activity.getString(R.string.api_key_hint),
            activity.findViewById<EditText>(R.id.api_key_edit).hint.toString(),
        )
        assertEquals(
            View.GONE,
            activity.findViewById<Button>(R.id.clear_api_key_button).visibility,
        )
    }

    @Test
    fun `key set - hint and visible clear button`() {
        // Seed the keystore-backed store's ciphertext marker directly (hasKey()
        // only inspects SharedPreferences, so no Keystore call is needed here).
        appContext.getSharedPreferences("secure_api_key", android.content.Context.MODE_PRIVATE)
            .edit().putString("iv", "AAAA").putString("ciphertext", "BBBB").apply()
        val activity = launch().get()
        assertEquals(
            activity.getString(R.string.api_key_hint_set),
            activity.findViewById<EditText>(R.id.api_key_edit).hint.toString(),
        )
        assertEquals(
            View.VISIBLE,
            activity.findViewById<Button>(R.id.clear_api_key_button).visibility,
        )
        // Plaintext must never be shown.
        assertEquals("", activity.findViewById<EditText>(R.id.api_key_edit).text.toString())
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
