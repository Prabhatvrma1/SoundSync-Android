package com.soundsync.app.audio.sync

import kotlin.math.abs

/**
 * Tracks synchronization drift between master audio stream time and local AudioTrack playback head.
 * Detects micro-drift and produces frame adjustments (+1, 0, -1) for click-free phase alignment.
 */
class DriftDetector(
    val sampleRate: Int = 48000
) {
    private var initialMasterTimestampNs: Long = 0L
    private var initialLocalFrameCount: Long = 0L
    private var isStarted = false

    @Volatile
    var currentDriftMs: Float = 0f
        private set

    fun onPlaybackStarted(startMasterTimestampNs: Long, initialFrames: Long) {
        initialMasterTimestampNs = startMasterTimestampNs
        initialLocalFrameCount = initialFrames
        isStarted = true
        currentDriftMs = 0f
    }

    /**
     * Checks drift at current playback time.
     * Returns:
     *   0: drift is within acceptable tolerance (+-3ms)
     *  -1: playback is lagging behind master -> drop 1 frame to speed up
     *  +1: playback is running ahead of master -> duplicate 1 frame to slow down
     */
    fun checkDrift(currentMasterTimeNs: Long, currentHeadFrames: Long): Int {
        if (!isStarted || initialMasterTimestampNs == 0L) return 0

        val elapsedMasterNs = currentMasterTimeNs - initialMasterTimestampNs
        val elapsedMasterMs = elapsedMasterNs / 1_000_000f

        val elapsedFrames = currentHeadFrames - initialLocalFrameCount
        val elapsedPlaybackMs = (elapsedFrames * 1000f) / sampleRate

        // drift = Master elapsed time - AudioTrack elapsed time
        // Positive drift means Master is ahead (AudioTrack is lagging)
        val drift = elapsedMasterMs - elapsedPlaybackMs
        currentDriftMs = drift

        return when {
            drift > 3.0f && drift < 50.0f -> -1 // Lagging: speed up
            drift < -3.0f && drift > -50.0f -> 1  // Running ahead: slow down
            else -> 0
        }
    }

    fun isHardDrift(): Boolean {
        return abs(currentDriftMs) >= 50.0f
    }

    fun reset() {
        initialMasterTimestampNs = 0L
        initialLocalFrameCount = 0L
        isStarted = false
        currentDriftMs = 0f
    }
}
