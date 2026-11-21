package com.soundsync.app

import com.soundsync.app.audio.sync.JitterBuffer
import com.soundsync.app.data.model.AudioPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class JitterBufferTest {

    private lateinit var buffer: JitterBuffer

    @Before
    fun setUp() {
        buffer = JitterBuffer(targetBufferMs = 200, sampleRate = 48000)
    }

    private fun createPacket(seq: Long, timestampMs: Long): AudioPacket {
        return AudioPacket(
            streamId = 1L,
            sequenceNumber = seq,
            masterTimestampNs = timestampMs * 1_000_000L,
            sampleRate = 48000,
            channels = 2,
            frameCount = 960,
            payload = ByteArray(3840) { (it % 128).toByte() }
        )
    }

    @Test
    fun testSequentialPushAndPoll() {
        buffer.push(createPacket(0, 0))
        buffer.push(createPacket(1, 20))
        buffer.push(createPacket(2, 40))

        assertEquals(3, buffer.size())

        val p0 = buffer.pollNext()
        assertNotNull(p0)
        assertEquals(0L, p0?.sequenceNumber)

        val p1 = buffer.pollNext()
        assertNotNull(p1)
        assertEquals(1L, p1?.sequenceNumber)

        val p2 = buffer.pollNext()
        assertNotNull(p2)
        assertEquals(2L, p2?.sequenceNumber)

        assertNull(buffer.pollNext())
    }

    @Test
    fun testReordersOutOfOrderPackets() {
        // Arrive out of order: seq 2, seq 0, seq 1
        buffer.push(createPacket(2, 40))
        buffer.push(createPacket(0, 0))
        buffer.push(createPacket(1, 20))

        val p0 = buffer.pollNext()
        val p1 = buffer.pollNext()
        val p2 = buffer.pollNext()

        assertEquals(0L, p0?.sequenceNumber)
        assertEquals(1L, p1?.sequenceNumber)
        assertEquals(2L, p2?.sequenceNumber)
    }

    @Test
    fun testBufferedDurationCalculation() {
        buffer.push(createPacket(0, 100))
        buffer.push(createPacket(1, 150))
        buffer.push(createPacket(2, 220))

        assertEquals(120, buffer.getBufferedDurationMs())
    }
}
