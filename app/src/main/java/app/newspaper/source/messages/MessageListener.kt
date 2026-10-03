package app.newspaper.source.messages

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import app.newspaper.model.Message
import app.newspaper.settings.MessagingApps
import app.newspaper.source.MessagesSource
import java.time.LocalDate
import java.time.ZoneId

/**
 * Captures messages from WhatsApp, Signal and Telegram notifications into [MessageStore].
 * Nothing is logged and nothing leaves the device.
 */
class MessageListener : NotificationListenerService() {

    private val store by lazy { MessageStore(this) }

    override fun onListenerConnected() {
        val active = activeNotifications.orEmpty()
        store.retainKeys(active.map { it.key }.toSet())
        active.forEach(::capture)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (MessagingApps.all.containsKey(sbn.packageName)) store.remove(sbn.key)
    }

    private fun capture(sbn: StatusBarNotification) {
        val app = MessagingApps.all[sbn.packageName] ?: return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return // backups, "checking for messages"
        val messages = NotificationText.extract(n, app, sbn.key, sbn.postTime)
        if (messages.isEmpty()) store.remove(sbn.key) else store.replace(sbn.key, messages)
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, MessageListener::class.java)
            return flat.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == me }
        }
    }
}

/** Reads messages out of a notification: MessagingStyle when present, else title + text. */
object NotificationText {

    fun extract(n: Notification, app: String, key: String, postTime: Long): List<StoredMessage> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val group = style.isGroupConversation
            val title = style.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
            return style.messages.mapNotNull { m ->
                val person = m.person?.name?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null // own reply
                val text = m.text?.toString()?.trim().orEmpty().ifEmpty { "New message" }
                if (group && title != null) StoredMessage(key, app, title, "$person: $text", m.timestamp)
                else StoredMessage(key, app, person, text, m.timestamp)
            }
        }
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim()
        if (title.isNullOrEmpty()) return emptyList()
        return listOf(StoredMessage(key, app, title, text.orEmpty().ifEmpty { "New message" }, n.`when`.takeIf { it > 0 } ?: postTime))
    }
}

/** [MessagesSource] over the captured unread messages. */
class CapturedMessagesSource(
    private val store: MessageStore,
    private val apps: Set<String>,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : MessagesSource {

    /** How many messages were left out on the last call because the band is full. */
    var omitted = 0
        private set

    override suspend fun unread(date: LocalDate): List<Message> {
        val result = MessageRules(zone).select(store.all(), apps, MessagingApps.names, now())
        omitted = result.omitted
        return result.messages
    }
}
