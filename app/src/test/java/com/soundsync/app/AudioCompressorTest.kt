package com.soundsync.app

import com.soundsync.app.audio.codec.AudioCompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class AudioCompressorTest {

    @Test
    fun testStereoAdpcmCompressionRatio() {
        // 960 frames of 48kHz stereo 16-bit PCM = 3840 bytes
        val sampleCount = 960 * 2 // 1920 samples
        val pcmBytes = ByteArray(sampleCount * 2) // 3840 bytes
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until sampleCount) {
            val sample = (sin(2.0 * Math.PI * 440.0 * i / 48000.0) * 16000.0).toInt().toShort()
            shortBuffer.put(sample)
        }

        val encoded = AudioCompressor.encode(pcmBytes, channels = 2)

        // 8 header bytes + (1920 + 1) / 2 = 8 + 960 = 968 bytes
        // Compression ratio: 3840 / 968 ≈ 3.97:1 (4:1 compression!)
        assertTrue("Encoded size should be ~4x smaller than PCM", encoded.size < pcmBytes.size / 3)
        assertEquals(968, encoded.size)

        val decoded = AudioCompressor.decode(encoded, channels = 2)
        assertEquals(pcmBytes.size, decoded.size)
    }

    @Test
    fun testMonoAdpcmCompression() {
        val sampleCount = 480
        val pcmBytes = ByteArray(sampleCount * 2)
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until sampleCount) {
            shortBuffer.put((i * 50).toShort())
        }

        val encoded = AudioCompressor.encode(pcmBytes, channels = 1)
        val decoded = AudioCompressor.decode(encoded, channels = 1)

        assertEquals(pcmBytes.size, decoded.size)
    }
}
