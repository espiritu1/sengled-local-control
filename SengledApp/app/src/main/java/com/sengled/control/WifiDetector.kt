package com.sengled.control

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Detects whether the phone is connected to the Sengled bulb's AP network.
 *
 * The bulb's AP always assigns addresses in the 192.168.8.x range.
 */
object WifiDetector {

    /** The bulb's AP always uses this subnet. */
    private const val BULB_SUBNET_PREFIX = "192.168.8."

    /** Returns the phone's current Wi-Fi IP, or null if not on Wi-Fi. */
    fun getPhoneIp(context: Context): String? {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: return null
            if (ip == 0) return null
            return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
        } catch (_: Exception) {
            return fallbackGetIp()
        }
    }

    /** Checks if the phone is on the Sengled bulb's AP network (192.168.8.x). */
    fun isOnBulbAp(context: Context): Boolean {
        val ip = getPhoneIp(context) ?: return false
        return ip.startsWith(BULB_SUBNET_PREFIX)
    }

    /** Fallback: scan network interfaces for a 192.168.8.x address. */
    private fun fallbackGetIp(): String? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith(BULB_SUBNET_PREFIX)) return ip
                    }
                }
            }
            // Try any non-loopback IPv4
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Returns the phone's LAN IP (non-bulb-AP). */
    fun getLanIp(context: Context): String? {
        val ip = getPhoneIp(context) ?: return null
        return if (ip.startsWith(BULB_SUBNET_PREFIX)) null else ip
    }
}
