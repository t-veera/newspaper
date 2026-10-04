package app.newspaper.source.feeds

import app.newspaper.model.News
import app.newspaper.model.NewsItem
import app.newspaper.net.FeedCache
import app.newspaper.net.Http
import app.newspaper.source.NewsSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.time.LocalDate

enum class Region { INDIA, WORLD }

data class Outlet(
    val id: String,
    val name: String,
    val region: Region,
    val rss: String,
    /** Feed host plus the hosts its article links point at. */
    val domains: Set<String>,
)

object Outlets {
    val all = listOf(
        // Independent outlets first.
        Outlet("scroll", "Scroll", Region.INDIA, "https://feeds.feedburner.com/ScrollinArticles.rss", setOf("feedburner.com", "scroll.in")),
        Outlet("the-news-minute", "The News Minute", Region.INDIA, "https://www.thenewsminute.com/stories.rss", setOf("thenewsminute.com")),
        Outlet("south-first", "South First", Region.INDIA, "https://thesouthfirst.com/feed/", setOf("thesouthfirst.com")),
        Outlet("siasat", "Siasat", Region.INDIA, "https://www.siasat.com/feed/", setOf("siasat.com")),
        Outlet("the-federal", "The Federal", Region.INDIA, "https://thefederal.com/feed", setOf("thefederal.com")),
        Outlet("the-quint", "The Quint", Region.INDIA, "https://www.thequint.com/stories.rss", setOf("thequint.com")),
        Outlet("frontline", "Frontline", Region.INDIA, "https://frontline.thehindu.com/feeder/default.rss", setOf("thehindu.com")),
        Outlet("the-hindu", "The Hindu", Region.INDIA, "https://www.thehindu.com/news/national/feeder/default.rss", setOf("thehindu.com")),
        Outlet("indian-express", "Indian Express", Region.INDIA, "https://indianexpress.com/section/india/feed/", setOf("indianexpress.com")),
        // The India section; the top-stories feed mixes in world news.
        Outlet("times-of-india", "Times of India", Region.INDIA, "https://timesofindia.indiatimes.com/rssfeeds/-2128936835.cms", setOf("indiatimes.com")),
        Outlet("hindustan-times", "Hindustan Times", Region.INDIA, "https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml", setOf("hindustantimes.com")),
        Outlet("the-print", "ThePrint", Region.INDIA, "https://theprint.in/category/india/feed/", setOf("theprint.in")),
        Outlet("ndtv", "NDTV", Region.INDIA, "https://feeds.feedburner.com/ndtvnews-top-stories", setOf("feedburner.com", "ndtv.com")),
        Outlet("bbc-world", "BBC", Region.WORLD, "https://feeds.bbci.co.uk/news/world/rss.xml", setOf("bbci.co.uk", "bbc.co.uk", "bbc.com")),
        Outlet("guardian-world", "The Guardian", Region.WORLD, "https://www.theguardian.com/international/rss", setOf("theguardian.com")),
        Outlet("al-jazeera", "Al Jazeera", Region.WORLD, "https://www.aljazeera.com/xml/rss/all.xml", setOf("aljazeera.com")),
        Outlet("npr-world", "NPR", Region.WORLD, "https://feeds.npr.org/1004/rss.xml", setOf("npr.org")),
        Outlet("dw-world", "DW", Region.WORLD, "https://rss.dw.com/rdf/rss-en-world", setOf("dw.com")),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id }
    fun region(r: Region) = all.filter { it.region == r }
}

/** Story ranking: topics first, otherwise "trending" (carried by several outlets, high in its feed). Pure. */
object NewsRules {

    private val stop = setOf("after", "about", "amid", "says", "said", "with", "from", "that", "this", "over", "into",
        "will", "have", "been", "their", "what", "when", "where", "which", "while", "more", "than", "news", "live",
        "india", "world", "first", "year", "years", "could", "would", "should", "they", "there", "were", "your")

    /** Formats that are not news stories: live blogs, round-ups, galleries, puzzles. */
    private val notAStory = Regex(
        "(?i)\\b(live updates?|live:|live blog|viral video|watch:|video:|photos?:|in pictures|in pics|news wrap|" +
            "morning brief|evening brief|top headlines|quiz|horoscope|crossword|sudoku|wordle|podcast|newsletter)\\b|^live\\b")

    fun isStory(item: FeedItem) = !notAStory.containsMatchIn(item.title)

    /** Outlets' world desks, kept out of the India column. */
    private val foreignDesk = Regex("(?i)/(world|international|global|foreign|nri)/")

    fun isIndiaStory(item: FeedItem) = !foreignDesk.containsMatchIn(java.net.URI(item.link).path.orEmpty())

    /** Two headlines are about the same event when they share 3+ significant words (or 2 of a short headline). */
    fun sameStory(a: String, b: String): Boolean {
        val wa = words(a)
        val wb = words(b)
        val shared = (wa intersect wb).size
        return shared >= 3 || (shared >= 2 && minOf(wa.size, wb.size) <= 4)
    }

    fun topics(csv: String): List<String> = csv.split(',').map { it.trim().lowercase() }.filter { it.length >= 2 }

    private fun mentions(item: FeedItem, word: String) =
        Regex("\\b" + Regex.escape(word), RegexOption.IGNORE_CASE).containsMatchIn(item.title + " " + item.summary)

    /** Significant words, crudely stemmed so "attacked", "attacks" and "attack" match. */
    fun words(title: String): Set<String> =
        title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in stop }
            .map { w -> w.removeSuffix("ing").removeSuffix("ed").removeSuffix("es").removeSuffix("s").takeIf { it.length >= 4 } ?: w }
            .toSet()

    /** How many other outlets have a headline sharing at least two significant words with [item]. */
    fun trend(item: FeedItem, others: List<List<FeedItem>>): Int {
        val w = words(item.title)
        return others.count { feed -> feed.any { (words(it.title) intersect w).size >= 2 } }
    }

    /**
     * Candidates for one outlet, best first. Skipped words remove a story outright. Score: 3 per matching
     * topic, then 2 per other outlet carrying the story, plus up to 1 for being near the top of the feed.
     */
    fun rank(items: List<FeedItem>, others: List<List<FeedItem>>, topics: List<String>, skip: List<String>): List<FeedItem> {
        val pool = items.take(25).filter { item -> isStory(item) && skip.none { mentions(item, it) } }
        return pool.withIndex().sortedByDescending { (i, item) ->
            3.0 * topics.count { mentions(item, it) } + 2.0 * trend(item, others) + (pool.size - i).toDouble() / pool.size
        }.map { it.value }
    }
}

/**
 * One story per chosen outlet: its newest item not printed in the last few days, with body text
 * extracted from the article page (RSS summary as fallback), trimmed to fit the page-1 layout.
 */
class NewsFeed(
    private val http: Http,
    private val cache: FeedCache,
    private val india: List<Outlet>,
    private val world: List<Outlet>,
    private val indiaTopics: List<String> = emptyList(),
    private val worldTopics: List<String> = emptyList(),
    private val skip: List<String> = emptyList(),
) : NewsSource {

    /** Outlets that printed only their RSS summary on the last call (article page unreadable). */
    val summaryOnly = mutableListOf<String>()

    /** Outlets whose feed failed on the last call; the other outlets still print. */
    val failed = mutableListOf<String>()

    override suspend fun news(date: LocalDate): News = coroutineScope {
        // Every outlet's feed first, so each story can be compared against the others for "trending".
        // Every outlet of the chosen regions is read, so "trending" means what that region's press is carrying now.
        val chosen = (india + world).toSet()
        val outlets = Outlets.all.filter { o -> chosen.any { it.region == o.region } }
        val feeds = outlets.map { o ->
            async {
                runCatching { Rss.parse(http.getText(o.rss)) }
                    .onFailure { if (o in chosen) synchronized(failed) { failed += o.name } }.getOrNull()
            }
        }.map { it.await() }
        val loaded = outlets.zip(feeds).filter { it.second != null }.associate { it.first to it.second!! }
        fun ranked(o: Outlet, topics: List<String>): List<FeedItem> {
            val items = loaded[o]?.filter { o.region != Region.INDIA || NewsRules.isIndiaStory(it) } ?: return emptyList()
            return NewsRules.rank(items, loaded.filterKeys { it != o && it.region == o.region }.values.toList(), topics, skip)
        }
        // Slots are filled one at a time so no story is printed twice, even from different outlets.
        val printed = mutableListOf<String>()
        suspend fun fill(slots: List<Outlet>, topics: List<String>) = slots.mapNotNull { o ->
            val candidates = ranked(o, topics).filter { c -> printed.none { NewsRules.sameStory(it, c.title) } }
            runCatching { story(o, candidates, date) }.onFailure { synchronized(failed) { failed += o.name } }.getOrNull()
                ?.also { printed += it.headline }
        }
        val news = News(india = fill(india, indiaTopics), world = fill(world, worldTopics))
        if (news.india.isEmpty() && news.world.isEmpty()) throw IOException("All news feeds failed")
        news
    }

    private suspend fun story(outlet: Outlet, ranked: List<FeedItem>, date: LocalDate): NewsItem? {
        if (ranked.isEmpty()) throw IOException("${outlet.name}: no stories left after filtering")
        val printed = cache.printedBefore("news-${outlet.id}", date)
        val fresh = ranked.filter { it.link !in printed }.ifEmpty { ranked }
        for (item in fresh.take(3)) {
            val body = runCatching { ArticleText.paragraphs(http.getText(item.link), item.link) }.getOrDefault(emptyList())
            val text = ArticleText.trimWords(body, MAX_WORDS)
            if (text.split(' ').size >= MIN_WORDS) {
                cache.markPrinted("news-${outlet.id}", date, item.link)
                return NewsItem(headline = item.title, source = outlet.name, text = text)
            }
        }
        val top = fresh.first()
        cache.markPrinted("news-${outlet.id}", date, top.link)
        synchronized(summaryOnly) { summaryOnly += outlet.name }
        return NewsItem(headline = top.title, source = outlet.name, text = top.summary.ifBlank { top.title })
    }

    companion object {
        /** The reference stories run 95 to 140 words. */
        const val MAX_WORDS = 140
        const val MIN_WORDS = 60
    }
}
