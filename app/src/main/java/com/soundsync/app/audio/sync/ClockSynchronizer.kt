package com.soundsync.app.audio.sync

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Precision clock synchronizer based on Cristian's / NTP algorithm with RTT filtering.
 * Estimates clock offset between Master and Slave:
 *   RTT = (t3 - t0) - (t2 - t1)
 *   offset = ((t1 - t0) + (t2 - t3)) / 2
 *   masterTime = localTime + offset
 */
class ClockSynchronizer {

    data class SyncSample(
        val rttNs: Long,
        val offsetNs: Long,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val samples = CopyOnWriteArrayList<SyncSample>()
    private val maxSamples = 16

    @Volatile
    var estimatedOffsetNs: Long = 0L
        private set

    @Volatile
    var lowestRttNs: Long = Long.MAX_VALUE
        private set

    @Volatile
    var currentRttMs: Float = 0f
        private set

    @Volatile
    var isSynchronized: Boolean = false
        private set

    fun addSample(t0: Long, t1: Long, t2: Long, t3: Long) {
        val totalRoundTrip = t3 - t0
        val masterProcessingTime = t2 - t1
        val rttNs = maxOf(0L, totalRoundTrip - masterProcessingTime)
        val offsetNs = ((t1 - t0) + (t2 - t3)) / 2

        val sample = SyncSample(rttNs, offsetNs)
        samples.add(sample)
        if (samples.size > maxSamples) {
            samples.removeAt(0)
        }

        recalculateOffset()
    }

    private fun recalculateOffset() {
        if (samples.isEmpty()) return

        // Sort by RTT: samples with lower RTT have significantly less asymmetric queueing delay
        val sortedByRtt = samples.sortedBy { it.rttNs }
        val bestSample = sortedByRtt.first()
        lowestRttNs = bestSample.rttNs
        currentRttMs = bestSample.rttNs / 1_000_000f

        // Pick top 50% lowest RTT samples to compute robust median offset
        val subsetCount = maxOf(1, sortedByRtt.size / 2)
        val bestSubset = sortedByRtt.take(subsetCount)
        val medianOffset = bestSubset.map { it.offsetNs }.sorted()[bestSubset.size / 2]

        // Smooth with exponential moving average to prevent sudden phase jumps
        estimatedOffsetNs = if (!isSynchronized) {
            medianOffset
        } else {
            (estimatedOffsetNs * 0.8 + medianOffset * 0.2).toLong()
        }

        if (samples.size >= 4) {
            isSynchronized = true
        }
    }

    /**
     * Converts local nanoTime to estimated master nanoTime.
     */
    fun toMasterTime(localNanoTime: Long): Long {
        return localNanoTime + estimatedOffsetNs
    }

    /**
     * Converts master nanoTime to local nanoTime.
     */
    fun toLocalTime(masterNanoTime: Long): Long {
        return masterNanoTime - estimatedOffsetNs
    }

    fun reset() {
        samples.clear()
        estimatedOffsetNs = 0L
        lowestRttNs = Long.MAX_VALUE
        currentRttMs = 0f
        isSynchronized = false
    }
}
