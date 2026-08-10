package org.gptvoiceinput.audio

/**
 * One captured PCM frame. Frames handed to the analysis pipeline are copies —
 * the audio streamed to OpenAI never passes through analysis processing.
 */
data class AudioFrame(
    val samples: ShortArray,
    val sampleCount: Int,
    val sampleRate: Int,
    /** Milliseconds since recording start (monotonic). */
    val elapsedMs: Long,
)
