package app.newspaper

import app.newspaper.model.Edition
import app.newspaper.model.EditionJson
import app.newspaper.model.Masthead
import app.newspaper.source.EditionAssembler
import app.newspaper.source.FixtureSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The WebView render of an empty edition is covered by the instrumented EditionPdfTest. */
class EmptyEditionTest {

    private val masthead = Masthead(name = "The First Light", date = "2026-10-03", volume = 1, number = 276)

    @Test
    fun emptyEditionSerializesWithOnlyTheMasthead() {
        val json = EditionJson.encodeToString(Edition.serializer(), Edition(masthead))
        val back = EditionJson.decodeFromString(Edition.serializer(), json)
        assertEquals(Edition(masthead), back)
        assertTrue(back.messages.isEmpty() && back.sports.isEmpty() && back.news.india.isEmpty())
    }

    @Test
    fun minimalJsonDeserializes() {
        val e = EditionJson.decodeFromString(Edition.serializer(),
            """{"masthead":{"name":"X","date":"2026-01-01","volume":1,"number":1}}""")
        assertNull(e.weather)
        assertNull(e.health)
        assertTrue(e.schedule.isEmpty())
    }

    @Test
    fun failingSourcesGiveEmptySectionsAndWarnings() = runBlocking {
        val broken = object : app.newspaper.source.HealthSource {
            override suspend fun yesterday(date: LocalDate) = throw IllegalStateException("login expired")
        }
        val base = FixtureSource(Edition(masthead)).asSources()
        val sources = app.newspaper.source.EditionSources(base.weather, base.science, base.news, base.sports,
            base.calendar, base.tasks, broken, base.messages, base.comic)
        val assembled = EditionAssembler(sources).assemble(LocalDate.of(2026, 10, 3), masthead)
        assertNull(assembled.edition.health)
        assertEquals(listOf("Health unavailable (login expired)"), assembled.warnings)
    }
}
