package org.gptvoiceinput.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Streaming mono 16-bit PCM WAV writer. The 44-byte RIFF header is written
 * with placeholder sizes and patched on [finish]. The upload pipeline writes
 * raw device PCM here with zero processing.
 */
class WavWriter(file: File, private val sampleRate: Int) {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        // RIFF header with placeholder sizes (patched in finish()).
        writeAscii("RIFF")
        writeLEInt(0) // riffSize placeholder
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLEInt(16) // fmt chunk size
        writeLEShort(1) // audio format: PCM
        writeLEShort(1) // channels: mono
        writeLEInt(sampleRate)
        writeLEInt(sampleRate * BYTES_PER_FRAME)
        writeLEShort(BYTES_PER_FRAME) // block align
        writeLEShort(BITS_PER_SAMPLE)
        writeAscii("data")
        writeLEInt(0) // dataSize placeholder
    }

    fun appendPcm(samples: ShortArray, count: Int) {
        require(count <= samples.size)
        for (i in 0 until count) {
            writeLEShort(samples[i].toInt())
        }
        dataBytes += count * 2L
    }

    /** Patches header sizes and closes. Must be called before the file is used. */
    fun finish() {
        raf.seek(4)
        writeLEInt((36 + dataBytes).toInt())
        raf.seek(40)
        writeLEInt(dataBytes.toInt())
        raf.fd.sync()
        raf.close()
    }

    private fun writeLEInt(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
        raf.write((value ushr 16) and 0xFF)
        raf.write((value ushr 24) and 0xFF)
    }

    private fun writeLEShort(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
    }

    private fun writeAscii(s: String) {
        raf.write(s.toByteArray(Charsets.US_ASCII))
    }

    /** Abandons the file (header left invalid); callers must delete it. */
    fun abort() {
        runCatching { raf.close() }
    }

    companion object {
        private const val BYTES_PER_FRAME = 2
        private const val BITS_PER_SAMPLE = 16
    }
}
