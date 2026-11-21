package com.soundsync.app.network

import android.util.Log
import com.soundsync.app.data.model.AudioPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * UDP socket receiver for incoming audio packets on the Slave device.
 */
class AudioUdpReceiver(
    val port: Int = 8890,
    private val onPacketReceived: (AudioPacket) -> Unit
) {
    companion object {
        private const val TAG = "AudioUdpReceiver"
    }

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null

    fun start(scope: CoroutineScope) {
        stop()

        receiveJob = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(port)
                socket = sock
                val buffer = ByteArray(65535)

                while (isActive) {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    sock.receive(datagram)

                    val packet = AudioPacket.deserialize(datagram.data, datagram.length)
                    if (packet != null) {
                        onPacketReceived(packet)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "UDP receive error", e)
                }
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
    }
}
