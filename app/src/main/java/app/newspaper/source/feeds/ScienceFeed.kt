package app.newspaper.source.feeds

import app.newspaper.model.ScienceLead
import app.newspaper.net.FeedCache
import app.newspaper.net.Http
import app.newspaper.source.ScienceSource
import java.io.IOException
import java.time.LocalDate

data class ScienceJournal(
    val id: String,
    val name: String,
    val rss: String,
    val domains: Set<String>,
    /** Keeps research papers, drops news and editorials. */
    val isPaper: (FeedItem) -> Boolean = { true },
    /** Abstract taken from the RSS item itself (arXiv puts it there). */
    val abstractInFeed: Boolean = false,
    /** The page body is itself a written story (press releases), so it may be used when there is no abstract. */
    val bodyIsStory: Boolean = false,
)

object Journals {
    val all = listOf(
        ScienceJournal("nature", "Nature", "https://www.nature.com/nature.rss", setOf("nature.com"),
            isPaper = { it.link.contains("/articles/s41586") }),
        ScienceJournal("science-advances", "Science Advances", "https://www.science.org/action/showFeed?type=etoc&feed=rss&jc=sciadv",
            setOf("science.org")),
        ScienceJournal("plos-biology", "PLOS Biology", "https://journals.plos.org/plosbiology/feed/atom", setOf("plos.org")),
        ScienceJournal("arxiv-physics", "arXiv (physics)", "https://rss.arxiv.org/rss/physics", setOf("arxiv.org"),
            isPaper = { !it.summary.contains("Announce Type: replace") }, abstractInFeed = true),
        ScienceJournal("sciencedaily", "ScienceDaily", "https://www.sciencedaily.com/rss/top/science.xml", setOf("sciencedaily.com"),
            bodyIsStory = true),
        ScienceJournal("sciencedaily-fossils", "ScienceDaily: Fossils & Ruins", "https://www.sciencedaily.com/rss/fossils_ruins.xml",
            setOf("sciencedaily.com"), bodyIsStory = true),
        ScienceJournal("sciencedaily-early-humans", "ScienceDaily: Early Humans",
            "https://www.sciencedaily.com/rss/fossils_ruins/early_humans.xml", setOf("sciencedaily.com"), bodyIsStory = true),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all.first()
}

/** Pure helpers, unit-tested. */
object ScienceRules {

    /** arXiv: "arXiv:2610.01234v1 Announce Type: new Abstract: We report ..." */
    fun arxivAbstract(summary: String): String = summary.substringAfter("Abstract:", summary).trim()

    /** Corrections, retractions, editorials and replies are not papers to report on. */
    private val notResearch = Regex("(?i)^(retraction|correction|author correction|publisher correction|erratum|addendum|" +
        "editorial|editor'?s note|expression of concern|matters arising|reply to|news ?& ?views|outlook|book review|obituary)\\b")

    fun isResearch(item: FeedItem) = !notResearch.containsMatchIn(item.title.trim())

    private val abbreviation = Regex("(?:\\b[A-Z]|et al|e\\.g|i\\.e|Fig|Figs|Eq|Ref|Refs|Dr|Prof|vs|approx|ca|no|Nos?)\\.$")

    /** Sentence split that does not break after initials ("J. Smith") or common abbreviations. */
    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        val tokens = text.trim().split(Regex("\\s+"))
        tokens.forEachIndexed { i, tok ->
            if (current.isNotEmpty()) current.append(' ')
            current.append(tok)
            val next = tokens.getOrNull(i + 1)
            val ends = tok.endsWith('.') || tok.endsWith('!') || tok.endsWith('?')
            if (ends && next != null && next.first().let { it.isUpperCase() || it.isDigit() || it == '“' || it == '"' } &&
                !abbreviation.containsMatchIn(tok)) {
                out += current.toString(); current.clear()
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Splits an abstract into 2-4 newspaper paragraphs at sentence ends. */
    fun paragraphs(text: String, perParagraph: Int = 3): List<String> = sentences(text).chunked(perParagraph).map { it.joinToString(" ") }

    /** The deck is the RSS summary's first sentence when it says something the abstract does not open with. */
    /**
     * Orders papers by how many interests their title and summary mention (case-insensitive, word
     * starts), newest first among equals. A whole phrase counts double, and each of its words (singular)
     * also counts, so "fossils denisovans" typed without a comma still finds either. No interests keeps feed order.
     */
    fun rankByInterest(items: List<FeedItem>, interests: List<String>): List<FeedItem> {
        val topics = interests.map { it.trim().lowercase() }.filter { it.length >= 3 }
        if (topics.isEmpty()) return items
        // Matching is by word start, so "denisovans" is cut to "denisovan" to find the singular too.
        val words = topics.flatMap { it.split(Regex("\\s+")) }.filter { it.length >= 4 }
            .map { it.removeSuffix("s") }.distinct() - topics.toSet()
        fun score(i: FeedItem): Int {
            val text = (i.title + " " + i.summary).lowercase()
            fun has(t: String) = Regex("\\b" + Regex.escape(t)).containsMatchIn(text)
            return 2 * topics.count(::has) + words.count(::has)
        }
        return items.withIndex().sortedWith(compareBy({ -score(it.value) }, { it.index })).map { it.value }
    }

    fun deck(summary: String, abstract: String): String? {
        val first = summary.split(Regex("(?<=[.!?])\\s+")).firstOrNull()?.trim().orEmpty()
        if (first.length < 40 || abstract.startsWith(first.take(40))) return null
        return first.take(220)
    }
}

/** Today's science lead: the journal's newest research paper not printed before, as its abstract. */
class ScienceFeed(
    private val http: Http,
    private val cache: FeedCache,
    private val journal: ScienceJournal,
    private val interests: List<String> = emptyList(),
) : ScienceSource {

    override suspend fun lead(date: LocalDate): ScienceLead? {
        val items = ScienceRules.rankByInterest(
            Rss.parse(http.getText(journal.rss)).filter { journal.isPaper(it) && ScienceRules.isResearch(it) }.take(40), interests)
        if (items.isEmpty()) throw IOException("${journal.name}: no papers in feed")
        val printed = cache.printedBefore("science-${journal.id}", date)
        for (item in items.filter { it.link !in printed }.ifEmpty { items }.take(4)) {
            val abstract = abstractOf(item) ?: continue
            if (abstract.split(' ').size < 60) continue
            cache.markPrinted("science-${journal.id}", date, item.link)
            return ScienceLead(
                headline = item.title,
                deck = ScienceRules.deck(item.summary, abstract),
                paragraphs = ScienceRules.paragraphs(abstract),
                sourceUrl = item.link,
            )
        }
        throw IOException("${journal.name}: no readable abstract")
    }

    private suspend fun abstractOf(item: FeedItem): String? {
        if (journal.abstractInFeed) return ScienceRules.arxivAbstract(item.summary)
        val html = runCatching { http.getText(item.link) }.getOrNull() ?: return item.summary.ifBlank { null }
        // A press release's own "abstract" is a two-line teaser; the story is the body.
        if (journal.bodyIsStory) ArticleText.paragraphs(html, item.link).takeIf { it.isNotEmpty() }?.let { return ArticleText.trimWords(it, 260) }
        // Never print a paper's body: it runs into figures and reference lists.
        return ArticleText.section(html, "#Abs1-content", "section[aria-labelledby=Abs1]", "div.abstract", "section.abstract",
            "#abstract", "[class*=abstract-content]", "blockquote.abstract")
            ?: ArticleText.meta(html, "citation_abstract", "dc.description", "DC.description")
    }
}
