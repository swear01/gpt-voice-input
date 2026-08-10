package org.gptvoiceinput.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-secret runtime settings. The API key lives in [org.gptvoiceinput.security.SecureApiKeyStore].
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Endpoint silence delay in seconds; [AUTO_STOP_OFF] disables auto-stop. */
    var autoStopSeconds: Double
        get() = prefs.getFloat(KEY_AUTO_STOP, DEFAULT_AUTO_STOP_SECONDS.toFloat()).toDouble()
        set(value) {
            val v = when {
                value <= 0.0 -> AUTO_STOP_OFF
                value < AUTO_STOP_MIN -> AUTO_STOP_MIN
                value > AUTO_STOP_MAX -> AUTO_STOP_MAX
                else -> roundToStep(value)
            }
            prefs.edit().putFloat(KEY_AUTO_STOP, v.toFloat()).apply()
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

    private fun roundToStep(value: Double): Double {
        val steps = ((value - AUTO_STOP_MIN) / AUTO_STOP_STEP).roundToInt()
        return AUTO_STOP_MIN + steps * AUTO_STOP_STEP
    }

    private fun Double.roundToInt(): Int = Math.round(this).toInt()

    companion object {
        private const val PREFS_NAME = "settings"

        const val AUTO_STOP_OFF = 0.0
        const val AUTO_STOP_MIN = 1.0
        const val AUTO_STOP_MAX = 3.0
        const val AUTO_STOP_STEP = 0.2
        const val DEFAULT_AUTO_STOP_SECONDS = 1.8

        /** Slider positions: 1.0..3.0 in 0.2 steps, then OFF. */
        val AUTO_STOP_OPTIONS: List<Double> =
            generateSequence(AUTO_STOP_MIN) { it + AUTO_STOP_STEP }
                .takeWhile { it <= AUTO_STOP_MAX + 0.001 }
                .toList() + AUTO_STOP_OFF

        private const val KEY_AUTO_STOP = "auto_stop_seconds"
        private const val KEY_CUSTOM_TERMS = "custom_terms"
    }
}
