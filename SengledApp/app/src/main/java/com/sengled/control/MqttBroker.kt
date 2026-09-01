package com.sengled.control

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocketFactory

/**
 * Minimal MQTT 3.1.1 broker with TLS for Sengled bulb pairing.
 *
 * The ESP8266 bulb firmware uses the AWS IoT C SDK which requires TLS.
 * After receiving WiFi credentials, the bulb connects to this broker to
 * complete its setup sequence — without a working MQTT connection the bulb
 * keeps blinking indefinitely.
 *
 * This broker handles only the operations the bulb needs:
 *   CONNECT  → CONNACK
 *   SUBSCRIBE → SUBACK
 *   PUBLISH  → (store for future command delivery)
 *   PINGREQ  → PINGRESP
 *   DISCONNECT → close
 */
class MqttBroker(private val port: Int = DEFAULT_PORT) {

    companion object {
        private const val TAG = "MqttBroker"
        private const val DEFAULT_PORT = 8883

        // MQTT packet types
        private const val CONNECT = 1
        private const val CONNACK = 2
        private const val PUBLISH = 3
        private const val PUBACK = 4
        private const val PUBREC = 5
        private const val PUBREL = 6
        private const val PUBCOMP = 7
        private const val SUBSCRIBE = 8
        private const val SUBACK = 9
        private const val UNSUBSCRIBE = 10
        private const val UNSUBACK = 11
        private const val PINGREQ = 12
        private const val PINGRESP = 13
        private const val DISCONNECT = 14
    }

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    @Volatile var isRunning = false
        private set

    // Track connected clients for future command delivery
    val clients = ConcurrentHashMap<String, Socket>()

    fun start(): Boolean {
        return try {
            val factory: SSLServerSocketFactory = SslCertGenerator.createSslServerSocketFactory()
            serverSocket = factory.createServerSocket(port).apply {
                reuseAddress = true
            }
            isRunning = true

            thread = Thread({
                Log.d(TAG, "MQTT broker started on port $port (TLS)")
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        Thread({ handleClient(client) }, "mqtt-client").start()
                    } catch (ex: Exception) {
                        if (isRunning) Thread.sleep(100)
                    }
                }
            }, "mqtt-broker").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start broker: ${e.message}")
            false
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (ex: Exception) { /* ignore */ }
        serverSocket = null
        clients.values.forEach { s -> try { s.close() } catch (ex: Exception) { /* ignore */ } }
        clients.clear()
        thread?.interrupt()
        thread = null
        Log.d(TAG, "MQTT broker stopped")
    }

    // ── Client handling ────────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        val clientIp = socket.inetAddress?.hostAddress ?: "unknown"
        var activeClientId: String? = null
        try {
            socket.soTimeout = 30_000 // 30s timeout for idle clients
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            while (isRunning && !socket.isClosed) {
                val packet = readPacket(input) ?: break
                val type = (packet[0].toInt() shr 4) and 0x0F

                when (type) {
                    CONNECT -> {
                        activeClientId = handleConnect(packet, output)
                        if (activeClientId != null) {
                            clients[activeClientId] = socket
                            Log.d(TAG, "Client connected: $activeClientId from $clientIp")
                        }
                    }
                    SUBSCRIBE -> handleSubscribe(packet, output)
                    PUBLISH -> {
                        val topic = handlePublish(packet, output)
                        if (topic != null) Log.d(TAG, "Published to: $topic")
                    }
                    PUBACK, PUBREC, PUBREL, PUBCOMP -> { /* QoS ack — ignore */ }
                    PINGREQ -> handlePingreq(output)
                    UNSUBSCRIBE -> handleUnsubscribe(packet, output)
                    DISCONNECT -> {
                        Log.d(TAG, "Client disconnected: $activeClientId")
                        break
                    }
                    else -> Log.w(TAG, "Unknown packet type: $type")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client error ($clientIp): ${e.message}")
        } finally {
            if (activeClientId != null) clients.remove(activeClientId)
            try { socket.close() } catch (ex: Exception) { /* ignore */ }
        }
    }

    // ── MQTT packet handlers ───────────────────────────────────────────

    private fun handleConnect(packet: ByteArray, output: OutputStream): String? {
        var offset = 1 // skip fixed header byte
        val decoded = decodeRemainingLength(packet, offset)
        offset += decoded.second

        // Variable header
        if (offset + 2 > packet.size) return null
        val protocolNameLen = ((packet[offset].toInt() and 0xFF) shl 8) or
                (packet[offset + 1].toInt() and 0xFF)
        offset += 2

        if (offset + protocolNameLen > packet.size) return null
        val protocolName = String(packet, offset, protocolNameLen)
        offset += protocolNameLen

        if (offset + 4 > packet.size) return null
        val protocolLevel = packet[offset].toInt() and 0xFF // 4 = MQTT 3.1.1
        val connectFlags = packet[offset + 1].toInt() and 0xFF
        val keepAlive = ((packet[offset + 2].toInt() and 0xFF) shl 8) or
                (packet[offset + 3].toInt() and 0xFF)
        offset += 4

        Log.d(TAG, "CONNECT: protocol=$protocolName level=$protocolLevel flags=$connectFlags keepAlive=$keepAlive")

        // Payload: client ID
        if (offset + 2 > packet.size) return null
        val clientIdLen = ((packet[offset].toInt() and 0xFF) shl 8) or
                (packet[offset + 1].toInt() and 0xFF)
        offset += 2
        val clientId = if (clientIdLen > 0 && offset + clientIdLen <= packet.size) {
            String(packet, offset, clientIdLen)
        } else {
            "bulb_${System.currentTimeMillis()}"
        }

        // Send CONNACK — session present = 0, return code = 0 (accepted)
        val connack = byteArrayOf(
            (CONNACK shl 4).toByte(),
            0x02, // remaining length
            0x00, // session present = 0
            0x00  // return code = 0 (connection accepted)
        )
        output.write(connack)
        output.flush()

        return clientId
    }

    private fun handleSubscribe(packet: ByteArray, output: OutputStream) {
        var offset = 1
        val decoded = decodeRemainingLength(packet, offset)
        offset += decoded.second

        if (offset + 2 > packet.size) return
        val packetId = ((packet[offset].toInt() and 0xFF) shl 8) or
                (packet[offset + 1].toInt() and 0xFF)
        offset += 2

        // Parse topic filters
        val topics = mutableListOf<String>()
        val endOffset = minOf(offset + decoded.first, packet.size)
        while (offset < endOffset) {
            if (offset + 2 > packet.size) break
            val topicLen = ((packet[offset].toInt() and 0xFF) shl 8) or
                    (packet[offset + 1].toInt() and 0xFF)
            offset += 2
            if (offset + topicLen > packet.size) break
            val topic = String(packet, offset, topicLen)
            offset += topicLen
            topics.add(topic)
            if (offset < packet.size) offset++ // skip QoS byte
        }

        Log.d(TAG, "SUBSCRIBE packetId=$packetId topics=$topics")

        // Send SUBACK — one QoS 0 grant per topic
        val subackPayload = ByteArray(2 + topics.size) // packetId(2) + return codes
        subackPayload[0] = ((packetId shr 8) and 0xFF).toByte()
        subackPayload[1] = (packetId and 0xFF).toByte()
        for (i in topics.indices) {
            subackPayload[2 + i] = 0x00 // QoS 0 granted
        }

        val subackHeader = byteArrayOf((SUBACK shl 4).toByte()) + encodeRemainingLength(subackPayload.size)
        output.write(subackHeader)
        output.write(subackPayload)
        output.flush()
    }

    private fun handlePublish(packet: ByteArray, output: OutputStream): String? {
        var offset = 1
        val decoded = decodeRemainingLength(packet, offset)
        val remainingLen = decoded.first
        offset += decoded.second

        if (offset + 2 > packet.size) return null
        val topicLen = ((packet[offset].toInt() and 0xFF) shl 8) or
                (packet[offset + 1].toInt() and 0xFF)
        offset += 2
        if (offset + topicLen > packet.size) return null
        val topic = String(packet, offset, topicLen)
        offset += topicLen

        // QoS is in the fixed header flags (bits 1-2)
        val flags = (packet[0].toInt() and 0x0F)
        val qos = (flags shr 1) and 0x03
        var packetId = 0

        // Packet ID present for QoS 1 and QoS 2
        if (qos > 0) {
            if (offset + 2 > packet.size) return null
            packetId = ((packet[offset].toInt() and 0xFF) shl 8) or
                    (packet[offset + 1].toInt() and 0xFF)
            offset += 2
        }

        // Payload: remainingLen = topicLen(2) + topicLen + [packetId(2)] + payload
        val payloadLen = remainingLen - 2 - topicLen - if (qos > 0) 2 else 0
        if (payloadLen > 0 && offset + payloadLen <= packet.size) {
            val payload = String(packet, offset, payloadLen)
            Log.d(TAG, "PUBLISH topic=$topic payload=$payload")
        }

        // Send PUBACK for QoS 1
        if (qos == 1) {
            val puback = byteArrayOf(
                (PUBACK shl 4).toByte(),
                0x02,
                ((packetId shr 8) and 0xFF).toByte(),
                (packetId and 0xFF).toByte()
            )
            output.write(puback)
            output.flush()
        }

        return topic
    }

    private fun handlePingreq(output: OutputStream) {
        val pingresp = byteArrayOf(
            (PINGRESP shl 4).toByte(),
            0x00
        )
        output.write(pingresp)
        output.flush()
    }

    private fun handleUnsubscribe(packet: ByteArray, output: OutputStream) {
        var offset = 1
        val decoded = decodeRemainingLength(packet, offset)
        offset += decoded.second

        if (offset + 2 > packet.size) return
        val packetId = ((packet[offset].toInt() and 0xFF) shl 8) or
                (packet[offset + 1].toInt() and 0xFF)

        // Send UNSUBACK
        val unsuback = byteArrayOf(
            (UNSUBACK shl 4).toByte(),
            0x02,
            ((packetId shr 8) and 0xFF).toByte(),
            (packetId and 0xFF).toByte()
        )
        output.write(unsuback)
        output.flush()
    }

    // ── Low-level packet I/O ───────────────────────────────────────────

    private fun readPacket(input: InputStream): ByteArray? {
        // Read fixed header
        val firstByte = input.read()
        if (firstByte == -1) return null

        // Read remaining length (variable-length encoding)
        var multiplier = 1
        var remainingLength = 0
        var value: Int
        do {
            val b = input.read()
            if (b == -1) return null
            value = b and 0x7F
            remainingLength += value * multiplier
            if (multiplier > 128 * 128 * 128) return null // malformed
            multiplier *= 128
        } while (value and 0x80 != 0)

        // Sanity check — don't allocate more than 64KB for a single packet
        if (remainingLength > 65536) return null

        // Read payload
        val payload = ByteArray(remainingLength)
        var totalRead = 0
        while (totalRead < remainingLength) {
            val read = input.read(payload, totalRead, remainingLength - totalRead)
            if (read == -1) return null
            totalRead += read
        }

        // Return full packet (fixed header + remaining)
        val packet = ByteArray(1 + remainingLength)
        packet[0] = firstByte.toByte()
        System.arraycopy(payload, 0, packet, 1, remainingLength)
        return packet
    }

    // ── MQTT variable-length encoding helpers ──────────────────────────

    /**
     * Decodes MQTT remaining length field starting at [offset].
     * Returns (decoded value, number of bytes consumed).
     */
    private fun decodeRemainingLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        var multiplier = 1
        var value = 0
        var pos = offset
        do {
            if (pos >= data.size) return Pair(value, pos - offset)
            val b = data[pos].toInt() and 0xFF
            value += (b and 0x7F) * multiplier
            pos++
            if (multiplier > 128 * 128 * 128) break
            multiplier *= 128
            if (b and 0x80 == 0) break
        } while (true)
        return Pair(value, pos - offset)
    }

    /**
     * Encodes an integer as MQTT remaining length field.
     */
    private fun encodeRemainingLength(length: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var value = length
        do {
            var encodedByte = (value % 128).toByte()
            value /= 128
            if (value > 0) {
                encodedByte = (encodedByte.toInt() or 0x80).toByte()
            }
            bytes.add(encodedByte)
        } while (value > 0)
        return bytes.toByteArray()
    }
}
