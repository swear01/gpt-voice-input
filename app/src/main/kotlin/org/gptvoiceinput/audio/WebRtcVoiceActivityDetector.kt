package org.gptvoiceinput.audio

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import android.util.Log

/**
 * Voice activity detection with Google's WebRTC VAD — the GMM-based detector
 * used by Chrome / Google Meet (the "gold standard" for delay-sensitive
 * speech detection; Android's own speech pipeline is the AMR-WB VAD on the
 * same principle).
 *
 * - Frame: 320 samples @ 16 kHz = 20 ms (matches the analysis pipeline).
 * - Mode NORMAL: least aggressive classifier — the priority is never
 *   misclassifying soft-but-continuous speech as silence (the mid-speech
 *   auto-stop failure), at the cost of a few extra false positives which the
 *   EndpointDetector's debounce absorbs.
 * - silenceDurationMs 300: frames are only declared silence after 300 ms of
 *   quiet, so micro-gaps inside words/phrases never start the endpoint
 *   clock (the built-in "hangover").
 * - speechDurationMs 50: requires ~2.5 frames of speech before flipping to
 *   speech (debounce against ambient blips).
 *
 * The class is NOT thread-safe; the recorder feeds it from the record
 * thread only.
 */
class WebRtcVoiceActivityDetector : VoiceActivityDetector {

    private var vad = createVad().also {
        Log.i(TAG, "WebRTC VAD loaded (native lib ok)")
    }

    override fun isSpeech(samples: ShortArray, count: Int): Boolean {
        val frame = if (count == samples.size) samples else samples.copyOf(count)
        return try {
            vad.isSpeech(frame)
        } catch (e: Exception) {
            Log.w(TAG, "WebRTC VAD failed; treating frame as silence", e)
            false
        }
    }

    override fun reset() {
        // VadWebRTC has no reset; recreate the native instance.
        runCatching { vad.close() }
        vad = createVad()
    }

    override fun close() {
        runCatching { vad.close() }
    }

    companion object {
        private const val TAG = "WebRtcVad"

        private fun createVad(): VadWebRTC = VadWebRTC(
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_320,
            mode = Mode.NORMAL,
            speechDurationMs = 50,
            silenceDurationMs = 300,
        )
    }
}
