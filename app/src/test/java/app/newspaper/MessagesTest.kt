package app.newspaper

import app.newspaper.model.Message
import app.newspaper.settings.MessagingApps
import app.newspaper.source.messages.MessageRules
import app.newspaper.source.messages.MessageStore
import app.newspaper.source.messages.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

class MessagesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 10, 3, 6, 0)
    private fun at(h: Int, m: Int, daysAgo: Long = 0) =
        now.toLocalDate().minusDays(daysAgo).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val nowMillis = now.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun groupsByAppInPrintOrderOldestFirst() {
        val stored = listOf(
            StoredMessage("t", "Telegram", "Kiran", "Check this", at(21, 2, 1)),
            StoredMessage("w2", "WhatsApp", "Dad", "Landlord called", at(22, 40, 1)),
            StoredMessage("w1", "WhatsApp", "Anu", "Running late", at(22, 14, 1)),
            StoredMessage("s", "Signal", "Ravi", "Rev C", at(21, 50, 1)),
        )
        val r = MessageRules(zone).select(stored, MessagingApps.names.toSet(), MessagingApps.names, nowMillis)
        assertEquals(listOf(
            Message("WhatsApp", "Anu", "22:14", "Running late"),
            Message("WhatsApp", "Dad", "22:40", "Landlord called"),
            Message("Signal", "Ravi", "21:50", "Rev C"),
            Message("Telegram", "Kiran", "21:02", "Check this"),
        ), r.messages)
        assertEquals(0, r.omitted)
    }

    @Test
    fun dropsDisabledAppsStaleAndDuplicatesAndCapsCount() {
        val many = (0 until 20).map { StoredMessage("w$it", "WhatsApp", "P$it", "m$it", at(5, it)) }
        val stored = many + listOf(
            StoredMessage("w0dup", "WhatsApp", "P0", "m0", at(5, 0)),
            StoredMessage("old", "WhatsApp", "Old", "stale", at(5, 0, 2)),
            StoredMessage("s", "Signal", "Ravi", "off", at(5, 30)),
        )
        val r = MessageRules(zone, maxMessages = 14).select(stored, setOf("WhatsApp"), MessagingApps.names, nowMillis)
        assertEquals(14, r.messages.size)
        assertEquals(6, r.omitted)
        assertEquals("P6", r.messages.first().sender) // newest 14 kept: minutes 6..19
    }

    @Test
    fun storeReplacesRemovesAndRetains() {
        val dir = Files.createTempDirectory("msgs").toFile()
        val store = MessageStore(File(dir, "m.json"))
        store.replace("a", listOf(StoredMessage("a", "WhatsApp", "Anu", "1", 1)))
        store.replace("b", listOf(StoredMessage("b", "Signal", "Ravi", "2", 2)))
        store.replace("a", listOf(StoredMessage("a", "WhatsApp", "Anu", "1", 1), StoredMessage("a", "WhatsApp", "Anu", "3", 3)))
        assertEquals(listOf("2", "1", "3"), store.all().map { it.text })
        store.remove("b")
        assertEquals(setOf("a"), store.all().map { it.key }.toSet())
        store.retainKeys(emptySet())
        assertEquals(emptyList<StoredMessage>(), store.all())
        dir.deleteRecursively()
    }
}
