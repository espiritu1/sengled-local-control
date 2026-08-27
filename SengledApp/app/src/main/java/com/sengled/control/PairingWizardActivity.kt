package com.sengled.control

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.net.DatagramSocket

/**
 * Step-by-step wizard for pairing a new Sengled bulb to the home WiFi.
 *
 * Steps:
 *   1. Enter WiFi SSID + password
 *   2. Connect to bulb's AP (192.168.8.x) — user does this in system settings
 *   3. Pairing in progress — sends credentials via UDP
 *   4. Result — shows IP + MAC, lets user name the bulb and set static IP
 */
class PairingWizardActivity : AppCompatActivity() {

    private lateinit var btnNext: MaterialButton
    private lateinit var stepDots: List<View>

    private var currentStep = 1
    private var ssid = ""
    private var password = ""
    private var bulbMac = ""
    private var bulbIp = ""

    private val handler = Handler(Looper.getMainLooper())
    private var apCheckRunnable: Runnable? = null
    private var pairingServer: PairingServer? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing_wizard)

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        btnNext = findViewById(R.id.btnNext)
        stepDots = listOf(
            findViewById(R.id.step1Dot),
            findViewById(R.id.step2Dot),
            findViewById(R.id.step3Dot),
            findViewById(R.id.step4Dot)
        )

        btnNext.setOnClickListener { onNextClicked() }
        showStep(1)
    }

    override fun onDestroy() {
        super.onDestroy()
        apCheckRunnable?.let { handler.removeCallbacks(it) }
        unregisterNetworkCallback()
        pairingServer?.stop()
    }

    // ── Step navigation ────────────────────────────────────────────────

    private fun showStep(step: Int) {
        currentStep = step
        val container = findViewById<android.widget.FrameLayout>(R.id.stepContainer)
        container.removeAllViews()

        // Update dots
        for (i in stepDots.indices) {
            stepDots[i].setBackgroundResource(
                if (i + 1 <= step) R.drawable.step_dot_active else R.drawable.step_dot_inactive
            )
        }

        when (step) {
            1 -> {
                layoutInflater.inflate(R.layout.step_wifi_credentials, container, true)
                btnNext.text = getString(R.string.pairing_next)
            }
            2 -> {
                layoutInflater.inflate(R.layout.step_connect_ap, container, true)
                btnNext.text = getString(R.string.pairing_next)
                startApDetection()
            }
            3 -> {
                layoutInflater.inflate(R.layout.step_pairing_progress, container, true)
                btnNext.visibility = View.GONE
                runPairing()
            }
            4 -> {
                layoutInflater.inflate(R.layout.step_result, container, true)
                btnNext.text = getString(R.string.pairing_finish)
                btnNext.visibility = View.VISIBLE
                showResult()
            }
        }
    }

    private fun onNextClicked() {
        when (currentStep) {
            1 -> {
                val editSsid = findViewById<TextInputEditText>(R.id.editSsid)
                val editPass = findViewById<TextInputEditText>(R.id.editPassword)
                ssid = editSsid.text?.toString()?.trim() ?: ""
                password = editPass.text?.toString()?.trim() ?: ""

                if (ssid.isEmpty()) {
                    editSsid.error = "Ingresá el nombre de la red"
                    return
                }
                if (password.isEmpty()) {
                    editPass.error = "Ingresá la contraseña"
                    return
                }
                showStep(2)
            }
            2 -> {
                // User confirms they connected to bulb AP
                showStep(3)
            }
            4 -> {
                saveBulbAndFinish()
            }
        }
    }

    // ── Step 2: AP detection ───────────────────────────────────────────

    private fun startApDetection() {
        val txtStatus = findViewById<TextView>(R.id.txtApStatus)

        // Check immediately
        if (WifiDetector.isOnBulbAp(this)) {
            txtStatus.text = getString(R.string.pairing_ap_detected)
            btnNext.isEnabled = true
            return
        }

        // Register network callback for real-time detection
        registerNetworkCallback()

        // Also poll every 2 seconds as fallback
        apCheckRunnable = object : Runnable {
            override fun run() {
                if (isFinishing) return
                if (WifiDetector.isOnBulbAp(this@PairingWizardActivity)) {
                    txtStatus.text = getString(R.string.pairing_ap_detected)
                    btnNext.isEnabled = true
                    return
                }
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(apCheckRunnable!!, 2000)
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handler.post {
                        if (WifiDetector.isOnBulbAp(this@PairingWizardActivity)) {
                            val txtStatus = findViewById<TextView>(R.id.txtApStatus)
                            txtStatus?.text = getString(R.string.pairing_ap_detected)
                            btnNext?.isEnabled = true
                            unregisterNetworkCallback()
                        }
                    }
                }
            }
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
    }

    // ── Step 3: Pairing ────────────────────────────────────────────────

    private fun runPairing() {
        val txtStatus = findViewById<TextView>(R.id.txtPairingStatus)
        val txtLog = findViewById<TextView>(R.id.txtPairingLog)

        Thread {
            try {
                updateUi(txtStatus, txtLog, getString(R.string.pairing_connecting_bulb), "")

                // Get phone IP on bulb AP
                val phoneIp = WifiDetector.getPhoneIp(this@PairingWizardActivity) ?: "192.168.8.100"

                // Start HTTP verification server
                pairingServer = PairingServer(port = 57542)
                val serverStarted = pairingServer?.start() ?: false
                if (!serverStarted) {
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_verification_failed),
                        "No se pudo iniciar el servidor de verificación")
                    return@Thread
                }
                appendLog(txtLog, "Servidor HTTP iniciado en :57542")

                val socket = DatagramSocket()
                socket.soTimeout = 5000

                // Step 1: Handshake
                appendLog(txtLog, "Handshake con el foco…")
                val handshake = PairingProtocol.handshake(socket)
                if (handshake == null) {
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_error_handshake),
                        "No se pudo conectar al foco en 192.168.8.1:9080")
                    socket.close()
                    return@Thread
                }
                bulbMac = handshake.mac
                appendLog(txtLog, "MAC: $bulbMac")

                // Step 2: Scan WiFi
                appendLog(txtLog, "Escaneando redes WiFi…")
                PairingProtocol.scanWifi(socket)
                Thread.sleep(5000)
                val networks = PairingProtocol.getApList(socket)
                appendLog(txtLog, "Redes encontradas: ${networks.size}")

                // Step 3: Re-handshake
                appendLog(txtLog, "Re-handshake…")
                if (!PairingProtocol.reHandshake(socket)) {
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_error_handshake),
                        "El foco no confirmó el re-handshake")
                    socket.close()
                    return@Thread
                }

                // Step 4: Send credentials
                updateUi(txtStatus, txtLog, getString(R.string.pairing_sending_credentials), "")
                appendLog(txtLog, "Enviando credenciales a $ssid…")
                val sent = PairingProtocol.sendCredentials(
                    socket, ssid, password, null, phoneIp, 57542
                )
                if (!sent) {
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_error_credentials),
                        "El foco rechazó las credenciales WiFi")
                    socket.close()
                    return@Thread
                }

                // Step 5: End config
                appendLog(txtLog, "Finalizando configuración…")
                PairingProtocol.endConfig(socket)
                socket.close()

                // Step 6: Wait for verification
                updateUi(txtStatus, txtLog, getString(R.string.pairing_waiting_verification), "")
                appendLog(txtLog, "Esperando que el foco se conecte a tu red…")

                val verified = waitForVerification(120)
                if (verified) {
                    bulbIp = pairingServer?.bulbIp ?: ""
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_verification_success),
                        "IP: $bulbIp")
                    // Move to result step on UI thread
                    handler.post { showStep(4) }
                } else {
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_verification_failed),
                        "El foco no contactó los endpoints de verificación")
                }

            } catch (e: Exception) {
                updateUi(txtStatus, txtLog, "Error: ${e.message}", e.stackTraceToString())
            }
        }.start()
    }

    private fun waitForVerification(timeoutSeconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (pairingServer?.bothEndpointsHit == true) return true
            Thread.sleep(1000)
        }
        return false
    }

    // ── Step 4: Result ─────────────────────────────────────────────────

    private fun showResult() {
        val txtIp = findViewById<TextView>(R.id.txtBulbIp)
        val txtMac = findViewById<TextView>(R.id.txtBulbMac)
        val editName = findViewById<TextInputEditText>(R.id.editBulbName)
        val editStaticIp = findViewById<TextInputEditText>(R.id.editStaticIp)

        txtIp.text = bulbIp.ifEmpty { "No detectada aún" }
        txtMac.text = bulbMac

        // Suggest a default name
        editName.setText("Foco ${bulbMac.takeLast(5)}")

        // Suggest a static IP based on the bulb's current IP
        if (bulbIp.isNotEmpty()) {
            editStaticIp.setText(bulbIp)
        }
    }

    private fun saveBulbAndFinish() {
        val editName = findViewById<TextInputEditText>(R.id.editBulbName)
        val editStaticIp = findViewById<TextInputEditText>(R.id.editStaticIp)

        val name = editName.text?.toString()?.trim() ?: ""
        val staticIp = editStaticIp.text?.toString()?.trim() ?: bulbIp

        if (name.isEmpty()) {
            editName.error = "Ingresá un nombre para el foco"
            return
        }
        if (staticIp.isEmpty()) {
            editStaticIp.error = "Ingresá la IP del foco"
            return
        }

        // Generate a safe ID from the MAC (last 6 chars, no colons)
        val bulbId = bulbMac.replace(":", "").takeLast(6).lowercase()

        val bulb = Bulb(
            id = bulbId,
            name = name,
            ip = staticIp
        )
        BulbRegistry.addBulb(this, bulb, bulbMac)

        Toast.makeText(this, "¡Foco '$name' agregado!", Toast.LENGTH_SHORT).show()

        // Return to main screen
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    // ── UI helpers ─────────────────────────────────────────────────────

    private fun updateUi(statusView: TextView?, logView: TextView?, status: String, log: String) {
        handler.post {
            statusView?.text = status
            if (log.isNotEmpty()) logView?.text = log
        }
    }

    private fun appendLog(logView: TextView?, line: String) {
        handler.post {
            val current = logView?.text?.toString() ?: ""
            logView?.text = if (current.isEmpty()) line else "$current\n$line"
        }
    }
}
