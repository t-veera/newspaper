package app.newspaper.source

import app.newspaper.model.Birthday
import app.newspaper.model.Comic
import app.newspaper.model.Edition
import app.newspaper.model.Health
import app.newspaper.model.Message
import app.newspaper.model.News
import app.newspaper.model.Renewal
import app.newspaper.model.ScheduleItem
import app.newspaper.model.ScienceLead
import app.newspaper.model.SportLine
import app.newspaper.model.TaskItem
import app.newspaper.model.Weather
import java.time.LocalDate

/*
 * Data sources for one edition. Milestone 1 only has fixture implementations; real ones
 * (notification listener, calendar provider, Garmin via Chaquopy, feeds) come later and
 * must fetch any images themselves and pass them on as data URIs.
 *
 * A source that cannot deliver should throw; EditionAssembler turns that into an empty
 * section plus a warning, so one failing feed never blocks the paper.
 */

interface WeatherSource {
    suspend fun weather(date: LocalDate): Weather?
}

interface ScienceSource {
    suspend fun lead(date: LocalDate): ScienceLead?
}

interface NewsSource {
    suspend fun news(date: LocalDate): News
}

interface SportsSource {
    suspend fun results(date: LocalDate): List<SportLine>
}

interface CalendarSource {
    suspend fun schedule(date: LocalDate): List<ScheduleItem>
    suspend fun birthdays(date: LocalDate): List<Birthday>
    suspend fun renewals(date: LocalDate): List<Renewal>
}

interface TasksSource {
    suspend fun tasks(date: LocalDate): List<TaskItem>
}

/** Yesterday's summary. Null (or a throw) makes the health box print dashes. */
interface HealthSource {
    suspend fun yesterday(date: LocalDate): Health?
}

interface MessagesSource {
    suspend fun unread(date: LocalDate): List<Message>
}

interface ComicSource {
    suspend fun comic(date: LocalDate): Comic?
}

class EditionSources(
    val weather: WeatherSource,
    val science: ScienceSource,
    val news: NewsSource,
    val sports: SportsSource,
    val calendar: CalendarSource,
    val tasks: TasksSource,
    val health: HealthSource,
    val messages: MessagesSource,
    val comic: ComicSource,
)

/** Serves every section from one fixture edition. */
class FixtureSource(private val fixture: Edition) :
    WeatherSource, ScienceSource, NewsSource, SportsSource, CalendarSource, TasksSource,
    HealthSource, MessagesSource, ComicSource {

    override suspend fun weather(date: LocalDate) = fixture.weather
    override suspend fun lead(date: LocalDate) = fixture.science
    override suspend fun news(date: LocalDate) = fixture.news
    override suspend fun results(date: LocalDate) = fixture.sports
    override suspend fun schedule(date: LocalDate) = fixture.schedule
    override suspend fun birthdays(date: LocalDate) = fixture.birthdays
    override suspend fun renewals(date: LocalDate) = fixture.renewals
    override suspend fun tasks(date: LocalDate) = fixture.tasks
    override suspend fun yesterday(date: LocalDate) = fixture.health
    override suspend fun unread(date: LocalDate) = fixture.messages
    override suspend fun comic(date: LocalDate) = fixture.comic

    fun asSources() = EditionSources(this, this, this, this, this, this, this, this, this)
}
