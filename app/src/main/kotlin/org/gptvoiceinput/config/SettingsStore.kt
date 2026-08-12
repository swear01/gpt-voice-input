package org.gptvoiceinput.config

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Non-secret runtime settings. The API key lives in [org.gptvoiceinput.security.SecureApiKeyStore].
 *
 * Auto-stop is persisted as exact integer milliseconds (0 = OFF, 1000..3000 in
 * 200 ms steps). Legacy versions stored a Float (`auto_stop_seconds`,
 * e.g. 1.799999952); a one-time migration maps it to the nearest supported
 * step before the new exact representation is used. The old key is never read
 * as an Int, so no ClassCastException is possible.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Endpoint silence delay in exact milliseconds; [AUTO_STOP_OFF_MS] disables auto-stop. */
    val autoStopMs: Int
        get() = readAutoStopMs()

    /** Convenience: seconds derived exactly from the persisted milliseconds. */
    val autoStopSeconds: Double
        get() = readAutoStopMs() / 1000.0

    /** Sets auto-stop from a validated seconds value (0 = OFF; else nearest supported step). */
    fun setAutoStopSeconds(seconds: Double) {
        prefs.edit().putInt(KEY_AUTO_STOP_MS, secondsToMs(seconds)).apply()
    }

    /** Sets auto-stop from exact milliseconds (0 = OFF, else a supported step). */
    fun setAutoStopMs(ms: Int) {
        prefs.edit().putInt(KEY_AUTO_STOP_MS, normalizeMs(ms)).apply()
    }

    private fun normalizeMs(ms: Int): Int = when {
        ms == AUTO_STOP_OFF_MS -> AUTO_STOP_OFF_MS
        ms in AUTO_STOP_OPTIONS_MS -> ms
        else -> secondsToMs(ms / 1000.0)
    }

    fun customTerms(): List<String> =
        prefs.getString(KEY_CUSTOM_TERMS, null)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()

    fun setCustomTerms(terms: List<String>) {
        prefs.edit().putString(KEY_CUSTOM_TERMS, terms.joinToString("\n")).apply()
    }

    // ------------------------------------------------------------------ auto stop

    private fun readAutoStopMs(): Int {
        if (!prefs.contains(KEY_AUTO_STOP_MS)) {
            migrateLegacyAutoStop()
        }
        return prefs.getInt(KEY_AUTO_STOP_MS, DEFAULT_AUTO_STOP_MS)
    }

    /**
     * One-time migration from the legacy Float `auto_stop_seconds` key. Reads
     * the old value by runtime type (never getInt on a Float), maps it to the
     * nearest supported step, and writes the new exact Int representation.
     * Corrupt/out-of-range values fall back to the documented default 1.8 s,
     * never silently to OFF (unless the value is the explicit OFF sentinel).
     */
    private fun migrateLegacyAutoStop() {
        val legacy = prefs.all[KEY_AUTO_STOP]
        val ms = when (legacy) {
            is Float -> secondsToMs(legacy.toDouble())
            is Int -> secondsToMs(legacy.toDouble())
            is Double -> secondsToMs(legacy)
            else -> DEFAULT_AUTO_STOP_MS
        }
        prefs.edit()
            .putInt(KEY_AUTO_STOP_MS, ms)
            .remove(KEY_AUTO_STOP)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "settings"

        const val AUTO_STOP_OFF_MS = 0
        const val DEFAULT_AUTO_STOP_MS = 2500

        /** Slider positions: 1.0s..5.0s in 0.2 steps, then OFF. Exact ms. */
        val AUTO_STOP_OPTIONS_MS: List<Int> =
            (0..20).map { 1000 + it * 200 } + AUTO_STOP_OFF_MS

        /**
         * Maps a seconds value to exact milliseconds: exactly 0 → OFF;
         * otherwise the nearest supported step in 1.0–3.0 s. Corrupt values
         * (negative, out-of-range, NaN/Infinity) fall back to the default
         * 1.8 s — never silently to OFF.
         */
        fun secondsToMs(seconds: Double): Int {
            if (seconds.isNaN() || seconds.isInfinite()) return DEFAULT_AUTO_STOP_MS
            if (seconds == 0.0) return AUTO_STOP_OFF_MS
            if (seconds < 0.0) return DEFAULT_AUTO_STOP_MS
            val idx = ((seconds - 1.0) / 0.2).roundToInt()
            if (idx !in 0..20) return DEFAULT_AUTO_STOP_MS
            return 1000 + idx * 200
        }

        private const val KEY_AUTO_STOP_MS = "auto_stop_ms"
        private const val KEY_AUTO_STOP = "auto_stop_seconds" // legacy Float
        private const val KEY_CUSTOM_TERMS = "custom_terms"
    }
}
