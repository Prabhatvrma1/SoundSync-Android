package com.soundsync.app.data.model

data class SlaveDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val controlPort: Int = 8889,
    val audioPort: Int = 8890,
    val rttMs: Float = 0f,
    val clockOffsetNs: Long = 0L,
    val bufferHealthMs: Int = 0,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val isConnected: Boolean = true,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val underruns: Int = 0,
    val packetLossPct: Float = 0f
)
