package com.soundsync.app.data.model

enum class StreamQualityPreset(
    val title: String,
    val sampleRate: Int,
    val frameDurationMs: Int,
    val bufferTargetMs: Int,
    val description: String
) {
    TYPE_C_WIRE(
        title = "Type-C Cable (Cinema / 15ms)",
        sampleRate = 48000,
        frameDurationMs = 5,
        bufferTargetMs = 15,
        description = "Hardware wire speed (15ms delay) via USB-C cable — perfect zero-lag movie lip-sync"
    ),
    ULTRA_LOW(
        title = "Ultra Low (Hotspot/Gaming)",
        sampleRate = 48000,
        frameDurationMs = 10,
        bufferTargetMs = 40,
        description = "Minimal 40ms delay — ideal for phone hotspot, video sync & gaming"
    ),
    LOW_LATENCY(
        title = "Low Latency (Fast Wi-Fi)",
        sampleRate = 48000,
        frameDurationMs = 15,
        bufferTargetMs = 75,
        description = "75ms buffer — balanced low latency for responsive audio"
    ),
    BALANCED(
        title = "Balanced",
        sampleRate = 48000,
        frameDurationMs = 20,
        bufferTargetMs = 140,
        description = "140ms buffer — optimal stability for music and normal Wi-Fi"
    ),
    HIGH_QUALITY(
        title = "High Quality",
        sampleRate = 48000,
        frameDurationMs = 30,
        bufferTargetMs = 250,
        description = "250ms buffer — deep jitter protection for weak or busy Wi-Fi"
    );

    val frameCountPerPacket: Int
        get() = (sampleRate * frameDurationMs) / 1000

    val pcmByteCountPerPacket: Int
        get() = frameCountPerPacket * 2 * 2 // 16-bit (2 bytes) * stereo (2 channels)
}
