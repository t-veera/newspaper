package app.newspaper.edition

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.newspaper.R
import app.newspaper.settings.SettingsStore
import app.newspaper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** Generates the edition in the background, then notifies and re-arms. */
class EditionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                val result = withTimeout(50_000) { TodayEdition.generate(app) }
                EditionNotifier.ready(app, result.warnings.size)
            } catch (e: Exception) {
                Log.w("EditionAlarm", "Scheduled edition failed: ${e.javaClass.simpleName}")
                EditionNotifier.failed(app)
            } finally {
                DailySchedule.sync(app)
                pending.finish()
            }
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
    private const val ID = 1

    fun ready(context: Context, warnings: Int) = post(context, "Today's edition is ready",
        if (warnings == 0) "Tap to view or share." else "Ready with $warnings layout warnings. Tap to review.")

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
