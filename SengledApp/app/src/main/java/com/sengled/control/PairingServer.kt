package com.sengled.control

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Lightweight HTTP server that runs on the phone during pairing verification.
 *
 * After the bulb receives WiFi credentials, it connects to your home network and
 * hits two endpoints to verify the setup:
 *   - POST /life2/device/accessCloud.json
 *   - GET  /jbalancer/new/bimqtt
 *
 * This server responds to both and tracks when both have been hit.
 * The bulb's IP (from which it contacts us) is captured as the new bulb IP.
 */
class PairingServer(
    private val port: Int = 57542
) {
    companion object {
        private const val TAG = "PairingServer"
    }

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private var running = false

    @Volatile var bulbIp: String? = null
        private set

    @Volatile var hitAccessCloud = false
        private set

    @Volatile var hitBimqtt = false
        private set

    /**
     * The MQTT broker host IP the bulb should connect to.
     * Must be set to the phone's LAN IP BEFORE starting pairing.
     * The bulb cannot reach 127.0.0.1 (that's the bulb itself).
     */
    @Volatile var brokerHost: String = "127.0.0.1"
    @Volatile var brokerPort: Int = 8883

    val bothEndpointsHit: Boolean
        get() = hitAccessCloud && hitBimqtt

    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket(port)
            serverSocket?.reuseAddress = true
            running = true

            thread = Thread({
                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        Thread { handleClient(client) }.start()
                    } catch (_: Exception) {
                        if (running) Thread.sleep(100)
                    }
                }
            }, "pairing-server").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "Server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        thread?.interrupt()
        thread = null
        Log.d(TAG, "Server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val clientIp = socket.inetAddress.hostAddress ?: return
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return

            // Parse method and path: "GET /path HTTP/1.1"
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            // Read remaining headers (discard body for POST)
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            // Consume body if present
            if (contentLength > 0) reader.read(CharArray(contentLength))

            // Route
            when (path) {
                "/life2/device/accessCloud.json" -> {
                    if (method in listOf("POST", "PUT")) {
                        hitAccessCloud = true
                        bulbIp = clientIp
                        Log.d(TAG, "Hit /life2/device/accessCloud.json from $clientIp")
                    }
                    sendJson(socket, JSONObject()
                        .put("messageCode", "200")
                        .put("info", "OK")
                        .put("success", true)
                    )
                }
                "/jbalancer/new/bimqtt" -> {
                    if (method in listOf("GET", "POST")) {
                        hitBimqtt = true
                        bulbIp = clientIp
                        Log.d(TAG, "Hit /jbalancer/new/bimqtt from $clientIp — broker=$brokerHost:$brokerPort")
                    }
                    sendJson(socket, JSONObject()
                        .put("protocal", "mqtt")
                        .put("host", brokerHost)
                        .put("port", brokerPort)
                    )
                }
                else -> {
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendJson(socket: Socket, data: JSONObject) {
        val body = data.toString().toByteArray(Charsets.UTF_8)
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val os: OutputStream = socket.getOutputStream()
        os.write(response.toByteArray())
        os.write(body)
        os.flush()
    }

    /** Reset hit state for a new pairing session. */
    fun reset() {
        hitAccessCloud = false
        hitBimqtt = false
        bulbIp = null
    }
}
