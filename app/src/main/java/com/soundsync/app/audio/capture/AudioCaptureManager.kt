package com.soundsync.app.audio.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import com.soundsync.app.data.model.AudioPacket
import com.soundsync.app.data.model.StreamQualityPreset
import com.soundsync.app.service.StreamStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sin

/**
 * Manages system-wide audio capture via Android 10+ AudioPlaybackCapture API and MediaProjection.
 * Also includes a high-precision audio pulse/tone generator for synchronization verification.
 */
class AudioCaptureManager {

    companion object {
        private const val TAG = "AudioCaptureManager"
    }

    enum class CaptureState {
        IDLE,
        INITIALIZING,
        CAPTURING_SYSTEM,
        GENERATING_TEST_PULSE,
        RESTRICTED,
        ERROR
    }

    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _audioPackets = MutableSharedFlow<AudioPacket>(extraBufferCapacity = 64)
    val audioPackets: SharedFlow<AudioPacket> = _audioPackets.asSharedFlow()

    // Real-time amplitude flow for waveform visualization (0.0f .. 1.0f)
    private val _currentRms = MutableStateFlow(0f)
    val currentRms: StateFlow<Float> = _currentRms.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val sequenceCounter = AtomicLong(0)
    private var currentStreamId: Long = System.currentTimeMillis()

    @Volatile
    var isTestToneEnabled: Boolean = false

    @SuppressLint("MissingPermission")
    fun startSystemAudioCapture(
        mediaProjection: MediaProjection,
        preset: StreamQualityPreset = StreamQualityPreset.BALANCED,
        scope: CoroutineScope
    ) {
        if (isRunning.get()) stopCapture()

        currentStreamId = System.currentTimeMillis()
        sequenceCounter.set(0)
        _captureState.value = CaptureState.INITIALIZING
        _statusMessage.value = "Configuring AudioPlaybackCapture..."
        StreamStateHolder.captureState.value = CaptureState.INITIALIZING
        StreamStateHolder.captureMessage.value = "Configuring AudioPlaybackCapture..."

        captureJob = scope.launch(Dispatchers.IO) {
            try {
                // Build AudioPlaybackCaptureConfiguration (Android 10+)
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val sampleRate = preset.sampleRate
                val channelConfig = AudioFormat.CHANNEL_IN_STEREO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val chunkFrameCount = preset.frameCountPerPacket
                val chunkByteSize = chunkFrameCount * 2 * 2 // stereo 16-bit
                val recordBufferSize = maxOf(minBufferSize * 2, chunkByteSize * 4)

                val record = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(recordBufferSize)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord not initialized, falling back to sync pulses")
                    _captureState.value = CaptureState.RESTRICTED
                    _statusMessage.value = "Audio capture restricted; emitting sync test pulses"
                    startTestPulseOnly(preset, scope)
                    return@launch
                }

                audioRecord = record
                record.startRecording()
                isRunning.set(true)
                _captureState.value = CaptureState.CAPTURING_SYSTEM
                _statusMessage.value = "Capturing System Audio (48kHz Stereo)"
                StreamStateHolder.captureState.value = CaptureState.CAPTURING_SYSTEM
                StreamStateHolder.captureMessage.value = "Capturing System Audio (48kHz Stereo)"

                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                val buffer = ByteArray(chunkByteSize)

                while (isActive && isRunning.get()) {
                    if (isTestToneEnabled) {
                        // Generate test pulse with sharp transient click for sync testing
                        val masterTimeNs = System.nanoTime()
                        generateClickPulsePcm(buffer, sampleRate, chunkFrameCount)
                        emitPacket(buffer, chunkFrameCount, sampleRate, 2, masterTimeNs)
                        Thread.sleep(preset.frameDurationMs.toLong())
                        continue
                    }

                    var bytesReadTotal = 0
                    while (bytesReadTotal < chunkByteSize && isActive && isRunning.get()) {
                        val read = record.read(
                            buffer,
                            bytesReadTotal,
                            chunkByteSize - bytesReadTotal,
                            AudioRecord.READ_BLOCKING
                        )
                        if (read > 0) {
                            bytesReadTotal += read
                        } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                            Log.e(TAG, "AudioRecord read error: $read")
                            break
                        }
                    }

                    if (bytesReadTotal == chunkByteSize) {
                        val masterTimeNs = System.nanoTime()
                        calculateRms(buffer)
                        emitPacket(buffer, chunkFrameCount, sampleRate, 2, masterTimeNs)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException starting audio capture", e)
                _captureState.value = CaptureState.RESTRICTED
                _statusMessage.value = "Capture restricted: ${e.localizedMessage}"
                startTestPulseOnly(preset, scope)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during audio capture", e)
                _captureState.value = CaptureState.ERROR
                _statusMessage.value = "Audio capture fallback: ${e.localizedMessage}"
                startTestPulseOnly(preset, scope)
            } finally {
                cleanUpRecord()
            }
        }
    }

    fun startTestPulseOnly(
        preset: StreamQualityPreset = StreamQualityPreset.BALANCED,
        scope: CoroutineScope,
        onPulseGenerated: ((ByteArray) -> Unit)? = null
    ) {
        if (isRunning.get()) stopCapture()

        currentStreamId = System.currentTimeMillis()
        sequenceCounter.set(0)
        isRunning.set(true)
        _captureState.value = CaptureState.GENERATING_TEST_PULSE
        _statusMessage.value = "Emitting Precision Sync Pulses (1kHz click @ 500ms)"
        StreamStateHolder.captureState.value = CaptureState.GENERATING_TEST_PULSE
        StreamStateHolder.captureMessage.value = "Emitting Precision Sync Pulses (1kHz click @ 500ms)"

        captureJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sampleRate = preset.sampleRate
            val chunkFrameCount = preset.frameCountPerPacket
            val chunkByteSize = chunkFrameCount * 2 * 2
            val buffer = ByteArray(chunkByteSize)

            val frameDurationMs = preset.frameDurationMs.toLong()

            while (isActive && isRunning.get()) {
                val startLoop = System.nanoTime()
                generateClickPulsePcm(buffer, sampleRate, chunkFrameCount)
                calculateRms(buffer)
                emitPacket(buffer, chunkFrameCount, sampleRate, 2, startLoop)
                onPulseGenerated?.invoke(buffer.copyOf())

                val elapsedMs = (System.nanoTime() - startLoop) / 1_000_000
                val sleepMs = frameDurationMs - elapsedMs
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs)
                }
            }
        }
    }

    private var pulseSampleOffset = 0L

    private fun generateClickPulsePcm(buffer: ByteArray, sampleRate: Int, frameCount: Int) {
        val periodSamples = sampleRate / 2 // click every 500ms
        val clickLength = sampleRate / 100 // 10ms click duration

        var byteIdx = 0
        for (i in 0 until frameCount) {
            val samplePosInPeriod = (pulseSampleOffset + i) % periodSamples
            val sampleVal: Short = if (samplePosInPeriod < clickLength) {
                val phase = 2.0 * Math.PI * 1000.0 * samplePosInPeriod / sampleRate
                val decay = 1.0 - (samplePosInPeriod.toDouble() / clickLength)
                (sin(phase) * 30000.0 * decay).toInt().toShort()
            } else {
                0
            }

            // Left Channel (Little-Endian)
            buffer[byteIdx++] = (sampleVal.toInt() and 0xFF).toByte()
            buffer[byteIdx++] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
            // Right Channel
            buffer[byteIdx++] = (sampleVal.toInt() and 0xFF).toByte()
            buffer[byteIdx++] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }
        pulseSampleOffset += frameCount
    }

    private suspend fun emitPacket(
        pcmData: ByteArray,
        frameCount: Int,
        sampleRate: Int,
        channels: Int,
        masterTimeNs: Long
    ) {
        val seq = sequenceCounter.getAndIncrement()
        val copy = pcmData.copyOf()
        val packet = AudioPacket(
            streamId = currentStreamId,
            sequenceNumber = seq,
            masterTimestampNs = masterTimeNs,
            sampleRate = sampleRate,
            channels = channels,
            frameCount = frameCount,
            payload = copy
        )
        _audioPackets.emit(packet)
    }

    private fun calculateRms(buffer: ByteArray) {
        var sum = 0.0
        val sampleCount = buffer.size / 2
        for (i in 0 until sampleCount) {
            val low = buffer[i * 2].toInt() and 0xFF
            val high = buffer[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = Math.sqrt(sum / sampleCount)
        val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        _currentRms.value = normalized
        StreamStateHolder.amplitude.value = normalized
    }

    fun stopCapture() {
        isRunning.set(false)
        captureJob?.cancel()
        captureJob = null
        cleanUpRecord()
        _captureState.value = CaptureState.IDLE
        _statusMessage.value = "Capture stopped"
        _currentRms.value = 0f
        StreamStateHolder.captureState.value = CaptureState.IDLE
        StreamStateHolder.captureMessage.value = "Capture stopped"
        StreamStateHolder.amplitude.value = 0f
    }

    private fun cleanUpRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }
}
