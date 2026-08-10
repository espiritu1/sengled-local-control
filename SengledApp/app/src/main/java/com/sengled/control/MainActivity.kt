package com.sengled.control

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
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

        bulbs += BulbRegistry.bulbs.map { bulb ->
            bulb.copy(name = prefs.getString("name_${bulb.id}", null) ?: bulb.name)
        }

        adapter = BulbAdapter(bulbs, this)
        binding.recyclerBulbs.layoutManager = LinearLayoutManager(this)
        binding.recyclerBulbs.adapter = adapter

        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    refresh()
                    true
                }
                R.id.action_schedule -> {
                    showRoutineDialog()
                    true
                }
                else -> false
            }
        }

        ScheduleManager.startService(this)

        setupDiagnostics()

        refresh()
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

    private fun showRoutineDialog() {
        val bulb = bulbs.firstOrNull() ?: return
        RoutineDialog.show(
            context = this,
            bulbs = bulbs.toList(),
            initialBulbId = bulb.id
        ) { bulbId, enabled, onMinutes, offMinutes, brightness ->
            ScheduleManager.save(this, bulbId, enabled, onMinutes, offMinutes, brightness)
        }
    }

    private fun setupDiagnostics() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }

        val routinePart = buildRoutineSubtitle()
        val subtitle = if (version != null) {
            getString(R.string.main_subtitle_version, version) + " · " + routinePart
        } else {
            routinePart
        }
        binding.toolbar.subtitle = subtitle

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
        for (bulb in BulbRegistry.bulbs) {
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
