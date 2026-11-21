package com.soundsync.app

import com.soundsync.app.audio.sync.DriftDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriftDetectorTest {

    private lateinit var detector: DriftDetector

    @Before
    fun setUp() {
        detector = DriftDetector(sampleRate = 48000)
    }

    @Test
    fun testZeroDriftOnExactTiming() {
        val startMasterNs = 1_000_000_000L
        val initialHeadFrames = 0L
        detector.onPlaybackStarted(startMasterNs, initialHeadFrames)

        // After 1000ms:
        val currentMasterNs = startMasterNs + 1_000_000_000L // 1000ms
        val currentHeadFrames = 48000L // exactly 1000ms of frames at 48kHz

        val adjustment = detector.checkDrift(currentMasterNs, currentHeadFrames)
        assertEquals(0, adjustment)
        assertEquals(0.0f, detector.currentDriftMs, 0.01f)
        assertFalse(detector.isHardDrift())
    }

    @Test
    fun testDetectsLaggingAudioTrackAndSpeedsUp() {
        val startMasterNs = 1_000_000_000L
        detector.onPlaybackStarted(startMasterNs, 0L)

        // Master has advanced 1000ms
        val currentMasterNs = startMasterNs + 1_000_000_000L
        // But AudioTrack only rendered 990ms (47,520 frames) -> 10ms lagging behind
        val currentHeadFrames = (48000 * 0.990).toLong()

        val adjustment = detector.checkDrift(currentMasterNs, currentHeadFrames)
        // adjustment should be -1 (drop 1 frame to speed up)
        assertEquals(-1, adjustment)
        assertTrue(detector.currentDriftMs > 3.0f)
    }

    @Test
    fun testDetectsLeadingAudioTrackAndSlowsDown() {
        val startMasterNs = 1_000_000_000L
        detector.onPlaybackStarted(startMasterNs, 0L)

        // Master has advanced 1000ms
        val currentMasterNs = startMasterNs + 1_000_000_000L
        // But AudioTrack rendered 1010ms (48,480 frames) -> 10ms ahead
        val currentHeadFrames = (48000 * 1.010).toLong()

        val adjustment = detector.checkDrift(currentMasterNs, currentHeadFrames)
        // adjustment should be +1 (duplicate 1 frame to slow down)
        assertEquals(1, adjustment)
        assertTrue(detector.currentDriftMs < -3.0f)
    }

    @Test
    fun testHardDriftDetection() {
        val startMasterNs = 1_000_000_000L
        detector.onPlaybackStarted(startMasterNs, 0L)

        // 80ms discrepancy
        val currentMasterNs = startMasterNs + 1_000_000_000L
        val currentHeadFrames = (48000 * 0.920).toLong()

        detector.checkDrift(currentMasterNs, currentHeadFrames)
        assertTrue(detector.isHardDrift())
    }
}
