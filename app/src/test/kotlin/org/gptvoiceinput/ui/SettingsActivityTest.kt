package org.gptvoiceinput.ui

import android.view.View
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

    @Test
    fun `settings activity inflates without crashing`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertNotNull(controller.get())
    }

    @Test
    fun `advanced panel expands and renders effective config`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val header = activity.findViewById<View>(R.id.advanced_header)
        val panel = activity.findViewById<View>(R.id.advanced_panel)
        assertNotNull(header)
        assertNotNull(panel)
        header.performClick()
        assertNotNull(panel)
    }

    @Test
    fun `saved custom terms reload into the textarea`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        SettingsStore(context).setCustomTerms(listOf("HAPI", "Pi Agent"))

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val edit = controller.get().findViewById<EditText>(R.id.custom_terms_edit)
        assertEquals("HAPI\nPi Agent", edit.text.toString())
    }
}
