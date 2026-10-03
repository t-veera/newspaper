package app.newspaper.source.feeds

import app.newspaper.model.ScienceLead
import app.newspaper.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * Rewrites a paper's abstract into a newspaper science story with Claude (Messages API over
 * plain HTTPS, no SDK). Only public paper text is sent; nothing personal.
 */
class ScienceSummary(private val http: Http, private val apiKey: String) {

    @Serializable data class Story(val headline: String, val deck: String, val paragraphs: List<String>)

    @Serializable private data class Response(
        val content: List<Block> = emptyList(),
        val stop_reason: String? = null,
    )
    @Serializable private data class Block(val type: String, val text: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun rewrite(lead: ScienceLead, journal: String): ScienceLead {
        val body = request(lead, journal).toString().toByteArray()
        val raw = http.post(URL, body, mapOf(
            "content-type" to "application/json",
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01",
            "anthropic-beta" to "server-side-fallback-2026-07-01",
        )).decodeToString()
        val response = json.decodeFromString(Response.serializer(), raw)
        when (response.stop_reason) {
            "refusal" -> throw IOException("Claude declined to summarise this paper")
            "max_tokens" -> throw IOException("Claude's summary was cut off")
        }
        val text = response.content.lastOrNull { it.type == "text" }?.text ?: throw IOException("Claude returned no text")
        val story = json.decodeFromString(Story.serializer(), text)
        if (story.paragraphs.isEmpty()) throw IOException("Claude returned an empty story")
        return lead.copy(headline = story.headline.trim(), deck = story.deck.trim().ifEmpty { null },
            paragraphs = story.paragraphs.map { it.trim() }.filter { it.isNotEmpty() })
    }

    /** Built separately so the request shape is unit-tested. */
    fun request(lead: ScienceLead, journal: String): JsonObject = buildJsonObject {
        put("model", MODEL)
        put("max_tokens", 4000)
        put("fallbacks", "default")
        put("system", SYSTEM)
        putJsonObject("output_config") {
            put("effort", "low")
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", SCHEMA)
            }
        }
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                put("content", "Journal: $journal\nTitle: ${lead.headline}\n\nAbstract:\n${lead.paragraphs.joinToString("\n\n")}")
            })
        }
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val URL = "https://api.anthropic.com/v1/messages"
        val DOMAINS = setOf("api.anthropic.com")

        private val SYSTEM = """
            You write the science lead for a small printed morning newspaper read by curious non-specialists.
            Turn the paper's abstract into a news story of 220 to 280 words in 4 or 5 short paragraphs.
            Say what was found, how, why it matters, and what remains uncertain. Use only facts stated in
            the abstract; do not invent numbers, names, quotes or institutions. Plain words, no hype, no
            jargon without a short explanation. Headline: at most 12 words, title case. Deck: one sentence
            of at most 30 words that adds to the headline.
        """.trimIndent().replace("\n", " ")

        private val SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("headline") { put("type", "string") }
                putJsonObject("deck") { put("type", "string") }
                putJsonObject("paragraphs") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            put("required", buildJsonArray { listOf("headline", "deck", "paragraphs").forEach { add(JsonPrimitive(it)) } })
            put("additionalProperties", false)
        }

    }
}
