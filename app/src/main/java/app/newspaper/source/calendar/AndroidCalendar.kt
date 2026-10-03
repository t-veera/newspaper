package app.newspaper.source.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import app.newspaper.model.Birthday
import app.newspaper.model.Renewal
import app.newspaper.model.ScheduleItem
import app.newspaper.model.TaskItem
import app.newspaper.settings.AppSettings
import app.newspaper.source.CalendarSource
import app.newspaper.source.TasksSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    /** Events stored on the phone; tells apart calendars with the same name (e.g. an old, empty "Todoist"). */
    val eventCount: Int = 0,
)

class CalendarPermissionMissing : Exception("Calendar permission not granted")

/** Reads the device calendar store (Google, Samsung, Exchange accounts) without any network. */
class AndroidCalendar(private val context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {

    private val rules = CalendarRules(zone)

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection,
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3)))
            }
        }.orEmpty().let { list ->
            val counts = eventCounts()
            list.map { it.copy(eventCount = counts[it.id] ?: 0) }
        }.sortedWith(compareBy({ it.account }, { it.name }, { -it.eventCount }))
    }

    private fun eventCounts(): Map<Long, Int> =
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.CALENDAR_ID),
            "${CalendarContract.Events.DELETED} = 0", null, null)?.use { c ->
            buildMap { while (c.moveToNext()) merge(c.getLong(0), 1, Int::plus) }
        }.orEmpty()

    /** Instances overlapping [from, to] (inclusive dates), padded a day each side for all-day UTC events. */
    suspend fun events(calendarIds: Set<Long>, from: LocalDate, to: LocalDate): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            if (calendarIds.isEmpty()) return@withContext emptyList()
            if (!hasPermission()) throw CalendarPermissionMissing()
            val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, begin)
                ContentUris.appendId(it, end)
            }.build()
            val projection = arrayOf(
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.RRULE,
            )
            val ids = calendarIds.joinToString(",")
            // STATUS is often NULL; "NULL != 2" is not true in SQL, so test for it explicitly.
            val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($ids) AND " +
                "(${CalendarContract.Instances.STATUS} IS NULL OR " +
                "${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED})"
            context.contentResolver.query(uri, projection, selection, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(CalendarEvent(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5)))
                    }
                }
            }.orEmpty().filter { e -> !rules.endDate(e).isBefore(from) && !rules.startDate(e).isAfter(to) }
        }
}

/** [CalendarSource] backed by the calendars chosen in Settings. */
class DeviceCalendarSource(
    private val calendar: AndroidCalendar,
    private val settings: AppSettings,
    zone: ZoneId = ZoneId.systemDefault(),
) : CalendarSource, TasksSource {

    private val rules = CalendarRules(zone)

    /** Task calendars never feed the schedule, so to-dos are not printed twice. */
    override suspend fun schedule(date: LocalDate): List<ScheduleItem> =
        rules.schedule(calendar.events(settings.scheduleCalendarIds - settings.tasksCalendarIds, date, date), date)

    override suspend fun tasks(date: LocalDate): List<TaskItem> =
        rules.tasks(calendar.events(settings.tasksCalendarIds, date, date), date)

    override suspend fun birthdays(date: LocalDate): List<Birthday> =
        rules.birthdays(calendar.events(setOfNotNull(settings.birthdayCalendarId), date, date), date)

    override suspend fun renewals(date: LocalDate): List<Renewal> {
        val to = date.plusDays(settings.renewalsLookaheadDays.toLong())
        val id = settings.renewalsCalendarId
        val shared = id != null && (id in settings.scheduleCalendarIds || id == settings.birthdayCalendarId)
        return rules.renewals(calendar.events(setOfNotNull(id), date, to), date, settings.renewalsLookaheadDays,
            keywordsOnly = shared)
    }
}
