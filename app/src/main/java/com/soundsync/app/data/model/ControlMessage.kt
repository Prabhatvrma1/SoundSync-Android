package com.soundsync.app.data.model

import com.google.gson.Gson

/**
 * JSON control messages for TCP connection (port 8889).
 */
data class ControlMessage(
    val type: Type,
    val slaveId: String? = null,
    val slaveName: String? = null,
    val pin: String? = null,
    val masterName: String? = null,
    val sessionId: String? = null,
    val preset: StreamQualityPreset? = null,
    val scheduledStartMasterTimeNs: Long? = null,
    val scheduledResumeMasterTimeNs: Long? = null,
    val streamId: Long? = null,
    val originTimeNs: Long? = null,    // t0 (slave ping send time)
    val receiveTimeNs: Long? = null,   // t1 (master receive time)
    val transmitTimeNs: Long? = null,  // t2 (master pong send time)
    val volume: Float? = null,
    val isMuted: Boolean? = null,
    val rttMs: Float? = null,
    val clockOffsetMs: Float? = null,
    val bufferHealthMs: Int? = null,
    val underruns: Int? = null,
    val packetLossPct: Float? = null,
    val errorMessage: String? = null
) {
    enum class Type {
        HANDSHAKE,
        HANDSHAKE_ACK,
        HANDSHAKE_REJECT,
        NTP_PING,
        NTP_PONG,
        START_STREAM,
        PAUSE_STREAM,
        RESUME_STREAM,
        STOP_STREAM,
        SET_VOLUME,
        SET_MUTE,
        TELEMETRY,
        DISCONNECT
    }

    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()
        fun fromJson(json: String): ControlMessage? {
            return try {
                gson.fromJson(json, ControlMessage::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
