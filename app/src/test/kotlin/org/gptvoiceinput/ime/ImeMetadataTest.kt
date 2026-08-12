package org.gptvoiceinput.ime

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * IME manifest/metadata invariants (v1.0.0): the voice IME is a normal
 * (visible) input method with switching support and a voice-only subtype.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeMetadataTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun attr(parser: org.xmlpull.v1.XmlPullParser, name: String): String? =
        parser.getAttributeValue(null, name)
            ?: parser.getAttributeValue(androidNs, name)

    @Test
    fun `IME service resolves the system input-method intent`() {
        // THE registration requirement: the system enumerates IMEs by
        // querying services that resolve android.view.InputMethod. This is
        // exactly how InputMethodManagerService builds its IME list; without
        // the intent-filter the IME never appears in Manage keyboards.
        val intent = Intent("android.view.InputMethod")
        val ris = context.packageManager.queryIntentServices(intent, 0)
        assertTrue(
            "IME service must resolve android.view.InputMethod",
            ris.any { it.serviceInfo.name == GptVoiceIme::class.java.name },
        )
    }

    @Test
    fun `IME service is declared with the input-method binding permission`() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, GptVoiceIme::class.java),
            0,
        )
        assertNotNull(info)
        assertEquals("android.permission.BIND_INPUT_METHOD", info.permission)
        assertTrue(info.exported)
    }

    @Test
    fun `method metadata is present and declares a visible non-auxiliary voice subtype`() {
        val parser = context.resources.getXml(
            context.resources.getIdentifier("method", "xml", context.packageName),
        )

        var foundInputMethod = false
        var supportsSwitching = false
        var settingsActivity = false
        var subtypeMode = ""
        var isAuxiliary = ""
        var showInPicker = ""

        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "input-method" -> {
                        foundInputMethod = true
                        supportsSwitching = attr(parser, "supportsSwitchingToNextInputMethod") == "true"
                        settingsActivity = attr(parser, "settingsActivity")
                            ?.contains("SettingsActivity") == true
                    }
                    "subtype" -> {
                        subtypeMode = attr(parser, "imeSubtypeMode") ?: ""
                        isAuxiliary = attr(parser, "isAuxiliary") ?: ""
                        showInPicker = attr(parser, "showInInputMethodPicker") ?: ""
                    }
                }
            }
            event = parser.next()
        }

        assertTrue("input-method root", foundInputMethod)
        assertTrue(
            "supports switching to next input method",
            supportsSwitching,
        )
        assertTrue("settings activity wired", settingsActivity)
        assertEquals("voice subtype mode", "voice", subtypeMode)
        // It must be a NORMAL visible subtype so it lives in the globe cycle.
        assertTrue("not auxiliary", isAuxiliary != "true")
        assertTrue("visible in the picker", showInPicker != "false")
    }
}
