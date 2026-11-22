package com.soundsync.app.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Robust network utilities designed for multi-phone audio streaming.
 * Handles Wi-Fi client, Wi-Fi SoftAP / Hotspot, Wi-Fi Direct (P2P),
 * and Type-C USB tethering with strict interface priority ordering.
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * Resolves the primary local IPv4 address for SoundSync streaming.
     * Strictly prioritizes Wi-Fi and Hotspot interfaces over cellular (rmnet/ccmni) or VPN/virtual interfaces.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null

            // Helper to check if an address is a valid non-loopback private IPv4
            fun isPrivateIpv4(addr: InetAddress): Boolean {
                if (addr.isLoopbackAddress || addr !is Inet4Address) return false
                val host = addr.hostAddress ?: return false
                return host.startsWith("192.168.") ||
                        host.startsWith("10.") ||
                        host.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
            }

            // Priority 1: Wi-Fi client & Hotspot SoftAP interfaces (wlan, ap, softap, swlan, p2p)
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap") ||
                    name.startsWith("swlan") || name.startsWith("p2p")) {
                    for (addr in intf.inetAddresses) {
                        if (isPrivateIpv4(addr)) {
                            Log.d(TAG, "Selected Wi-Fi/Hotspot IP: ${addr.hostAddress} on ${intf.name}")
                            return addr.hostAddress
                        }
                    }
                }
            }

            // Priority 2: Real USB Tethering interfaces (rndis, usb, ncm)
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")) {
                    for (addr in intf.inetAddresses) {
                        if (isPrivateIpv4(addr)) {
                            Log.d(TAG, "Selected USB Tether IP: ${addr.hostAddress} on ${intf.name}")
                            return addr.hostAddress
                        }
                    }
                }
            }

            // Priority 3: Ethernet interfaces (eth0 on emulators or USB-to-Ethernet dongles)
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("eth")) {
                    for (addr in intf.inetAddresses) {
                        if (isPrivateIpv4(addr)) {
                            return addr.hostAddress
                        }
                    }
                }
            }

            // Priority 4: Any other private IPv4 (excluding cellular rmnet/ccmni/pdp and vpn tun/tap)
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ||
                    name.startsWith("tun") || name.startsWith("dummy")) {
                    continue
                }
                for (addr in intf.inetAddresses) {
                    if (isPrivateIpv4(addr)) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating local IP address", e)
        }
        return null
    }

    /**
     * Resolves the broadcast address for UDP beacon announcement.
     * Prioritizes the active Wi-Fi interface broadcast address so packets reach receivers.
     */
    fun getBroadcastAddress(): InetAddress {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()

            // Prioritize Wi-Fi / Hotspot interface broadcast
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap")) {
                    for (interfaceAddress in intf.interfaceAddresses) {
                        val broadcast = interfaceAddress.broadcast
                        if (broadcast != null && broadcast is Inet4Address) {
                            return broadcast
                        }
                    }
                }
            }

            // Fallback: any interface broadcast
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (interfaceAddress in intf.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && broadcast is Inet4Address) {
                        return broadcast
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding broadcast address", e)
        }
        return InetAddress.getByName("255.255.255.255")
    }

    /**
     * Resolves the Wi-Fi / Mobile Hotspot default gateway IP (Leader phone's IP).
     * Guaranteed never to return a cellular mobile data gateway.
     */
    fun getActiveGatewayIp(context: Context): String = getGatewayIp(context)

    fun getGatewayIp(context: Context): String {
        // Step 1: Query WifiManager DHCP Info (most reliable on Android for Wi-Fi/Hotspot)
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val ip = dhcp.gateway
                val gw = String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
                if (gw != "0.0.0.0" && (gw.startsWith("192.168.") || gw.startsWith("10.") || gw.startsWith("172."))) {
                    Log.d(TAG, "Found Wi-Fi DHCP Gateway: $gw")
                    return gw
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager DHCP query failed: ${e.message}")
        }

        // Step 2: Query ConnectivityManager specifically for TRANSPORT_WIFI (avoids cellular carrier gateway)
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val linkProps = cm.getLinkProperties(network) ?: continue
                        for (route in linkProps.routes) {
                            val gw = route.gateway
                            if (gw is Inet4Address && !gw.isAnyLocalAddress) {
                                val host = gw.hostAddress
                                if (!host.isNullOrEmpty() && host != "0.0.0.0") {
                                    Log.d(TAG, "Found Wi-Fi Route Gateway: $host")
                                    return host
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ConnectivityManager Wi-Fi route query failed: ${e.message}")
        }

        // Step 3: Check if connected to an Android hotspot (standard AOSP/Samsung hotspot gateway is 192.168.43.1)
        val localIp = getLocalIpAddress()
        if (localIp != null && localIp.startsWith("192.168.43.")) {
            return "192.168.43.1"
        }

        // Step 4: Fallback to standard Android Hotspot Gateway
        return "192.168.43.1"
    }

    /**
     * Checks if a genuine Type-C USB tethered network interface is currently active (rndis, usb, ncm).
     * Does NOT match generic Wi-Fi or cellular interfaces.
     */
    fun isUsbTetheringActive(): Boolean {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return false
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking USB tethering interfaces: ${e.message}")
        }
        return false
    }

    /**
     * Detects or computes the USB tethering gateway IP.
     * Standard AOSP USB tether gateway: 192.168.42.129 (or 192.168.42.1)
     * Samsung One UI USB tether gateway: 192.168.45.1 (or 192.168.43.1)
     */
    fun getUsbGatewayIp(context: Context): String {
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    val ifaceName = (linkProps.interfaceName ?: "").lowercase()
                    if (ifaceName.startsWith("rndis") || ifaceName.startsWith("usb") || ifaceName.startsWith("ncm")) {
                        for (route in linkProps.routes) {
                            val gw = route.gateway
                            if (gw is Inet4Address && !gw.isAnyLocalAddress) {
                                val host = gw.hostAddress
                                if (!host.isNullOrEmpty() && host != "0.0.0.0") {
                                    return host
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying USB routes: ${e.message}")
        }

        // Infer gateway from local USB interface IP address
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                if (parts[2] == "42") {
                                    return if (parts[3] == "129") "192.168.42.1" else "192.168.42.129"
                                }
                                if (parts[2] == "45") {
                                    return if (parts[3] == "1") "192.168.45.2" else "192.168.45.1"
                                }
                                return "${parts[0]}.${parts[1]}.${parts[2]}.1"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deriving USB gateway: ${e.message}")
        }

        return "192.168.42.129"
    }

    /**
     * Returns descriptive information about active USB network interfaces.
     */
    fun getUsbInterfaceDetails(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return "${intf.name} (${addr.hostAddress})"
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "No USB tethering active"
    }

    /**
     * Safely opens the device's Tethering / Hotspot settings screen with fallbacks.
     */
    fun openTetheringSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // Continue to next fallback
            }
        }
    }
}
