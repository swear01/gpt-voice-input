package org.gptvoiceinput.audio

/**
 * End-of-speech endpointing state machine, driven by the analysis-side VAD:
 *
 * ```
 * WAITING_FOR_SPEECH ──speech──▶ IN_SPEECH ──silence──▶ ENDPOINT_CANDIDATE
 *      │                              ▲                       │
 *      │ no speech for ~8s            └───────speech──────────┘
 *      ▼                                                       │ silence for
 * onNoSpeechTimeout()                                   endpoint delay (1.0–3.0s)
 *                                                            ▼
 *                                                  onEndOfSpeech() → submit()
 * ```
 *
 * Rules (per the spec):
 * - The silence timer NEVER starts before speech has been detected.
 * - Auto-stop is optional: endpointDelayMs == 0 disables it entirely.
 * - Speech/silence decisions are debounced (hysteresis) so brief blips don't
 *   gate the state machine.
 * - Manual tap goes through the same submit() path in the caller, bypassing
 *   this machine.
 */
class EndpointDetector(
    /** Silence duration required after speech to auto-submit; 0 disables. */
    private val endpointDelayMs: Int,
    /** If no speech at all within this window, cancel gracefully. */
    private val noSpeechTimeoutMs: Int = DEFAULT_NO_SPEECH_TIMEOUT_MS,
    /** Hard cap on recording length; at this point we submit what we have. */
    private val maxDurationMs: Int = DEFAULT_MAX_DURATION_MS,
    private val listener: Listener,
) {

    enum class State { WAITING_FOR_SPEECH, IN_SPEECH, ENDPOINT_CANDIDATE, STOPPED }

    interface Listener {
        fun onEndOfSpeech()
        fun onNoSpeechTimeout()
        fun onMaxDuration()
    }

    var state: State = State.WAITING_FOR_SPEECH
        private set

    private var speechStreak = 0
    private var silenceStreak = 0
    private var candidateSinceMs = 0L

    /** Feed one VAD decision; [elapsedMs] is monotonic time since recording start. */
    fun onFrame(speech: Boolean, elapsedMs: Long) {
        if (state == State.STOPPED) return

        when (state) {
            State.WAITING_FOR_SPEECH -> {
                if (speech) {
                    speechStreak++
                    if (speechStreak >= SPEECH_DEBOUNCE_FRAMES) {
                        speechStreak = 0
                        state = State.IN_SPEECH
                    }
                } else {
                    speechStreak = 0
                }
                if (elapsedMs >= noSpeechTimeoutMs) {
                    stop()
                    listener.onNoSpeechTimeout()
                }
            }

            State.IN_SPEECH -> {
                if (speech) {
                    silenceStreak = 0
                } else {
                    silenceStreak++
                    if (silenceStreak >= SILENCE_DEBOUNCE_FRAMES) {
                        silenceStreak = 0
                        state = State.ENDPOINT_CANDIDATE
                        candidateSinceMs = elapsedMs
                    }
                }
                checkMaxDuration(elapsedMs)
            }

            State.ENDPOINT_CANDIDATE -> {
                if (speech) {
                    state = State.IN_SPEECH
                    silenceStreak = 0
                    return
                }
                if (endpointDelayMs > 0 && elapsedMs - candidateSinceMs >= endpointDelayMs) {
                    stop()
                    listener.onEndOfSpeech()
                }
                checkMaxDuration(elapsedMs)
            }

            State.STOPPED -> Unit
        }
    }

    private fun checkMaxDuration(elapsedMs: Long) {
        if (elapsedMs >= maxDurationMs) {
            stop()
            listener.onMaxDuration()
        }
    }

    /** Terminal — no further callbacks. */
    fun stop() {
        state = State.STOPPED
    }

    companion object {
        const val DEFAULT_NO_SPEECH_TIMEOUT_MS = 8_000
        const val DEFAULT_MAX_DURATION_MS = 120_000

        /** ~40ms of speech to enter IN_SPEECH; ~60ms of silence to leave it. */
        private const val SPEECH_DEBOUNCE_FRAMES = 2
        private const val SILENCE_DEBOUNCE_FRAMES = 3
    }
}
