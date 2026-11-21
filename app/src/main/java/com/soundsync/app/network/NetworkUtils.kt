package com.soundsync.app.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

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
}
