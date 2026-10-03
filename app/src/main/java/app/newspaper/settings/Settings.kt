package app.newspaper.settings

import android.content.Context
import app.newspaper.source.feeds.FollowedTeam
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalTime

/** Package names of the messaging apps the notification listener may read. */
object MessagingApps {
    val all: Map<String, String> = linkedMapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp",
        "org.thoughtcrime.securesms" to "Signal",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
    )

    /** App names in print order. */
    val names: List<String> = all.values.distinct()
}

data class AppSettings(
    val mastheadName: String = "The First Light",
    val place: String = "Hyderabad",
    /** Vol. I is this year; the issue number is the day of the year. */
    val foundingYear: Int = 2026,
    val scheduleCalendarIds: Set<Long> = emptySet(),
    val birthdayCalendarId: Long? = null,
    val renewalsCalendarId: Long? = null,
    val renewalsLookaheadDays: Int = 14,
    /** Calendars whose events are to-dos, e.g. Todoist's Google Calendar sync. */
    val tasksCalendarIds: Set<Long> = emptySet(),
    /** Names from [MessagingApps.names]. */
    val messageApps: Set<String> = MessagingApps.names.toSet(),
    val dailyEnabled: Boolean = false,
    val editionTime: LocalTime = LocalTime.of(6, 0),
    /** Weather: a typed place name, or the phone's coarse location when [weatherUseLocation] is on. */
    val weatherPlace: String = "Hyderabad",
    val weatherUseLocation: Boolean = false,
    /** Outlet ids from Outlets: two India slots, two World slots. */
    val newsIndia: List<String> = listOf("the-hindu", "indian-express"),
    val newsWorld: List<String> = listOf("bbc-world", "guardian-world"),
    /** Comma-separated topics per region; empty means "trending" (stories several outlets carry). */
    val newsIndiaTopics: String = "",
    val newsWorldTopics: String = "",
    /** Comma-separated words; stories mentioning any of them are skipped. */
    val newsSkip: String = "",
    /** Journal id from Journals. */
    val scienceJournal: String = "nature",
    /** Comma-separated topics; papers matching them are preferred over simply the newest. */
    val scienceInterests: String = "",
    /** Teams followed via Settings search; one score line each. */
    val followedTeams: List<FollowedTeam> = emptyList(),
    /** Comic id from Comics, e.g. "gocomics:calvinandhobbes" or "xkcd". */
    val comic: String = "gocomics:calvinandhobbes",
    /** Health has no real source until Garmin arrives; print the sample instead of dashes. */
    val sampleFallback: Boolean = true,
)

/** Plain SharedPreferences in app-private storage (backups are disabled app-wide). */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            mastheadName = prefs.getString(K_NAME, null) ?: d.mastheadName,
            place = prefs.getString(K_PLACE, null) ?: d.place,
            foundingYear = prefs.getInt(K_FOUNDED, d.foundingYear),
            scheduleCalendarIds = prefs.getStringSet(K_SCHEDULE, null)?.mapNotNull { it.toLongOrNull() }?.toSet()
                ?: d.scheduleCalendarIds,
            birthdayCalendarId = prefs.getLong(K_BIRTHDAYS, -1).takeIf { it >= 0 },
            renewalsCalendarId = prefs.getLong(K_RENEWALS, -1).takeIf { it >= 0 },
            renewalsLookaheadDays = prefs.getInt(K_LOOKAHEAD, d.renewalsLookaheadDays),
            tasksCalendarIds = prefs.getStringSet(K_TASKS_CALS, null)?.mapNotNull { it.toLongOrNull() }?.toSet()
                ?: setOfNotNull(prefs.getLong(K_TASKS_CAL, -1).takeIf { it >= 0 }), // single-calendar setting before multi-select
            messageApps = prefs.getStringSet(K_APPS, null)?.toSet() ?: d.messageApps,
            dailyEnabled = prefs.getBoolean(K_DAILY, d.dailyEnabled),
            editionTime = LocalTime.of(prefs.getInt(K_HOUR, d.editionTime.hour), prefs.getInt(K_MINUTE, d.editionTime.minute)),
            sampleFallback = prefs.getBoolean(K_SAMPLE, d.sampleFallback),
            weatherPlace = prefs.getString(K_W_PLACE, null) ?: d.weatherPlace,
            weatherUseLocation = prefs.getBoolean(K_W_LOC, d.weatherUseLocation),
            newsIndia = prefs.getString(K_INDIA, null)?.split(',')?.filter { it.isNotBlank() } ?: d.newsIndia,
            newsWorld = prefs.getString(K_WORLD, null)?.split(',')?.filter { it.isNotBlank() } ?: d.newsWorld,
            newsIndiaTopics = prefs.getString(K_INDIA_TOPICS, null) ?: d.newsIndiaTopics,
            newsWorldTopics = prefs.getString(K_WORLD_TOPICS, null) ?: d.newsWorldTopics,
            newsSkip = prefs.getString(K_NEWS_SKIP, null) ?: d.newsSkip,
            scienceJournal = prefs.getString(K_JOURNAL, null) ?: d.scienceJournal,
            scienceInterests = prefs.getString(K_INTERESTS, null) ?: d.scienceInterests,
            followedTeams = prefs.getString(K_TEAMS, null)?.let {
                runCatching { teamsJson.decodeFromString(ListSerializer(FollowedTeam.serializer()), it) }.getOrNull()
            } ?: d.followedTeams,
            comic = prefs.getString(K_COMIC, null) ?: d.comic,
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString(K_NAME, s.mastheadName)
            .putString(K_PLACE, s.place)
            .putInt(K_FOUNDED, s.foundingYear)
            .putStringSet(K_SCHEDULE, s.scheduleCalendarIds.map { it.toString() }.toSet())
            .putLong(K_BIRTHDAYS, s.birthdayCalendarId ?: -1)
            .putLong(K_RENEWALS, s.renewalsCalendarId ?: -1)
            .putInt(K_LOOKAHEAD, s.renewalsLookaheadDays)
            .putStringSet(K_TASKS_CALS, s.tasksCalendarIds.map { it.toString() }.toSet())
            .remove(K_TASKS_CAL)
            .putStringSet(K_APPS, s.messageApps)
            .putBoolean(K_DAILY, s.dailyEnabled)
            .putInt(K_HOUR, s.editionTime.hour)
            .putInt(K_MINUTE, s.editionTime.minute)
            .putBoolean(K_SAMPLE, s.sampleFallback)
            .putString(K_W_PLACE, s.weatherPlace)
            .putBoolean(K_W_LOC, s.weatherUseLocation)
            .putString(K_INDIA, s.newsIndia.joinToString(","))
            .putString(K_WORLD, s.newsWorld.joinToString(","))
            .putString(K_INDIA_TOPICS, s.newsIndiaTopics)
            .putString(K_WORLD_TOPICS, s.newsWorldTopics)
            .putString(K_NEWS_SKIP, s.newsSkip)
            .putString(K_JOURNAL, s.scienceJournal)
            .putString(K_INTERESTS, s.scienceInterests)
            .putString(K_TEAMS, teamsJson.encodeToString(ListSerializer(FollowedTeam.serializer()), s.followedTeams))
            .putString(K_COMIC, s.comic)
            .apply()
    }

    private companion object {
        const val K_NAME = "masthead_name"
        const val K_PLACE = "place"
        const val K_FOUNDED = "founding_year"
        const val K_SCHEDULE = "schedule_calendars"
        const val K_BIRTHDAYS = "birthday_calendar"
        const val K_RENEWALS = "renewals_calendar"
        const val K_LOOKAHEAD = "renewals_lookahead_days"
        const val K_TASKS_CAL = "tasks_calendar"
        const val K_TASKS_CALS = "tasks_calendars"
        const val K_APPS = "message_apps"
        const val K_DAILY = "daily_enabled"
        const val K_HOUR = "edition_hour"
        const val K_MINUTE = "edition_minute"
        const val K_SAMPLE = "sample_fallback"
        const val K_W_PLACE = "weather_place"
        const val K_W_LOC = "weather_use_location"
        const val K_INDIA = "news_india"
        const val K_WORLD = "news_world"
        const val K_INDIA_TOPICS = "news_india_topics"
        const val K_WORLD_TOPICS = "news_world_topics"
        const val K_NEWS_SKIP = "news_skip"
        const val K_JOURNAL = "science_journal"
        const val K_INTERESTS = "science_interests"
        const val K_TEAMS = "followed_teams"
        val teamsJson = Json { ignoreUnknownKeys = true }
        const val K_COMIC = "comic"
    }
}
