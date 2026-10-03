package app.newspaper

import app.newspaper.edition.DailySchedule
import app.newspaper.settings.AppSettings
import app.newspaper.source.EditionBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ScheduleAndMastheadTest {

    @Test
    fun nextRunIsLaterTodayOrTomorrow() {
        val six = LocalTime.of(6, 0)
        assertEquals(LocalDateTime.of(2026, 10, 3, 6, 0), DailySchedule.nextRun(LocalDateTime.of(2026, 10, 3, 5, 59), six))
        assertEquals(LocalDateTime.of(2026, 10, 4, 6, 0), DailySchedule.nextRun(LocalDateTime.of(2026, 10, 3, 6, 0), six))
        assertEquals(LocalDateTime.of(2027, 1, 1, 6, 0), DailySchedule.nextRun(LocalDateTime.of(2026, 12, 31, 23, 0), six))
    }

    @Test
    fun mastheadMatchesReferenceNumbering() {
        val m = EditionBuilder.masthead(AppSettings(foundingYear = 2026), LocalDateTime.of(2026, 10, 3, 6, 0))
        assertEquals(1, m.volume)
        assertEquals(276, m.number)
        assertEquals("2026-10-03", m.date)
        assertEquals("06:00", m.printedAt)
        assertEquals(2, EditionBuilder.masthead(AppSettings(foundingYear = 2026), LocalDateTime.of(2027, 1, 1, 6, 0)).volume)
    }
}
