package org.gptvoiceinput.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Recognizer result-delivery contract tests (issue #4). The activity uses
 * standard (non-singleTask) launch semantics, supports the documented
 * EXTRA_RESULTS_PENDINGINTENT forwarding route, and always finishes with the
 * standard EXTRA_RESULTS shape for the activity-result route.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResultDeliveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val controllers = mutableListOf<ActivityController<RecognitionActivity>>()

    private fun launch(intent: Intent? = null): RecognitionActivity {
        val controller = if (intent == null) {
            Robolectric.buildActivity(RecognitionActivity::class.java)
        } else {
            Robolectric.buildActivity(RecognitionActivity::class.java, intent)
        }
        controllers += controller
        return controller.setup().get()
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
        controllers.clear()
    }

    private fun plainLaunchIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).setPackage(context.packageName)

    // -------------------------------------------------------- delivery plan

    @Test
    fun `delivery plan uses activity result when no pending intent is present`() {
        val plan = RecognitionActivity().deliveryPlan(plainLaunchIntent())
        assertFalse(plan.viaPendingIntent)
        assertTrue(plan.viaActivityResult)
    }

    @Test
    fun `delivery plan uses pending intent route when supplied`() {
        val intent = plainLaunchIntent().putExtra(
            RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT,
            PendingIntent.getActivity(context, 7, Intent(), PendingIntent.FLAG_IMMUTABLE),
        )
        val plan = RecognitionActivity().deliveryPlan(intent)
        assertTrue(plan.viaPendingIntent)
        assertFalse(plan.viaActivityResult)
    }

    // -------------------------------------------------- activity result route

    @Test
    fun `deliverResult sets RESULT_OK with EXTRA_RESULTS`() {
        val activity = launch()
        activity.deliverResult("UNIQUE_TEST_TEXT")

        val shadow = shadowOf(activity)
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        val results = shadow.resultIntent
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        assertEquals(listOf("UNIQUE_TEST_TEXT"), results)
    }

    // -------------------------------------------------- pending intent route

    @Test
    fun `pending intent route actually forwards the result`() {
        // An activity-type PendingIntent lets Robolectric observe the send via
        // the shadowed ActivityManager (nextStartedActivity). NOTE: Robolectric
        // does not simulate PendingIntent fill-in extras, so the forwarded
        // content is verified by `forwarded intent content merges...`; this
        // test proves the send itself targets the caller's activity.
        val pi = PendingIntent.getActivity(
            context,
            42,
            Intent(context, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val activity = launch(
            plainLaunchIntent().putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, pi),
        )

        activity.deliverResult("UNIQUE_TEST_TEXT")

        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity
        assertNotNull("PendingIntent send must start the target activity", started)
        // The activity-result path must NOT be used for this caller.
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun `forwarded intent content merges caller bundle and result`() {
        val activity = launch()
        val result = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("UNIQUE_TEST_TEXT"),
        )
        val callerBundle = android.os.Bundle().apply { putString("caller_tag", "x") }
        val forwarded = activity.buildForwardedIntent(result, callerBundle)
        assertEquals("x", forwarded.getStringExtra("caller_tag"))
        assertEquals(
            listOf("UNIQUE_TEST_TEXT"),
            forwarded.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS),
        )
    }

    // ------------------------------------------------- duplicate-session guard

    @Test
    fun `second simultaneous session cancels itself without disturbing the first`() {
        val first = launch()
        val second = launch()

        // The duplicate instance must deliver RESULT_CANCELED and finish.
        assertEquals(
            "duplicate session must return RESULT_CANCELED",
            Activity.RESULT_CANCELED,
            shadowOf(second).resultCode,
        )
        assertTrue("duplicate session must finish", second.isFinishing)

        // The first session's result path is not corrupted by the duplicate.
        // (Robolectric's task simulation may mark the first instance as
        // finishing when the second is built; the semantic invariant is that
        // the first can still deliver its own result payload.)
        first.deliverResult("UNIQUE_TEST_TEXT")
        assertEquals(
            "first session must still deliver its result",
            Activity.RESULT_OK,
            shadowOf(first).resultCode,
        )
        assertEquals(
            listOf("UNIQUE_TEST_TEXT"),
            shadowOf(first).resultIntent
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS),
        )

        // After the owner is destroyed, a fresh session starts normally
        // instead of being refused (phase proceeds past the guard to NO_KEY).
        controllers[0].destroy()
        val third = launch()
        assertEquals("fresh session must start, not be refused", "NO_KEY", third.phaseForTest())
    }

    // ------------------------------------------------------- manifest invariant

    @Test
    fun `recognition activity uses standard launch mode, not singleTask`() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, RecognitionActivity::class.java),
            0,
        )
        assertEquals(
            "singleTask breaks result-return semantics; standard is required",
            ActivityInfo.LAUNCH_MULTIPLE, // 0 = standard
            info.launchMode,
        )
    }

    @Test
    fun `settings front door alias still resolves without interfering`() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_INFO)
            .setPackage(context.packageName)
        val ris = context.packageManager.queryIntentActivities(intent, 0)
        assertTrue(ris.isNotEmpty())
        assertNotNull(
            context.packageManager.getLaunchIntentForPackage(context.packageName),
        )
    }

    private class TestReceiver : android.content.BroadcastReceiver() {
        @Volatile
        var lastIntent: Intent? = null

        override fun onReceive(c: Context?, intent: Intent?) {
            lastIntent = intent
        }
    }
}
