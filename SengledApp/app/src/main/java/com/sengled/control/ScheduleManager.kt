package com.sengled.control

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Persists one routine per bulb (enabled + on/off minutes since midnight) in
 * SharedPreferences and manages the lifecycle of [RoutineService], which is the
 * single owner of firing actions. The only AlarmManager use left is a wake-up
 * kick at the next event time so the service's handler fires near the right
 * moment even in deep doze.
 */
object ScheduleManager {

    const val PREFS_NAME = "sengled_prefs"

    const val ACTION_ROUTINE_CHECK = "com.sengled.control.ROUTINE_CHECK"

    private const val KICK_REQUEST_CODE = 4242
    private const val DEFAULT_ON_MINUTES = 19 * 60 + 30
    private const val DEFAULT_OFF_MINUTES = 0
    private const val DEFAULT_ROUTINE_BRIGHTNESS = 1

    data class RoutineConfig(
        val enabled: Boolean,
        val onMinutes: Int,
        val offMinutes: Int,
        val brightness: Int
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRoutine(context: Context, bulbId: String): RoutineConfig {
        val p = prefs(context)
        return RoutineConfig(
            enabled = p.getBoolean("routine_enabled_$bulbId", false),
            onMinutes = p.getInt("routine_on_$bulbId", DEFAULT_ON_MINUTES),
            offMinutes = p.getInt("routine_off_$bulbId", DEFAULT_OFF_MINUTES),
            brightness = p.getInt("routine_brightness_$bulbId", DEFAULT_ROUTINE_BRIGHTNESS)
        )
    }

    /**
     * Persists the config and restarts the scheduling service so it re-evaluates.
     * The service stops itself when nothing is enabled.
     */
    fun save(
        context: Context,
        bulbId: String,
        enabled: Boolean,
        onMinutes: Int,
        offMinutes: Int,
        brightness: Int
    ) {
        prefs(context).edit()
            .putBoolean("routine_enabled_$bulbId", enabled)
            .putInt("routine_on_$bulbId", onMinutes)
            .putInt("routine_off_$bulbId", offMinutes)
            .putInt("routine_brightness_$bulbId", brightness)
            .apply()
        startService(context)
    }

    fun hasEnabledRoutine(context: Context): Boolean =
        BulbRegistry.getBulbs(context).any { getRoutine(context, it.id).enabled }

    fun getBulbName(context: Context, bulbId: String): String =
        prefs(context).getString("name_$bulbId", null)
            ?: BulbRegistry.getBulbs(context).firstOrNull { it.id == bulbId }?.name
            ?: bulbId

    /** Last manual brightness set via the slider, or -1 if never set. */
    fun getLastBrightness(context: Context, bulbId: String): Int =
        prefs(context).getInt("last_brightness_$bulbId", -1)

    fun setLastBrightness(context: Context, bulbId: String, brightness: Int) {
        prefs(context).edit()
            .putInt("last_brightness_$bulbId", brightness)
            .apply()
    }

    /** Epoch minute of the last time this action fired, or -1 if never. */
    fun getLastFired(context: Context, bulbId: String, isOn: Boolean): Long =
        prefs(context).getLong("last_fired_${bulbId}_${if (isOn) "on" else "off"}", -1L)

    fun setLastFired(context: Context, bulbId: String, isOn: Boolean, epochMinute: Long) {
        prefs(context).edit()
            .putLong("last_fired_${bulbId}_${if (isOn) "on" else "off"}", epochMinute)
            .apply()
    }

    /** Starts the foreground scheduling service; it stops itself when idle. */
    fun startService(context: Context) {
        try {
            context.startForegroundService(Intent(context, RoutineService::class.java))
        } catch (_: Exception) {
        }
    }

    /** Wake-up kick so the service's handler fires near the target even in deep doze. */
    fun scheduleWakeKick(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = kickPendingIntent(context)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (_: Exception) {
            try {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } catch (_: Exception) {
            }
        }
    }

    fun cancelWakeKick(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(kickPendingIntent(context))
        } catch (_: Exception) {
        }
    }

    private fun kickPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ACTION_ROUTINE_CHECK)
        return PendingIntent.getBroadcast(
            context,
            KICK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
