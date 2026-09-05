package com.sengled.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the embedded MQTT broker ([MqttBroker])
 * running persistently so paired bulbs always find their master on TLS port
 * 8883. The service is started at app launch, on boot, and also during the
 * pairing wizard (which acquires an additional reference via [MqttBroker.acquire]).
 *
 * It shares the single persistent "rutinas" notification with [RoutineService]
 * (same channel and id) so the phone shows only one foreground bar instead of
 * two; the routine service overwrites it with the next scheduled time, and any
 * temporary "bulb turned on/off" alert stays a separate auto-cancel notice.
 */
class MqttBrokerService : Service() {

    companion object {
        private const val CHANNEL_ID = "rutinas"
        private const val NOTIF_ID = 1
        private const val TAG = "MqttBrokerService"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, MqttBrokerService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        if (!MqttBroker.acquire()) {
            Log.e(TAG, "MqttBroker.acquire() returned false — stopping service")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        MqttBroker.release()
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_schedule)
            .setContentTitle(getString(R.string.mqtt_broker_notification_title))
            .setContentText(getString(R.string.mqtt_broker_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.routine_service_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
