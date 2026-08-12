package org.gptvoiceinput.audio

import kotlin.math.abs
import kotlin.math.log10

/**
 * Non-destructive microphone input level estimator — visualization only.
 *
 * Reference implementation: WebRTC `AudioLevel` (voe::AudioLevel), the same
 * algorithm behind W3C WebRTC-stats audio level. Key ideas reused:
 *
 *  - **peak amplitude** per frame (not RMS) — matches how meters present
 *    "signal level"; RMS sits mid-range and reads too low,
 *  - **peak hold with decay**: the frame peak is held; every
 *    [HOLD_FRAMES] frames the held peak is emitted and then decayed, so the
 *    meter jumps up on speech and falls off smoothly in silence,
 *  - the emitted peak (dBFS) is mapped over a **voice-calibrated band**: the
 *    floor is well below normal speech and the ceiling is set so that normal
 *    speaking (with the AGC'd VOICE_RECOGNITION source) reads near full.
 *
 * The uploaded WAV never passes through this; the estimator is fed only the
 * downsampled analysis copy (see AudioRecorder).
 */
class MicLevelEstimator(
    /** dBFS at which the meter reads zero. */
    private val floorDb: Float = DEFAULT_FLOOR_DB,
    /** dBFS at which the meter reads full (normal speech ≈ ceiling). */
    private val ceilingDb: Float = DEFAULT_CEILING_DB,
    /** Emission cadence in 20 ms frames (~100-120 ms, like WebRTC's ~9 Hz). */
    private val holdFrames: Int = DEFAULT_HOLD_FRAMES,
    /** Decay factor applied to the held peak after each emission (WebRTC: >>=2). */
    private val holdDecay: Float = DEFAULT_HOLD_DECAY,
    /** Display smoothing toward the emitted value (rising). */
    private val attack: Float = DEFAULT_ATTACK,
    /** Display smoothing toward the emitted value (falling). */
    private val release: Float = DEFAULT_RELEASE,
) {

    private var holdPeak = 0f // held linear peak, 0..1
    private var holdCount = 0
    private var level = 0f // smoothed display level, 0..1

    /** Feeds one PCM frame; returns the smoothed normalized level in 0..1. */
    fun processFrame(samples: ShortArray, count: Int): Float {
        require(count in 0..samples.size)
        var peak = 0
        var i = 0
        while (i < count) {
            val v = abs(samples[i].toInt())
            if (v > peak) peak = v
            i++
        }
        if (peak > 0) {
            val frameLevel = (peak / MAX_PCM_AMPLITUDE).toFloat()
            if (frameLevel > holdPeak) holdPeak = frameLevel
        }
        holdCount++
        if (holdCount >= holdFrames) {
            holdCount = 0
            val emitted = holdPeak
            holdPeak *= holdDecay // decay the hold (WebRTC divides by 4)
            val target = dbToLevel(emitted)
            val alpha = if (target >= level) attack else release
            level = level + (target - level) * alpha
        }
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
        holdPeak = 0f
        holdCount = 0
        level = 0f
    }

    companion object {
        private const val MAX_PCM_AMPLITUDE = 32768.0

        // Voice-calibrated band following the broadcast/K-system convention
        // (nominal speech level ≈ -20 dBFS = full scale):
        //   ≤ -40 dBFS (silence, ambient, whisper)  → 0
        //   -30 dBFS (quiet speech)                  → 0.5
        //   -20 dBFS (normal AGC'd speech)           → 1.0 (full)
        //   louder                                   → saturated 1.0
        private const val DEFAULT_FLOOR_DB = -40f
        private const val DEFAULT_CEILING_DB = -20f
        private const val DEFAULT_HOLD_FRAMES = 6 // ~120 ms at 20 ms frames
        private const val DEFAULT_HOLD_DECAY = 0.25f // WebRTC's >>= 2
        private const val DEFAULT_ATTACK = 0.5f
        private const val DEFAULT_RELEASE = 0.3f
    }
}
