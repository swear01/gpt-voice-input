package org.gptvoiceinput.audio

/**
 * Frame-level voice activity detector for the analysis side channel only.
 *
 * Implementations classify one 20 ms PCM frame (16 kHz mono) as speech or
 * silence. The recorder hands each analysis frame copy here before the
 * EndpointDetector; the audio uploaded to OpenAI never passes through this.
 */
interface VoiceActivityDetector {

    /** Classifies one frame; [count] is the number of valid samples. */
    fun isSpeech(samples: ShortArray, count: Int): Boolean

    /** Resets adaptation / internal state for a new recording session. */
    fun reset()

    /** Releases native resources, if any. Idempotent. */
    fun close() = Unit
}
