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

    @org.junit.Before
    fun setUp() {
        // The process-wide session guard is static; clear it between tests.
        RecognitionActivity.resetSessionGuardForTest()
    }

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

    // -------------------------------------------------------- mic level meter

    @Test
    fun `meter is hidden by default (not listening)`() {
        val activity = Robolectric.buildActivity(RecognitionActivity::class.java).setup().get()
        assertEquals(
            View.GONE,
            activity.findViewById<View>(R.id.mic_level_container).visibility,
        )
    }

    private fun bars(activity: RecognitionActivity): List<View> {
        val container = activity.findViewById<android.view.ViewGroup>(R.id.mic_level_container)
        return (0 until container.childCount).map { container.getChildAt(it) }
    }

    @Test
    fun `meter becomes visible when set visible and resets when hidden`() {
        val activity = Robolectric.buildActivity(RecognitionActivity::class.java).setup().get()
        activity.setMeterVisible(true)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mic_level_container).visibility)

        activity.setMeterVisible(false)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.mic_level_container).visibility)
        // Bars are reset to the dim state when hidden.
        bars(activity).forEach { assertEquals(0.12f, it.alpha, 0.01f) }
    }

    @Test
    fun `bars light progressively with level`() {
        val activity = Robolectric.buildActivity(RecognitionActivity::class.java).setup().get()
        val meterBars = bars(activity)
        activity.setMeterVisible(true)
        // Let the animation converge on a mid level: first bars bright, rest dim.
        repeat(20) { activity.renderMeter(0.5f) }
        val mid = (0.5f * meterBars.size).toInt()
        meterBars.take(mid).forEach { assertTrue("lit bar should be bright", it.alpha > 0.8f) }
        // Bars beyond the partial boundary bar are unlit.
        meterBars.drop(mid + 1).forEach { assertTrue("unlit bar should be dim", it.alpha < 0.5f) }
    }

    @Test
    fun `renderMeter handles NaN and out-of-range input`() {
        val activity = Robolectric.buildActivity(RecognitionActivity::class.java).setup().get()
        activity.setMeterVisible(true)
        val meterBars = bars(activity)
        // NaN must not escape; no exception, bars stay dim.
        activity.renderMeter(Float.NaN)
        repeat(10) { activity.renderMeter(Float.NaN) }
        meterBars.forEach { assertTrue("alpha must be finite", it.alpha.isFinite()) }
        assertTrue(meterBars.all { it.alpha < 0.5f })
        // Above-range input drives the meter to full brightness.
        repeat(20) { activity.renderMeter(2f) }
        meterBars.forEach { assertTrue(it.alpha > 0.9f) }
        // Below-range input drives it back down.
        repeat(20) { activity.renderMeter(-1f) }
        meterBars.forEach { assertTrue(it.alpha < 0.5f) }
    }

    @Test
    fun `renderMeter updates an accessible level description`() {
        val activity = Robolectric.buildActivity(RecognitionActivity::class.java).setup().get()
        val container = activity.findViewById<View>(R.id.mic_level_container)
        activity.setMeterVisible(true)
        activity.renderMeter(0.02f)
        assertTrue(container.contentDescription.toString().contains("quiet"))
        activity.renderMeter(0.9f)
        assertTrue(container.contentDescription.toString().contains("loud"))
    }
}
