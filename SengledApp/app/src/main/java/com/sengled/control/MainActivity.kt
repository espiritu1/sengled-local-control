package com.sengled.control

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
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
    private val udp = UdpClient()

    companion object {
        private const val PREFS_NAME = "sengled_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Apply saved language
        val savedLang = prefs.getString("lang", null)
        if (savedLang != null) {
            val locale = Locale(savedLang)
            Locale.setDefault(locale)
            val config = resources.configuration
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }

        bulbs += BulbRegistry.getBulbs(this).map { bulb ->
            bulb.copy(name = prefs.getString("name_${bulb.id}", null) ?: bulb.name)
        }

        adapter = BulbAdapter(bulbs, this)
        binding.recyclerBulbs.layoutManager = LinearLayoutManager(this)
        binding.recyclerBulbs.adapter = adapter

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnAddBulb.setOnClickListener {
            startActivity(Intent(this, PairingWizardActivity::class.java))
        }
        binding.btnInfo.setOnClickListener { showInfoDialog() }
        binding.btnLang.setOnClickListener { toggleLanguage() }

        ScheduleManager.startService(this)

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
                val state = udp.getState(bulb.ip)
                runOnUiThread {
                    val latest = bulbs.firstOrNull { it.id == bulb.id } ?: bulb
                    val updated = if (state != null) {
                        latest.copy(brightness = state.brightness, isOn = state.on, connected = true)
                    } else {
                        latest.copy(connected = false)
                    }
                    updateBulb(updated)
                }
            }
        }
    }

    private fun reloadBulbs() {
        val freshBulbs = BulbRegistry.getBulbs(this).map { bulb ->
            bulb.copy(name = prefs.getString("name_${bulb.id}", null) ?: bulb.name)
        }
        // Check for new bulbs
        val currentIds = bulbs.map { it.id }.toSet()
        val newBulbs = freshBulbs.filter { it.id !in currentIds }
        if (newBulbs.isNotEmpty()) {
            bulbs.addAll(newBulbs)
            adapter.notifyDataSetChanged()
            refresh()
        }
    }

    override fun onSwitchChanged(bulb: Bulb, isOn: Boolean) {
        val current = bulbs.firstOrNull { it.id == bulb.id } ?: bulb
        updateBulb(current.copy(isOn = isOn, connected = null))
        executor.execute {
            val ok = udp.setSwitch(current.ip, isOn)
            runOnUiThread {
                val latest = bulbs.firstOrNull { it.id == current.id } ?: current
                updateBulb(latest.copy(isOn = isOn, connected = if (ok) true else false))
            }
        }
    }

    override fun onBrightnessChanged(bulb: Bulb, value: Int) {
        val current = bulbs.firstOrNull { it.id == bulb.id } ?: bulb
        updateBulb(current.copy(brightness = value, isOn = value > 0, connected = null))
        ScheduleManager.setLastBrightness(this, current.id, value)
        executor.execute {
            // Slider is 1..100: only the switch turns the bulb off/on.
            // Setting brightness also turns it on (same as the working web panel).
            val ok = udp.setBrightness(current.ip, value)
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
                adapter.notifyDataSetChanged()
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

    @Suppress("DEPRECATION")
    private fun showInfoDialog() {
        val purple = "#BB86FC"

        fun section(titleKey: Int, textKey: Int): String {
            var text = getString(textKey).replace("fere.espiritu@gmail.com",
                "<a href='mailto:fere.espiritu@gmail.com'><b>fere.espiritu@gmail.com</b></a>")
            text = text.replace("https://github.com/espiritu1/SengledTools",
                "<a href='https://github.com/espiritu1/SengledTools'>https://github.com/espiritu1/SengledTools</a>")
            return "<br><br><font color='$purple' size='18'><b>${getString(titleKey)}</b></font><br><br>$text"
        }

        val disclaimerHtml = "<br><br><hr><br><font size='12' color='#888888'>" +
            "⚠ Esta aplicación NO está afiliada ni es oficial de Sengled.<br><br>" +
            "La app Android fue desarrollada de forma independiente. El protocolo de comunicación UDP se basa en la documentación del proyecto comunitario " +
            "<a href='https://github.com/HamzaETTH/SengledTools'>HamzaETTH/SengledTools</a> en GitHub, permitiendo que los focos funcionen de forma local sin depender de servidores externos.</font>"

        val infoHtml = section(R.string.info_section_how, R.string.info_section_how_text) +
            section(R.string.info_section_routines, R.string.info_section_routines_text) +
            section(R.string.info_section_router, R.string.info_section_router_text) +
            section(R.string.info_section_wifi, R.string.info_section_wifi_text) +
            section(R.string.info_section_contact, R.string.info_section_contact_text) +
            disclaimerHtml

        val textView = android.widget.TextView(this).apply {
            setText(Html.fromHtml(infoHtml, Html.FROM_HTML_MODE_LEGACY))
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(48, 0, 48, 0)
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setLinkTextColor(getColor(R.color.icon_purple))
        }

        val buttonsView = layoutInflater.inflate(R.layout.dialog_info, null)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(buttonsView)
            addView(textView)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(container)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.info_title_dialog)
            .setView(scrollView)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun toggleLanguage() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getString("lang", null)
        val newLang = if (current == "en") null else "en"

        val editor = prefs.edit()
        if (newLang == null) {
            editor.remove("lang")
        } else {
            editor.putString("lang", newLang)
        }
        editor.apply()

        val locale = if (newLang != null) Locale("en") else Locale("es")
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)

        recreate()
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
        udp.close()
    }
}
