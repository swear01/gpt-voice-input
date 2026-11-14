package org.gptvoiceinput.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Privacy-safe numeric level statistics (peak/RMS in dBFS) for a recording.
 * Produces numbers only — never audio content — so it is safe to log for
 * diagnosing "recording too quiet" issues.
 */
object AudioLevelStats {

    data class Stats(val peakDb: Float, val rmsDb: Float)

    fun computePcm(samples: ShortArray, count: Int): Stats =
        Accumulator().apply { add(samples, count) }.stats()

    /** Running peak/RMS over a whole recording, fed frame by frame. */
    class Accumulator {
        private var peak = 0
        private var sumSq = 0.0
        private var count = 0L

        fun add(samples: ShortArray, count: Int) {
            require(count in 0..samples.size)
            for (i in 0 until count) {
                val v = samples[i].toInt()
                val abs = if (v < 0) -v else v
                if (abs > peak) peak = abs
                sumSq += v.toDouble() * v
            }
            this.count += count
        }

        fun stats(): Stats {
            val peakDb = if (count > 0 && peak > 0) 20.0 * log10(peak / 32768.0) else -120.0
            val rms = if (count > 0) sqrt(sumSq / count) else 0.0
            val rmsDb = if (rms > 0.0) 20.0 * log10(rms / 32768.0) else -120.0
            return Stats(peakDb.toFloat(), rmsDb.toFloat())
        }
    }
}
