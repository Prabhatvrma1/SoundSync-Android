package com.soundsync.app.data.model

data class SyncMetrics(
    val rttMs: Float = 0f,
    val clockOffsetMs: Float = 0f,
    val bufferHealthMs: Int = 0,
    val targetBufferMs: Int = 200,
    val driftMs: Float = 0f,
    val packetLossPct: Float = 0f,
    val underrunCount: Int = 0,
    val isSynchronized: Boolean = false,
    val playbackActive: Boolean = false
)
