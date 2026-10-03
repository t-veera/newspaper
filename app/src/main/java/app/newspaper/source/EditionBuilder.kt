package app.newspaper.source

import android.content.Context
import app.newspaper.model.Birthday
import app.newspaper.model.Comic
import app.newspaper.model.Edition
import app.newspaper.model.Footer
import app.newspaper.model.Health
import app.newspaper.model.Masthead
import app.newspaper.model.News
import app.newspaper.model.Renewal
import app.newspaper.model.ScheduleItem
import app.newspaper.model.ScienceLead
import app.newspaper.model.SportLine
import app.newspaper.model.TaskItem
import app.newspaper.model.Weather
import app.newspaper.settings.AppSettings
import app.newspaper.source.calendar.AndroidCalendar
import app.newspaper.source.calendar.DeviceCalendarSource
import app.newspaper.source.messages.CapturedMessagesSource
import app.newspaper.source.messages.MessageListener
import app.newspaper.source.messages.MessageStore
import app.newspaper.net.FeedCache
import app.newspaper.net.Http
import app.newspaper.net.SecretStore
import app.newspaper.source.feeds.ComicFeed
import app.newspaper.source.feeds.Comics
import app.newspaper.source.feeds.Journals
import app.newspaper.source.feeds.NewsFeed
import app.newspaper.source.feeds.NewsRules
import app.newspaper.source.feeds.Outlets
import app.newspaper.source.feeds.ScienceFeed
import app.newspaper.source.feeds.ScienceSummary
import app.newspaper.source.feeds.TeamScoresFeed
import app.newspaper.source.garmin.GarminClient
import app.newspaper.source.garmin.GarminHealthSource
import app.newspaper.source.feeds.TheSportsDb
import app.newspaper.source.weather.WeatherFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds today's edition: personal sections from on-device sources, public sections from the
 * feeds chosen in Settings (fetched by Kotlin; the WebView stays offline), health from the
 * sample until Garmin is connected.
 */
class EditionBuilder(
    private val context: Context,
    private val settings: AppSettings,
    private val fixture: Edition,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val warnings = mutableListOf<String>()
    private fun warn(w: String) = synchronized(warnings) { warnings += w }
    private val cache = FeedCache(context)

    /** Fetches fresh; on failure prints the last good copy (up to [MAX_STALE_DAYS] old) with a warning. */
    private suspend fun <T> cached(key: String, label: String, serializer: KSerializer<T>, fetch: suspend () -> T): T =
        try {
            fetch().also { cache.put(key, serializer, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val old = cache.get(key, serializer)
            val ageDays = old?.let { (System.currentTimeMillis() - it.savedAtMillis) / 86_400_000 }
            if (old == null || ageDays!! > MAX_STALE_DAYS) throw e
            warn("$label: feed unavailable (${e.message ?: e.javaClass.simpleName}); printed the copy from " +
                if (ageDays == 0L) "earlier today" else "$ageDays day(s) ago")
            old.value
        }

    suspend fun build(now: LocalDateTime = LocalDateTime.now(zone)): AssembledEdition {
        val date = now.toLocalDate()
        val calendar = AndroidCalendar(context, zone)
        val deviceCalendar = if (calendar.hasPermission()) DeviceCalendarSource(calendar, settings, zone) else null
        val messages = CapturedMessagesSource(MessageStore(context), settings.messageApps, zone)

        val india = settings.newsIndia.mapNotNull(Outlets::byId)
        val world = settings.newsWorld.mapNotNull(Outlets::byId)
        val journal = Journals.byId(settings.scienceJournal)
        val secrets = SecretStore(context)
        val apiKey = secrets.get(SecretStore.ANTHROPIC_API_KEY)
        val domains = WeatherFeed.DOMAINS + (india + world).flatMap { it.domains } + journal.domains +
            TheSportsDb.DOMAINS + Comics.DOMAINS + ScienceSummary.DOMAINS + GarminClient.DOMAINS
        val http = Http(domains)
        val slowHttp = Http(ScienceSummary.DOMAINS, timeoutMs = AI_TIMEOUT_MS.toInt())

        val newsFeed = NewsFeed(http, cache, india, world, NewsRules.topics(settings.newsIndiaTopics),
            NewsRules.topics(settings.newsWorldTopics), NewsRules.topics(settings.newsSkip))
        val weatherFeed = WeatherFeed(context, http, cache, settings.weatherPlace, settings.weatherUseLocation, zone)
        val scienceFeed = ScienceFeed(http, cache, journal, settings.scienceInterests.split(','))
        val sample = if (settings.sampleFallback && !secrets.has(SecretStore.GARMIN_TOKENS)) FixtureSource(fixture) else null

        val sources = EditionSources(
            weather = object : WeatherSource {
                override suspend fun weather(date: LocalDate) =
                    cached("weather", "Weather", Weather.serializer()) { weatherFeed.weather(date) }
            },
            science = object : ScienceSource {
                override suspend fun lead(date: LocalDate) = cached("science", "Science", ScienceLead.serializer()) {
                    val lead = scienceFeed.lead(date) ?: throw java.io.IOException("No paper")
                    if (apiKey == null) lead else try {
                        withTimeout(AI_TIMEOUT_MS) { ScienceSummary(slowHttp, apiKey).rewrite(lead, journal.name) }
                    } catch (e: CancellationException) {
                        if (e is TimeoutCancellationException) lead.also { warn("Science summary timed out; printed the abstract") } else throw e
                    } catch (e: Exception) {
                        warn("Science summary failed (${e.message ?: e.javaClass.simpleName}); printed the abstract")
                        lead
                    }
                }
            },
            news = object : NewsSource {
                override suspend fun news(date: LocalDate) =
                    if (india.isEmpty() && world.isEmpty()) News()
                    else cached("news", "News", News.serializer()) { newsFeed.news(date) }
            },
            sports = object : SportsSource {
                override suspend fun results(date: LocalDate) =
                    if (settings.followedTeams.isEmpty()) emptyList()
                    else cached("sports", "Sports", ListSerializer(SportLine.serializer())) {
                        val key = SecretStore(context).get(SecretStore.SPORTSDB_KEY) ?: TheSportsDb.FREE_KEY
                        TeamScoresFeed(TheSportsDb(http, key), settings.followedTeams, zone).results(date)
                    }
            },
            calendar = deviceCalendar ?: NoSource,
            tasks = if (settings.tasksCalendarIds.isNotEmpty()) deviceCalendar ?: NoSource else NoSource,
            // Garmin when signed in (a failure prints dashes, per the spec); otherwise sample or dashes.
            health = if (secrets.has(SecretStore.GARMIN_TOKENS)) GarminHealthSource(secrets, http, zone) else sample ?: NoSource,
            messages = messages,
            comic = object : ComicSource {
                override suspend fun comic(date: LocalDate) =
                    cached("comic", "Comic", Comic.serializer()) { ComicFeed(context, http, settings.comic).comic(date) }
            },
        )
        val footer = Footer(
            publicNote = "Public edition: science, news, sports and weather." + if (sample != null) " Health is sample data." else "",
            privateNote = "Private edition: generated on-device. Not for sharing.",
        )
        val assembled = EditionAssembler(sources).assemble(date, masthead(settings, now), footer)

        val all = (assembled.warnings + synchronized(warnings) { warnings.toList() }).toMutableList()
        if (newsFeed.failed.isNotEmpty()) all += "News unavailable from ${newsFeed.failed.joinToString()}"
        if (newsFeed.summaryOnly.isNotEmpty()) all += "Only the RSS summary was readable for ${newsFeed.summaryOnly.joinToString()}"
        if (!calendar.hasPermission()) all += "Calendar permission not granted: schedule, birthdays and renewals are empty"
        else if (settings.scheduleCalendarIds.isEmpty()) all += "No calendar chosen for the schedule (Settings)"
        if (!MessageListener.isEnabled(context)) all += "Notification access is off: no messages captured"
        if (messages.omitted > 0) all += "${messages.omitted} older messages left out to fit the page"
        return AssembledEdition(assembled.edition, all)
    }

    companion object {
        private const val MAX_STALE_DAYS = 3L
        private const val AI_TIMEOUT_MS = 35_000L
        private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

        /** Vol. I in the founding year; the issue number is the day of the year (3 Oct 2026 = No. 276). */
        fun masthead(settings: AppSettings, now: LocalDateTime) = Masthead(
            name = settings.mastheadName,
            date = now.toLocalDate().toString(),
            volume = (now.year - settings.foundingYear + 1).coerceAtLeast(1),
            number = now.dayOfYear,
            place = settings.place.ifBlank { null },
            printedAt = now.format(hhmm),
        )
    }
}

/** A source with nothing to say; its sections hide (health prints dashes). */
object NoSource : WeatherSource, ScienceSource, NewsSource, SportsSource, CalendarSource, TasksSource, HealthSource,
    ComicSource {
    override suspend fun weather(date: LocalDate): Weather? = null
    override suspend fun lead(date: LocalDate): ScienceLead? = null
    override suspend fun news(date: LocalDate) = News()
    override suspend fun results(date: LocalDate): List<SportLine> = emptyList()
    override suspend fun schedule(date: LocalDate): List<ScheduleItem> = emptyList()
    override suspend fun birthdays(date: LocalDate): List<Birthday> = emptyList()
    override suspend fun renewals(date: LocalDate): List<Renewal> = emptyList()
    override suspend fun tasks(date: LocalDate): List<TaskItem> = emptyList()
    override suspend fun yesterday(date: LocalDate): Health? = null
    override suspend fun comic(date: LocalDate): Comic? = null
}
