package org.gptvoiceinput.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Auto-stop persistence (issue #7): exact integer-millisecond representation
 * with safe migration from the legacy Float preference. The old bug was that a
 * persisted Float (e.g. 1.799999952) was compared to Double options with exact
 * equality, so the slider fell back to OFF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun freshStore(): SettingsStore {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().apply()
        return SettingsStore(context)
    }

    private fun legacyStore(legacyFloat: Float): SettingsStore {
        // Seed the legacy Float key exactly like v0.1.4 did.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().clear().putFloat("auto_stop_seconds", legacyFloat).apply()
        return SettingsStore(context)
    }

    private fun reopened(): SettingsStore = SettingsStore(context)

    // ------------------------------------------------------------ basics

    @Test
    fun `fresh install defaults to 2 point 5 seconds`() {
        val s = freshStore()
        assertEquals(2500, s.autoStopMs)
        assertEquals(2.5, s.autoStopSeconds, 0.0)
    }

    @Test
    fun `every supported option survives a reopen round trip`() {
        val options = SettingsStore.AUTO_STOP_OPTIONS_MS
        for (ms in options) {
            freshStore().setAutoStopMs(ms)
            val reopened = reopened()
            assertEquals("ms=$ms", ms, reopened.autoStopMs)
            assertEquals("ms=$ms", ms, reopened.autoStopMs)
        }
    }

    @Test
    fun `off survives reopen`() {
        freshStore().setAutoStopMs(SettingsStore.AUTO_STOP_OFF_MS)
        assertEquals(SettingsStore.AUTO_STOP_OFF_MS, reopened().autoStopMs)
    }

    @Test
    fun `importing 1 point 8 seconds reopens as exactly 1 point 8`() {
        freshStore().setAutoStopSeconds(1.8)
        val s = reopened()
        assertEquals(1800, s.autoStopMs)
        assertEquals(1.8, s.autoStopSeconds, 0.0)
    }

    @Test
    fun `importing every supported value reopens exactly`() {
        val options = SettingsStore.AUTO_STOP_OPTIONS_MS
        for (ms in options) {
            freshStore().setAutoStopSeconds(ms / 1000.0)
            assertEquals(ms, reopened().autoStopMs)
        }
    }

    @Test
    fun `reading a valid preference does not mutate it`() {
        freshStore().setAutoStopMs(2400)
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val s = reopened()
        assertEquals(2400, s.autoStopMs)
        assertEquals(2400, s.autoStopMs)
        assertEquals(before, prefs.all)
    }

    // ------------------------------------------------------ legacy migration

    @Test
    fun `legacy float 1 point 8 migrates to 1 point 8`() {
        val s = legacyStore(1.799999952f)
        assertEquals(1800, s.autoStopMs)
        assertEquals(1.8, s.autoStopSeconds, 0.0)
        // New key written, old key removed.
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertEquals(1800, prefs.getInt("auto_stop_ms", -1))
        assertEquals(false, prefs.contains("auto_stop_seconds"))
    }

    @Test
    fun `legacy floats for every step migrate to the nearest option`() {
        val steps = listOf(
            1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 2.2f, 2.4f, 2.6f, 2.8f, 3.0f,
            3.2f, 3.4f, 3.6f, 3.8f, 4.0f, 4.2f, 4.4f, 4.6f, 4.8f, 5.0f,
        )
        val expected = SettingsStore.AUTO_STOP_OPTIONS_MS.take(21)
        for ((i, value) in steps.withIndex()) {
            // Simulate the float representation error the device actually saw.
            val store = legacyStore(value + 0.0000001f)
            assertEquals("$value", expected[i], store.autoStopMs)
        }
    }

    @Test
    fun `legacy zero migrates to off`() {
        assertEquals(SettingsStore.AUTO_STOP_OFF_MS, legacyStore(0.0f).autoStopMs)
    }

    @Test
    fun `out of range legacy value falls back to default not off`() {
        assertEquals(2500, legacyStore(6.0f).autoStopMs)
        assertEquals(2500, legacyStore(0.4f).autoStopMs)
        assertEquals(2500, legacyStore(-3.0f).autoStopMs)
    }

    @Test
    fun `corrupt non-float legacy value falls back to default not off`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().clear().putString("auto_stop_seconds", "garbage").apply()
        assertEquals(2500, SettingsStore(context).autoStopMs)
    }

    @Test
    fun `legacy key is never read with getInt`() {
        // Pre-conditions: legacy key holds a Float; the new key is absent.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().clear().putFloat("auto_stop_seconds", 2.4f).apply()
        val s = SettingsStore(context)
        assertEquals(2400, s.autoStopMs) // would throw ClassCastException with getInt
    }

    @Test
    fun `seconds to ms mapping is exact`() {
        for (ms in SettingsStore.AUTO_STOP_OPTIONS_MS) {
            val seconds = ms / 1000.0
            assertEquals(ms, SettingsStore.secondsToMs(seconds))
        }
        assertEquals(0, SettingsStore.secondsToMs(0.0))
        assertEquals(2500, SettingsStore.secondsToMs(-0.5)) // corrupt negative -> default, not OFF
    }

    @Test
    fun `off-grid seconds round to nearest supported step`() {
        assertEquals(1400, SettingsStore.secondsToMs(1.45))
        assertEquals(1600, SettingsStore.secondsToMs(1.55))
        assertEquals(2500, SettingsStore.secondsToMs(Double.NaN))
        assertEquals(2500, SettingsStore.secondsToMs(Double.POSITIVE_INFINITY))
    }

    // ------------------------------------------------------- endpoint delay

    @Test
    fun `recognition endpoint delay equals persisted milliseconds`() {
        freshStore().setAutoStopSeconds(1.8)
        assertEquals(1800, reopened().autoStopMs) // RecognitionActivity uses autoStopMs directly
        freshStore().setAutoStopMs(SettingsStore.AUTO_STOP_OFF_MS)
        assertEquals(0, reopened().autoStopMs)
    }
}
