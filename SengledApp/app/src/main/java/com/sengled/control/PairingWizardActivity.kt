package com.sengled.control

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import android.content.pm.ActivityInfo
import android.net.wifi.WifiManager
import androidx.appcompat.app.AlertDialog

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
    private var savedLanIp: String = ""

    private var pairingServer: PairingServer? = null

    // Set to true when the user aborts the wizard or the Activity is destroyed.
    // The background pairing Thread checks it between stages and bails out, so
    // an aborted or recreated wizard can never leave a pairing running.
    @Volatile
    private var pairingCancelled = false

    private val handler = Handler(Looper.getMainLooper())
    private var apCheckRunnable: Runnable? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val REQ_LOCATION = 1001
        private const val KEY_STEP = "wizard_step"
        private const val KEY_SSID = "wizard_ssid"
        private const val KEY_PASSWORD = "wizard_password"
        private const val KEY_LAN_IP = "wizard_lan_ip"
        private const val KEY_MAC = "wizard_mac"
        private const val KEY_IP = "wizard_ip"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing_wizard)

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // Restore the wizard position and inputs after a configuration change
        // (rotation, theme, locale). Without this, every recreation restarted at
        // step 1 and dropped the typed SSID/password.
        if (savedInstanceState != null) {
            currentStep = savedInstanceState.getInt(KEY_STEP, 1)
            ssid = savedInstanceState.getString(KEY_SSID) ?: ""
            password = savedInstanceState.getString(KEY_PASSWORD) ?: ""
            savedLanIp = savedInstanceState.getString(KEY_LAN_IP) ?: ""
            bulbMac = savedInstanceState.getString(KEY_MAC) ?: ""
            bulbIp = savedInstanceState.getString(KEY_IP) ?: ""
        }

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { goBack() }
        toolbar.navigationContentDescription = getString(R.string.pairing_back)

        // Keep the system back button in sync with the on-screen back arrow.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBack()
            }
        })

        btnNext = findViewById(R.id.btnNext)
        stepDots = listOf(
            findViewById(R.id.step1Dot),
            findViewById(R.id.step2Dot),
            findViewById(R.id.step3Dot),
            findViewById(R.id.step4Dot)
        )

        btnNext.setOnClickListener { onNextClicked() }
        showStep(currentStep)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, currentStep)
        outState.putString(KEY_SSID, ssid)
        outState.putString(KEY_PASSWORD, password)
        outState.putString(KEY_LAN_IP, savedLanIp)
        outState.putString(KEY_MAC, bulbMac)
        outState.putString(KEY_IP, bulbIp)
    }

    override fun onDestroy() {
        // Abort any in-flight pairing before the Activity goes away so the
        // background Thread stops touching a destroyed Activity.
        pairingCancelled = true
        apCheckRunnable?.let { handler.removeCallbacks(it) }
        unregisterNetworkCallback()
        pairingServer?.stop()
        pairingServer = null
        super.onDestroy()
    }

    // ── Back navigation ────────────────────────────────────────────────

    /**
     * Back arrow / system back behaviour per step:
     *   1. WiFi credentials   -> confirm exit (nothing to go back to)
     *   2. Connect to bulb AP -> step 1
     *   3. Pairing in progress-> confirm cancel, then step 2
     *   4. Result             -> exit without saving (pairing already finished)
     */
    private fun goBack() {
        when (currentStep) {
            1 -> confirmExit()
            2 -> showStep(1)
            3 -> confirmCancelPairing()
            else -> finish()
        }
    }

    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pairing_exit_confirm_title)
            .setMessage(R.string.pairing_exit_confirm_message)
            .setPositiveButton(R.string.pairing_exit_confirm_positive) { _, _ -> finish() }
            .setNegativeButton(R.string.pairing_exit_confirm_negative, null)
            .show()
    }

    private fun confirmCancelPairing() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pairing_cancel_confirm_title)
            .setMessage(R.string.pairing_cancel_confirm_message)
            .setPositiveButton(R.string.pairing_cancel_confirm_positive) { _, _ ->
                cancelPairing()
                showStep(2)
            }
            .setNegativeButton(R.string.pairing_cancel_confirm_negative, null)
            .show()
    }

    /**
     * Flags the running pairing Thread to stop. The Thread's finally block stops
     * its HTTP server and releases the MQTT broker reference, so no resources
     * are leaked by an aborted attempt.
     */
    private fun cancelPairing() {
        pairingCancelled = true
    }

    // ── Step navigation ────────────────────────────────────────────────

    private fun showStep(step: Int) {
        currentStep = step

        // Lock rotation only while pairing (step 3): the operation runs inside
        // this Activity and must not be recreated or duplicated mid-flight.
        // Every other step rotates freely, and the saved instance state keeps
        // the wizard on the same step with the same inputs.
        requestedOrientation = if (step == 3) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

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
                btnNext.visibility = View.VISIBLE
                // Restore typed credentials after a configuration change.
                if (ssid.isNotEmpty()) {
                    findViewById<TextInputEditText>(R.id.editSsid).setText(ssid)
                }
                if (password.isNotEmpty()) {
                    findViewById<TextInputEditText>(R.id.editPassword).setText(password)
                }
                val btnDetect = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDetect)
                btnDetect.setOnClickListener { showDetectedNetworks() }
            }
            2 -> {
                layoutInflater.inflate(R.layout.step_connect_ap, container, true)
                btnNext.text = getString(R.string.pairing_next)
                // Re-enable the button: cancelling the pairing (step 3) lands here
                // with the button hidden.
                btnNext.visibility = View.VISIBLE
                // Save the phone's home WiFi IP BEFORE the user switches to bulb AP.
                // We'll need this IP for the MQTT broker and HTTP server later.
                if (savedLanIp.isEmpty()) {
                    savedLanIp = WifiDetector.getLanIp(this@PairingWizardActivity)
                        ?: WifiDetector.getPhoneIp(this@PairingWizardActivity)
                        ?: ""
                }
                startApDetection()
            }
            3 -> {
                layoutInflater.inflate(R.layout.step_pairing_progress, container, true)
                btnNext.visibility = View.GONE
                pairingCancelled = false
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

    // ── Step 1: WiFi network detection ─────────────────────────────────

    private fun showDetectedNetworks() {
        // scanResults/startScan require location permission on Android 8+.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return
        }
        doWifiScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            doWifiScan()
        } else {
            Toast.makeText(this, getString(R.string.pairing_detect_empty), Toast.LENGTH_LONG).show()
        }
    }

    private fun doWifiScan() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        try { wifi.startScan() } catch (_: Exception) {}
        // Wait for the scan to complete before reading results.
        handler.postDelayed({
            if (isFinishing) return@postDelayed
            val networks = wifi.scanResults
                .mapNotNull { it.SSID.takeIf { ssid -> ssid.isNotBlank() } }
                .distinct()
                .sorted()

            if (networks.isEmpty()) {
                Toast.makeText(
                    this,
                    getString(R.string.pairing_detect_empty),
                    Toast.LENGTH_LONG
                ).show()
                return@postDelayed
            }

            val names = networks.toTypedArray()
            val builder = AlertDialog.Builder(this)
                .setTitle(R.string.pairing_detect_title)
                .setItems(names) { _, which ->
                    val editSsid = findViewById<TextInputEditText>(R.id.editSsid)
                    editSsid.setText(names[which])
                }
            val dialog = builder.create()
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.pairing_detect_refresh)) { _, _ ->
                doWifiScan()
            }
            dialog.show()
        }, 2500)
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
            // Track whether this run took a broker reference, so an early abort
            // never releases a broker the foreground service is holding.
            var mqttAcquired = false
            try {
                if (pairingCancelled) return@Thread
                updateUi(txtStatus, txtLog, getString(R.string.pairing_connecting_bulb), "")

                // Use the LAN IP captured before the user switched to bulb AP.
                // This is the IP the bulb will use to reach our HTTP server and
                // MQTT broker after it joins the home network.
                val lanIp = savedLanIp.ifEmpty { "192.168.1.100" }

                // Start the MQTT broker on the phone's LAN IP so the bulb can
                // connect after joining the home network. The ESP8266 firmware
                // requires TLS — without a working MQTT connection the bulb
                // blinks indefinitely.
                appendLog(txtLog, "Iniciando broker MQTT en $lanIp:8883…")
                mqttAcquired = MqttBroker.acquire()
                if (mqttAcquired) {
                    appendLog(txtLog, "Broker MQTT listo (TLS)")
                } else {
                    appendLog(txtLog, "⚠ No se pudo iniciar broker MQTT — el foco puede parpadear")
                }

                // Start the pairing HTTP server with the correct broker address
                // so the bulb's /jbalancer/new/bimqtt response points to our broker.
                pairingServer = PairingServer().apply {
                    brokerHost = lanIp
                    brokerPort = 8883
                    start()
                }
                appendLog(txtLog, "Servidor de pairing activo en $lanIp:57542")

                // Get phone IP on bulb AP (192.168.8.x) — sent in the credential
                // payload so the protocol stays well-formed.
                val phoneIp = WifiDetector.getPhoneIp(this@PairingWizardActivity) ?: "192.168.8.100"

                // Bind the socket to the phone's Wi-Fi interface so the handshake
                // reliably reaches the bulb AP (192.168.8.1:9080). A plain
                // DatagramSocket can route through the wrong network on Android.
                val socket = WifiDetector.createWifiSocket(this@PairingWizardActivity)

                // Also pin the whole process to the bulb-AP network for the
                // duration of the handshake. A WiFi without internet (the bulb AP)
                // otherwise makes Android route data through the default network
                // (mobile data), so the UDP handshake never reaches 192.168.8.1.
                val processBound = WifiDetector.bindProcessToBulbAp(this@PairingWizardActivity)
                // Short per-attempt timeout: the bulb answers fast once it is in
                // pairing mode, so we retry with a small window instead of one
                // long blocking timeout.
                // The official Sengled app can take up to ~5 minutes to pair, so we
                // keep retrying for roughly ~2.5 minutes before giving up.
                val maxAttempts = 40
                val attemptDelayMs = 2000L

                // Step 1: Handshake with bounded retries
                // The bulb may take a moment to bring up its config server (UDP 9080)
                // after entering pairing mode. Match the desktop tool's behaviour of
                // retrying instead of failing on a single missed packet.
                var handshake: PairingProtocol.HandshakeResult? = null
                PairingProtocol.logNetworkState(this@PairingWizardActivity)
                // The bulb may omit its MAC from the handshake response; fall
                // back to the BSSID of the AP we're connected to (the bulb's MAC
                // when on its AP), like the desktop tool does with an ARP lookup.
                val fallbackMac = WifiDetector.getConnectedBssid(this@PairingWizardActivity)
                appendLog(txtLog, "BSSID actual (fallback MAC): ${fallbackMac ?: "n/a"}")
                for (attempt in 1..maxAttempts) {
                    if (pairingCancelled) return@Thread
                    if (attempt > 1) {
                        updateUi(
                            txtStatus, txtLog,
                            getString(R.string.pairing_retrying),
                            getString(R.string.pairing_retry_attempt, attempt, maxAttempts)
                        )
                        Thread.sleep(attemptDelayMs)
                    }
                    socket.soTimeout = 2000
                    handshake = PairingProtocol.handshake(socket, fallbackMac)
                    if (handshake != null) {
                        appendLog(txtLog, "Handshake OK en intento $attempt")
                        break
                    } else if (attempt % 5 == 0) {
                        appendLog(txtLog, "Intento $attempt: ${PairingProtocol.lastTransportError ?: "sin respuesta"}")
                        if (attempt == 5) PairingProtocol.logNetworkState(this@PairingWizardActivity)
                    }
                }
                if (handshake == null) {
                    updateUi(
                        txtStatus, txtLog,
                        getString(R.string.pairing_error_handshake),
                        getString(R.string.pairing_handshake_failed, maxAttempts)
                    )
                    if (processBound) WifiDetector.unbindProcess(this@PairingWizardActivity)
                    socket.close()
                    return@Thread
                }
                bulbMac = handshake.mac
                appendLog(txtLog, "MAC: $bulbMac")
                if (pairingCancelled) return@Thread

                // Step 2: Scan WiFi
                appendLog(txtLog, "Escaneando redes WiFi…")
                PairingProtocol.scanWifi(socket)
                Thread.sleep(5000)
                val networks = PairingProtocol.getApList(socket)
                appendLog(txtLog, "Redes encontradas: ${networks.size}")
                if (pairingCancelled) return@Thread

                // Step 3: Re-handshake
                appendLog(txtLog, "Re-handshake…")
                if (!PairingProtocol.reHandshake(socket)) {
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_error_handshake),
                        "El foco no confirmó el re-handshake")
                    if (processBound) WifiDetector.unbindProcess(this@PairingWizardActivity)
                    socket.close()
                    return@Thread
                }
                if (pairingCancelled) return@Thread

                // Step 4: Send credentials
                // Use the phone's LAN IP (not the bulb AP IP) for the HTTP
                // verification endpoints — the bulb will be on the home network
                // after switching and needs to reach our servers there.
                updateUi(txtStatus, txtLog, getString(R.string.pairing_sending_credentials), "")
                appendLog(txtLog, "Enviando credenciales a $ssid…")
                val sent = PairingProtocol.sendCredentials(
                    socket, ssid, password, null, lanIp, 57542
                )
                if (!sent) {
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_error_credentials),
                        "El foco rechazó las credenciales WiFi")
                    if (processBound) WifiDetector.unbindProcess(this@PairingWizardActivity)
                    socket.close()
                    return@Thread
                }
                if (pairingCancelled) return@Thread

                // Step 5: End config
                appendLog(txtLog, "Finalizando configuración…")
                PairingProtocol.endConfig(socket)
                socket.close()
                // The socket is closed and credentials sent; release the bulb-AP
                // network pin so normal routing resumes on the home network.
                if (processBound) WifiDetector.unbindProcess(this@PairingWizardActivity)

                // Step 6: Ask the user to switch back to the home WiFi so the
                // phone and bulb are on the same LAN. We then discover the bulb's
                // new IP with a UDP search_devices scan (Opción B) instead of the
                // unreliable HTTP verification (the bulb cannot reach the phone's
                // 192.168.8.x AP address from the home network).
                updateUi(
                    txtStatus, txtLog,
                    getString(R.string.pairing_switch_network),
                    getString(R.string.pairing_switch_network_hint, ssid)
                )
                appendLog(txtLog, "Credenciales enviadas. Conecta el celular a \"$ssid\"…")

                val leftAp = WifiDetector.waitOffBulbAp(this@PairingWizardActivity, 180_000)
                if (!leftAp) {
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_verification_failed),
                        getString(R.string.pairing_no_network_switch, ssid))
                    return@Thread
                }
                appendLog(txtLog, "Ya estás en \"$ssid\". Buscando el foco en la red…")
                if (pairingCancelled) return@Thread

                // Step 7: Scan the LAN until we find the bulb by its MAC. The bulb
                // needs time to boot and join the network, so keep scanning up to
                // 3 minutes. Even if automatic discovery fails, we still go to the
                // result step with the known MAC so the user can type the IP they
                // see in their router (a reliable fallback).
                updateUi(txtStatus, txtLog, getString(R.string.pairing_searching_bulb), "")
                val discovered = WifiDetector.findBulbByMac(this@PairingWizardActivity, bulbMac, 180_000)
                if (discovered != null) {
                    bulbIp = discovered.ip
                    // The discovery replies with the bulb's REAL LAN MAC. That is
                    // authoritative: the handshake fallback (AP BSSID) can differ
                    // from it in the last byte, which would make the saved id/name
                    // and every future MAC lookup wrong. Prefer the reported MAC.
                    if (discovered.mac.isNotEmpty() && discovered.mac != bulbMac) {
                        appendLog(txtLog, "MAC del handshake ($bulbMac) ajustada a la MAC real del foco: ${discovered.mac}")
                        bulbMac = discovered.mac
                    }
                } else {
                    updateUi(txtStatus, txtLog, getString(R.string.pairing_verification_failed),
                        getString(R.string.pairing_scan_failed_hint))
                }
                if (pairingCancelled) return@Thread
                // Always land on the result step: the MAC is known from the
                // handshake, and the user can confirm/edit the auto-detected IP or
                // type the one shown in their router.
                handler.post { showStep(4) }

            } catch (e: Exception) {
                updateUi(txtStatus, txtLog, "Error: ${e.message}", e.stackTraceToString())
            } finally {
                // Release the broker reference acquired for pairing. The shared
                // broker stays alive if the MqttBrokerService holds it. Never
                // release a reference this run did not take.
                if (mqttAcquired) MqttBroker.release()
                pairingServer?.stop()
                pairingServer = null
            }
        }.start()
    }

    // ── Step 4: Result ─────────────────────────────────────────────────

    private fun showResult() {
        val txtIp = findViewById<TextView>(R.id.txtBulbIp)
        val txtMac = findViewById<TextView>(R.id.txtBulbMac)
        val editName = findViewById<TextInputEditText>(R.id.editBulbName)
        val editStaticIp = findViewById<TextInputEditText>(R.id.editStaticIp)

        txtMac.text = bulbMac

        // The IP may already have been discovered on the LAN. Show it when we
        // have it; otherwise tell the user it is optional (the app can find it
        // later via the MAC).
        if (bulbIp.isNotEmpty()) {
            txtIp.text = bulbIp
            editStaticIp.setText(bulbIp)
        } else {
            txtIp.text = getString(R.string.pairing_no_ip_detected)
        }

        // Suggest a default name
        editName.setText("Foco ${bulbMac.takeLast(5)}")
    }

    private fun saveBulbAndFinish() {
        val editName = findViewById<TextInputEditText>(R.id.editBulbName)
        val editStaticIp = findViewById<TextInputEditText>(R.id.editStaticIp)

        val name = editName.text?.toString()?.trim() ?: ""
        // IP is optional: the bulb is identified by its MAC. If the user does
        // not type one, fall back to the auto-discovered IP (if any). The app
        // can re-discover the current IP later using the saved MAC.
        val ip = editStaticIp.text?.toString()?.trim()?.ifEmpty { bulbIp } ?: ""

        if (name.isEmpty()) {
            editName.error = "Ingresá un nombre para el foco"
            return
        }

        // Generate a safe ID from the MAC's 12 hex chars (last 6), ignoring
        // any separator style. Stripping only ':' is not enough: the handshake
        // may fall back to the AP BSSID, which Android returns dash separated
        // ("80-A0-36-B1-93-73"), so the last 6 chars would be wrong and the id
        // would drift by one digit from the MAC-derived value.
        val bulbId = bulbMac.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            .takeLast(6)
            .lowercase()

        val bulb = Bulb(
            id = bulbId,
            name = name,
            ip = ip
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
