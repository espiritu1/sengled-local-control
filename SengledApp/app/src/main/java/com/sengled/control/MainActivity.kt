package com.sengled.control

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sengled.control.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), BulbAdapter.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: BulbAdapter

    private val bulbs = mutableListOf<Bulb>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)

    companion object {
        private const val PREFS_NAME = "sengled_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Apply saved language. Spanish is the app default, so the stored value
        // is always explicit ("es" or "en") and never falls back to the device
        // locale, which would otherwise leave the app stuck in English when the
        // device language is not Spanish.
        val savedLang = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("lang", "es")
        val locale = Locale(savedLang ?: "es")
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)

        bulbs += BulbRegistry.getBulbs(this).map { bulb ->
            bulb.copy(name = prefs.getString("name_${bulb.id}", null) ?: bulb.name)
        }

        adapter = BulbAdapter(bulbs, this)
        binding.recyclerBulbs.layoutManager = LinearLayoutManager(this)
        binding.recyclerBulbs.adapter = adapter
        setupDragAndDrop()

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnAddBulb.setOnClickListener { showAddBulbChooser() }
        binding.btnInfo.setOnClickListener { showInfoDialog() }
        binding.btnLang.setOnClickListener { toggleLanguage() }
        refreshLanguageButton()

        ScheduleManager.startService(this)
        MqttBrokerService.start(this)

        setupDiagnostics()

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Reload bulbs in case new ones were added via the wizard
        reloadBulbs()
    }

    private fun refresh() {
        bulbs.toList().forEach { bulb ->
            executor.execute {
                // Use one dedicated socket per query. A shared socket would let
                // parallel refreshes of different bulbs receive each other's
                // replies (UDP does not tag responses with a source), mixing up
                // the brightness shown on each card.
                UdpClient().use { udp ->
                    var state = if (bulb.ip.isNotBlank()) udp.getState(bulb.ip) else null
                    var effectiveIp = bulb.ip

                    // The saved IP may be empty (user skipped it during pairing) or
                    // stale (DHCP reassigned it). When the bulb does not answer,
                    // fall back to discovering it on the LAN by its MAC, then
                    // persist the fresh IP so the next refresh is instant.
                    if (state == null) {
                        val mac = BulbRegistry.getMac(this@MainActivity, bulb.id)
                        if (mac.isNotBlank()) {
                            val foundIp = WifiDetector.findBulbByMac(
                                this@MainActivity, mac, 12_000
                            )
                            if (foundIp != null) {
                                effectiveIp = foundIp
                                if (foundIp != bulb.ip) {
                                    BulbRegistry.updateBulbIp(this@MainActivity, bulb.id, foundIp)
                                }
                                state = udp.getState(foundIp)
                            }
                        }
                    }

                    val finalIp = effectiveIp
                    val finalState = state
                    runOnUiThread {
                        val latest = bulbs.firstOrNull { it.id == bulb.id } ?: bulb.copy(ip = finalIp)
                        val updated = if (finalState != null) {
                            // The bulb reports only a latent brightness, never the real
                            // on/off. Use the last power state the user set (if any) so
                            // a bulb that was turned off shows off instead of the stale
                            // brightness value it keeps reporting.
                            val rememberedOn = ScheduleManager.getLastPower(this@MainActivity, bulb.id)
                            val isOn = rememberedOn ?: finalState.on
                            latest.copy(ip = finalIp, brightness = finalState.brightness, isOn = isOn, connected = true)
                        } else {
                            latest.copy(ip = finalIp, connected = false)
                        }
                        updateBulb(updated)
                    }
                } // end UdpClient().use
            }
        }
    }

    private fun reloadBulbs() {
        val freshBulbs = BulbRegistry.getBulbs(this).map { bulb ->
            bulb.copy(name = prefs.getString("name_${bulb.id}", null) ?: bulb.name)
        }
        // Synchronize adapter with the persisted list: add new bulbs, remove
        // ones deleted elsewhere, keep existing ones untouched.
        val freshIds = freshBulbs.map { it.id }.toSet()
        val removedBulbs = bulbs.filter { it.id !in freshIds }
        removedBulbs.forEach { adapter.removeBulb(it.id) }
        bulbs.removeAll { it.id !in freshIds }
        val newBulbs = freshBulbs.filter { it.id !in bulbs.map { b -> b.id } }
        if (newBulbs.isNotEmpty()) {
            bulbs.addAll(newBulbs)
            newBulbs.forEach { adapter.addBulb(it) }
            refresh()
        }
    }

    private fun setupDragAndDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0 // no swipe
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from >= 0 && to >= 0) {
                    adapter.onItemMove(from, to)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                // Visual feedback while dragging: lighten the card so it's clearly
                // the one being moved (theme is dark, so a lighter grey stands out).
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    val card = viewHolder.itemView as? com.google.android.material.card.MaterialCardView
                    card?.setCardBackgroundColor(
                        androidx.core.content.ContextCompat.getColor(
                            this@MainActivity, R.color.card_background_active
                        )
                    )
                    viewHolder.itemView.elevation = 12f * resources.displayMetrics.density
                    // Freeze the brightness slider + switch while dragging so the
                    // seek bar never reacts to the finger passing over it.
                    (viewHolder as? BulbAdapter.BulbHolder)?.let {
                        it.binding.seekBrightness.isEnabled = false
                        it.binding.switchOn.isEnabled = false
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Restore the card's normal look
                val card = viewHolder.itemView as? com.google.android.material.card.MaterialCardView
                card?.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(
                        this@MainActivity, R.color.card_background
                    )
                )
                recyclerView.post { viewHolder.itemView.elevation = 0f }
                // Re-enable the controls of the dragged card.
                (viewHolder as? BulbAdapter.BulbHolder)?.let {
                    it.binding.seekBrightness.isEnabled = true
                    it.binding.switchOn.isEnabled = true
                }
                // Persist the new order once a drag ends
                val ids = adapter.orderIds()
                if (ids.isNotEmpty()) {
                    BulbRegistry.reorderBulbs(this@MainActivity, ids)
                    // Mirror the order in the in-memory list used by refresh()
                    val byId = bulbs.associateBy { it.id }
                    bulbs.clear()
                    for (id in ids) byId[id]?.let { bulbs.add(it) }
                }
            }
        }
        val itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerBulbs)
        // Long-press anywhere on a card starts the drag.
        adapter.onStartDrag = { holder -> itemTouchHelper.startDrag(holder) }
    }

    private fun showAddBulbChooser() {
        val options = arrayOf(
            getString(R.string.add_bulb_choice_pair),
            getString(R.string.add_bulb_choice_manual)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_bulb_choice_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, PairingWizardActivity::class.java))
                    1 -> showAddManualDialog()
                }
            }
            .show()
    }

    private fun showAddManualDialog() {
        val inputName = EditText(this).apply {
            hint = getString(R.string.add_manual_hint_name)
            filters = arrayOf(InputFilter.LengthFilter(40))
        }
        val inputIp = EditText(this).apply {
            hint = getString(R.string.add_manual_hint_ip)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val inputMac = EditText(this).apply {
            hint = getString(R.string.add_manual_hint_mac)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(17))
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(inputName)
            addView(inputIp)
            addView(inputMac)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_manual_title)
            .setMessage(R.string.add_manual_hint)
            .setView(container)
            .setPositiveButton(R.string.add_manual_save, null)
            .setNegativeButton(R.string.rename_cancel, null)
            .show()
            .also { dialog ->
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = inputName.text.toString().trim()
                    val ip = inputIp.text.toString().trim()
                    val mac = inputMac.text.toString().trim()

                    if (name.isEmpty()) {
                        inputName.error = getString(R.string.add_manual_error_name)
                        return@setOnClickListener
                    }
                    if (ip.isEmpty()) {
                        inputIp.error = getString(R.string.add_manual_error_ip)
                        return@setOnClickListener
                    }

                    // Same id scheme as the pairing wizard: last 6 hex chars of
                    // the MAC. Without a MAC, fall back to an IP-derived id.
                    val normalizedMac = mac.replace(":", "").replace("-", "").lowercase()
                    val id = if (normalizedMac.length >= 6) normalizedMac.takeLast(6)
                    else "ip" + ip.replace(".", "")

                    BulbRegistry.addBulb(this@MainActivity, Bulb(id = id, name = name, ip = ip), normalizedMac)
                    Toast.makeText(this, getString(R.string.add_manual_success, name), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    reloadBulbs()
                }
            }
    }

    override fun onSwitchChanged(bulb: Bulb, isOn: Boolean) {
        val current = bulbs.firstOrNull { it.id == bulb.id } ?: bulb
        updateBulb(current.copy(isOn = isOn, connected = null))
        executor.execute {
            val ok = UdpClient().use { it.setSwitch(current.ip, isOn) }
            runOnUiThread {
                val latest = bulbs.firstOrNull { it.id == current.id } ?: current
                // Remember the last power state the user actually set, so reopening
                // the app shows the correct on/off instead of the bulb's latent
                // brightness (UDP reports brightness only, never the real switch).
                ScheduleManager.setLastPower(this, current.id, isOn)
                updateBulb(latest.copy(isOn = isOn, connected = if (ok) true else false))
            }
        }
    }

    override fun onBrightnessChanged(bulb: Bulb, value: Int) {
        val current = bulbs.firstOrNull { it.id == bulb.id } ?: bulb
        updateBulb(current.copy(brightness = value, isOn = value > 0, connected = null))
        ScheduleManager.setLastBrightness(this, current.id, value)
        ScheduleManager.setLastPower(this, current.id, value > 0)
        executor.execute {
            // Slider is 1..100: only the switch turns the bulb off/on.
            // Setting brightness also turns it on (same as the working web panel).
            val ok = UdpClient().use { it.setBrightness(current.ip, value) }
            runOnUiThread {
                val latest = bulbs.firstOrNull { it.id == current.id } ?: current
                updateBulb(latest.copy(brightness = value, isOn = value > 0, connected = if (ok) true else false))
            }
        }
    }

    override fun onRename(bulb: Bulb, newName: String) {
        prefs.edit().putString("name_${bulb.id}", newName).apply()
        val latest = bulbs.firstOrNull { it.id == bulb.id } ?: bulb
        updateBulb(latest.copy(name = newName))
    }

    override fun onEditIp(bulb: Bulb, newIp: String) {
        BulbRegistry.updateBulbIp(this, bulb.id, newIp)
        val latest = bulbs.firstOrNull { it.id == bulb.id } ?: bulb
        updateBulb(latest.copy(ip = newIp))
    }

    override fun onEditSchedule(bulb: Bulb) {
        showRoutineDialogForBulb(bulb)
    }

    override fun onDeleteBulb(bulb: Bulb) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_confirm_message, bulb.name))
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                BulbRegistry.removeBulb(this, bulb.id)
                bulbs.removeAll { it.id == bulb.id }
                adapter.removeBulb(bulb.id)
                ScheduleManager.save(this, bulb.id, false, 0, 0, 1)
            }
            .setNegativeButton(R.string.delete_cancel, null)
            .show()
    }

    private fun showRoutineDialogForBulb(bulb: Bulb) {
        RoutineDialog.show(
            context = this,
            bulb = bulb
        ) { bulbId, enabled, onMinutes, offMinutes, brightness ->
            ScheduleManager.save(this, bulbId, enabled, onMinutes, offMinutes, brightness)
        }
    }

    private fun showInfoDialog() {
        // Sections = the descriptive text, declared as a static layout so the
        // content lives entirely in strings.xml (both ES and EN) and autoLink
        // turns the email/GitHub URLs into tappable links.
        val sectionsView = layoutInflater.inflate(R.layout.dialog_info_sections, null)

        val buttonsView = layoutInflater.inflate(R.layout.dialog_info, null)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(buttonsView)
            addView(sectionsView)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(container)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.info_title_dialog)
            .setView(scrollView)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun toggleLanguage() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getString("lang", "es")
        val newLang = if (current == "en") "es" else "en"

        prefs.edit().putString("lang", newLang).apply()
        recreate()
    }

    private fun refreshLanguageButton() {
        // The button is FIXED to show the CURRENT language (a status indicator,
        // not the target): "ES" while the app is in Spanish, "EN" while in English.
        val currentLang = prefs.getString("lang", "es")
        binding.btnLang.text = if (currentLang == "en") "EN" else "ES"
    }

    private fun setupDiagnostics() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        if (version != null) {
            binding.txtVersion.text = "v$version"
        }

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Snackbar.make(
                binding.root,
                R.string.notif_disabled_message,
                Snackbar.LENGTH_INDEFINITE
            ).setAction(R.string.notif_disabled_action) {
                openNotificationSettings()
            }.show()
        }
    }

    private fun buildRoutineSubtitle(): String {
        for (bulb in BulbRegistry.getBulbs(this)) {
            val config = ScheduleManager.getRoutine(this, bulb.id)
            if (config.enabled) {
                return getString(
                    R.string.main_subtitle_routine_active,
                    ScheduleManager.getBulbName(this, bulb.id),
                    formatTime(config.onMinutes),
                    formatTime(config.offMinutes)
                )
            }
        }
        return getString(R.string.main_subtitle_no_routine)
    }

    private fun formatTime(minutes: Int): String {
        val hour = minutes / 60
        val minute = minutes % 60
        val period = if (hour < 12) "AM" else "PM"
        val hour12 = when (hour % 12) { 0 -> 12; else -> hour % 12 }
        return String.format(Locale.US, "%d:%02d %s", hour12, minute, period)
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateBulb(updated: Bulb) {
        val index = bulbs.indexOfFirst { it.id == updated.id }
        if (index < 0) return
        bulbs[index] = updated
        adapter.updateBulb(updated)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
