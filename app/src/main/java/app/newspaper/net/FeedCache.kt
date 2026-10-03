package app.newspaper.net

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Last good result per feed, so a feed that is down prints yesterday's copy (with a warning)
 * instead of an empty section. Public data only; stored in no-backup app storage.
 */
class FeedCache(private val dir: File) {

    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "feeds"))

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    class Entry<T>(val value: T, val savedAtMillis: Long)

    fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        tmp.renameTo(File(dir, "$key.json"))
    }

    fun <T> get(key: String, serializer: KSerializer<T>): Entry<T>? {
        val f = File(dir, "$key.json")
        if (!f.exists()) return null
        return runCatching { Entry(json.decodeFromString(serializer, f.readText()), f.lastModified()) }.getOrNull()
    }

    /** Links printed before [today] ("date link" entries), so same-day regenerations keep their story. */
    fun printedBefore(key: String, today: java.time.LocalDate): Set<String> =
        remembered(key).mapNotNull { e -> e.split(' ', limit = 2).takeIf { it.size == 2 && it[0] < today.toString() }?.get(1) }.toSet()

    fun markPrinted(key: String, today: java.time.LocalDate, link: String) = remember(key, "$today $link")

    /** Small rolling set of strings, e.g. links already printed. */
    fun remembered(key: String): List<String> = File(dir, "$key.lst").takeIf { it.exists() }?.readLines().orEmpty()

    fun remember(key: String, value: String, keep: Int = 60) {
        dir.mkdirs()
        val list = (remembered(key) - value + value).takeLast(keep)
        File(dir, "$key.lst").writeText(list.joinToString("\n"))
    }
}
