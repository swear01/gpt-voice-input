package org.gptvoiceinput.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder

/** Upload-side sink for raw mono 16-bit PCM. */
interface PcmSink {
    fun appendPcm(samples: ShortArray, count: Int)

    /** Flushes and closes; the file is ready for upload afterwards. */
    fun finish()

    /** Abandons the file; callers must delete it. */
    fun abort()
}

/**
 * Streams mono 16-bit PCM into an Ogg/Opus file (MediaCodec Opus encoder +
 * MediaMuxer OGG, both API 29+). 32 kbps is transparent for speech
 * recognition and ~30x smaller than 48 kHz WAV, so a 20-minute recording
 * stays ~5 MB — far below the 25 MB API upload limit.
 *
 * [sampleRate] must be an Opus rate (8/12/16/24/48 kHz).
 */
class OpusOggWriter(file: File, private val sampleRate: Int) : PcmSink {

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val muxer = try {
        MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
    } catch (e: Exception) {
        codec.release()
        throw e
    }
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var samplesQueued = 0L

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            abort()
            throw e
        }
    }

    override fun appendPcm(samples: ShortArray, count: Int) {
        require(count <= samples.size)
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buf = codec.getInputBuffer(index)!!
            val n = minOf(count - offset, buf.remaining() / 2)
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples, offset, n)
            codec.queueInputBuffer(index, 0, n * 2, ptsUs(), 0)
            samplesQueued += n
            offset += n
        }
        drain(endOfStream = false)
    }

    override fun finish() {
        try {
            val deadline = SystemClock.elapsedRealtime() + EOS_TIMEOUT_MS
            var index = codec.dequeueInputBuffer(TIMEOUT_US)
            while (index < 0 && SystemClock.elapsedRealtime() < deadline) {
                drain(endOfStream = false)
                index = codec.dequeueInputBuffer(TIMEOUT_US)
            }
            check(index >= 0) { "Opus encoder never accepted end of stream" }
            codec.queueInputBuffer(index, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(endOfStream = true, deadline = deadline)
            codec.stop()
            // No packets (sub-frame tap): leave the empty file; callers treat
            // a zero-length recording as "no audio".
            if (track >= 0) muxer.stop()
        } finally {
            runCatching { codec.release() }
            runCatching { muxer.release() }
        }
    }

    override fun abort() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }

    private fun ptsUs(): Long = samplesQueued * 1_000_000L / sampleRate

    /** Moves encoded packets into the muxer; with [endOfStream], waits for EOS. */
    private fun drain(endOfStream: Boolean, deadline: Long = 0L) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    check(SystemClock.elapsedRealtime() < deadline) { "Opus encoder never reached end of stream" }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) muxer.writeSampleData(track, buf, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        const val BIT_RATE = 32_000
        private const val TIMEOUT_US = 10_000L
        private const val EOS_TIMEOUT_MS = 2_000L
    }
}
