package com.soundsync.app.network

import android.util.Log
import com.soundsync.app.data.model.ControlMessage
import com.soundsync.app.data.model.SlaveDevice
import com.soundsync.app.data.model.StreamQualityPreset
import com.soundsync.app.service.StreamStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP Control Server on Master phone (port 8889).
 * Manages slave connections, PIN authentication, NTP ping/pong, and playback commands.
 */
class ControlServer(
    val port: Int = 8889,
    val masterName: String = "Master Phone",
    val requiredPin: String? = null
) {
    companion object {
        private const val TAG = "ControlServer"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clientHandlers = ConcurrentHashMap<String, ClientHandler>()

    private val _connectedSlaves = MutableStateFlow<List<SlaveDevice>>(emptyList())
    val connectedSlaves: StateFlow<List<SlaveDevice>> = _connectedSlaves.asStateFlow()

    fun start(scope: CoroutineScope, onSlaveConnected: (SlaveDevice) -> Unit, onSlaveDisconnected: (String) -> Unit) {
        stop()

        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(port)
                serverSocket = server

                while (isActive) {
                    val socket = server.accept()
                    val handler = ClientHandler(socket, scope, onSlaveConnected, onSlaveDisconnected)
                    handler.start()
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "ControlServer accept error", e)
                }
            }
        }
    }

    fun broadcastStartStream(leadTimeMs: Int = 250, preset: StreamQualityPreset = StreamQualityPreset.BALANCED) {
        val targetStartMasterTimeNs = System.nanoTime() + (leadTimeMs * 1_000_000L)
        val msg = ControlMessage(
            type = ControlMessage.Type.START_STREAM,
            scheduledStartMasterTimeNs = targetStartMasterTimeNs,
            preset = preset
        )
        broadcast(msg)
    }

    fun broadcastPauseStream() {
        broadcast(ControlMessage(type = ControlMessage.Type.PAUSE_STREAM))
    }

    fun broadcastResumeStream(leadTimeMs: Int = 200) {
        val targetResumeMasterTimeNs = System.nanoTime() + (leadTimeMs * 1_000_000L)
        broadcast(
            ControlMessage(
                type = ControlMessage.Type.RESUME_STREAM,
                scheduledResumeMasterTimeNs = targetResumeMasterTimeNs
            )
        )
    }

    fun broadcastStopStream() {
        broadcast(ControlMessage(type = ControlMessage.Type.STOP_STREAM))
    }

    fun setSlaveVolume(slaveId: String, volume: Float) {
        clientHandlers[slaveId]?.sendMessage(
            ControlMessage(type = ControlMessage.Type.SET_VOLUME, volume = volume)
        )
        updateSlaveState(slaveId) { it.copy(volume = volume) }
    }

    fun setSlaveMute(slaveId: String, isMuted: Boolean) {
        clientHandlers[slaveId]?.sendMessage(
            ControlMessage(type = ControlMessage.Type.SET_MUTE, isMuted = isMuted)
        )
        updateSlaveState(slaveId) { it.copy(isMuted = isMuted) }
    }

    fun muteAll(isMuted: Boolean) {
        broadcast(ControlMessage(type = ControlMessage.Type.SET_MUTE, isMuted = isMuted))
        _connectedSlaves.value = _connectedSlaves.value.map { it.copy(isMuted = isMuted) }
    }

    fun disconnectSlave(slaveId: String) {
        clientHandlers[slaveId]?.disconnect()
    }

    private fun broadcast(message: ControlMessage) {
        for (handler in clientHandlers.values) {
            handler.sendMessage(message)
        }
    }

    private fun updateSlaveState(slaveId: String, transform: (SlaveDevice) -> SlaveDevice) {
        val list = _connectedSlaves.value.toMutableList()
        val index = list.indexOfFirst { it.id == slaveId }
        if (index != -1) {
            list[index] = transform(list[index])
            _connectedSlaves.value = list
            StreamStateHolder.connectedSlaves.value = list
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        for (handler in clientHandlers.values) {
            handler.disconnect()
        }
        clientHandlers.clear()
        _connectedSlaves.value = emptyList()
        StreamStateHolder.connectedSlaves.value = emptyList()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
    }

    inner class ClientHandler(
        private val socket: Socket,
        private val scope: CoroutineScope,
        private val onSlaveConnected: (SlaveDevice) -> Unit,
        private val onSlaveDisconnected: (String) -> Unit
    ) {
        private var reader: BufferedReader? = null
        private var writer: PrintWriter? = null
        private var slaveId: String = ""
        private var job: Job? = null

        fun start() {
            job = scope.launch(Dispatchers.IO) {
                try {
                    reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    writer = PrintWriter(socket.getOutputStream(), true)

                    while (isActive) {
                        val line = reader?.readLine() ?: break
                        val msg = ControlMessage.fromJson(line) ?: continue
                        handleMessage(msg)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "ClientHandler socket closed", e)
                } finally {
                    cleanup()
                }
            }
        }

        private fun handleMessage(msg: ControlMessage) {
            when (msg.type) {
                ControlMessage.Type.HANDSHAKE -> {
                    val id = msg.slaveId ?: (socket.inetAddress?.hostAddress ?: "127.0.0.1")
                    val name = msg.slaveName ?: "Unknown Receiver"
                    val pin = msg.pin

                    // Validate PIN if required
                    if (requiredPin != null && pin != requiredPin) {
                        sendMessage(
                            ControlMessage(
                                type = ControlMessage.Type.HANDSHAKE_REJECT,
                                errorMessage = "Invalid PIN"
                            )
                        )
                        disconnect()
                        return
                    }

                    slaveId = id
                    clientHandlers[id] = this
                    val device = SlaveDevice(
                        id = id,
                        name = name,
                        ipAddress = socket.inetAddress?.hostAddress ?: "127.0.0.1"
                    )
                    _connectedSlaves.value = _connectedSlaves.value.filter { it.id != id } + device
                    StreamStateHolder.connectedSlaves.value = _connectedSlaves.value
                    onSlaveConnected(device)

                    sendMessage(
                        ControlMessage(
                            type = ControlMessage.Type.HANDSHAKE_ACK,
                            masterName = masterName
                        )
                    )

                    // If Master is already streaming, inform the new slave immediately
                    if (StreamStateHolder.isStreaming.value) {
                        val activePreset = StreamStateHolder.currentPreset.value
                        sendMessage(
                            ControlMessage(
                                type = ControlMessage.Type.START_STREAM,
                                scheduledStartMasterTimeNs = System.nanoTime() + (activePreset.bufferTargetMs * 1_000_000L),
                                preset = activePreset
                            )
                        )
                    }
                }

                ControlMessage.Type.NTP_PING -> {
                    // Record exact receive and transmit timestamps with zero coroutine dispatch latency
                    val t1 = System.nanoTime()
                    val t0 = msg.originTimeNs ?: 0L
                    val t2 = System.nanoTime()
                    sendMessageDirect(
                        ControlMessage(
                            type = ControlMessage.Type.NTP_PONG,
                            originTimeNs = t0,
                            receiveTimeNs = t1,
                            transmitTimeNs = t2
                        )
                    )
                }

                ControlMessage.Type.TELEMETRY -> {
                    updateSlaveState(slaveId) { old ->
                        old.copy(
                            rttMs = msg.rttMs ?: old.rttMs,
                            clockOffsetNs = ((msg.clockOffsetMs ?: 0f) * 1_000_000L).toLong(),
                            bufferHealthMs = msg.bufferHealthMs ?: old.bufferHealthMs,
                            underruns = msg.underruns ?: old.underruns,
                            packetLossPct = msg.packetLossPct ?: old.packetLossPct,
                            lastSeenMs = System.currentTimeMillis()
                        )
                    }
                }

                ControlMessage.Type.DISCONNECT -> {
                    disconnect()
                }

                else -> {}
            }
        }

        fun sendMessageDirect(msg: ControlMessage) {
            try {
                writer?.println(msg.toJson())
                writer?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error direct sending to $slaveId", e)
            }
        }

        fun sendMessage(msg: ControlMessage) {
            scope.launch(Dispatchers.IO) {
                try {
                    writer?.println(msg.toJson())
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending to $slaveId", e)
                }
            }
        }

        fun disconnect() {
            job?.cancel()
            cleanup()
        }

        private fun cleanup() {
            if (slaveId.isNotEmpty()) {
                clientHandlers.remove(slaveId)
                _connectedSlaves.value = _connectedSlaves.value.filter { it.id != slaveId }
                StreamStateHolder.connectedSlaves.value = _connectedSlaves.value
                onSlaveDisconnected(slaveId)
            }
            try {
                reader?.close()
                writer?.close()
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
