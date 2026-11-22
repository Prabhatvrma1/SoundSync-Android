package com.soundsync.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap
import com.soundsync.app.service.StreamStateHolder

data class DiscoveredMaster(
    val deviceName: String,
    val ipAddress: String,
    val controlPort: Int = 8889,
    val receiverCount: Int = 0,
    val isStreaming: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis()
)

/**
 * Handles device discovery via dual mDNS / NSD and UDP broadcast beacon fallback.
 */
class DiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryManager"
        const val SERVICE_TYPE = "_soundsync._tcp"
        const val BEACON_PORT = 8888
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private val gson = Gson()

    private val _discoveredMasters = MutableStateFlow<List<DiscoveredMaster>>(emptyList())
    val discoveredMasters: StateFlow<List<DiscoveredMaster>> = _discoveredMasters.asStateFlow()

    private val masterMap = ConcurrentHashMap<String, DiscoveredMaster>()

    private var beaconBroadcastJob: Job? = null
    private var beaconListenJob: Job? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    fun startAdvertising(
        deviceName: String,
        controlPort: Int = 8889,
        receiverCountSupplier: () -> Int,
        isStreamingSupplier: () -> Boolean,
        scope: CoroutineScope
    ) {
        stopAdvertising()

        // 1. Register NSD Service
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            port = controlPort
        }

        nsdRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service registered: ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service unregistered")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering NSD", e)
        }

        // 2. Broadcast UDP Beacon fallback
        beaconBroadcastJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val broadcastAddr = NetworkUtils.getBroadcastAddress()

                while (isActive) {
                    val localIp = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
                    val beacon = DiscoveredMaster(
                        deviceName = deviceName,
                        ipAddress = localIp,
                        controlPort = controlPort,
                        receiverCount = receiverCountSupplier(),
                        isStreaming = isStreamingSupplier()
                    )
                    val json = gson.toJson(beacon)
                    val bytes = json.toByteArray()
                    try {
                        socket.send(DatagramPacket(bytes, bytes.size, broadcastAddr, BEACON_PORT))
                    } catch (_: Exception) {}

                    // Also broadcast to 255.255.255.255 for broader receiver compatibility
                    if (broadcastAddr.hostAddress != "255.255.255.255") {
                        try {
                            val globalBroadcast = java.net.InetAddress.getByName("255.255.255.255")
                            socket.send(DatagramPacket(bytes, bytes.size, globalBroadcast, BEACON_PORT))
                        } catch (_: Exception) {}
                    }
                    delay(1500)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "UDP Beacon broadcast error", e)
                }
            } finally {
                socket?.close()
            }
        }
    }

    fun stopAdvertising() {
        beaconBroadcastJob?.cancel()
        beaconBroadcastJob = null
        try {
            nsdRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering NSD", e)
        }
        nsdRegistrationListener = null
    }

    fun startDiscovery(scope: CoroutineScope) {
        stopDiscovery()
        masterMap.clear()
        _discoveredMasters.value = emptyList()

        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("SoundSyncMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring MulticastLock", e)
        }

        // 1. Listen for UDP Beacons
        beaconListenJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(BEACON_PORT)
                socket.broadcast = true
                val buffer = ByteArray(2048)

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val json = String(packet.data, 0, packet.length)
                    try {
                        val beacon = gson.fromJson(json, DiscoveredMaster::class.java)
                        if (beacon != null && beacon.ipAddress.isNotEmpty()) {
                            masterMap[beacon.ipAddress] = beacon.copy(lastSeenMs = System.currentTimeMillis())
                            updateMasterList()
                        }
                    } catch (e: Exception) {
                        // ignore malformed beacon
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "UDP Beacon listen error", e)
                }
            } finally {
                socket?.close()
            }
        }

        // Clean stale devices every 3 seconds
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(3000)
                val now = System.currentTimeMillis()
                val removed = masterMap.entries.removeIf { now - it.value.lastSeenMs > 6000 }
                if (removed) {
                    updateMasterList()
                }
            }
        }
    }

    private fun updateMasterList() {
        val list = masterMap.values.toList().sortedBy { it.deviceName }
        _discoveredMasters.value = list
        StreamStateHolder.discoveredMasters.value = list
    }

    fun stopDiscovery() {
        beaconListenJob?.cancel()
        beaconListenJob = null
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MulticastLock", e)
        }
        masterMap.clear()
        _discoveredMasters.value = emptyList()
        StreamStateHolder.discoveredMasters.value = emptyList()
    }
}
