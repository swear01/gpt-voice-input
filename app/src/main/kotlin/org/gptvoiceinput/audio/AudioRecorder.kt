package org.gptvoiceinput.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Raw speech capture.
 *
 * Split pipeline:
 * ```
 * AudioRecord → raw PCM → WavWriter (upload path, zero processing)
 *                     └→ frame copy → downsample → VadProcessor → EndpointDetector
 * ```
 *
 * - Source: `UNPROCESSED` when the device reports support, else
 *   `VOICE_RECOGNITION`. Never `VOICE_COMMUNICATION`.
 * - No NoiseSuppressor / AGC is attached to this AudioRecord: Android audio
 *   effects attached to the session alter the captured signal itself, which
 *   would corrupt the audio sent to OpenAI.
 * - The analysis path only ever sees copies of frames.
 */
class AudioRecorder(
    private val context: Context,
    private val wavFile: File,
    private val endpointDelayMs: Int,
    private val listener: Listener,
    private val noSpeechTimeoutMs: Int = EndpointDetector.DEFAULT_NO_SPEECH_TIMEOUT_MS,
    private val maxDurationMs: Int = EndpointDetector.DEFAULT_MAX_DURATION_MS,
) {

    interface Listener {
        fun onFrameCaptured(elapsedMs: Long)
        fun onEndOfSpeech()
        fun onNoSpeechTimeout()
        fun onMaxDuration()
        fun onRecordingError(message: String)
    }

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var wavWriter: WavWriter? = null
    private var vad: VadProcessor? = null
    private var endpoint: EndpointDetector? = null
    private var startRealtimeMs = 0L

    val sampleRate: Int get() = record?.sampleRate ?: 0
    val sourceDescription: String get() = sourceName(usedSource)

    @Volatile
    private var usedSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

    /** Returns false if the microphone could not be opened. */
    fun start(): Boolean {
        if (running) return true
        return try {
            val chosen = openAudioRecord() ?: run {
                listener.onRecordingError("Microphone unavailable on this device")
                return false
            }
            record = chosen.record
            usedSource = chosen.source

            wavWriter = WavWriter(wavFile, chosen.record.sampleRate)
            vad = VadProcessor(sampleRate = ANALYSIS_RATE)
            endpoint = EndpointDetector(
                endpointDelayMs = endpointDelayMs,
                noSpeechTimeoutMs = noSpeechTimeoutMs,
                maxDurationMs = maxDurationMs,
                listener = object : EndpointDetector.Listener {
                    override fun onEndOfSpeech() = listener.onEndOfSpeech()
                    override fun onNoSpeechTimeout() = listener.onNoSpeechTimeout()
                    override fun onMaxDuration() = listener.onMaxDuration()
                },
            )

            startRealtimeMs = SystemClock.elapsedRealtime()
            chosen.record.startRecording()
            running = true
            thread = Thread(::recordLoop, "gvi-recorder").also { it.start() }
            Log.i(TAG, "Recording: source=${sourceName(chosen.source)} rate=${chosen.record.sampleRate}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            release()
            listener.onRecordingError("Could not start recording (${e.message ?: "unknown error"})")
            false
        }
    }

    /** Stops capture, joins the reader thread, and finalizes a valid WAV. */
    fun stopAndFinalize() {
        stopLoop()
        thread?.join(JOIN_TIMEOUT_MS)
        wavWriter?.finish()
        release()
    }

    /** Stops capture without producing a usable WAV (caller deletes the file). */
    fun cancelAndAbort() {
        stopLoop()
        thread?.join(JOIN_TIMEOUT_MS)
        wavWriter?.abort()
        release()
    }

    private fun stopLoop() {
        running = false
    }

    private fun recordLoop() {
        val r = record ?: return
        val frameSamples = r.sampleRate * VadProcessor.FRAME_MS / 1000
        val frame = ShortArray(frameSamples)
        var filled = 0

        while (running) {
            val n = r.read(frame, filled, frameSamples - filled)
            if (n < 0) {
                if (running) {
                    running = false
                    postError("Microphone read failed")
                }
                break
            }
            if (n == 0) continue
            filled += n
            if (filled == frameSamples) {
                val elapsed = SystemClock.elapsedRealtime() - startRealtimeMs
                wavWriter?.appendPcm(frame, frameSamples)
                analyzeCopy(frame, frameSamples, r.sampleRate, elapsed)
                listener.onFrameCaptured(elapsed)
                filled = 0
            }
        }
    }

    /** Analysis side channel: copied frame only; upload audio untouched. */
    private fun analyzeCopy(frame: ShortArray, count: Int, rate: Int, elapsedMs: Long) {
        val vad = vad ?: return
        val endpoint = endpoint ?: return

        // Downsample the copy to the analysis rate (box averaging); never
        // resample the upload path.
        val factor = (rate / ANALYSIS_RATE).coerceAtLeast(1)
        val outCount = count / factor
        if (outCount <= 0) return
        val analysis = ShortArray(outCount)
        var o = 0
        var i = 0
        while (i + factor <= count) {
            var acc = 0L
            for (k in 0 until factor) acc += frame[i + k]
            analysis[o++] = (acc / factor).toShort()
            i += factor
        }
        endpoint.onFrame(vad.isSpeech(analysis, outCount), elapsedMs)
    }

    private fun postError(message: String) {
        listener.onRecordingError(message)
    }

    private fun openAudioRecord(): ChosenSource? {
        val sources = buildList {
            if (supportsUnprocessed()) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }
        for (source in sources) {
            for (rate in SAMPLE_RATE_CANDIDATES) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) continue
                val frameSamples = rate * VadProcessor.FRAME_MS / 1000
                val bufSize = minBuf.coerceAtLeast(frameSamples * 2 * 2)
                val r = try {
                    AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord failed source=${sourceName(source)} rate=$rate", e)
                    continue
                }
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    r.release()
                    continue
                }
                return ChosenSource(r, source)
            }
        }
        return null
    }

    private fun supportsUnprocessed(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            val prop = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            prop?.equals("true", ignoreCase = true) ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun release() {
        runCatching { record?.stop() }
        record?.release()
        record = null
        wavWriter = null
        vad = null
        endpoint = null
        thread = null
    }

    private data class ChosenSource(val record: AudioRecord, val source: Int)

    companion object {
        private const val TAG = "AudioRecorder"
        private const val ANALYSIS_RATE = 16_000
        private const val JOIN_TIMEOUT_MS = 2_000L

        private val SAMPLE_RATE_CANDIDATES = intArrayOf(48_000, 44_100, 16_000, 8_000)

        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> "source#$source"
        }
    }
}
