package app.newspaper.source

import app.newspaper.model.Edition
import app.newspaper.model.Footer
import app.newspaper.model.Masthead
import app.newspaper.model.News
import app.newspaper.render.QrCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

class AssembledEdition(val edition: Edition, val warnings: List<String>)

/** Pulls every section from its source and adds the derived QR code. */
class EditionAssembler(private val sources: EditionSources) {

    suspend fun assemble(date: LocalDate, masthead: Masthead, footer: Footer = Footer()): AssembledEdition {
        val warnings = mutableListOf<String>()

        // Section failures are reported by name and reason only; never edition content.
        suspend fun <T> section(name: String, empty: T, load: suspend () -> T): T = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(warnings) { warnings += "$name unavailable (${e.message ?: e.javaClass.simpleName})" }
            empty
        }

        // Network sections run concurrently so one slow feed does not add up with the others.
        val edition = coroutineScope {
            val science = async { section("Science", null) { sources.science.lead(date) } }
            val weather = async { section("Weather", null) { sources.weather.weather(date) } }
            val news = async { section("News", News()) { sources.news.news(date) } }
            val sports = async { section("Sports", emptyList()) { sources.sports.results(date) } }
            val comic = async { section("Comic", null) { sources.comic.comic(date) } }
            val lead = science.await()
            val qr = lead?.sourceUrl?.let { url -> section("QR code", null) { QrCode.svgDataUri(url) } }
            Edition(
                masthead = masthead,
                footer = footer,
                weather = weather.await(),
                science = lead?.copy(qrDataUri = qr),
                news = news.await(),
                sports = sports.await(),
                schedule = section("Calendar", emptyList()) { sources.calendar.schedule(date) },
                tasks = section("Tasks", emptyList()) { sources.tasks.tasks(date) },
                birthdays = section("Birthdays", emptyList()) { sources.calendar.birthdays(date) },
                renewals = section("Renewals", emptyList()) { sources.calendar.renewals(date) },
                health = section("Health", null) { sources.health.yesterday(date) },
                messages = section("Messages", emptyList()) { sources.messages.unread(date) },
                comic = comic.await(),
            )
        }
        return AssembledEdition(edition, warnings)
    }
}
