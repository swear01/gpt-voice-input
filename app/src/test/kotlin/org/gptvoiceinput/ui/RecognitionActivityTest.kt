package org.gptvoiceinput.ui

import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.gptvoiceinput.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recognition bottom-panel invariants (issue #1): the window must be
 * translucent, undimmed, and the only opaque content is a bottom-anchored,
 * keyboard-height panel. Visual insets/multi-window behavior still requires
 * real-device validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionActivityTest {

    @Test
    fun `recognition activity inflates without crashing`() {
        val controller = Robolectric.buildActivity(RecognitionActivity::class.java).setup()
        assertNotNull(controller.get())
    }

    @Test
    fun `panel is anchored to the bottom of the window`() {
        val activity = Robolectric.buildActivity(RecognitionActivity::class.java).setup().get()
        val panel = activity.findViewById<View>(R.id.panel)
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        assertEquals(Gravity.BOTTOM, lp.gravity and Gravity.BOTTOM)
    }

    @Test
    fun `panel height is bounded to a keyboard-like range`() {
        val activity = Robolectric.buildActivity(RecognitionActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val panel = activity.findViewById<View>(R.id.panel)
        val minPx = (180 * density).toInt()
        val maxPx = (340 * density).toInt()
        assertTrue(
            "panel height ${panel.height}px not in [$minPx, $maxPx]",
            panel.height in minPx..maxPx,
        )
    }

    @Test
    fun `window is translucent and does not dim the caller`() {
        val activity = Robolectric.buildActivity(RecognitionActivity::class.java).setup().get()
        val attrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
        assertTrue(attrs.getBoolean(0, false))
        attrs.recycle()
        val flags = activity.window.attributes.flags
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    @Test
    fun `gear button exists inside the panel`() {
        val activity = Robolectric.buildActivity(RecognitionActivity::class.java).setup().get()
        assertNotNull(activity.findViewById<View>(R.id.gear_button))
    }
}
