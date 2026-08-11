package org.gptvoiceinput.audio

import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Privacy-safe numeric level statistics (peak/RMS in dBFS) for a recorded
 * WAV. Produces numbers only — never audio content — so it is safe to log
 * for diagnosing "recording too quiet" issues.
 */
object AudioLevelStats {

    data class Stats(val peakDb: Float, val rmsDb: Float)

    fun computePcm(samples: ShortArray, count: Int): Stats {
        require(count in 0..samples.size)
        var peak = 0
        var sumSq = 0.0
        var i = 0
        while (i < count) {
            val v = samples[i].toInt()
            val abs = if (v < 0) -v else v
            if (abs > peak) peak = abs
            sumSq += v.toDouble() * v
            i++
        }
        val peakDb = if (count > 0 && peak > 0) {
            20.0 * log10(peak / 32768.0)
        } else {
            -120.0
        }
        val rms = if (count > 0) sqrt(sumSq / count) else 0.0
        val rmsDb = if (rms > 0.0) 20.0 * log10(rms / 32768.0) else -120.0
        return Stats(peakDb.toFloat(), rmsDb.toFloat())
    }

    /** Reads a 16-bit mono WAV and computes level stats; null on parse failure. */
    fun fromWav(file: File): Stats? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44) {
                null
            } else {
                val dataSize = (bytes[40].toInt() and 0xFF) or
                    ((bytes[41].toInt() and 0xFF) shl 8) or
                    ((bytes[42].toInt() and 0xFF) shl 16) or
                    ((bytes[43].toInt() and 0xFF) shl 24)
                if (dataSize <= 0 || 44 + dataSize > bytes.size) {
                    null
                } else {
                    val sampleCount = dataSize / 2
                    val samples = ShortArray(sampleCount)
                    for (i in 0 until sampleCount) {
                        val lo = bytes[44 + i * 2].toInt() and 0xFF
                        val hi = bytes[44 + i * 2 + 1].toInt() and 0xFF
                        samples[i] = (lo or (hi shl 8)).toShort()
                    }
                    computePcm(samples, sampleCount)
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
