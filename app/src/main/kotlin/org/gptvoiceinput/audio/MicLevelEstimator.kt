package org.gptvoiceinput.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Non-destructive microphone input level estimator — visualization only.
 *
 * Computes RMS from the analysis-side (copied) PCM frame, maps dBFS into a
 * normalized 0..1 range over a fixed floor, and applies attack/release
 * smoothing so the meter reads cleanly: fast rise on speech, slower decay in
 * silence.
 *
 * The uploaded WAV never passes through this; the estimator is fed only the
 * downsampled analysis copy (see AudioRecorder).
 */
class MicLevelEstimator(
    /** dBFS below which the meter sits at minimum. */
    private val floorDb: Float = DEFAULT_FLOOR_DB,
    /** Smoothing when the signal is rising (fast). */
    private val attack: Float = DEFAULT_ATTACK,
    /** Smoothing when the signal is falling (slower, readable). */
    private val release: Float = DEFAULT_RELEASE,
) {

    private var level: Float = 0f

    /** Feeds one PCM frame; returns the smoothed normalized level in 0..1. */
    fun processFrame(samples: ShortArray, count: Int): Float {
        require(count in 0..samples.size)
        var sumSq = 0.0
        var i = 0
        while (i < count) {
            val v = samples[i].toDouble()
            sumSq += v * v
            i++
        }
        val target = if (count <= 0 || sumSq <= 0.0) {
            0f
        } else {
            val rms = sqrt(sumSq / count)
            val db = 20.0 * log10(rms / MAX_PCM_AMPLITUDE)
            ((db - floorDb) / -floorDb).toFloat().coerceIn(0f, 1f)
        }
        val alpha = if (target >= level) attack else release
        level = level + alpha * (target - level)
        return level
    }

    /** Current smoothed level without consuming a frame. */
    fun currentLevel(): Float = level

    fun reset() {
        level = 0f
    }

    companion object {
        private const val MAX_PCM_AMPLITUDE = 32768.0
        private const val DEFAULT_FLOOR_DB = -50f
        private const val DEFAULT_ATTACK = 0.7f
        private const val DEFAULT_RELEASE = 0.25f
    }
}
