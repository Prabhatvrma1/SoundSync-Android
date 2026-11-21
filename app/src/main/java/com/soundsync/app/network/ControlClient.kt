package com.soundsync.app.network

import android.util.Log
import com.soundsync.app.audio.playback.AudioTrackPlayer
import com.soundsync.app.audio.sync.ClockSynchronizer
import com.soundsync.app.audio.sync.SyncEngine
import com.soundsync.app.data.model.ControlMessage
import com.soundsync.app.data.model.StreamQualityPreset
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * TCP Control Client on Slave phone.
 * Connects to Master phone, runs NTP clock synchronization, and executes playback commands.
 */
class ControlClient(
    val slaveId: String,
    val slaveName: String,
    val clockSynchronizer: ClockSynchronizer,
    val syncEngine: SyncEngine,
    val player: AudioTrackPlayer
) {
    companion object {
        private const val TAG = "ControlClient"
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        REJECTED,
        ERROR
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _masterName = MutableStateFlow<String?>("Master")
    val masterName: StateFlow<String?> = _masterName.asStateFlow()

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var listenJob: Job? = null
    private var ntpJob: Job? = null
    private var telemetryJob: Job? = null

    private var isExplicitDisconnect = false
    private var reconnectAttempts = 0

    fun connect(masterIp: String, port: Int = 8889, pin: String? = null, scope: CoroutineScope) {
        isExplicitDisconnect = false
        reconnectAttempts = 0
        connectInternal(masterIp, port, pin, scope)
    }

    private fun connectInternal(masterIp: String, port: Int, pin: String?, scope: CoroutineScope) {
        disconnectInternal(keepConnectingState = true)

        _connectionState.value = ConnectionState.CONNECTING
        StreamStateHolder.slaveConnectionState.value = ConnectionState.CONNECTING

        listenJob = scope.launch(Dispatchers.IO) {
            try {
                // 4.5 second connect timeout prevents hanging when connecting to invalid/unreachable IP
                val sock = Socket()
                sock.connect(java.net.InetSocketAddress(masterIp, port), 4500)
                socket = sock
                reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                writer = PrintWriter(sock.getOutputStream(), true)

                // Send Handshake
                val handshake = ControlMessage(
                    type = ControlMessage.Type.HANDSHAKE,
                    slaveId = slaveId,
                    slaveName = slaveName,
                    pin = pin
                )
                writer?.println(handshake.toJson())
                writer?.flush()
                reconnectAttempts = 0 // Reset on successful socket connection

                while (isActive) {
                    val line = reader?.readLine() ?: break
                    val msg = ControlMessage.fromJson(line) ?: continue
                    handleMessage(msg, scope)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "Connection event to $masterIp: ${e.message}")
                }
            } finally {
                disconnectInternal()
                // Auto-reconnect if dropped unexpectedly (e.g. Wi-Fi blip or router channel change)
                if (!isExplicitDisconnect && reconnectAttempts < 3 && isActive) {
                    reconnectAttempts++
                    Log.d(TAG, "Attempting auto-reconnect #$reconnectAttempts in 2s...")
                    _connectionState.value = ConnectionState.CONNECTING
                    StreamStateHolder.slaveConnectionState.value = ConnectionState.CONNECTING
                    delay(2000)
                    if (!isExplicitDisconnect && isActive) {
                        connectInternal(masterIp, port, pin, scope)
                    }
                } else if (!isExplicitDisconnect) {
                    _connectionState.value = ConnectionState.ERROR
                    StreamStateHolder.slaveConnectionState.value = ConnectionState.ERROR
                }
            }
        }
    }

    private fun handleMessage(msg: ControlMessage, scope: CoroutineScope) {
        when (msg.type) {
            ControlMessage.Type.HANDSHAKE_ACK -> {
                Log.d(TAG, "HANDSHAKE_ACK from master: ${msg.masterName}")
                _connectionState.value = ConnectionState.CONNECTED
                StreamStateHolder.slaveConnectionState.value = ConnectionState.CONNECTED
                val name = msg.masterName ?: "Master Phone"
                _masterName.value = name
                StreamStateHolder.connectedMasterName.value = name
                startNtpSync(scope)
                startTelemetry(scope)
                syncEngine.arm(scope, StreamStateHolder.currentPreset.value)
            }

            ControlMessage.Type.HANDSHAKE_REJECT -> {
                Log.w(TAG, "HANDSHAKE_REJECT: ${msg.errorMessage}")
                _connectionState.value = ConnectionState.REJECTED
                StreamStateHolder.slaveConnectionState.value = ConnectionState.REJECTED
                disconnectInternal()
            }

            ControlMessage.Type.NTP_PONG -> {
                val t0 = msg.originTimeNs ?: 0L
                val t1 = msg.receiveTimeNs ?: 0L
                val t2 = msg.transmitTimeNs ?: 0L
                val t3 = System.nanoTime()
                clockSynchronizer.addSample(t0, t1, t2, t3)
            }

            ControlMessage.Type.START_STREAM -> {
                val startMasterTime = msg.scheduledStartMasterTimeNs ?: (System.nanoTime() + 200_000_000L)
                val preset = msg.preset ?: StreamQualityPreset.BALANCED
                syncEngine.scheduleStart(startMasterTime, preset, scope)
            }

            ControlMessage.Type.PAUSE_STREAM -> {
                player.pause()
            }

            ControlMessage.Type.RESUME_STREAM -> {
                player.play()
            }

            ControlMessage.Type.STOP_STREAM -> {
                syncEngine.stop()
            }

            ControlMessage.Type.SET_VOLUME -> {
                msg.volume?.let { player.volume = it }
            }

            ControlMessage.Type.SET_MUTE -> {
                msg.isMuted?.let { player.isMuted = it }
            }

            else -> {}
        }
    }

    private fun startNtpSync(scope: CoroutineScope) {
        ntpJob?.cancel()
        ntpJob = scope.launch(Dispatchers.IO) {
            repeat(8) {
                sendPing()
                delay(25)
            }
            while (isActive) {
                sendPing()
                delay(1000)
            }
        }
    }

    private fun sendPing() {
        val t0 = System.nanoTime()
        val ping = ControlMessage(type = ControlMessage.Type.NTP_PING, originTimeNs = t0)
        writer?.println(ping.toJson())
        writer?.flush()
    }

    private fun startTelemetry(scope: CoroutineScope) {
        telemetryJob?.cancel()
        telemetryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000)
                val metrics = syncEngine.syncMetrics.value
                val telemetry = ControlMessage(
                    type = ControlMessage.Type.TELEMETRY,
                    slaveId = slaveId,
                    rttMs = metrics.rttMs,
                    clockOffsetMs = metrics.clockOffsetMs,
                    bufferHealthMs = metrics.bufferHealthMs,
                    underruns = metrics.underrunCount,
                    packetLossPct = metrics.packetLossPct
                )
                writer?.println(telemetry.toJson())
            }
        }
    }

    fun disconnect() {
        isExplicitDisconnect = true
        disconnectInternal()
    }

    private fun disconnectInternal(keepConnectingState: Boolean = false) {
        ntpJob?.cancel()
        ntpJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        listenJob?.cancel()
        listenJob = null
        syncEngine.stop()
        clockSynchronizer.reset()

        try {
            reader?.close()
            writer?.close()
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        socket = null
        if (!keepConnectingState && _connectionState.value != ConnectionState.REJECTED) {
            _connectionState.value = ConnectionState.DISCONNECTED
            StreamStateHolder.slaveConnectionState.value = ConnectionState.DISCONNECTED
        }
    }
}
