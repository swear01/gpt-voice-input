package org.gptvoiceinput.audio

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import android.util.Log

/**
 * Thin seam over the WebRTC native VAD so the wrapper's error contract
 * (native failure -> reported as silence, never a crash) is unit-testable
 * without the Android .so, which cannot load in the unit-test JVM.
 */
internal interface WebRtcNativeVad {
    fun isSpeech(frame: ShortArray): Boolean
    fun close()
}

internal class VadWebRtcAdapter(
    private val vad: VadWebRTC,
) : WebRtcNativeVad {
    override fun isSpeech(frame: ShortArray): Boolean = vad.isSpeech(frame)
    override fun close() = vad.close()
}

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
 *
 * Construction loads the native library and throws (typically
 * [UnsatisfiedLinkError]) when it cannot — there is intentionally no
 * fallback detector: AudioRecorder fails the session with a clear error
 * instead of recording without endpointing.
 */
class WebRtcVoiceActivityDetector internal constructor(
    private val nativeFactory: () -> WebRtcNativeVad = { VadWebRtcAdapter(createVad()) },
) : VoiceActivityDetector {

    private var vad: WebRtcNativeVad = nativeFactory()

    override fun isSpeech(samples: ShortArray, count: Int): Boolean {
        val frame = if (count == samples.size) samples else samples.copyOf(count)
        return try {
            vad.isSpeech(frame)
        } catch (t: Throwable) {
            // Native boundary: treat any failure as silence, never crash the
            // recording thread.
            Log.w(TAG, "WebRTC VAD failed; treating frame as silence", t)
            false
        }
    }

    override fun reset() {
        // VadWebRTC has no reset; recreate the native instance.
        runCatching { vad.close() }
        vad = nativeFactory()
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
