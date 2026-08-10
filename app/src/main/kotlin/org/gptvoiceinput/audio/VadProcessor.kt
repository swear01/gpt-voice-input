package org.gptvoiceinput.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight energy-based voice activity detector for the *analysis* side
 * channel only.
 *
 * - one-pole high-pass to remove DC and low-frequency rumble (the only
 *   "noise handling" here; deliberately cheap),
 * - frame RMS in dBFS,
 * - an adaptive noise floor so a quiet or loud ambient both endpoint
 *   reasonably (never a bare absolute threshold),
 * - a margin above the noise floor classifies a frame as speech.
 *
 * The audio uploaded to OpenAI never touches this class (see AudioRecorder).
 */
class VadProcessor(
    /** Sample rate of the frames fed to [isSpeech] (analysis rate). */
    private val sampleRate: Int,
    /** Frame duration in milliseconds (used only for the RMS window). */
    private val frameMillis: Int = FRAME_MS,
) {

    private var prevInput = 0.0
    private var prevFiltered = 0.0
    private var prevLevelDb = INITIAL_LEVEL_DB
    private var noiseFloorDb = INITIAL_NOISE_FLOOR_DB
    private var framesSeen = 0

    fun isSpeech(samples: ShortArray, count: Int): Boolean {
        require(count <= samples.size)
        // High-pass filter: y[n] = alpha * (y[n-1] + x[n] - x[n-1])
        var sumSquares = 0.0
        var i = 0
        while (i < count) {
            val x = samples[i].toDouble()
            val y = HP_ALPHA * (prevFiltered + x - prevInput)
            prevInput = x
            prevFiltered = y
            sumSquares += y * y
            i++
        }
        val rms = sqrt(sumSquares / count)
        val rmsDb = if (rms > 0.0) {
            20.0 * log10(rms / MAX_PCM_AMPLITUDE)
        } else {
            NEGATIVE_INFINITY_DB
        }

        // Smooth level so single-sample spikes don't gate on/off.
        val levelDb = prevLevelDb * LEVEL_SMOOTHING + rmsDb * (1.0 - LEVEL_SMOOTHING)
        prevLevelDb = levelDb

        // Pre-roll: the first frames normally capture the ambient. Train the
        // noise floor on them quickly (clamped so an instantly-loud start can't
        // silence the detector) and report silence.
        framesSeen++
        if (framesSeen <= PRE_ROLL_FRAMES) {
            noiseFloorDb = minOf(noiseFloorDb + PRE_ROLL_ALPHA * (levelDb - noiseFloorDb), MAX_PRE_ROLL_FLOOR_DB)
            return false
        }

        val threshold = max(noiseFloorDb + MARGIN_DB, ABSOLUTE_FLOOR_DB)
        val speech = levelDb > threshold
        if (!speech) {
            // Drift the noise floor toward the observed quiet level.
            noiseFloorDb += NOISE_FLOOR_ALPHA * (levelDb - noiseFloorDb)
        }
        return speech
    }

    /** Resets adaptation state (e.g., new recording session). */
    fun reset() {
        prevInput = 0.0
        prevFiltered = 0.0
        prevLevelDb = INITIAL_LEVEL_DB
        noiseFloorDb = INITIAL_NOISE_FLOOR_DB
        framesSeen = 0
    }

    companion object {
        const val FRAME_MS = 20

        private const val MAX_PCM_AMPLITUDE = 32768.0
        private const val NEGATIVE_INFINITY_DB = -120.0
        private const val INITIAL_LEVEL_DB = -60.0
        private const val INITIAL_NOISE_FLOOR_DB = -50.0
        private const val LEVEL_SMOOTHING = 0.7
        private const val NOISE_FLOOR_ALPHA = 0.05
        private const val MARGIN_DB = 10.0
        private const val ABSOLUTE_FLOOR_DB = -45.0
        private const val HP_ALPHA = 0.9

        /** First frames are treated as ambient and never classify as speech. */
        private const val PRE_ROLL_FRAMES = 5
        private const val PRE_ROLL_ALPHA = 0.3
        /** Hard cap so an instantly-loud start can't silence the detector. */
        private const val MAX_PRE_ROLL_FLOOR_DB = -30.0
    }
}
