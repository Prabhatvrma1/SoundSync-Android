package com.soundsync.app.audio.sync

import com.soundsync.app.data.model.AudioPacket
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Ordered jitter buffer for incoming audio packets.
 * Buffers packets, reorders packets arriving out-of-order, detects packet loss,
 * and maintains target buffer depth.
 */
class JitterBuffer(
    val targetBufferMs: Int = 200,
    val sampleRate: Int = 48000
) {
    private val packetMap = ConcurrentSkipListMap<Long, AudioPacket>()
    private val expectedSequence = AtomicLong(-1)

    private var totalPacketsReceived = 0L
    private var totalPacketsLost = 0L

    fun push(packet: AudioPacket) {
        val seq = packet.sequenceNumber
        if (expectedSequence.get() == -1L || seq < expectedSequence.get()) {
            expectedSequence.set(seq)
        }

        packetMap[seq] = packet
        totalPacketsReceived++

        // Prevent memory overflow if reader stalls
        while (packetMap.size > 200) {
            val oldestKey = packetMap.firstKey()
            packetMap.remove(oldestKey)
        }
    }

    /**
     * Polls the next packet in sequence.
     * If next sequence packet is missing, checks if subsequent packets exist.
     * If gap is too wide or timeout elapsed, returns concealment (null) and advances expectedSequence.
     */
    fun pollNext(): AudioPacket? {
        val exp = expectedSequence.get()
        if (exp == -1L || packetMap.isEmpty()) return null

        val packet = packetMap.remove(exp)
        if (packet != null) {
            expectedSequence.incrementAndGet()
            return packet
        }

        // Check if higher sequence numbers are waiting
        val higherKey = packetMap.higherKey(exp)
        if (higherKey != null) {
            // A gap exists. If we have buffered ahead beyond 40ms, declare missing packet lost
            val aheadPackets = higherKey - exp
            if (aheadPackets > 2 || packetMap.size > 5) {
                totalPacketsLost++
                expectedSequence.set(higherKey + 1)
                return packetMap.remove(higherKey)
            }
        }
        return null
    }

    fun getBufferedDurationMs(): Int {
        if (packetMap.size < 2) return 0
        val first = packetMap.firstEntry()?.value ?: return 0
        val last = packetMap.lastEntry()?.value ?: return 0
        val spanNs = last.masterTimestampNs - first.masterTimestampNs
        return maxOf(0, (spanNs / 1_000_000).toInt())
    }

    fun getPacketLossPercentage(): Float {
        val total = totalPacketsReceived + totalPacketsLost
        return if (total > 0) (totalPacketsLost * 100f) / total else 0f
    }

    fun size(): Int = packetMap.size

    fun clear() {
        packetMap.clear()
        expectedSequence.set(-1)
        totalPacketsReceived = 0
        totalPacketsLost = 0
    }
}
