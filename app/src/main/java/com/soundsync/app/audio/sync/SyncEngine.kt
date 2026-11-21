package com.soundsync.app.audio.sync

import com.soundsync.app.audio.codec.AudioCompressor
import com.soundsync.app.audio.playback.AudioTrackPlayer
import com.soundsync.app.data.model.AudioPacket
import com.soundsync.app.data.model.StreamQualityPreset
import com.soundsync.app.data.model.SyncMetrics
import com.soundsync.app.service.StreamStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates Clock Synchronization, Jitter Buffering, Scheduled Playback,
 * and Continuous Phase-Locked Loop (PLL) Drift Correction on the Slave device.
 */
class SyncEngine(
    val clockSynchronizer: ClockSynchronizer,
    val player: AudioTrackPlayer
) {
    companion object {
        private const val TAG = "SyncEngine"
        // Target playout buffer depth: 100ms
        const val TARGET_BUFFER_MS = 100
        const val TARGET_BUFFER_NS = TARGET_BUFFER_MS * 1_000_000L
    }

    private val jitterBuffer = JitterBuffer(targetBufferMs = 200, sampleRate = player.sampleRate)
    private val isRunning = AtomicBoolean(false)
    private var playbackJob: Job? = null

    private val _syncMetrics = MutableStateFlow(SyncMetrics())
    val syncMetrics: StateFlow<SyncMetrics> = _syncMetrics.asStateFlow()

    @Volatile
    var calibrationOffsetMs: Float = 0f

    @Volatile
    var isDecompressingAdpcm: Boolean = false

    fun onPacketReceived(packet: AudioPacket) {
        jitterBuffer.push(packet)
    }

    /**
     * Arms the playback engine as soon as the slave connects and completes handshake.
     */
    fun arm(scope: CoroutineScope, preset: StreamQualityPreset = StreamStateHolder.currentPreset.value) {
        if (isRunning.get()) return
        startPlaybackLoop(scope, preset)
    }

    fun scheduleStart(
        targetStartMasterTimeNs: Long,
        preset: StreamQualityPreset,
        scope: CoroutineScope
    ) {
        startPlaybackLoop(scope, preset)
    }

    private fun startPlaybackLoop(scope: CoroutineScope, preset: StreamQualityPreset) {
        stop()

        isRunning.set(true)
        player.initialize()
        player.play()

        playbackJob = scope.launch(Dispatchers.Default) {
            // Wait for clock synchronization — no fixed limit, wait until actually synced
            // This ensures we never play with an incorrect clock offset
            while (!clockSynchronizer.isSynchronized && isActive && isRunning.get()) {
                delay(10)
            }

            // Prime jitter buffer: 2 packets for ultra low latency (20ms), 4 for higher presets
            val minPrimePackets = if (preset.bufferTargetMs <= 75) 2 else 4
            var primeWaitAttempts = 0
            while (jitterBuffer.size() < minPrimePackets && primeWaitAttempts < 60 && isActive && isRunning.get()) {
                delay(5)
                primeWaitAttempts++
            }

            while (isActive && isRunning.get()) {
                val packet = jitterBuffer.pollNext()
                if (packet == null) {
                    // Buffer starving: yield briefly
                    delay(2)
                    continue
                }

                val pcmBytes = if (isDecompressingAdpcm) {
                    AudioCompressor.decode(packet.payload, packet.channels)
                } else {
                    packet.payload
                }

                // If not yet synchronized, play as best effort
                if (!clockSynchronizer.isSynchronized) {
                    player.writePcm(pcmBytes, 0)
                    continue
                }

                // Precision Phase-Locked Loop (PLL) Timing calculation:
                // Desired presentation time on slave in local nanoseconds
                val packetLocalNs = clockSynchronizer.toLocalTime(packet.masterTimestampNs)
                val targetBufferNs = preset.bufferTargetMs * 1_000_000L
                val userOffsetNs = (calibrationOffsetMs * 1_000_000L).toLong()
                val targetPresentationNs = packetLocalNs + targetBufferNs + userOffsetNs

                // When would this chunk exit the speaker if written right now?
                val unplayedFrames = player.getUnplayedFrames()
                val unplayedNs = (unplayedFrames * 1_000_000_000L) / player.sampleRate
                val nowNs = System.nanoTime()
                val estimatedSpeakerNs = nowNs + unplayedNs

                val timingErrorNs = estimatedSpeakerNs - targetPresentationNs
                val timingErrorMs = timingErrorNs / 1_000_000f

                // Dynamic PLL thresholds adapted to the selected latency profile
                val flushLagThreshold = maxOf(35f, preset.bufferTargetMs * 0.50f)
                val microLagThreshold = maxOf(6f, preset.bufferTargetMs * 0.12f)
                val microLeadThreshold = -maxOf(4f, preset.bufferTargetMs * 0.10f)
                val severeLeadThreshold = -maxOf(12f, preset.bufferTargetMs * 0.25f)

                when {
                    // Severe Lag: flush AudioTrack and write immediately to eliminate accumulated delay
                    timingErrorMs > flushLagThreshold -> {
                        player.flush()
                        player.writePcm(pcmBytes, 0)
                    }

                    // Moderate Lag: speed up slightly (drop 1 micro-frame)
                    timingErrorMs > microLagThreshold -> {
                        player.writePcm(pcmBytes, -1)
                    }

                    // Too early: yield so hardware plays down buffer towards target
                    timingErrorMs < severeLeadThreshold -> {
                        val waitMs = (-timingErrorMs).toLong().coerceIn(1L, 10L)
                        delay(waitMs)
                        player.writePcm(pcmBytes, 0)
                    }

                    // Mild lead: slow down slightly (duplicate 1 micro-frame)
                    timingErrorMs < microLeadThreshold -> {
                        player.writePcm(pcmBytes, 1)
                    }

                    // Phase Locked: zero adjustment needed
                    else -> {
                        player.writePcm(pcmBytes, 0)
                    }
                }

                // Update Telemetry Metrics
                val metrics = SyncMetrics(
                    rttMs = clockSynchronizer.currentRttMs,
                    clockOffsetMs = clockSynchronizer.estimatedOffsetNs / 1_000_000f,
                    bufferHealthMs = jitterBuffer.getBufferedDurationMs(),
                    targetBufferMs = preset.bufferTargetMs,
                    driftMs = timingErrorMs,
                    packetLossPct = jitterBuffer.getPacketLossPercentage(),
                    underrunCount = player.getUnderrunCount(),
                    isSynchronized = clockSynchronizer.isSynchronized,
                    playbackActive = true
                )
                _syncMetrics.value = metrics
                StreamStateHolder.syncMetrics.value = metrics
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        playbackJob?.cancel()
        playbackJob = null
        player.pause()
        player.flush()
        jitterBuffer.clear()
        _syncMetrics.value = SyncMetrics()
        StreamStateHolder.syncMetrics.value = SyncMetrics()
    }
}
