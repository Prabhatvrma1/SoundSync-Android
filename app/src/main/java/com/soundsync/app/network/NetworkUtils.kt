package com.soundsync.app.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    private const val TAG = "NetworkUtils"

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getBroadcastAddress(): InetAddress {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return InetAddress.getByName("255.255.255.255")
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
            e.printStackTrace()
        }
        return InetAddress.getByName("255.255.255.255")
    }

    /**
     * Resolves the active network's default gateway IP across Wi-Fi, Hotspot, and USB tethering interfaces.
     */
    fun getActiveGatewayIp(context: Context): String {
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val activeNetwork = cm.activeNetwork
                val linkProperties = cm.getLinkProperties(activeNetwork)
                if (linkProperties != null) {
                    for (route in linkProperties.routes) {
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
        } catch (e: Exception) {
            Log.w(TAG, "Error querying ConnectivityManager default route: ${e.message}")
        }

        // Secondary fallback: WifiManager DHCP gateway
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val ip = dhcp.gateway
                return String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying WifiManager gateway: ${e.message}")
        }

        // Tertiary fallback: USB Tethering interfaces
        val usbGw = getUsbGatewayIp(context)
        if (usbGw.isNotEmpty() && usbGw != "192.168.42.129") {
            return usbGw
        }

        return "192.168.43.1"
    }

    fun getGatewayIp(context: Context): String = getActiveGatewayIp(context)

    /**
     * Checks if a Type-C USB tethered network interface is currently active (rndis, usb, ncm, or eth).
     */
    fun isUsbTetheringActive(): Boolean {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") || name.startsWith("eth")) {
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
     * Android AOSP default USB tether gateway: 192.168.42.129 or 192.168.42.1
     * Samsung One UI default USB tether gateway: 192.168.45.1 or 192.168.43.1
     */
    fun getUsbGatewayIp(context: Context): String {
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    val ifaceName = (linkProps.interfaceName ?: "").lowercase()
                    if (ifaceName.startsWith("rndis") || ifaceName.startsWith("usb") || ifaceName.startsWith("ncm") || ifaceName.startsWith("eth")) {
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
            Log.w(TAG, "Error searching USB routes: ${e.message}")
        }

        // Check local IP on USB interface to infer gateway subnet
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "192.168.42.129"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") || name.startsWith("eth")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                // If 192.168.42.X, standard AOSP gateway is 192.168.42.129 or 192.168.42.1
                                if (parts[2] == "42") {
                                    return if (parts[3] == "129") "192.168.42.1" else "192.168.42.129"
                                }
                                // If 192.168.45.X (Samsung), gateway is 192.168.45.1
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
            Log.w(TAG, "Error deriving USB gateway from interface: ${e.message}")
        }

        return "192.168.42.129"
    }

    /**
     * Returns descriptive information about active USB network interfaces.
     */
    fun getUsbInterfaceDetails(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "No USB network found"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") || name.startsWith("eth")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return "${intf.name} (${addr.hostAddress})"
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "No USB network active"
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
