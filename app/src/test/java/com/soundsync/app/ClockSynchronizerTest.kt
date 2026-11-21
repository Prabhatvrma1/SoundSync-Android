package com.soundsync.app

import com.soundsync.app.audio.sync.ClockSynchronizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClockSynchronizerTest {

    private lateinit var synchronizer: ClockSynchronizer

    @Before
    fun setUp() {
        synchronizer = ClockSynchronizer()
    }

    @Test
    fun testClockOffsetCalculation() {
        // Assume master clock is exactly 500,000,000 ns (500 ms) ahead of slave
        val trueOffset = 500_000_000L
        val oneWayNetworkDelay = 10_000_000L // 10 ms network transit

        // Slave sends ping at t0 = 1,000,000,000 ns
        val t0 = 1_000_000_000L
        // Master receives at t1 = t0 + trueOffset + delay
        val t1 = t0 + trueOffset + oneWayNetworkDelay
        // Master processes for 1 ms and sends pong at t2
        val t2 = t1 + 1_000_000L
        // Slave receives pong at t3 = t2 - trueOffset + delay
        val t3 = t2 - trueOffset + oneWayNetworkDelay

        // Expected RTT = (t3 - t0) - (t2 - t1) = 20 ms = 20,000,000 ns
        // Expected offset = ((t1 - t0) + (t2 - t3)) / 2 = 500,000,000 ns
        synchronizer.addSample(t0, t1, t2, t3)

        assertEquals(trueOffset, synchronizer.estimatedOffsetNs)
        assertEquals(20_000_000L, synchronizer.lowestRttNs)
        assertEquals(20.0f, synchronizer.currentRttMs, 0.01f)
    }

    @Test
    fun testMasterAndLocalTimeConversion() {
        val trueOffset = 300_000_000L // 300 ms
        val t0 = 2_000_000_000L
        val t1 = t0 + trueOffset + 5_000_000L
        val t2 = t1 + 500_000L
        val t3 = t2 - trueOffset + 5_000_000L

        synchronizer.addSample(t0, t1, t2, t3)

        val localTime = 5_000_000_000L
        val expectedMasterTime = localTime + trueOffset
        assertEquals(expectedMasterTime, synchronizer.toMasterTime(localTime))
        assertEquals(localTime, synchronizer.toLocalTime(expectedMasterTime))
    }

    @Test
    fun testRejectionOfHighRttSpike() {
        val trueOffset = 100_000_000L

        // Provide 4 good samples with RTT = 10 ms
        for (i in 1..4) {
            val t0 = i * 1_000_000_000L
            val t1 = t0 + trueOffset + 5_000_000L
            val t2 = t1 + 200_000L
            val t3 = t2 - trueOffset + 5_000_000L
            synchronizer.addSample(t0, t1, t2, t3)
        }

        assertTrue(synchronizer.isSynchronized)
        val baselineOffset = synchronizer.estimatedOffsetNs

        // Add 1 bad sample with a massive 200 ms network queueing jitter spike
        val badT0 = 10_000_000_000L
        val badT1 = badT0 + trueOffset + 180_000_000L // heavily asymmetric delay
        val badT2 = badT1 + 1_000_000L
        val badT3 = badT2 - trueOffset + 20_000_000L
        synchronizer.addSample(badT0, badT1, badT2, badT3)

        // The smoothed offset should remain tightly anchored around baselineOffset
        val delta = Math.abs(synchronizer.estimatedOffsetNs - baselineOffset)
        assertTrue("Offset jumped too much after jitter spike: delta=$delta", delta < 20_000_000L)
    }
}
