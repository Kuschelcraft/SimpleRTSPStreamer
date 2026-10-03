package com.kuschelcraft.simplertspstreamer

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    data class Address(val iface: String, val ip: String, val rank: Int)

    /** IPv4 addresses a viewer on the local network could use, best candidate first. */
    fun addresses(): List<Address> {
        val result = ArrayList<Address>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-") ||
                    name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("ppp")
                ) continue
                for (ia in ni.inetAddresses.toList()) {
                    if (ia !is Inet4Address || ia.isLoopbackAddress) continue
                    val ip = ia.hostAddress ?: continue
                    var rank = when {
                        name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> 0
                        name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis") || name.startsWith("enx") -> 1
                        else -> 5
                    }
                    if (ia.isLinkLocalAddress) rank += 10
                    result.add(Address(ni.name, ip, rank))
                }
            }
        } catch (e: Exception) {
            AppLog.log("Network enumeration failed: $e")
        }
        return result.sortedBy { it.rank }
    }

    fun url(ip: String, port: Int) = "rtsp://$ip:$port/"
}
