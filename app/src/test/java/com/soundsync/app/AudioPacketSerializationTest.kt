package com.soundsync.app

import com.soundsync.app.data.model.AudioPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPacketSerializationTest {

    @Test
    fun testSerializeAndDeserializeRoundtrip() {
        val originalPayload = ByteArray(120) { it.toByte() }
        val packet = AudioPacket(
            streamId = 9876543210L,
            sequenceNumber = 42L,
            masterTimestampNs = 1_700_000_000_000_000L,
            sampleRate = 48000,
            channels = 2,
            frameCount = 960,
            payload = originalPayload
        )

        val serialized = AudioPacket.serialize(packet)
        assertEquals(AudioPacket.HEADER_SIZE + originalPayload.size, serialized.size)

        val deserialized = AudioPacket.deserialize(serialized, serialized.size)
        assertNotNull(deserialized)
        assertEquals(packet.streamId, deserialized?.streamId)
        assertEquals(packet.sequenceNumber, deserialized?.sequenceNumber)
        assertEquals(packet.masterTimestampNs, deserialized?.masterTimestampNs)
        assertEquals(packet.sampleRate, deserialized?.sampleRate)
        assertEquals(packet.channels, deserialized?.channels)
        assertEquals(packet.frameCount, deserialized?.frameCount)
        assertArrayEquals(originalPayload, deserialized?.payload)
    }

    @Test
    fun testRejectsInvalidMagicOrTruncatedBytes() {
        val truncated = ByteArray(20)
        assertNull(AudioPacket.deserialize(truncated, truncated.size))

        val invalidMagic = ByteArray(AudioPacket.HEADER_SIZE)
        invalidMagic[0] = 0x12
        invalidMagic[1] = 0x34
        assertNull(AudioPacket.deserialize(invalidMagic, invalidMagic.size))
    }
}
