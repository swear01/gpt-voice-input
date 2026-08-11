package org.gptvoiceinput.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Package front-door regression tests (v0.1.4).
 *
 * The package has an ACTION_MAIN + CATEGORY_INFO activity-alias so Android
 * Settings / installers can offer "Open", but NO CATEGORY_LAUNCHER entry, so
 * the app stays out of the app drawer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackageFrontDoorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager = context.packageManager

    private val aliasName = "${context.packageName}.SettingsFrontDoor"

    @Test
    fun `getLaunchIntentForPackage returns a usable front-door intent`() {
        val intent = pm.getLaunchIntentForPackage(context.packageName)
        assertNotNull("front door must exist", intent)
    }

    @Test
    fun `front-door launch intent targets the settings alias`() {
        val intent = pm.getLaunchIntentForPackage(context.packageName)!!
        val info = pm.resolveActivity(intent, 0)
        assertNotNull(info)
        // The alias is resolved as an activity; its target is SettingsActivity.
        assertEquals(aliasName, info!!.activityInfo.name)
        assertEquals(
            SettingsActivity::class.java.name,
            info.activityInfo.targetActivity,
        )
    }

    @Test
    fun `ACTION_MAIN + CATEGORY_INFO resolves exactly the settings front door`() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_INFO)
            .setPackage(context.packageName)
        val ris = pm.queryIntentActivities(intent, 0)
        assertEquals(1, ris.size)
        assertEquals(aliasName, ris[0].activityInfo.name)
        assertEquals(
            SettingsActivity::class.java.name,
            ris[0].activityInfo.targetActivity,
        )
    }

    @Test
    fun `ACTION_MAIN + CATEGORY_LAUNCHER resolves nothing - no app-drawer entry`() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        assertTrue(
            "CATEGORY_LAUNCHER must not resolve: the app has no drawer entry",
            pm.queryIntentActivities(intent, 0).isEmpty(),
        )
    }

    @Test
    fun `ACTION_RECOGNIZE_SPEECH still resolves to RecognitionActivity`() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val ris = pm.queryIntentActivities(intent, 0)
        assertTrue(
            "recognize-speech must resolve to RecognitionActivity",
            ris.any { it.activityInfo.name == RecognitionActivity::class.java.name },
        )
    }
}
