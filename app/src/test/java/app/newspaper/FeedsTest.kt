package app.newspaper

import app.newspaper.model.ScienceLead
import app.newspaper.model.WeatherIcon
import app.newspaper.net.Http
import app.newspaper.source.feeds.ArticleText
import app.newspaper.source.feeds.Comics
import app.newspaper.source.feeds.FeedItem
import app.newspaper.source.feeds.Rss
import app.newspaper.source.feeds.ScienceRules
import app.newspaper.source.feeds.ScienceSummary
import app.newspaper.source.feeds.FollowedTeam
import app.newspaper.source.feeds.ScoreRules
import app.newspaper.source.feeds.TheSportsDb
import app.newspaper.source.weather.WeatherRules
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.time.Instant
import java.time.LocalDate

class FeedsTest {

    @Test
    fun parsesRssAndAtom() {
        val rss = """<?xml version="1.0"?><rss><channel><title>Feed</title>
            <item><title>Monsoon &amp; rain</title><link>https://www.thehindu.com/a1</link>
            <description><![CDATA[<p>The weather <b>office</b> said.</p>]]></description>
            <pubDate>Sat, 03 Oct 2026 04:30:00 +0530</pubDate></item>
            <item><title>Insecure</title><link>http://example.com/x</link></item></channel></rss>"""
        val items = Rss.parse(rss)
        assertEquals(1, items.size)
        assertEquals("Monsoon & rain", items[0].title)
        assertEquals("The weather office said.", items[0].summary)
        assertEquals(Instant.parse("2026-10-02T23:00:00Z"), items[0].published)
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Paper</title>
            <link rel="alternate" href="https://journals.plos.org/p1"/><summary>Abstract text</summary>
            <published>2026-10-02T10:00:00Z</published></entry></feed>"""
        assertEquals("https://journals.plos.org/p1", Rss.parse(atom).single().link)
    }

    @Test
    fun extractsArticleBodyAndDropsBoilerplate() {
        val p = "This is a long enough paragraph of real article text that should be kept by the extractor, really."
        val html = """<html><body><nav><p>$p nav</p></nav><article>
            <p>$p one</p><p>Subscribe to our newsletter for the latest updates and more stories every day.</p>
            <p>$p two</p><p>short</p></article><footer><p>$p footer</p></footer></body></html>"""
        assertEquals(listOf("$p one", "$p two"), ArticleText.paragraphs(html, "https://x.com/a"))
    }

    @Test
    fun trimsAtSentenceEnd() {
        val text = ArticleText.trimWords(listOf("One two three. Four five six. Seven eight nine ten."), 7)
        assertEquals("One two three. Four five six.", text)
    }

    @Test
    fun weatherForecastParses() {
        val body = """{"current":{"temperature_2m":28.6,"weather_code":2,"is_day":1},
          "hourly":{"time":["2026-10-03T06:00","2026-10-03T09:00","2026-10-03T15:00"],
            "temperature_2m":[22.1,26.0,31.2],"weather_code":[0,1,61],"precipitation_probability":[0,10,45]},
          "daily":{"temperature_2m_max":[31.4],"temperature_2m_min":[21.0],"sunrise":["2026-10-03T06:02"],
            "sunset":["2026-10-03T17:51"],"uv_index_max":[7.6]}}"""
        val w = WeatherRules.parseForecast(body, LocalDate.of(2026, 10, 3), null)
        assertEquals(WeatherIcon.PARTLY_CLOUDY, w.icon)
        assertEquals("Partly cloudy", w.condition)
        assertEquals(listOf("06", "09", "15"), w.hourly.map { it.hour })
        assertEquals(listOf(null, null, 45), w.hourly.map { it.precipPct })
        assertEquals(WeatherIcon.RAIN, w.hourly[2].icon)
        assertEquals("06:02", w.sunrise)
        assertEquals(8, w.uvIndex)
    }

    @Test
    fun moonPhaseNearKnownDates() {
        // New moon 2024-01-11 11:57 UTC; full moon 2024-01-25 17:54 UTC.
        val nm = WeatherRules.moonPhase(Instant.parse("2024-01-11T11:57:00Z"))
        assertTrue("new moon phase $nm", nm < 0.02 || nm > 0.98)
        val full = WeatherRules.moon(Instant.parse("2024-01-25T17:54:00Z"), "18:01")
        assertEquals("Full Moon", full.phaseName)
        assertTrue(full.illuminationPct!! >= 98)
        assertEquals("23:48", WeatherRules.parseMoonrise("""{"properties":{"moonrise":{"time":"2026-10-03T23:48+05:30"}}}"""))
        assertNull(WeatherRules.parseMoonrise("""{"properties":{}}"""))
    }

    @Test
    fun scienceHelpers() {
        assertEquals("We report a thing.", ScienceRules.arxivAbstract("arXiv:2610.01234v1 Announce Type: new Abstract: We report a thing."))
        val paras = ScienceRules.paragraphs("A one. B two. C three. D four. E five.", perParagraph = 2)
        assertEquals(listOf("A one. B two.", "C three. D four.", "E five."), paras)
    }

    @Test
    fun followedTeamPrintsRecentResultElseNextFixture() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val now = Instant.parse("2026-10-03T00:30:00Z")
        val team = FollowedTeam("133612", "Man United", "Soccer")
        val last = listOf(
            TheSportsDb.Event(strHomeTeam = "Man United", strAwayTeam = "Brighton", intHomeScore = "2", intAwayScore = "3",
                strTimestamp = "2026-10-01T19:00:00"),
            TheSportsDb.Event(strHomeTeam = "Everton", strAwayTeam = "Man United", intHomeScore = "2", intAwayScore = "2",
                strTimestamp = "2026-09-20T14:00:00"),
        )
        val next = listOf(TheSportsDb.Event(strHomeTeam = "Spurs", strAwayTeam = "Man United", strTimestamp = "2026-10-04T13:00:00"))
        assertEquals("Man United 2\u20133 Brighton.", ScoreRules.line(team, last, next, now, zone)!!.text)
        val old = ScoreRules.line(team, last.drop(1), next, now, zone)!!
        assertEquals("Next: Spurs v Man United, Sun 18:30.", old.text)
        assertEquals("Man United", old.sport)
        assertNull(ScoreRules.line(team, emptyList(), emptyList(), now, zone))
    }

    @Test
    fun cricketUsesResultText() {
        val e = TheSportsDb.Event(strHomeTeam = "Sunrisers Hyderabad", strAwayTeam = "Mumbai Indians",
            intHomeScore = "201", intAwayScore = "195", strResult = "Sunrisers Hyderabad won by 6 runs", strSport = "Cricket")
        assertEquals("Sunrisers Hyderabad v Mumbai Indians: Sunrisers Hyderabad won by 6 runs", ScoreRules.result(e))
    }

    @Test
    fun comicDataUriTypesImages() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2)
        assertTrue(Comics.dataUri(png).startsWith("data:image/png;base64,"))
        assertTrue(runCatching { Comics.dataUri("<html>".toByteArray()) }.isFailure)
        val rss = """<rss><channel><item><title>Interesting</title><description><![CDATA[<p><img src="https://s.w.org/images/core/emoji/1f4ac.png"></p>
            <img src="https://www.smbc-comics.com/comics/20261002.png"/>]]></description></item></channel></rss>"""
        assertEquals("https://www.smbc-comics.com/comics/20261002.png" to "Interesting", Comics.imageFromFeed(rss))
    }

    @Test
    fun httpAllowListIsHttpsAndDomainScoped() {
        val http = Http(setOf("bbc.co.uk", "api.open-meteo.com"))
        assertTrue(http.isAllowed(URL("https://feeds.bbc.co.uk/x")))
        assertTrue(http.isAllowed(URL("https://api.open-meteo.com/v1")))
        assertFalse(http.isAllowed(URL("http://feeds.bbc.co.uk/x")))
        assertFalse(http.isAllowed(URL("https://evilbbc.co.uk/x")))
        assertFalse(http.isAllowed(URL("https://geocoding-api.open-meteo.com/x")))
    }

    @Test
    fun claudeRequestShape() {
        val req = ScienceSummary(Http(emptySet()), "sk-ant-test")
            .request(ScienceLead(headline = "T", paragraphs = listOf("Abstract.")), "Nature")
        assertEquals("claude-opus-5-5", req["model"]!!.jsonPrimitive.content)
        assertEquals("default", req["fallbacks"]!!.jsonPrimitive.content)
        val cfg = req["output_config"]!!.jsonObject
        assertEquals("low", cfg["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", cfg["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(req.containsKey("thinking"))
        assertTrue(req["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content.contains("Abstract."))
    }

    @Test
    fun interestsPickMatchingPaperFirst() {
        fun item(t: String, sum: String = "") = FeedItem(t, "https://nature.com/articles/s41586-$t", sum, null)
        val items = listOf(item("Galaxy survey"), item("Neurons fire", "A quantum sensor measures neuroscience signals"), item("Quantum dots"))
        assertEquals(listOf("Neurons fire", "Quantum dots", "Galaxy survey"),
            ScienceRules.rankByInterest(items, listOf("neuroscience", " Quantum")).map { it.title })
        assertEquals(items, ScienceRules.rankByInterest(items, listOf("", " ")))
    }

    @Test
    fun teamSearchVariantsFindWomensTeams() {
        assertEquals(listOf("Manchester United Women", "Manchester United W", "Manchester United WFC"),
            app.newspaper.source.feeds.SearchRules.variants("Manchester United Women"))
        assertEquals(listOf("man utd women", "Manchester United women", "Manchester United W", "Manchester United WFC"),
            app.newspaper.source.feeds.SearchRules.variants("man utd women"))
        assertEquals(listOf("SRH", "Sunrisers Hyderabad"), app.newspaper.source.feeds.SearchRules.variants("SRH"))
        assertEquals(listOf("Arsenal"), app.newspaper.source.feeds.SearchRules.variants("  Arsenal "))
    }

    @Test
    fun newsRanksTopicsThenTrendingAndSkips() {
        fun item(t: String) = FeedItem(t, "https://bbc.co.uk/" + t.hashCode(), "", null)
        val bbc = listOf(item("Pilot attacked at airport"), item("Local council meeting"), item("Climate talks close with funding pledge"))
        val guardian = listOf(item("Climate talks end with modest funding deal"))
        val rules = app.newspaper.source.feeds.NewsRules
        assertEquals("Climate talks close with funding pledge", rules.rank(bbc, listOf(guardian), emptyList(), emptyList()).first().title)
        assertEquals("Local council meeting", rules.rank(bbc, listOf(guardian), listOf("council"), emptyList()).first().title)
        assertTrue(rules.rank(bbc, listOf(guardian), emptyList(), listOf("pilot")).none { it.title.startsWith("Pilot") })
        assertEquals(listOf("space", "ai"), rules.topics("space, AI ,x, "))
    }

    @Test
    fun newsSkipsFormatsAndDuplicates() {
        val r = app.newspaper.source.feeds.NewsRules
        fun item(t: String) = FeedItem(t, "https://x.com/" + t.hashCode(), "", null)
        assertFalse(r.isStory(item("Evening news wrap: cricket gold and more")))
        assertFalse(r.isStory(item("Viral video shows crowd at station")))
        assertFalse(r.isStory(item("LIVE: Protest against CEC continues")))
        assertTrue(r.isStory(item("Metro corridor extension gets final clearance")))
        assertTrue(r.sameStory("Flydubai co-pilot attacked captain with crash axe", "Who is the Flydubai co-pilot who attacked the captain"))
        assertTrue(r.sameStory("Flydubai co-pilot attacked captain with axe, UAE official says",
            "Who is the mystery co-pilot behind the Flydubai attack?"))
        assertFalse(r.sameStory("Climate talks close with funding pledge", "Central banks hold rates as inflation cools"))
    }

    @Test
    fun scienceSkipsNonResearchAndSplitsSentencesSafely() {
        assertFalse(ScienceRules.isResearch(FeedItem("Retraction Note: Ribosomal frameshifting", "https://n.com/a", "", null)))
        assertFalse(ScienceRules.isResearch(FeedItem("Author Correction: Something", "https://n.com/b", "", null)))
        assertTrue(ScienceRules.isResearch(FeedItem("Denisovan fossils from a cave in Laos", "https://n.com/c", "", null)))
        assertEquals(listOf("Work by J. Smith et al. shows a result in Fig. 2.", "A second sentence follows."),
            ScienceRules.sentences("Work by J. Smith et al. shows a result in Fig. 2. A second sentence follows."))
    }

    @Test
    fun articleTextFallsBackToJsonLd() {
        val body = (1..12).joinToString(" ") { "Sentence number $it of the article has enough words to count." }
        val html = """<html><head><script type="application/ld+json">{"@graph":[{"@type":"NewsArticle","articleBody":"$body"}]}</script>
            </head><body><p>Short.</p></body></html>"""
        val paras = ArticleText.paragraphs(html, "https://timesofindia.indiatimes.com/x")
        assertEquals(4, paras.size)
        assertTrue(paras.first().startsWith("Sentence number 1"))
    }

    @Test
    fun liveMatchPrintsScoreSoFar() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val now = Instant.parse("2026-10-03T13:50:00Z")
        val team = FollowedTeam("140226", "Manchester United WFC", "Soccer")
        val live = TheSportsDb.Event(strHomeTeam = "Manchester United WFC", strAwayTeam = "Liverpool FC Women",
            intHomeScore = "1", intAwayScore = "0", strTimestamp = "2026-10-03T12:30:00", strStatus = "2H")
        val old = TheSportsDb.Event(strHomeTeam = "Manchester United WFC", strAwayTeam = "West Ham Women",
            intHomeScore = "2", intAwayScore = "1", strTimestamp = "2026-09-27T12:00:00", strStatus = "FT")
        assertEquals("Live: Manchester United WFC 1\u20130 Liverpool FC Women (2nd half).",
            ScoreRules.line(team, listOf(old), listOf(live), now, zone)!!.text)
        // Finished earlier today but still listed under "next": printed as a result.
        val done = live.copy(strStatus = "FT", intHomeScore = "2")
        assertEquals("Manchester United WFC 2\u20130 Liverpool FC Women.",
            ScoreRules.line(team, listOf(old), listOf(done), now.plusSeconds(4 * 3600), zone)!!.text)
    }
}
