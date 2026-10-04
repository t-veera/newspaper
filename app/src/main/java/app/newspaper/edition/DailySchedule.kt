package app.newspaper.edition

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.newspaper.R
import app.newspaper.settings.SettingsStore
import app.newspaper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Arms one alarm for the next edition time; each run re-arms the following day. */
object DailySchedule {

    private const val TAG = "DailySchedule"

    /** Next occurrence of [time] strictly after [now]. */
    fun nextRun(now: LocalDateTime, time: LocalTime): LocalDateTime {
        val today = now.toLocalDate().atTime(time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    fun canUseExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Arms or cancels the alarm to match Settings. Safe to call any time. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val settings = SettingsStore(app).load()
        val alarms = app.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(app, 0, Intent(app, EditionAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.cancel(intent)
        if (!settings.dailyEnabled) return
        val zone = ZoneId.systemDefault()
        val at = nextRun(LocalDateTime.now(zone), settings.editionTime).atZone(zone).toInstant().toEpochMilli()
        if (canUseExactAlarms(app)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            // Without the exact-alarm grant the system may delay this by some minutes.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
        Log.i(TAG, "Next edition armed (exact=${canUseExactAlarms(app)})")
    }
}

/**
 * Starts [EditionService] for the run. An exact alarm may start a foreground service from the
 * background; without the exact-alarm grant that can be refused, so the run happens here instead.
 */
class EditionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        try {
            app.startForegroundService(Intent(app, EditionService::class.java))
            return
        } catch (e: IllegalStateException) {
            Log.w("EditionAlarm", "Foreground start refused; generating in the receiver")
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                EditionRun.run(app, RECEIVER_TIMEOUT_MS)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** A receiver gets about a minute before the system kills it. */
        const val RECEIVER_TIMEOUT_MS = 50_000L
    }
}

/**
 * Runs the scheduled edition as a foreground service. An alarm alone gets only ~10 s of network
 * while the phone is idle, after which every feed fails and the paper prints yesterday's copies.
 */
class EditionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(EditionNotifier.WORKING_ID, EditionNotifier.working(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            try {
                EditionRun.run(applicationContext, SERVICE_TIMEOUT_MS)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SERVICE_TIMEOUT_MS = 180_000L
    }
}

/** One scheduled run: generate, notify, re-arm for tomorrow. */
object EditionRun {
    suspend fun run(app: Context, timeoutMs: Long) {
        try {
            val result = withTimeout(timeoutMs) { TodayEdition.generate(app) }
            EditionNotifier.ready(app, result.warnings.size)
        } catch (e: Exception) {
            Log.w("EditionAlarm", "Scheduled edition failed: ${e.javaClass.simpleName}")
            EditionNotifier.failed(app)
        } finally {
            DailySchedule.sync(app)
        }
    }
}

/** Re-arms the alarm after reboot, app update, clock or time zone change, or exact-alarm grant. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = DailySchedule.sync(context)
}

/** Notifications carry no edition content, only that it is ready. */
object EditionNotifier {

    private const val CHANNEL = "editions"
    private const val WORKING_CHANNEL = "editions-working"
    private const val ID = 1
    const val WORKING_ID = 2

    fun ready(context: Context, warnings: Int) = post(context, "Today's edition is ready",
        if (warnings == 0) "Tap to view or share." else "Ready with $warnings note${if (warnings > 1) "s" else ""}. Tap to review.")

    /** Shown while the scheduled edition is being made (required for the foreground service). */
    fun working(context: Context): android.app.Notification {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(WORKING_CHANNEL, "Making the edition", NotificationManager.IMPORTANCE_LOW))
        return android.app.Notification.Builder(context, WORKING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Making today's edition…")
            .setOngoing(true)
            .build()
    }

    fun failed(context: Context) = post(context, "Edition not generated", "Open the app and tap Generate to see why.")

    private fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Daily edition", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify(ID, notification)
    }
}
