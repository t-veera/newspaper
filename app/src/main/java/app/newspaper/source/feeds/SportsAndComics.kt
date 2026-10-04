package app.newspaper.source.feeds

import app.newspaper.model.Comic
import app.newspaper.net.FeedCache
import app.newspaper.net.Http
import app.newspaper.source.ComicSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.LocalDate
import java.util.Base64

/** A comic: xkcd's JSON, an open RSS feed, or a GoComics strip ("gocomics:<slug>", fetched via [GoComicsFetcher]). */
data class ComicStrip(val id: String, val name: String, val rss: String? = null) {
    val goComicsSlug: String? get() = id.removePrefix("gocomics:").takeIf { id.startsWith("gocomics:") }
}

object Comics {
    const val XKCD = "xkcd"
    val all = listOf(
        ComicStrip("gocomics:calvinandhobbes", "Calvin and Hobbes"),
        ComicStrip("gocomics:peanuts", "Peanuts"),
        ComicStrip("gocomics:garfield", "Garfield"),
        ComicStrip("gocomics:pearlsbeforeswine", "Pearls Before Swine"),
        ComicStrip(XKCD, "xkcd"),
        ComicStrip("smbc", "Saturday Morning Breakfast Cereal", "https://www.smbc-comics.com/comic/rss"),
    )
    val DOMAINS = setOf("xkcd.com", "smbc-comics.com") + GoComicsFetcher.DOMAINS

    /** Older settings stored bare GoComics slugs ("calvinandhobbes"). */
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == "gocomics:$id" } ?: all.first()

    /** Data URI from image bytes, typed by their magic numbers. */
    fun dataUri(bytes: ByteArray): String {
        val type = when {
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpeg"
            bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
            bytes.size > 11 && String(bytes, 8, 4) == "WEBP" -> "webp"
            else -> throw IOException("Comic is not a PNG, JPEG, GIF or WebP image")
        }
        return "data:image/$type;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    /** First comic image in the newest feed item's HTML, skipping emoji and tracking pixels. */
    fun imageFromFeed(xml: String): Pair<String, String>? {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val item = doc.selectFirst("item") ?: return null
        val title = Rss.text(item.selectFirst("> title")?.text())
        val html = item.select("> description, > encoded, > content|encoded").joinToString(" ") { it.text() }
        val src = Jsoup.parse(html).select("img[src]").map { it.attr("src") }
            .firstOrNull { it.startsWith("https://") && !it.contains("emoji") && !it.contains("feeds.feedburner.com/~r") }
            ?: return null
        return src to title
    }
}

/** Fetches the strip image in Kotlin and hands it to the template as a data URI. */
class ComicFeed(
    private val context: android.content.Context,
    private val http: Http,
    private val cache: FeedCache,
    private val stripId: String,
) : ComicSource {

    @Serializable private data class Xkcd(val img: String, val alt: String = "", val safe_title: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun comic(date: LocalDate): Comic {
        val strip = Comics.byId(stripId)
        strip.goComicsSlug?.let { slug ->
            val fetcher = GoComicsFetcher(context)
            val key = "comic-$slug"
            var found = fetcher.strip(slug, date)
            // Today's strip is not out yet and the newest one already ran in an earlier edition: print the one before it.
            if (found.date.toString() in cache.printedBefore(key, date)) found = fetcher.strip(slug, found.date.minusDays(1))
            val bytes = http.get(found.imageUrl, mapOf("Referer" to "https://www.gocomics.com/"))
            cache.markPrinted(key, date, found.date.toString())
            return Comic(title = strip.name.uppercase(), imageDataUri = Comics.dataUri(bytes), altText = "${strip.name}, ${found.date}")
        }
        if (strip.id == Comics.XKCD) {
            val x = json.decodeFromString(Xkcd.serializer(), http.getText("https://xkcd.com/info.0.json"))
            return Comic(title = "XKCD", imageDataUri = Comics.dataUri(http.get(x.img)),
                altText = listOf(x.safe_title, x.alt).filter { it.isNotBlank() }.joinToString(": "))
        }
        val (img, title) = Comics.imageFromFeed(http.getText(strip.rss!!)) ?: throw IOException("No image in the ${strip.name} feed")
        return Comic(title = strip.name.uppercase(), imageDataUri = Comics.dataUri(http.get(img)),
            altText = listOf(strip.name, title).filter { it.isNotBlank() }.joinToString(": "))
    }
}
