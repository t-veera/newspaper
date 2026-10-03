package app.newspaper

import app.newspaper.model.Birthday
import app.newspaper.model.Renewal
import app.newspaper.model.ScheduleItem
import app.newspaper.source.calendar.CalendarEvent
import app.newspaper.source.calendar.CalendarRules
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarRulesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val rules = CalendarRules(zone)
    private val today = LocalDate.of(2026, 10, 3)

    private fun timed(title: String, start: LocalDateTime, minutes: Long = 60) = CalendarEvent(1, title,
        start.atZone(zone).toInstant().toEpochMilli(), start.plusMinutes(minutes).atZone(zone).toInstant().toEpochMilli(), false)

    private fun allDay(title: String, day: LocalDate, days: Long = 1, rrule: String? = null) = CalendarEvent(2, title,
        day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        day.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, rrule)

    @Test
    fun scheduleListsTodayAllDayFirstThenByTime() {
        val events = listOf(
            timed("Lunch with Anu", today.atTime(12, 30)),
            timed("Standup", today.atTime(8, 30), 15),
            timed("Yesterday's thing", today.minusDays(1).atTime(10, 0)),
            timed("Tomorrow's thing", today.plusDays(1).atTime(10, 0)),
            allDay("Holiday", today),
            allDay("Yesterday all day", today.minusDays(1)),
            timed("Overnight shift", today.minusDays(1).atTime(22, 0), 10 * 60),
        )
        assertEquals(listOf(
            ScheduleItem("All day", "Holiday"),
            ScheduleItem("Cont.", "Overnight shift"),
            ScheduleItem("08:30", "Standup"),
            ScheduleItem("12:30", "Lunch with Anu"),
        ), rules.schedule(events, today))
    }

    @Test
    fun allDayEventsUseUtcDateNotLocalZone() {
        // Stored at 00:00 UTC = 05:30 IST; must still count as 3 October only.
        val e = allDay("Holiday", today)
        assertEquals(listOf("Holiday"), rules.schedule(listOf(e), today).map { it.title })
        assertEquals(emptyList<ScheduleItem>(), rules.schedule(listOf(e), today.plusDays(1)))
    }

    @Test
    fun birthdayNamesComeFromTitles() {
        assertEquals("Meera", CalendarRules.birthdayName("Meera's birthday"))
        assertEquals("Meera", CalendarRules.birthdayName("Meera’s Birthday"))
        assertEquals("Ravi", CalendarRules.birthdayName("Birthday: Ravi"))
        assertEquals("James", CalendarRules.birthdayName("James' birthday"))
        assertEquals("Anu", CalendarRules.birthdayName("Anu"))
        assertEquals(listOf(Birthday("Meera", "Today")),
            rules.birthdays(listOf(allDay("Meera's birthday", today), allDay("Kiran's birthday", today.plusDays(1))), today))
    }

    @Test
    fun renewalsWithinLookaheadWithLabels() {
        val events = listOf(
            allDay("Domain: example.in", today.plusDays(4)),
            allDay("Cloud storage, annual", today),
            allDay("Music streaming", today.plusDays(9)),
            allDay("Insurance", today.plusDays(1)),
            allDay("Too far", today.plusDays(15)),
            allDay("Past", today.minusDays(1)),
        )
        assertEquals(listOf(
            Renewal("Today", "Cloud storage, annual"),
            Renewal("Tomorrow", "Insurance"),
            Renewal("In 4 days", "Domain: example.in"),
            Renewal("In 9 days", "Music streaming"),
        ), rules.renewals(events, today, 14))
    }

    @Test
    fun sharedMainCalendarSplitsBirthdaysFromSchedule() {
        // Google keeps birthdays in the main calendar alongside ordinary events.
        val events = listOf(
            allDay("Meera's birthday", today, rrule = "FREQ=YEARLY"),
            allDay("Ravi", today, rrule = "FREQ=YEARLY;WKST=SU"),
            allDay("Day off", today),
            timed("Standup", today.atTime(8, 30)),
        )
        assertEquals(listOf("Meera", "Ravi"), rules.birthdays(events, today).map { it.name })
        assertEquals(listOf("Day off", "Standup"), rules.schedule(events, today).map { it.title })
    }

    @Test
    fun sharedCalendarRenewalsNeedKeywords() {
        val events = listOf(
            allDay("Domain renewal: example.in", today.plusDays(4)),
            allDay("Netflix subscription", today.plusDays(2)),
            allDay("Car insurance due", today.plusDays(6)),
            timed("Dentist", today.plusDays(1).atTime(10, 0)),
            allDay("Meera's birthday", today.plusDays(3), rrule = "FREQ=YEARLY"),
        )
        assertEquals(listOf("Netflix subscription", "Domain renewal: example.in", "Car insurance due"),
            rules.renewals(events, today, 14, keywordsOnly = true).map { it.text })
        assertEquals(4, rules.renewals(events, today, 14, keywordsOnly = false).size) // birthday still excluded
    }

    @Test
    fun taskCalendarGivesTodaysOpenTasks() {
        val events = listOf(
            allDay("Pack kit bag", today),
            timed("Call electrician", today.atTime(11, 0), 30),
            allDay("✓ Order solder wire", today),
            allDay("Write Sunday draft", today.plusDays(1)),
        )
        assertEquals(listOf("Pack kit bag", "Call electrician"), rules.tasks(events, today).map { it.text })
    }
}
