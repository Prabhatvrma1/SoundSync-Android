package com.soundsync.app.audio.sync

import com.soundsync.app.audio.codec.AudioCompressor
import com.soundsync.app.audio.playback.AudioTrackPlayer
import com.soundsync.app.data.model.AudioPacket
import com.soundsync.app.data.model.StreamQualityPreset
import com.soundsync.app.data.model.SyncMetrics
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
 * and Continuous Drift Correction on the Slave device.
 */
class SyncEngine(
    val clockSynchronizer: ClockSynchronizer,
    val player: AudioTrackPlayer
) {
    private val jitterBuffer = JitterBuffer(targetBufferMs = 200, sampleRate = player.sampleRate)
    private val driftDetector = DriftDetector(sampleRate = player.sampleRate)

    private val isRunning = AtomicBoolean(false)
    private var playbackJob: Job? = null

    private val _syncMetrics = MutableStateFlow(SyncMetrics())
    val syncMetrics: StateFlow<SyncMetrics> = _syncMetrics.asStateFlow()

    @Volatile
    var isDecompressingAdpcm: Boolean = false

    fun onPacketReceived(packet: AudioPacket) {
        jitterBuffer.push(packet)
    }

    fun scheduleStart(
        targetStartMasterTimeNs: Long,
        preset: StreamQualityPreset,
        scope: CoroutineScope
    ) {
        stop()

        isRunning.set(true)
        player.initialize()

        playbackJob = scope.launch(Dispatchers.Default) {
            val targetStartLocalNs = clockSynchronizer.toLocalTime(targetStartMasterTimeNs)

            // Wait until scheduled local start time
            var nowNs = System.nanoTime()
            val initialWaitNs = targetStartLocalNs - nowNs
            if (initialWaitNs > 0) {
                val waitMs = initialWaitNs / 1_000_000
                val waitRemNs = (initialWaitNs % 1_000_000).toInt()
                if (waitMs > 0) delay(waitMs)
                // Busy wait for the final fractional millisecond for sub-millisecond precision
                while (System.nanoTime() < targetStartLocalNs && isActive) {
                    // spin
                }
            }

            player.play()
            val initialHeadFrames = player.getPlaybackHeadPositionFrames()
            driftDetector.onPlaybackStarted(targetStartMasterTimeNs, initialHeadFrames)

            while (isActive && isRunning.get()) {
                val packet = jitterBuffer.pollNext()
                if (packet != null) {
                    val pcmBytes = if (isDecompressingAdpcm) {
                        AudioCompressor.decode(packet.payload, packet.channels)
                    } else {
                        packet.payload
                    }

                    // Check clock drift against AudioTrack head
                    val currentMasterTime = clockSynchronizer.toMasterTime(System.nanoTime())
                    val currentHead = player.getPlaybackHeadPositionFrames()
                    val driftAdj = driftDetector.checkDrift(currentMasterTime, currentHead)

                    if (driftDetector.isHardDrift()) {
                        // Resynchronize on major network jitter
                        player.flush()
                        driftDetector.onPlaybackStarted(currentMasterTime, player.getPlaybackHeadPositionFrames())
                    }

                    player.writePcm(pcmBytes, driftAdj)
                } else {
                    // Buffer starving: yield slightly
                    delay(2)
                }

                // Update Telemetry Metrics every loop
                _syncMetrics.value = SyncMetrics(
                    rttMs = clockSynchronizer.currentRttMs,
                    clockOffsetMs = clockSynchronizer.estimatedOffsetNs / 1_000_000f,
                    bufferHealthMs = jitterBuffer.getBufferedDurationMs(),
                    targetBufferMs = preset.bufferTargetMs,
                    driftMs = driftDetector.currentDriftMs,
                    packetLossPct = jitterBuffer.getPacketLossPercentage(),
                    underrunCount = player.getUnderrunCount(),
                    isSynchronized = clockSynchronizer.isSynchronized,
                    playbackActive = true
                )
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
        driftDetector.reset()
        _syncMetrics.value = SyncMetrics()
    }
}
