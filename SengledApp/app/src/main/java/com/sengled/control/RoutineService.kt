package com.sengled.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns routine firing. It computes the next due on/off
 * event across all enabled routines, sleeps on a background HandlerThread, and
 * sends the UDP command itself when the event fires. An exact alarm is kept as
 * a wake-up kick only: it wakes the device and nudges this service, which stays
 * idempotent through the last-fired markers in SharedPreferences.
 */
class RoutineService : Service() {

    companion object {
        const val ACTION_STOP = "com.sengled.control.ROUTINE_STOP"

        private const val CHANNEL_STATUS = "rutinas"
        private const val CHANNEL_EVENTS = "rutinas_aviso"
        private const val NOTIF_ID_STATUS = 1
        private const val NOTIF_ID_EVENT_BASE = 1000
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val GRACE_FORWARD_MS = 90_000L
        private const val GRACE_BACK_MS = 10 * 60_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
        private const val NOTIF_STAGGER_MS = 400L
        private const val PREFS_NOTIF_COUNTER = "event_notification_id"
        private const val CATCH_UP_DELAY_MS = 2_000L

        private val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.US)
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var notificationCounter = 0

    private val ticker = Runnable { tick() }
    private val catchUpRunnable = Runnable { runCatchUp() }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        thread = HandlerThread("sengled-routine").also { it.start() }
        handler = Handler(thread!!.looper)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sengled:routine")
        notificationCounter = prefs().getInt(PREFS_NOTIF_COUNTER, 0)
        registerNetworkCallback()
        startForeground(NOTIF_ID_STATUS, buildStatusNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRoutine()
            return START_NOT_STICKY
        }
        handler?.let {
            it.removeCallbacks(ticker)
            it.post(ticker)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        val now = System.currentTimeMillis()
        var stagger = 0L
        for (bulb in BulbRegistry.getBulbs(this)) {
            val config = ScheduleManager.getRoutine(this, bulb.id)
            if (!config.enabled) continue
            stagger += fireActionIfDue(bulb, isOn = true, config, now, stagger)
            stagger += fireActionIfDue(bulb, isOn = false, config, now, stagger)
        }

        val next = nextTarget(now)
        if (next == null) {
            stopRoutine()
            return
        }

        val h = handler ?: return
        h.removeCallbacks(ticker)
        val delay = next - System.currentTimeMillis()
        if (delay <= 0) h.post(ticker) else h.postDelayed(ticker, delay)
        ScheduleManager.scheduleWakeKick(this, next)
        updateStatusNotification(next)
    }

    private fun fireActionIfDue(
        bulb: Bulb,
        isOn: Boolean,
        config: ScheduleManager.RoutineConfig,
        now: Long,
        staggerMs: Long
    ): Long {
        val minutesSinceMidnight = if (isOn) config.onMinutes else config.offMinutes
        val occurrence = occurrenceToday(minutesSinceMidnight)
        if (occurrence > now + GRACE_FORWARD_MS) return 0
        if (occurrence <= now - GRACE_BACK_MS) return 0

        val epochMinute = occurrence / 60_000L
        if (ScheduleManager.getLastFired(this, bulb.id, isOn) == epochMinute) return 0
        ScheduleManager.setLastFired(this, bulb.id, isOn, epochMinute)

        val lock = wakeLock
        try {
            lock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            val ok = try {
                if (isOn) {
                    val b = config.brightness.coerceIn(1, 100)
                    UdpClient().use {
                        val brightnessOk = it.setBrightness(bulb.ip, b)
                        val switchOk = it.setSwitch(bulb.ip, true)
                        brightnessOk && switchOk
                    }
                } else {
                    UdpClient().use { it.setSwitch(bulb.ip, false) }
                }
            } catch (_: Exception) {
                false
            }
            if (ok) {
                postEventNotification(bulb, isOn, occurrence, staggerMs)
            } else {
                Log.d("SengledRoutine", "Routine ${if (isOn) "ON" else "OFF"} command failed for ${bulb.name}")
            }
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Exception) {
            }
        }
        return NOTIF_STAGGER_MS
    }

    private fun nextTarget(now: Long): Long? {
        var target = Long.MAX_VALUE
        for (bulb in BulbRegistry.getBulbs(this)) {
            val config = ScheduleManager.getRoutine(this, bulb.id)
            if (!config.enabled) continue
            target = minOf(target, nextOccurrence(config.onMinutes, now))
            target = minOf(target, nextOccurrence(config.offMinutes, now))
        }
        return if (target == Long.MAX_VALUE) null else target
    }

    private fun nextOccurrence(minutesSinceMidnight: Int, after: Long): Long {
        var t = occurrenceToday(minutesSinceMidnight)
        while (t <= after) t += DAY_MILLIS
        return t
    }

    private fun occurrenceToday(minutesSinceMidnight: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutesSinceMidnight / 60)
            set(Calendar.MINUTE, minutesSinceMidnight % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val h = handler ?: return
                    h.removeCallbacks(catchUpRunnable)
                    h.postDelayed(catchUpRunnable, CATCH_UP_DELAY_MS)
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.d("SengledRoutine", "Network callback registration failed: ${e.message}", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val callback = networkCallback ?: return
            networkCallback = null
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    /**
     * Silent reconciliation after a network return. Recovers schedule on/off
     * transitions that the tick could not fire while the network was down, using
     * the exact same `last_fired` guards as the tick so an action already applied
     * is never re-sent. It deliberately does NOT force the strict current state of
     * the schedule onto a bulb: re-applying "expected on" to a bulb the user
     * turned on by hand (or re-applying "expected off" after midnight) was what
     * fought the user and produced the occasional "blinks off then back on"
     * flicker — most visibly on the sala, whose schedule keeps it "expected on"
     * for many hours, whenever the network reconnected (Wi-Fi handover, doze
     * exit, band switch). No state query is used (a powered-off bulb keeps
     * reporting its last brightness) and no notification is posted.
     */
    private fun runCatchUp() {
        try {
            val now = System.currentTimeMillis()
            for (bulb in BulbRegistry.getBulbs(this)) {
                try {
                    val config = ScheduleManager.getRoutine(this, bulb.id)
                    if (!config.enabled) continue
                    recoverTransitions(bulb, config, now)
                } catch (_: Exception) {
                    Log.d("SengledRoutine", "Catch-up failed for ${bulb.name}")
                }
            }
        } catch (_: Exception) {
            Log.d("SengledRoutine", "Catch-up aborted")
        }
    }

    /**
     * Applies a missed on/off transition for [bulb] when its scheduled occurrence
     * has passed and the previous tick never fired it (tracked via `last_fired`).
     * Because it shares those guards with the tick, running this on every network
     * reconnect is idempotent: a transition already applied is skipped, so a bulb
     * the user set by hand is left alone.
     */
    private fun recoverTransitions(bulb: Bulb, config: ScheduleManager.RoutineConfig, now: Long) {
        for (isOn in listOf(true, false)) {
            val minutes = if (isOn) config.onMinutes else config.offMinutes
            val occurrence = occurrenceToday(minutes)
            if (occurrence > now + GRACE_FORWARD_MS) continue
            val epochMinute = occurrence / 60_000L
            if (ScheduleManager.getLastFired(this, bulb.id, isOn) == epochMinute) continue

            ScheduleManager.setLastFired(this, bulb.id, isOn, epochMinute)
            UdpClient().use {
                if (isOn) {
                    val brightness = config.brightness.coerceIn(1, 100)
                    it.setBrightness(bulb.ip, brightness)
                    it.setSwitch(bulb.ip, true)
                } else {
                    it.setSwitch(bulb.ip, false)
                }
            }
        }
    }

    private fun postEventNotification(bulb: Bulb, isOn: Boolean, occurrence: Long, staggerMs: Long) {
        val name = ScheduleManager.getBulbName(this, bulb.id)
        val title = getString(if (isOn) R.string.routine_on_notification else R.string.routine_off_notification, name)
        val body = getString(R.string.routine_notification_body_at, TIME_FORMAT.format(Date(occurrence)))
        val notification = NotificationCompat.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_schedule)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = NOTIF_ID_EVENT_BASE + nextNotificationId()
        val h = handler
        if (staggerMs > 0 && h != null) {
            h.postDelayed({ nm.notify(id, notification) }, staggerMs)
        } else {
            nm.notify(id, notification)
        }
    }

    private fun nextNotificationId(): Int {
        notificationCounter++
        prefs().edit().putInt(PREFS_NOTIF_COUNTER, notificationCounter).apply()
        return notificationCounter
    }

    private fun updateStatusNotification(nextEvent: Long) {
        val text = getString(R.string.routine_service_notification_next, TIME_FORMAT.format(Date(nextEvent)))
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_STATUS, buildStatusNotification(text))
    }

    private fun buildStatusNotification(nextText: String? = null): Notification {
        val text = nextText ?: getString(R.string.routine_service_notification_text)
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_schedule)
            .setContentTitle(getString(R.string.routine_service_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                getString(R.string.routine_service_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                getString(R.string.routine_service_event_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun stopRoutine() {
        ScheduleManager.cancelWakeKick(this)
        handler?.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        super.onDestroy()
    }

    private fun prefs() =
        getSharedPreferences(ScheduleManager.PREFS_NAME, Context.MODE_PRIVATE)
}
