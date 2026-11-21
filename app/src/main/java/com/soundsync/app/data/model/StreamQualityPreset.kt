package com.soundsync.app.data.model

enum class StreamQualityPreset(
    val title: String,
    val sampleRate: Int,
    val frameDurationMs: Int,
    val bufferTargetMs: Int,
    val description: String
) {
    LOW_LATENCY(
        title = "Low Latency",
        sampleRate = 48000,
        frameDurationMs = 20,
        bufferTargetMs = 120,
        description = "Optimized for minimal delay (fast Wi-Fi, games, video)"
    ),
    BALANCED(
        title = "Balanced",
        sampleRate = 48000,
        frameDurationMs = 25,
        bufferTargetMs = 200,
        description = "Optimal balance of stability, latency and quality"
    ),
    HIGH_QUALITY(
        title = "High Quality",
        sampleRate = 48000,
        frameDurationMs = 40,
        bufferTargetMs = 350,
        description = "Deep jitter buffer for congested Wi-Fi networks"
    );

    val frameCountPerPacket: Int
        get() = (sampleRate * frameDurationMs) / 1000

    val pcmByteCountPerPacket: Int
        get() = frameCountPerPacket * 2 * 2 // 16-bit (2 bytes) * stereo (2 channels)
}
