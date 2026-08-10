package com.sengled.control

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wraps the Sengled local UDP protocol (port 9080, JSON over UTF-8).
 *
 * NOT thread-safe. Call it from a single background thread only (the app
 * serializes all network work through one executor).
 */
class UdpClient(
    private val port: Int = 9080,
    private val timeoutMs: Int = 1500
) : AutoCloseable {

    private val socket: DatagramSocket = DatagramSocket().apply { soTimeout = timeoutMs }

    /** Sends "set_device_switch". Returns true if a valid reply came back. */
    fun setSwitch(ip: String, on: Boolean): Boolean {
        val payload = JSONObject()
            .put("func", "set_device_switch")
            .put("param", JSONObject().put("switch", if (on) 1 else 0))
        return exchange(payload, ip) != null
    }

    /** Sends "set_device_brightness". Value is clamped to 0..100. */
    fun setBrightness(ip: String, value: Int): Boolean {
        val clamped = value.coerceIn(0, 100)
        val payload = JSONObject()
            .put("func", "set_device_brightness")
            .put("param", JSONObject().put("brightness", clamped))
        return exchange(payload, ip) != null
    }

    /**
     * Sends "get_device_brightness". The bulb replies with
     * {"result":{"ret":0,"brightness":NN}}. This model reports brightness only;
     * the panel web infers power as on when brightness > 0. Returns null on
     * timeout, malformed reply, or ret != 0.
     */
    fun getState(ip: String): BulbState? {
        val payload = JSONObject()
            .put("func", "get_device_brightness")
            .put("param", JSONObject())
        val response = exchange(payload, ip) ?: return null

        val result = response.optJSONObject("result") ?: return null
        if (result.optInt("ret", -1) != 0) return null
        val brightness = result.optInt("brightness", -1)
        if (brightness < 0) return null

        return BulbState(brightness, brightness > 1)
    }

    /** Serialize the request, send it, and wait for one reply. */
    private fun exchange(payload: JSONObject, ip: String): JSONObject? {
        return try {
            val data = payload.toString().toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), port))

            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)

            val text = String(buffer, 0, packet.length, Charsets.UTF_8)
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        socket.close()
    }
}

data class BulbState(val brightness: Int, val on: Boolean)
