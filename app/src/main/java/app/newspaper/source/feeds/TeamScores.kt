package app.newspaper.source.feeds

import app.newspaper.model.SportLine
import app.newspaper.net.Http
import app.newspaper.source.SportsSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A team the user follows. Stored in settings; ids are TheSportsDB team ids. */
@Serializable
data class FollowedTeam(val id: String, val name: String, val sport: String, val league: String = "")

/** TheSportsDB: team search, last result and next fixture. Public data only. */
class TheSportsDb(private val http: Http, private val key: String = FREE_KEY) {

    @Serializable data class Team(
        val idTeam: String, val strTeam: String, val strSport: String? = null, val strLeague: String? = null,
        val strCountry: String? = null,
    )
    @Serializable private data class Teams(val teams: List<Team>? = null)

    @Serializable data class Event(
        val strEvent: String? = null,
        val strHomeTeam: String? = null,
        val strAwayTeam: String? = null,
        val intHomeScore: String? = null,
        val intAwayScore: String? = null,
        val strResult: String? = null,
        val dateEvent: String? = null,
        val strTime: String? = null,
        val strTimestamp: String? = null,
        val strSport: String? = null,
        val strStatus: String? = null,
    )
    @Serializable private data class Results(val results: List<Event>? = null)
    @Serializable private data class Events(val events: List<Event>? = null)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val base get() = "https://www.thesportsdb.com/api/v1/json/$key"

    /** Tries [SearchRules.variants] of the query and merges the hits, since the API matches name prefixes only. */
    suspend fun search(query: String): List<Team> {
        val seen = LinkedHashMap<String, Team>()
        for (v in SearchRules.variants(query)) {
            val q = URLEncoder.encode(v, "UTF-8")
            json.decodeFromString(Teams.serializer(), http.getText("$base/searchteams.php?t=$q")).teams.orEmpty()
                .forEach { seen.putIfAbsent(it.idTeam, it) }
        }
        return seen.values.toList()
    }

    suspend fun last(teamId: String): List<Event> =
        json.decodeFromString(Results.serializer(), http.getText("$base/eventslast.php?id=${enc(teamId)}")).results.orEmpty()

    suspend fun next(teamId: String): List<Event> =
        json.decodeFromString(Events.serializer(), http.getText("$base/eventsnext.php?id=${enc(teamId)}")).events.orEmpty()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** TheSportsDB's public free key; a paid key can be saved in Settings for fresher data. */
        const val FREE_KEY = "123"
        val DOMAINS = setOf("thesportsdb.com")
    }
}

/** Query rewriting for TheSportsDB's prefix-only team search. Pure, unit-tested. */
object SearchRules {
    private val women = Regex("\\b(women'?s?|ladies|female|wfc)\\b", RegexOption.IGNORE_CASE)
    private val aliases = listOf(
        Regex("\\bman\\.? ?utd\\b", RegexOption.IGNORE_CASE) to "Manchester United",
        Regex("\\bman\\.? ?city\\b", RegexOption.IGNORE_CASE) to "Manchester City",
        Regex("\\butd\\b", RegexOption.IGNORE_CASE) to "United",
        Regex("\\bsrh\\b", RegexOption.IGNORE_CASE) to "Sunrisers Hyderabad",
        Regex("\\brcb\\b", RegexOption.IGNORE_CASE) to "Royal Challengers",
        Regex("\\bcsk\\b", RegexOption.IGNORE_CASE) to "Chennai Super Kings",
        Regex("\\bmi\\b", RegexOption.IGNORE_CASE) to "Mumbai Indians",
    )

    /** At most 4 distinct queries: as typed, with aliases expanded, and women's-team spellings (W, WFC, Women). */
    fun variants(query: String): List<String> {
        var q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()
        val typed = q
        aliases.forEach { (r, full) -> q = r.replace(q, full) }
        val out = linkedSetOf(typed, q)
        if (women.containsMatchIn(q)) {
            val base = women.replace(q, "").trim().replace(Regex("\\s+"), " ")
            out += "$base W"
            out += "$base WFC"
            out += "$base Women"
        }
        return out.filter { it.isNotBlank() }.take(4)
    }
}

/** Turns a followed team's events into one printed line. Pure, unit-tested. */
object ScoreRules {

    const val RECENT_DAYS = 3L
    const val UPCOMING_DAYS = 14L
    private val day = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val dayOnly = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** Kick-off in UTC: strTimestamp, else dateEvent + strTime (both UTC in TheSportsDB). */
    fun start(e: TheSportsDb.Event): Instant? {
        e.strTimestamp?.let { ts -> runCatching { return LocalDateTime.parse(ts.removeSuffix("Z").take(19)).toInstant(ZoneOffset.UTC) } }
        val d = e.dateEvent?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val t = e.strTime?.take(8)?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        return (if (t != null) d.atTime(t) else d.atStartOfDay()).toInstant(ZoneOffset.UTC)
    }

    fun result(e: TheSportsDb.Event): String? {
        val home = e.strHomeTeam ?: return null
        val away = e.strAwayTeam ?: return null
        val hs = e.intHomeScore?.trim().orEmpty()
        val aws = e.intAwayScore?.trim().orEmpty()
        val score = if (hs.isNotEmpty() && aws.isNotEmpty()) "$home $hs–$aws $away" else "$home v $away"
        val res = e.strResult?.trim()?.takeIf { it.isNotEmpty() && it.length <= 90 }
        return if (res != null && (hs.isEmpty() || e.strSport == "Cricket")) "$home v $away: $res" else score
    }

    private val finished = setOf("FT", "AET", "PEN", "AP", "MATCH FINISHED", "FINISHED", "AWARDED")
    private val notStarted = setOf("", "NS", "NOT STARTED", "TBD", "POSTPONED", "PST", "CANC", "CANCELLED")
    private val phases = mapOf("1H" to "1st half", "HT" to "half-time", "2H" to "2nd half", "ET" to "extra time",
        "BT" to "break", "P" to "penalties", "LIVE" to "live", "INT" to "interrupted")

    private fun status(e: TheSportsDb.Event) = e.strStatus?.trim()?.uppercase().orEmpty()

    /** In play right now: a live status code, or kicked off within the last 3 hours and not finished. */
    fun isLive(e: TheSportsDb.Event, now: Instant): Boolean {
        val st = status(e)
        if (st in phases) return true
        if (st in finished) return false
        val at = start(e) ?: return false
        return st !in notStarted && at.isBefore(now) && ChronoUnit.MINUTES.between(at, now) <= 180
    }

    fun line(team: FollowedTeam, last: List<TheSportsDb.Event>, next: List<TheSportsDb.Event>, now: Instant, zone: ZoneId): SportLine? {
        val all = (last + next).distinctBy { listOf(it.strHomeTeam, it.strAwayTeam, it.strTimestamp ?: it.dateEvent) }

        // 1. A match in progress at print time, with the score so far.
        all.firstOrNull { isLive(it, now) }?.let { e ->
            val phase = phases[status(e)]?.let { " ($it)" }.orEmpty()
            result(e)?.let { return SportLine(sport = team.name, text = "Live: $it$phase.") }
        }
        // 2. The latest finished result within the last few days (may sit in either list).
        val recent = all.mapNotNull { e -> start(e)?.let { e to it } }
            .filter { (e, at) ->
                at.isBefore(now) && ChronoUnit.HOURS.between(at, now) <= RECENT_DAYS * 24 &&
                    (status(e) in finished || (status(e).isEmpty() && e.intHomeScore != null))
            }
            .maxByOrNull { it.second }
        if (recent != null) result(recent.first)?.let { return SportLine(sport = team.name, text = "$it.") }
        // 3. Otherwise the next fixture within two weeks.
        val upcoming = all.mapNotNull { e -> start(e)?.let { e to it } }
            .filter { (e, at) -> at.isAfter(now) && ChronoUnit.DAYS.between(now, at) <= UPCOMING_DAYS && status(e) !in finished }
            .minByOrNull { it.second } ?: return null
        val (e, at) = upcoming
        val local = at.atZone(zone)
        val hasTime = e.strTimestamp != null || e.strTime != null
        val vs = listOfNotNull(e.strHomeTeam, e.strAwayTeam).joinToString(" v ").ifEmpty { e.strEvent.orEmpty() }
        return SportLine(sport = team.name, text = "Next: $vs, ${local.format(if (hasTime) day else dayOnly)}.")
    }
}

/** One line per followed team, in the order they were followed. */
class TeamScoresFeed(
    private val db: TheSportsDb,
    private val teams: List<FollowedTeam>,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) : SportsSource {

    override suspend fun results(date: LocalDate): List<SportLine> = coroutineScope {
        val lines = teams.map { t ->
            async { runCatching { ScoreRules.line(t, db.last(t.id), db.next(t.id), now(), zone) } }
        }.map { it.await() }
        if (lines.isNotEmpty() && lines.all { it.isFailure }) throw IOException("Scores unavailable")
        lines.mapNotNull { it.getOrNull() }
    }
}
