package com.sengled.control

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom

/**
 * RC4 encryption for the Sengled Wi-Fi setup protocol.
 *
 * The key is the same hardcoded value used by the original Sengled app.
 * RC4 is not cryptographically secure — this exists purely for protocol compatibility.
 */
object SengledCrypto {

    private const val KEY_STR = "MTlCaWppbmdTaGFuZ2hhaVdpU2VuZ2xlZEZpMjBBQUJBU0U2NA=="

    /** Encrypt a JSON payload → base64 string to send over UDP. */
    fun encryptPayload(data: JSONObject): String {
        val jsonBytes = data.toString().toByteArray(Charsets.UTF_8)
        val key = KEY_STR.toByteArray(Charsets.UTF_8)
        val encrypted = rc4(jsonBytes, key)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /** Decrypt a base64 string received from the bulb → JSONObject. */
    fun decryptPayload(b64: String): JSONObject? {
        return try {
            val ciphertext = Base64.decode(b64, Base64.NO_WRAP)
            val key = KEY_STR.toByteArray(Charsets.UTF_8)
            val decrypted = rc4(ciphertext, key)
            JSONObject(String(decrypted, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /** RC4 stream cipher — same operation for encrypt and decrypt. */
    private fun rc4(data: ByteArray, key: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        val keyLen = key.size

        // KSA (Key Scheduling Algorithm)
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % keyLen].toInt() and 0xFF)) % 256
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }

        // PRGA (Pseudo-Random Generation Algorithm)
        var i = 0
        j = 0
        val out = ByteArray(data.size)
        for (k in data.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            val ki = s[(s[i] + s[j]) % 256]
            out[k] = (data[k].toInt() xor ki).toByte()
        }
        return out
    }
}
