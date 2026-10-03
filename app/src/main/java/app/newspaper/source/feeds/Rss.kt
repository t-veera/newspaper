package app.newspaper.source.feeds

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class FeedItem(
    val title: String,
    val link: String,
    /** Plain text of the item's description/summary. */
    val summary: String,
    val published: Instant?,
    val categories: List<String> = emptyList(),
)

/** RSS 2.0, RDF and Atom, parsed with jsoup's XML parser so it runs in JVM unit tests. */
object Rss {

    fun parse(xml: String): List<FeedItem> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rss = doc.select("item").map { item ->
            FeedItem(
                title = text(item.selectFirst("> title")?.text()),
                link = item.selectFirst("> link")?.text()?.trim().orEmpty()
                    .ifEmpty { item.selectFirst("> guid")?.text()?.trim().orEmpty() },
                summary = text(item.selectFirst("> description")?.text()),
                published = date(item.selectFirst("> pubDate")?.text() ?: item.selectFirst("> dc|date")?.text()),
                categories = item.select("> category").map { it.text().trim() },
            )
        }
        val atom = doc.select("entry").map { e ->
            FeedItem(
                title = text(e.selectFirst("> title")?.text()),
                link = (e.select("> link").firstOrNull { it.attr("rel").ifEmpty { "alternate" } == "alternate" }
                    ?: e.selectFirst("> link"))?.attr("href").orEmpty(),
                summary = text((e.selectFirst("> summary") ?: e.selectFirst("> content"))?.text()),
                published = date((e.selectFirst("> published") ?: e.selectFirst("> updated"))?.text()),
                categories = e.select("> category").map { it.attr("term") },
            )
        }
        return (rss + atom).filter { it.title.isNotBlank() && it.link.startsWith("https://") }
    }

    /** Descriptions often carry escaped HTML; reduce to clean text. */
    fun text(s: String?): String =
        Jsoup.parse(s.orEmpty()).text().replace(Regex("\\s+"), " ").trim()

    private fun date(s: String?): Instant? {
        val t = s?.trim()?.ifEmpty { null } ?: return null
        return runCatching { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(t) }.getOrNull()
    }
}

/** Pulls the readable body text out of an article page. */
object ArticleText {

    private val boilerplate = Regex(
        "(?i)(subscribe|sign up|newsletter|follow us|read more|also read|click here|download the app|" +
            "advertisement|all rights reserved|copyright|©|whatsapp channel|join our|for all the latest)",
    )
    private val bodySelectors = listOf(
        "[itemprop=articleBody]", "[data-component=text-block]", "#story_text", "#text", ".article-main",
        "[class*=article-body]", "[class*=articlebody]", "[class*=story-body]", "[class*=storyBody]",
        "[class*=content-body]", "article", "main",
    )

    fun paragraphs(html: String, baseUrl: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl)
        val ldBody = jsonLdBody(doc) // before clean(), which removes <script> blocks
        clean(doc)
        for (sel in bodySelectors) {
            val paras = doc.select(sel).flatMap { readable(it) }.distinct()
            if (paras.size >= 2 && paras.sumOf { it.length } >= 400) return paras
        }
        // Many sites (e.g. Times of India) keep the text only in schema.org JSON-LD.
        ldBody?.let { body -> if (body.length >= 400) return splitBody(body) }
        return readable(doc.body() ?: return emptyList()).distinct()
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /** The longest "articleBody" found in any JSON-LD block, searching nested objects and @graph arrays. */
    fun jsonLdBody(doc: Document): String? {
        fun find(e: kotlinx.serialization.json.JsonElement): List<String> = when (e) {
            is kotlinx.serialization.json.JsonObject -> e.entries.flatMap { (k, v) ->
                if (k == "articleBody" && v is kotlinx.serialization.json.JsonPrimitive && v.isString) listOf(v.content) else find(v)
            }
            is kotlinx.serialization.json.JsonArray -> e.flatMap(::find)
            else -> emptyList()
        }
        return doc.select("script[type=application/ld+json]")
            .flatMap { s -> runCatching { find(json.parseToJsonElement(s.data())) }.getOrDefault(emptyList()) }
            .map { Rss.text(it) }
            .maxByOrNull { it.length }
    }

    /** JSON-LD bodies are one long string; regroup into readable paragraphs of about three sentences. */
    private fun splitBody(body: String): List<String> =
        body.split(Regex("(?<=[.!?\u201d\"])\\s+(?=[A-Z\u201c\"])")).chunked(3).map { it.joinToString(" ") }
            .filter { !boilerplate.containsMatchIn(it) }

    /** Page metadata descriptions, used for paper abstracts. */
    fun meta(html: String, vararg names: String): String? {
        val doc = Jsoup.parse(html)
        for (n in names) {
            val v = doc.selectFirst("meta[name=$n], meta[property=$n]")?.attr("content")?.trim()
            if (!v.isNullOrBlank()) return Rss.text(v)
        }
        return null
    }

    fun section(html: String, vararg selectors: String): String? {
        val doc = Jsoup.parse(html)
        for (s in selectors) {
            val t = doc.select(s).text().replace(Regex("\\s+"), " ").trim()
            if (t.length >= 200) return t.removePrefix("Abstract").removePrefix(":").trim()
        }
        return null
    }

    private fun clean(doc: Document) {
        doc.select("script, style, noscript, figure, figcaption, aside, nav, header, footer, form, button, iframe, " +
            "[class*=related], [class*=newsletter], [class*=promo], [class*=advert], [id*=advert], [class*=share], " +
            "[class*=social], [class*=caption], [class*=byline], [class*=subscribe]").remove()
    }

    private fun readable(root: Element): List<String> =
        root.select("p").map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 60 && !boilerplate.containsMatchIn(it) }

    /** At most [maxWords] words, cut at a sentence end where possible. */
    fun trimWords(paragraphs: List<String>, maxWords: Int): String {
        val words = paragraphs.joinToString(" ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= maxWords) return words.joinToString(" ")
        val cut = words.take(maxWords).joinToString(" ")
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > cut.length / 2) cut.substring(0, end + 1) else "$cut…"
    }
}
