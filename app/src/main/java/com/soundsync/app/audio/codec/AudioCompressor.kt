package com.soundsync.app.audio.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance IMA-ADPCM (Adaptive Differential Pulse Code Modulation) audio compressor.
 * Compresses 16-bit linear PCM to 4-bit samples (4:1 compression ratio) with ZERO algorithmic delay,
 * reducing 48kHz stereo bandwidth from 1.54 Mbps to ~384 kbps over wireless network.
 */
object AudioCompressor {

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    /**
     * Encodes 16-bit PCM byte array to ADPCM byte array.
     * For stereo audio (channels = 2), left and right channels are interleaved.
     */
    fun encode(pcmBytes: ByteArray, channels: Int = 2): ByteArray {
        val sampleCount = pcmBytes.size / 2
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(sampleCount)
        shortBuffer.get(pcm)

        // 4 header bytes per channel for state (predictor short + index byte + reserved byte)
        val headerSize = channels * 4
        val compressedDataSize = (sampleCount + 1) / 2
        val output = ByteArray(headerSize + compressedDataSize)
        val outBuffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)

        if (channels == 1) {
            var predicted = pcm[0].toInt()
            var index = 0
            outBuffer.putShort(predicted.toShort())
            outBuffer.put(index.toByte())
            outBuffer.put(0.toByte())

            var outIdx = headerSize
            var i = 0
            while (i < sampleCount) {
                val sample1 = pcm[i].toInt()
                val (delta1, newPred1, newIdx1) = encodeSample(sample1, predicted, index)
                predicted = newPred1
                index = newIdx1
                i++

                val delta2: Int
                if (i < sampleCount) {
                    val sample2 = pcm[i].toInt()
                    val (d2, newPred2, newIdx2) = encodeSample(sample2, predicted, index)
                    predicted = newPred2
                    index = newIdx2
                    delta2 = d2
                    i++
                } else {
                    delta2 = 0
                }

                output[outIdx++] = ((delta2 shl 4) or (delta1 and 0x0F)).toByte()
            }
        } else {
            // Stereo: Left and Right channels interleaved
            var predL = pcm[0].toInt()
            var indexL = 0
            var predR = if (sampleCount > 1) pcm[1].toInt() else 0
            var indexR = 0

            outBuffer.putShort(predL.toShort())
            outBuffer.put(indexL.toByte())
            outBuffer.put(0.toByte())
            outBuffer.putShort(predR.toShort())
            outBuffer.put(indexR.toByte())
            outBuffer.put(0.toByte())

            var outIdx = headerSize
            var i = 0
            while (i < sampleCount) {
                // Left sample
                val sampleL = pcm[i].toInt()
                val (deltaL, npL, niL) = encodeSample(sampleL, predL, indexL)
                predL = npL
                indexL = niL
                i++

                // Right sample
                val sampleR = if (i < sampleCount) pcm[i].toInt() else 0
                val (deltaR, npR, niR) = encodeSample(sampleR, predR, indexR)
                predR = npR
                indexR = niR
                i++

                // Pack both 4-bit nibbles into 1 byte (L in lower nibble, R in upper nibble)
                output[outIdx++] = ((deltaR shl 4) or (deltaL and 0x0F)).toByte()
            }
        }

        return output
    }

    /**
     * Decodes ADPCM byte array back to 16-bit linear PCM byte array.
     */
    fun decode(adpcmBytes: ByteArray, channels: Int = 2): ByteArray {
        val headerSize = channels * 4
        if (adpcmBytes.size < headerSize) return ByteArray(0)

        val inBuffer = ByteBuffer.wrap(adpcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        if (channels == 1) {
            var predicted = inBuffer.getShort().toInt()
            var index = inBuffer.get().toInt() and 0xFF
            inBuffer.get() // Skip reserved

            val dataSize = adpcmBytes.size - headerSize
            val sampleCount = dataSize * 2
            val pcmOut = ShortArray(sampleCount)
            var sampleIdx = 0

            for (i in headerSize until adpcmBytes.size) {
                val b = adpcmBytes[i].toInt() and 0xFF
                val delta1 = b and 0x0F
                val (newPred1, newIdx1) = decodeSample(delta1, predicted, index)
                predicted = newPred1
                index = newIdx1
                pcmOut[sampleIdx++] = predicted.toShort()

                val delta2 = (b shr 4) and 0x0F
                val (newPred2, newIdx2) = decodeSample(delta2, predicted, index)
                predicted = newPred2
                index = newIdx2
                pcmOut[sampleIdx++] = predicted.toShort()
            }

            val outBytes = ByteArray(sampleIdx * 2)
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmOut, 0, sampleIdx)
            return outBytes
        } else {
            var predL = inBuffer.getShort().toInt()
            var indexL = inBuffer.get().toInt() and 0xFF
            inBuffer.get()
            var predR = inBuffer.getShort().toInt()
            var indexR = inBuffer.get().toInt() and 0xFF
            inBuffer.get()

            val dataSize = adpcmBytes.size - headerSize
            val sampleCount = dataSize * 2
            val pcmOut = ShortArray(sampleCount)
            var sampleIdx = 0

            for (i in headerSize until adpcmBytes.size) {
                val b = adpcmBytes[i].toInt() and 0xFF
                val deltaL = b and 0x0F
                val deltaR = (b shr 4) and 0x0F

                val (newPredL, newIdxL) = decodeSample(deltaL, predL, indexL)
                predL = newPredL
                indexL = newIdxL
                pcmOut[sampleIdx++] = predL.toShort()

                val (newPredR, newIdxR) = decodeSample(deltaR, predR, indexR)
                predR = newPredR
                indexR = newIdxR
                pcmOut[sampleIdx++] = predR.toShort()
            }

            val outBytes = ByteArray(sampleIdx * 2)
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmOut, 0, sampleIdx)
            return outBytes
        }
    }

    private data class EncodeResult(val delta: Int, val newPredicted: Int, val newIndex: Int)
    private data class DecodeResult(val newPredicted: Int, val newIndex: Int)

    private fun encodeSample(sample: Int, predicted: Int, index: Int): EncodeResult {
        var diff = sample - predicted
        var sign = 0
        if (diff < 0) {
            sign = 8
            diff = -diff
        }

        val step = STEP_TABLE[index]
        var delta = 0
        var vpdiff = step shr 3

        if (diff >= step) {
            delta = delta or 4
            diff -= step
            vpdiff += step
        }
        val step2 = step shr 1
        if (diff >= step2) {
            delta = delta or 2
            diff -= step2
            vpdiff += step2
        }
        val step4 = step shr 2
        if (diff >= step4) {
            delta = delta or 1
            vpdiff += step4
        }

        var newPredicted = if (sign != 0) predicted - vpdiff else predicted + vpdiff
        newPredicted = clampShort(newPredicted)

        var newIndex = index + INDEX_TABLE[delta]
        if (newIndex < 0) newIndex = 0
        if (newIndex > 88) newIndex = 88

        delta = delta or sign
        return EncodeResult(delta, newPredicted, newIndex)
    }

    private fun decodeSample(delta: Int, predicted: Int, index: Int): DecodeResult {
        val step = STEP_TABLE[index]
        val sign = delta and 8
        val mag = delta and 7

        var vpdiff = step shr 3
        if ((mag and 4) != 0) vpdiff += step
        if ((mag and 2) != 0) vpdiff += step shr 1
        if ((mag and 1) != 0) vpdiff += step shr 2

        var newPredicted = if (sign != 0) predicted - vpdiff else predicted + vpdiff
        newPredicted = clampShort(newPredicted)

        var newIndex = index + INDEX_TABLE[mag]
        if (newIndex < 0) newIndex = 0
        if (newIndex > 88) newIndex = 88

        return DecodeResult(newPredicted, newIndex)
    }

    private fun clampShort(value: Int): Int {
        return when {
            value > 32767 -> 32767
            value < -32768 -> -32768
            else -> value
        }
    }
}
