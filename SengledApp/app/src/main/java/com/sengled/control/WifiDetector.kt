package com.sengled.control

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
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

    /**
     * Returns the BSSID (MAC) of the Wi-Fi network the phone is currently
     * connected to, e.g. "80:A0:36:B1:5C:F0", or null if not on Wi-Fi.
     *
     * When the phone is on the bulb's AP this BSSID is the bulb's own MAC — a
     * reliable fallback for the handshake, since the bulb does not always
     * include its MAC in the startConfig response.
     */
    fun getConnectedBssid(context: Context): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.connectionInfo?.bssid
        } catch (_: Exception) {
            null
        }
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

    /**
     * Creates a UDP socket bound to the phone's current Wi-Fi interface.
     *
     * On Android a plain `DatagramSocket()` routes through whatever network the
     * OS picks as default, which is frequently NOT the Sengled bulb AP when the
     * AP has no internet. Binding the socket to the phone's local Wi-Fi IP
     * forces its traffic out through that exact interface (exactly what the
     * desktop tool does by having a single active network), so the handshake
     * actually reaches `192.168.8.1:9080`.
     */
    fun createWifiSocket(context: Context): DatagramSocket {
        val localIp = getPhoneIp(context)
        if (localIp != null) {
            try {
                return DatagramSocket(InetSocketAddress(localIp, 0)).apply { broadcast = true }
            } catch (_: Exception) {
                // fall through to the default socket
            }
        }
        return DatagramSocket().apply { broadcast = true }
    }

    /**
     * Forces all of this process's network traffic (including UDP) through the
     * network interface that owns the bulb AP subnet (192.168.8.x). Returns
     * true when the bulb-AP network was found and bound.
     *
     * On Android, connecting to a WiFi network without internet (the bulb AP)
     * keeps it connected but the OS routes data through whatever network it
     * deems default (mobile data or another saved WiFi). Binding the process to
     * the bulb AP pins the UDP handshake to `192.168.8.1:9080` to egress that
     * exact interface — mirroring the desktop tool, which reaches the bulb
     * because it has a single active network.
     */
    fun bindProcessToBulbAp(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val target = findNetworkByIp(cm, BULB_SUBNET_PREFIX) ?: return false
        return try {
            cm.bindProcessToNetwork(target)
        } catch (_: Exception) {
            false
        }
    }

    /** Releases a previous [bindProcessToBulbAp] so the process uses normal routing again. */
    fun unbindProcess(context: Context) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {}
    }

    private fun findNetworkByIp(cm: ConnectivityManager, prefix: String): Network? {
        return try {
            for (network in cm.allNetworks) {
                val lp = cm.getLinkProperties(network) ?: continue
                for (addr in lp.linkAddresses) {
                    val ip = addr?.address?.hostAddress ?: continue
                    if (ip.startsWith(prefix)) return network
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Waits until the phone is no longer connected to the bulb's AP, or until
     * [timeoutMs] elapses. Returns true if the phone left the AP network, false
     * if it is still connected when the timeout runs out.
     *
     * Used after sending the WiFi credentials: the user must switch the phone
     * back to the home network so we can discover the bulb on the LAN.
     */
    fun waitOffBulbAp(context: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isOnBulbAp(context)) return true
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return false }
        }
        return !isOnBulbAp(context)
    }

    /**
     * Discovers the bulb on the local network by broadcasting/sweeping
     * `search_devices` over UDP port 9080 and matching the reply MAC against
     * [targetMac]. Returns the bulb's IP, or null if not found within
     * [timeoutMs].
     *
     * The phone MUST be connected to the same WiFi network as the bulb (NOT the
     * bulb's AP) for this to work. This is blocking network I/O: call it from a
     * background thread only.
     *
     * A blank or non-MAC [targetMac] aborts immediately so we never mistake an
     * unrelated device on the LAN for our bulb.
     */
    fun findBulbByMac(context: Context, targetMac: String, timeoutMs: Long = 180_000): String? {
        val macNorm = targetMac.replace(":", "").replace("-", "").lowercase()
        if (macNorm.isEmpty()) return null

        val socket = createWifiSocket(context).apply { soTimeout = 1500 }
        val req = "{\"func\":\"search_devices\",\"param\":{}}".toByteArray(Charsets.UTF_8)
        val targets = buildTargets(context)

        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                // Fire a broadcast plus a per-host sweep of the local subnet.
                for (address in targets) {
                    try {
                        socket.send(DatagramPacket(req, req.size, address, BULB_DISCOVERY_PORT))
                    } catch (_: Exception) {}
                }
                // Listen for replies until the socket timeout or the deadline.
                while (System.currentTimeMillis() < deadline) {
                    val buf = ByteArray(2048)
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(pkt)
                    } catch (_: Exception) {
                        break // socket timed out -> fire another round
                    }
                    val ip = pkt.address?.hostAddress ?: continue
                    val mac = extractMac(buf, pkt.length) ?: continue
                    if (mac == macNorm) return ip
                }
            }
        } finally {
            socket.close()
        }
        return null
    }

    private const val BULB_DISCOVERY_PORT = 9080

    /**
     * Returns the addresses to probe for discovery: the limited broadcast
     * address plus every host in the phone's /24 subnet (when a LAN IP is
     * available), which is more robust against routers that block broadcast.
     */
    private fun buildTargets(context: Context): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}

        val lan = getLanIp(context) ?: return addresses
        val dotIdx = lan.lastIndexOf('.')
        if (dotIdx < 0) return addresses
        val prefix = lan.substring(0, dotIdx + 1)
        for (host in 1..254) {
            try {
                addresses.add(InetAddress.getByName("$prefix$host"))
            } catch (_: Exception) {}
        }
        return addresses
    }

    /** Parses a `search_devices` reply and returns its normalized MAC, or null. */
    private fun extractMac(buf: ByteArray, length: Int): String? {
        return try {
            val text = String(buf, 0, length, Charsets.UTF_8)
            val result = JSONObject(text).optJSONObject("result") ?: return null
            val mac = result.optString("mac", "") ?: ""
            mac.replace(":", "").replace("-", "").lowercase().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
