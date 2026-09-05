package com.sengled.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the foreground scheduling service after boot, on time/zone changes, and
 * on the wake-up kick alarm. The service owns firing; this receiver never sends
 * UDP commands itself.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                ScheduleManager.startService(context)
                MqttBrokerService.start(context)
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ScheduleManager.ACTION_ROUTINE_CHECK -> ScheduleManager.startService(context)
        }
    }
}
