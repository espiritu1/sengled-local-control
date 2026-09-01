package com.sengled.control

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sengled UDP pairing protocol for the W21-N11 bulb.
 *
 * All communication happens over UDP port 9080 to 192.168.8.1 (bulb AP mode).
 * Credential payloads are RC4-encrypted via [SengledCrypto].
 */
object PairingProtocol {

    private const val BULB_AP_IP = "192.168.8.1"
    private const val BULB_PORT = 9080
    private const val TIMEOUT_MS = 5000

    data class HandshakeResult(val mac: String, val raw: JSONObject)
    data class WifiNetwork(val ssid: String, val bssid: String, val signal: Int)

    // ── Step 1: Initial handshake ──────────────────────────────────────

    fun handshake(socket: DatagramSocket, fallbackMac: String? = null): HandshakeResult? {
        val req = JSONObject()
            .put("name", "startConfigRequest")
            .put("totalStep", 1)
            .put("curStep", 1)
            .put("payload", JSONObject().put("protocol", 1))

        val resp = sendAndReceive(socket, req) ?: return null
        var mac = resp.optJSONObject("payload")?.optString("mac", "") ?: ""
        if (mac.length != 17) {
            // Some bulblets don't return the MAC in the handshake response (only
            // `result:true`). Fall back to the BSSID of the Wi-Fi network we're
            // connected to — that IS the bulb's MAC when we're on its AP.
            mac = fallbackMac?.replace(":", "")?.replace("-", "")?.uppercase() ?: ""
            if (mac.isEmpty()) return null
            // Normalize to XX:XX:XX:XX:XX:XX
            mac = mac.chunked(2).joinToString(":")
        }
        return HandshakeResult(mac, resp)
    }

    // ── Step 2: Scan for WiFi networks ─────────────────────────────────

    fun scanWifi(socket: DatagramSocket): Boolean {
        val scanReq = JSONObject()
            .put("name", "scanWifiRequest")
            .put("totalStep", 1)
            .put("curStep", 1)
            .put("payload", JSONObject())

        return sendAndReceive(socket, scanReq) != null
    }

    fun getApList(socket: DatagramSocket): List<WifiNetwork> {
        val apReq = JSONObject()
            .put("name", "getAPListRequest")
            .put("totalStep", 1)
            .put("curStep", 1)
            .put("payload", JSONObject())

        val resp = sendAndReceive(socket, apReq) ?: return emptyList()
        val routers = resp.optJSONObject("payload")?.optJSONArray("routers") ?: return emptyList()

        val networks = mutableListOf<WifiNetwork>()
        for (i in 0 until routers.length()) {
            val r = routers.optJSONObject(i) ?: continue
            networks.add(WifiNetwork(
                ssid = r.optString("ssid", ""),
                bssid = r.optString("bssid", ""),
                signal = r.optInt("signal", 0)
            ))
        }
        return networks
    }

    // ── Step 3: Re-handshake ───────────────────────────────────────────

    fun reHandshake(socket: DatagramSocket): Boolean {
        val req = JSONObject()
            .put("name", "startConfigRequest")
            .put("totalStep", 1)
            .put("curStep", 1)
            .put("payload", JSONObject().put("protocol", 1))

        val resp = sendAndReceive(socket, req) ?: return false
        return resp.optJSONObject("payload")?.optBoolean("result", false) == true
    }

    // ── Step 4: Send WiFi credentials (encrypted) ─────────────────────

    fun sendCredentials(
        socket: DatagramSocket,
        ssid: String,
        password: String,
        bssid: String?,
        httpHost: String,
        httpPort: Int
    ): Boolean {
        val routerInfo = if (ssid.all { it.code < 128 }) {
            JSONObject().put("ssid", ssid).put("password", password)
        } else {
            JSONObject().put("ssid", "").put("bssid", (bssid ?: "").uppercase()).put("password", password)
        }

        val tz = java.util.TimeZone.getDefault().id

        val paramsPayload = JSONObject()
            .put("name", "setParamsRequest")
            .put("totalStep", 1)
            .put("curStep", 1)
            .put("payload", JSONObject()
                .put("userID", "618")
                .put("appServerDomain", "http://$httpHost:$httpPort/life2/device/accessCloud.json")
                .put("jbalancerDomain", "http://$httpHost:$httpPort/jbalancer/new/bimqtt")
                .put("timeZone", tz)
                .put("routerInfo", routerInfo)
            )

        val encrypted = SengledCrypto.encryptPayload(paramsPayload)
        val resp = sendRawAndReceive(socket, encrypted)
        if (resp == null) {
            // Timeout is acceptable here — the bulb may disconnect before replying
            return true
        }
        // Check if plaintext response says rejected
        try {
            val json = JSONObject(resp)
            if (json.optJSONObject("payload")?.optBoolean("result", true) == false) {
                return false
            }
        } catch (_: Exception) {
            // Not JSON — likely encrypted or garbled, assume OK
        }
        return true
    }

    // ── Step 5: End configuration ──────────────────────────────────────

    fun endConfig(socket: DatagramSocket) {
        val req = JSONObject()
            .put("name", "endConfigRequest")
            .put("totalStep", 1)
            .put("curStep", 1)
            .put("payload", JSONObject())

        try {
            sendAndReceive(socket, req)
        } catch (_: Exception) {
            // Timeout expected — bulb is switching networks
        }
    }

    // ── Low-level UDP transport ────────────────────────────────────────

    /** Last transport failure reason (diagnostics). */
    @Volatile
    var lastTransportError: String? = null

    private fun sendAndReceive(socket: DatagramSocket, payload: JSONObject): JSONObject? {
        val text = payload.toString()
        val response = sendRawAndReceive(socket, text) ?: return null
        return try {
            JSONObject(response)
        } catch (_: Exception) {
            null
        }
    }

    private fun sendRawAndReceive(socket: DatagramSocket, text: String): String? {
        try {
            val data = text.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(BULB_AP_IP), BULB_PORT))
            android.util.Log.d("SengledPair", "UDP sent to $BULB_AP_IP:$BULB_PORT")

            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.soTimeout = TIMEOUT_MS
            socket.receive(packet)
            val resp = String(buffer, 0, packet.length, Charsets.UTF_8)
            android.util.Log.d("SengledPair", "UDP recv: $resp")
            return resp
        } catch (e: java.net.SocketTimeoutException) {
            lastTransportError = "timeout"
            android.util.Log.w("SengledPair", "UDP timeout waiting for bulb")
            return null
        } catch (e: java.net.ConnectException) {
            lastTransportError = "connect: ${e.message}"
            android.util.Log.w("SengledPair", "UDP connect failed: ${e.message}")
            return null
        } catch (e: java.net.PortUnreachableException) {
            lastTransportError = "port_unreachable: ${e.message}"
            android.util.Log.w("SengledPair", "UDP port unreachable: ${e.message}")
            return null
        } catch (e: Exception) {
            lastTransportError = "${e.javaClass.simpleName}: ${e.message}"
            android.util.Log.w("SengledPair", "UDP error: ${e.javaClass.simpleName} -> ${e.message}")
            return null
        }
    }

    /** Logs the phone's current network binding state (diagnostics). */
    fun logNetworkState(context: Context, tag: String = "SengledPair") {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = WifiDetector.getPhoneIp(context)
            val active = cm?.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            android.util.Log.d(tag, "WifiIP=$ip activeNetwork=$active caps=$caps")
            cm?.allNetworks?.forEach { n ->
                val lp = cm.getLinkProperties(n)
                val ips = lp?.linkAddresses?.map { it?.address?.hostAddress } ?: emptyList()
                android.util.Log.d(tag, "  network=$n ips=$ips")
            }
        } catch (_: Exception) {}
    }
}
