package org.gptvoiceinput.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.gptvoiceinput.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * UI localization (issue #5): the UI follows the system/app locale. English is
 * the complete default; Traditional Chinese (values-b+zh+Hant) must be
 * selected automatically for zh-Hant / zh-TW locales. UI locale is independent
 * of the transcription `expectedLanguages` profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocaleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `default (English) resources are complete`() {
        assertEquals("Listening…", context.getString(R.string.status_listening))
        assertEquals("Tap to submit", context.getString(R.string.hint_tap_to_submit))
        assertEquals("Auto stop after silence", context.getString(R.string.auto_stop_label))
        assertEquals("Import settings", context.getString(R.string.import_settings))
    }

    @Test
    fun `zh-Hant script locale shows Traditional Chinese`() {
        assertEquals(
            "聆聽中…",
            localized(Locale.forLanguageTag("zh-Hant")).getString(R.string.status_listening),
        )
        assertEquals(
            "點擊送出",
            localized(Locale.forLanguageTag("zh-Hant")).getString(R.string.hint_tap_to_submit),
        )
        assertEquals(
            "靜音後自動停止",
            localized(Locale.forLanguageTag("zh-Hant")).getString(R.string.auto_stop_label),
        )
    }

    @Test
    fun `zh-TW region locale resolves Traditional Chinese via script matching`() {
        assertEquals(
            "聆聽中…",
            localized(Locale.TAIWAN).getString(R.string.status_listening),
        )
    }

    private fun localized(locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
