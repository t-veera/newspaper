package app.newspaper.source.calendar

import app.newspaper.model.Birthday
import app.newspaper.model.Renewal
import app.newspaper.model.ScheduleItem
import app.newspaper.model.TaskItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One event instance as read from CalendarContract.Instances. */
data class CalendarEvent(
    val calendarId: Long,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    /** Recurrence rule of the series, e.g. "FREQ=YEARLY", or null for one-off events. */
    val rrule: String? = null,
)

/**
 * Turns raw calendar instances into edition sections. Pure, so it is unit-tested on the JVM.
 *
 * All-day events are stored at UTC midnight, so their date is read in UTC; timed events use
 * the device zone.
 */
class CalendarRules(private val zone: ZoneId) {

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun startDate(e: CalendarEvent): LocalDate =
        Instant.ofEpochMilli(e.beginMillis).atZone(if (e.allDay) ZoneOffset.UTC else zone).toLocalDate()

    /** Last day the event covers (end is exclusive). */
    fun endDate(e: CalendarEvent): LocalDate {
        val end = Instant.ofEpochMilli(maxOf(e.beginMillis, e.endMillis - 1))
        return end.atZone(if (e.allDay) ZoneOffset.UTC else zone).toLocalDate()
    }

    fun coversDay(e: CalendarEvent, day: LocalDate) = !day.isBefore(startDate(e)) && !day.isAfter(endDate(e))

    /** Google keeps birthdays in the main calendar: titled "X's birthday", all-day, repeating yearly. */
    fun isBirthday(e: CalendarEvent): Boolean =
        birthdayTitle(e.title) != null || (e.allDay && e.rrule?.contains("FREQ=YEARLY", ignoreCase = true) == true)

    /** Today's events except birthdays: all-day first, then by start time. Earlier-day events show "Cont.". */
    fun schedule(events: List<CalendarEvent>, today: LocalDate): List<ScheduleItem> =
        events.filter { coversDay(it, today) && it.title.isNotBlank() && !isBirthday(it) }
            .sortedWith(compareBy({ !it.allDay }, { it.beginMillis }, { it.title }))
            .map { e ->
                val time = when {
                    e.allDay -> "All day"
                    startDate(e).isBefore(today) -> "Cont."
                    else -> Instant.ofEpochMilli(e.beginMillis).atZone(zone).format(hhmm)
                }
                ScheduleItem(time = time, title = e.title.trim())
            }
            .distinct()

    fun birthdays(events: List<CalendarEvent>, today: LocalDate): List<Birthday> =
        events.filter { coversDay(it, today) && isBirthday(it) }
            .mapNotNull { birthdayName(it.title) }
            .distinct()
            .map { Birthday(name = it, whenLabel = "Today") }

    /** To-dos from a task calendar (e.g. Todoist sync): today's events, skipping ones marked done. */
    fun tasks(events: List<CalendarEvent>, today: LocalDate): List<TaskItem> =
        events.filter { coversDay(it, today) && it.title.isNotBlank() && !isDone(it.title) }
            .sortedWith(compareBy({ !it.allDay }, { it.beginMillis }, { it.title }))
            .map { TaskItem(text = it.title.trim()) }
            .distinct()

    /**
     * Events starting in the next [lookaheadDays]. When the calendar is shared with other roles
     * ([keywordsOnly]), only titles that read like renewals count.
     */
    fun renewals(events: List<CalendarEvent>, today: LocalDate, lookaheadDays: Int, keywordsOnly: Boolean = false): List<Renewal> =
        events.map { it to startDate(it) }
            .filter { (e, d) -> e.title.isNotBlank() && !d.isBefore(today) && !d.isAfter(today.plusDays(lookaheadDays.toLong())) }
            .filter { (e, _) -> !isBirthday(e) && (!keywordsOnly || renewalKeywords.containsMatchIn(e.title)) }
            .sortedWith(compareBy({ it.second }, { it.first.title }))
            .map { (e, d) -> Renewal(whenLabel = whenLabel(today, d), text = e.title.trim()) }
            .distinct()

    companion object {
        fun whenLabel(today: LocalDate, day: LocalDate): String =
            when (val n = ChronoUnit.DAYS.between(today, day)) {
                0L -> "Today"
                1L -> "Tomorrow"
                else -> "In $n days"
            }

        private val birthdayPatterns = listOf(
            Regex("""^(.+?)['’]s\s+birthday$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)['’]\s+birthday$""", RegexOption.IGNORE_CASE),
            Regex("""^birthday\s*[:\-–]\s*(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+birthday$""", RegexOption.IGNORE_CASE),
            Regex("""^happy\s+birthday,?\s+(.+?)!?$""", RegexOption.IGNORE_CASE),
        )

        private val doneMarks = Regex("^\\s*[✓✔☑]")

        /** Task-sync tools prefix completed items with a check mark. */
        fun isDone(title: String) = doneMarks.containsMatchIn(title)

        /** Words that mark an event in a shared calendar as a renewal. */
        val renewalKeywords = Regex("""\b(renew\w*|subscription|expir\w*|premium|due\s+date|due)\b""", RegexOption.IGNORE_CASE)

        /** The name in a birthday-style title ("Meera's birthday" -> "Meera"), or null. */
        fun birthdayTitle(title: String): String? {
            val t = title.trim()
            for (p in birthdayPatterns) p.find(t)?.let { return it.groupValues[1].trim() }
            return null
        }

        /** Name for the birthday box; yearly all-day events without the word "birthday" keep their title. */
        fun birthdayName(title: String): String? = birthdayTitle(title) ?: title.trim().ifEmpty { null }
    }
}
