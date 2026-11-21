package com.soundsync.app.network

import android.util.Log
import com.soundsync.app.data.model.AudioPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-performance UDP socket sender for streaming audio packets to connected slaves.
 */
class AudioUdpSender(val port: Int = 8890) {

    companion object {
        private const val TAG = "AudioUdpSender"
    }

    private var socket: DatagramSocket? = null
    private val slaveEndpoints = CopyOnWriteArrayList<InetSocketAddress>()

    fun start() {
        try {
            socket?.close()
            socket = DatagramSocket()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Audio UDP socket", e)
        }
    }

    fun addReceiver(ip: String, port: Int = 8890) {
        try {
            val address = InetSocketAddress(ip, port)
            if (!slaveEndpoints.contains(address)) {
                slaveEndpoints.add(address)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid receiver address $ip:$port", e)
        }
    }

    fun removeReceiver(ip: String) {
        try {
            slaveEndpoints.removeIf { it.address?.hostAddress == ip || it.hostString == ip }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing receiver $ip", e)
        }
    }

    fun clearReceivers() {
        slaveEndpoints.clear()
    }

    fun getReceiverCount(): Int = slaveEndpoints.size

    suspend fun sendPacket(packet: AudioPacket) = withContext(Dispatchers.IO) {
        val sock = socket ?: return@withContext
        if (slaveEndpoints.isEmpty()) return@withContext

        val bytes = AudioPacket.serialize(packet)

        for (endpoint in slaveEndpoints) {
            try {
                val datagram = DatagramPacket(bytes, bytes.size, endpoint)
                sock.send(datagram)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet to $endpoint", e)
            }
        }
    }

    fun close() {
        slaveEndpoints.clear()
        socket?.close()
        socket = null
    }
}
