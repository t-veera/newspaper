package app.newspaper.source.messages

import android.content.Context
import app.newspaper.model.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One unread message captured from a notification. [key] is the notification's key. */
@Serializable
data class StoredMessage(
    val key: String,
    val app: String,
    val sender: String,
    val text: String,
    val timeMillis: Long,
)

/**
 * Unread messages, persisted in no-backup app storage so they survive process death.
 * A notification's messages are replaced when it updates and dropped when it is dismissed
 * or read, so the store holds exactly what is still unread.
 */
class MessageStore(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "unread_messages.json"))

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredMessage.serializer())

    @Synchronized
    fun all(): List<StoredMessage> =
        if (!file.exists()) emptyList()
        else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun replace(key: String, messages: List<StoredMessage>) = write(all().filter { it.key != key } + messages)

    @Synchronized
    fun remove(key: String) {
        val current = all()
        if (current.any { it.key == key }) write(current.filter { it.key != key })
    }

    /** Drops every notification that is no longer showing. */
    @Synchronized
    fun retainKeys(active: Set<String>) {
        val current = all()
        if (current.any { it.key !in active }) write(current.filter { it.key in active })
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun write(messages: List<StoredMessage>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, messages))
        if (!tmp.renameTo(file)) throw IllegalStateException("Could not save messages")
    }
}

/** Turns stored messages into the edition's Messages section. Pure, unit-tested. */
class MessageRules(
    private val zone: ZoneId,
    private val maxMessages: Int = MAX_MESSAGES,
    private val maxAgeMillis: Long = MAX_AGE_MILLIS,
) {
    class Result(val messages: List<Message>, val omitted: Int)

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    /** Newest [maxMessages] within [maxAgeMillis], grouped by app in [appOrder], oldest first in each group. */
    fun select(stored: List<StoredMessage>, apps: Set<String>, appOrder: List<String>, nowMillis: Long): Result {
        val fresh = stored
            .filter { it.app in apps && nowMillis - it.timeMillis <= maxAgeMillis }
            .distinctBy { listOf(it.app, it.sender, it.text, it.timeMillis) }
        val kept = fresh.sortedByDescending { it.timeMillis }.take(maxMessages)
        val ordered = kept.sortedWith(compareBy({ appOrder.indexOf(it.app).let { i -> if (i < 0) Int.MAX_VALUE else i } },
            { it.timeMillis }))
        return Result(
            ordered.map {
                Message(app = it.app, sender = it.sender, time = Instant.ofEpochMilli(it.timeMillis).atZone(zone).format(hhmm),
                    text = it.text)
            },
            omitted = fresh.size - kept.size,
        )
    }

    companion object {
        /** Extra messages flow onto page 3+; this only guards against runaway notification spam. */
        const val MAX_MESSAGES = 150
        const val MAX_AGE_MILLIS = 36L * 60 * 60 * 1000
    }
}
