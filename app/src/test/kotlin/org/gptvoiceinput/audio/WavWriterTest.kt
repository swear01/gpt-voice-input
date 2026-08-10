package org.gptvoiceinput.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    @Test
    fun `writes a valid PCM wav with patched header`() {
        val file = File.createTempFile("gvi-test", ".wav")
        try {
            val rate = 16000
            val samples = shortArrayOf(0, 1000, -1000, 32767, -32768, 42)
            WavWriter(file, rate).use { writer ->
                writer.appendPcm(samples, samples.size)
            }

            val bytes = file.readBytes()
            assertEquals(44 + samples.size * 2, bytes.size)

            // Header sanity
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals("fmt ", String(bytes, 12, 4))

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val riffSize = bb.getInt(4)
            assertEquals(36 + samples.size * 2, riffSize)
            val audioFormat = bb.getShort(20).toInt()
            assertEquals(1, audioFormat) // PCM
            val channels = bb.getShort(22).toInt()
            assertEquals(1, channels) // mono
            assertEquals(rate, bb.getInt(24))
            val blockAlign = bb.getShort(32).toInt()
            assertEquals(2, blockAlign)
            val bits = bb.getShort(34).toInt()
            assertEquals(16, bits)
            assertEquals("data", String(bytes, 36, 4))
            assertEquals(samples.size * 2, bb.getInt(40))

            // PCM payload, little-endian shorts
            bb.position(44)
            val payload = ShortArray(samples.size)
            for (i in payload.indices) payload[i] = bb.short
            assertEquals(samples.toList(), payload.toList())
        } finally {
            file.delete()
        }
    }

    private inline fun WavWriter.use(block: (WavWriter) -> Unit) {
        try {
            block(this)
            finish()
        } finally {
            // no-op; file deletion handled by caller
        }
    }
}
