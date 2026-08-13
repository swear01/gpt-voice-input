package org.gptvoiceinput.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Non-destructive microphone input level estimator — visualization only.
 *
 * The frame RMS is mapped to a voice-calibrated dBFS band, then smoothed with
 * fast attack and slower release so the display follows speech without
 * flickering:
 *
 *  - RMS tracks sustained intensity instead of saturating on one sample,
 *  - the wider dBFS band keeps quiet, normal, and loud speech distinct,
 *  - per-frame smoothing reacts faster than the previous 120 ms peak hold.
 *
 * The uploaded WAV never passes through this; the estimator is fed only the
 * downsampled analysis copy (see AudioRecorder).
 */
class MicLevelEstimator(
    /** dBFS at which the meter reads zero. */
    private val floorDb: Float = DEFAULT_FLOOR_DB,
    /** dBFS at which the meter reads full. */
    private val ceilingDb: Float = DEFAULT_CEILING_DB,
    /** Display smoothing toward the per-frame value (rising). */
    private val attack: Float = DEFAULT_ATTACK,
    /** Display smoothing toward the per-frame value (falling). */
    private val release: Float = DEFAULT_RELEASE,
) {

    init {
        require(ceilingDb > floorDb)
        require(attack in 0f..1f && release in 0f..1f)
    }

    private var level = 0f // smoothed display level, 0..1

    /** Feeds one PCM frame; returns the smoothed normalized level in 0..1. */
    fun processFrame(samples: ShortArray, count: Int): Float {
        require(count in 0..samples.size)
        var sumSquares = 0L
        var i = 0
        while (i < count) {
            val sample = samples[i].toInt()
            sumSquares += sample * sample
            i++
        }
        val rms = if (count == 0) 0f else
            (sqrt(sumSquares.toDouble() / count) / MAX_PCM_AMPLITUDE).toFloat()
        val target = dbToLevel(rms)
        val alpha = if (target >= level) attack else release
        level += (target - level) * alpha
        return level
    }

    private fun dbToLevel(linear: Float): Float {
        if (linear <= 0f) return 0f
        val db = 20.0 * log10(linear.toDouble())
        val span = ceilingDb - floorDb
        return ((db - floorDb) / span).toFloat().coerceIn(0f, 1f)
    }

    /** Current smoothed level without consuming a frame. */
    fun currentLevel(): Float = level

    fun reset() {
        level = 0f
    }

    companion object {
        private const val MAX_PCM_AMPLITUDE = 32768.0

        // Voice-calibrated display band, shifted DOWN to match how speech
        // actually lands on-device. Reference: the AMR-WB VAD that ships in
        // Android (Google's own speech pipeline) uses -26 dBov as the
        // nominal speech level (NOM_LEVEL 2050 ≈ -26 dBov Q15); real phone
        // capture without strong AGC is often another 5-10 dB quieter.
        // floor -55: anything below is silence (meter 0, ring invisible).
        // ceiling -30: normal speech (-26 dBov) reads ~0.93 — near full,
        // so the speaking ring is clearly visible while talking.
        private const val DEFAULT_FLOOR_DB = -55f
        private const val DEFAULT_CEILING_DB = -30f
        private const val DEFAULT_ATTACK = 0.6f
        private const val DEFAULT_RELEASE = 0.2f
    }
}
