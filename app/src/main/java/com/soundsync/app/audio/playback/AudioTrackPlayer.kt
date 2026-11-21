package com.soundsync.app.audio.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, low-latency AudioTrack player for Android.
 * Supports scheduled playback start, dynamic micro-resampling/drift correction, and volume/mute controls.
 */
class AudioTrackPlayer(
    val sampleRate: Int = 48000,
    val channels: Int = 2
) {
    companion object {
        private const val TAG = "AudioTrackPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)

    @Volatile
    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolume()
        }

    @Volatile
    var isMuted: Boolean = false
        set(value) {
            field = value
            applyVolume()
        }

    private var totalFramesWritten: Long = 0L

    fun initialize(): Boolean {
        try {
            release()

            val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
            // Buffer size = ~100ms of audio for low latency
            val targetBufferSize = maxOf(minBufferSize, (sampleRate * channels * 2 * 0.10).toInt())

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(targetBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize")
                return false
            }

            applyVolume()
            totalFramesWritten = 0L
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing AudioTrack", e)
            return false
        }
    }

    fun play() {
        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
                isPlaying.set(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
        }
    }

    fun pause() {
        try {
            audioTrack?.pause()
            isPlaying.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing playback", e)
        }
    }

    fun flush() {
        try {
            audioTrack?.flush()
            totalFramesWritten = 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack", e)
        }
    }

    /**
     * Writes 16-bit PCM bytes to AudioTrack with optional frame drift adjustment.
     * adjustment: 0 = normal, +1 = duplicate 1 frame (slows down playback), -1 = drop 1 frame (speeds up playback)
     */
    fun writePcm(pcmBytes: ByteArray, adjustment: Int = 0): Int {
        val track = audioTrack ?: return 0
        if (!isPlaying.get()) return 0

        val bytesToWrite = if (adjustment == 0) {
            pcmBytes
        } else {
            applyDriftAdjustment(pcmBytes, adjustment)
        }

        // Apply software volume scaling if needed
        val scaledBytes = if (isMuted || volume < 0.99f) {
            scaleVolume(bytesToWrite, if (isMuted) 0f else volume)
        } else {
            bytesToWrite
        }

        // Use NON_BLOCKING to avoid stalling the playback coroutine
        var offset = 0
        var totalWritten = 0
        var retries = 0
        while (offset < scaledBytes.size && retries < 20) {
            val written = track.write(scaledBytes, offset, scaledBytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written > 0) {
                offset += written
                totalWritten += written
            } else if (written == 0) {
                // Buffer full — sleep 1ms and retry
                Thread.sleep(1)
                retries++
            } else {
                break // error
            }
        }
        if (totalWritten > 0) {
            val bytesPerFrame = channels * 2
            totalFramesWritten += (totalWritten / bytesPerFrame)
        }
        return totalWritten
    }

    fun getPlaybackHeadPositionFrames(): Long {
        return (audioTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL
    }

    fun getUnplayedFrames(): Long {
        val head = getPlaybackHeadPositionFrames()
        return maxOf(0L, totalFramesWritten - head)
    }

    fun getUnderrunCount(): Int {
        return try {
            audioTrack?.underrunCount ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun applyDriftAdjustment(pcmBytes: ByteArray, adjustment: Int): ByteArray {
        val bytesPerFrame = channels * 2
        val totalFrames = pcmBytes.size / bytesPerFrame
        if (totalFrames <= 4) return pcmBytes

        return if (adjustment < 0) {
            // Drop 1 frame in the middle to speed up slightly
            val dropFrameIndex = totalFrames / 2
            val newSize = (totalFrames - 1) * bytesPerFrame
            val result = ByteArray(newSize)
            System.arraycopy(pcmBytes, 0, result, 0, dropFrameIndex * bytesPerFrame)
            System.arraycopy(
                pcmBytes,
                (dropFrameIndex + 1) * bytesPerFrame,
                result,
                dropFrameIndex * bytesPerFrame,
                (totalFrames - dropFrameIndex - 1) * bytesPerFrame
            )
            result
        } else {
            // Duplicate 1 frame in the middle to slow down slightly
            val dupFrameIndex = totalFrames / 2
            val newSize = (totalFrames + 1) * bytesPerFrame
            val result = ByteArray(newSize)
            System.arraycopy(pcmBytes, 0, result, 0, (dupFrameIndex + 1) * bytesPerFrame)
            // Duplicate the frame
            System.arraycopy(
                pcmBytes,
                dupFrameIndex * bytesPerFrame,
                result,
                (dupFrameIndex + 1) * bytesPerFrame,
                bytesPerFrame
            )
            System.arraycopy(
                pcmBytes,
                (dupFrameIndex + 1) * bytesPerFrame,
                result,
                (dupFrameIndex + 2) * bytesPerFrame,
                (totalFrames - dupFrameIndex - 1) * bytesPerFrame
            )
            result
        }
    }

    private fun scaleVolume(pcmBytes: ByteArray, vol: Float): ByteArray {
        if (vol <= 0f) return ByteArray(pcmBytes.size)
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val sampleCount = pcmBytes.size / 2
        val outBytes = ByteArray(pcmBytes.size)
        val outBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until sampleCount) {
            val sample = shortBuffer.get(i)
            val scaled = (sample * vol).toInt().coerceIn(-32768, 32767).toShort()
            outBuffer.put(scaled)
        }
        return outBytes
    }

    private fun applyVolume() {
        val target = if (isMuted) 0f else volume
        try {
            audioTrack?.setVolume(target)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting track volume", e)
        }
    }

    fun release() {
        try {
            isPlaying.set(false)
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        } finally {
            audioTrack = null
        }
    }
}
