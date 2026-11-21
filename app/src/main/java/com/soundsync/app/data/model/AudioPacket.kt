package com.soundsync.app.data.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance binary audio packet for UDP transmission.
 * Uses a compact 44-byte binary header with magic bytes to minimize wireless network overhead.
 */
data class AudioPacket(
    val streamId: Long,
    val sequenceNumber: Long,
    val masterTimestampNs: Long,
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Int,
    val payload: ByteArray
) {
    companion object {
        const val MAGIC = 0x53594E43 // "SYNC" in ASCII
        const val HEADER_SIZE = 48
        const val VERSION: Byte = 1

        fun serialize(packet: AudioPacket): ByteArray {
            val totalSize = HEADER_SIZE + packet.payload.size
            val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(MAGIC)
            buffer.put(VERSION)
            buffer.put(0.toByte()) // Reserved
            buffer.putShort(0.toShort()) // Reserved
            buffer.putLong(packet.streamId)
            buffer.putLong(packet.sequenceNumber)
            buffer.putLong(packet.masterTimestampNs)
            buffer.putInt(packet.sampleRate)
            buffer.put(packet.channels.toByte())
            buffer.put(0.toByte()) // Reserved
            buffer.putShort(0.toShort()) // Reserved
            buffer.putInt(packet.frameCount)
            buffer.putInt(packet.payload.size)
            buffer.put(packet.payload)
            return buffer.array()
        }

        fun deserialize(bytes: ByteArray, length: Int): AudioPacket? {
            if (length < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.getInt()
            if (magic != MAGIC) return null
            val version = buffer.get()
            if (version != VERSION) return null
            buffer.get() // Reserved
            buffer.getShort() // Reserved
            val streamId = buffer.getLong()
            val sequenceNumber = buffer.getLong()
            val masterTimestampNs = buffer.getLong()
            val sampleRate = buffer.getInt()
            val channels = buffer.get().toInt() and 0xFF
            buffer.get() // Reserved
            buffer.getShort() // Reserved
            val frameCount = buffer.getInt()
            val payloadSize = buffer.getInt()

            if (payloadSize < 0 || payloadSize > (length - HEADER_SIZE)) return null
            val payload = ByteArray(payloadSize)
            buffer.get(payload)

            return AudioPacket(
                streamId = streamId,
                sequenceNumber = sequenceNumber,
                masterTimestampNs = masterTimestampNs,
                sampleRate = sampleRate,
                channels = channels,
                frameCount = frameCount,
                payload = payload
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioPacket
        return sequenceNumber == other.sequenceNumber && streamId == other.streamId
    }

    override fun hashCode(): Int {
        var result = streamId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        return result
    }
}
