package ch.lirim.jarvis2

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Merkt sich die letzten Benachrichtigungen (nur im Arbeitsspeicher), damit Claude «Was ist neu?» beantworten kann. */
class NotificationListener : NotificationListenerService() {

    data class Item(val time: Long, val app: String, val title: String, val text: String)

    companion object {
        private val items = ArrayDeque<Item>()

        fun hasAccess(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(c, NotificationListener::class.java).flattenToString()
            return flat.split(":").any { it.equals(me, ignoreCase = true) }
        }

        fun asText(limit: Int): String {
            val list = synchronized(items) { items.take(limit) }
            if (list.isEmpty()) return "Keine neuen Benachrichtigungen gespeichert (seit Jarvis 2.0 läuft)."
            val f = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN)
            return "Neueste Benachrichtigungen:\n" + list.joinToString("\n") {
                "${f.format(Date(it.time))} · ${it.app} · ${it.title}: ${it.text}"
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (Safety.blocked(n.packageName)) return
        if (n.isOngoing) return
        val ex = n.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim()?.replace("\n", " ").orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = Apps.labelFor(this, n.packageName)
        synchronized(items) {
            items.removeAll { it.app == app && it.title == title && it.text == text }
            items.addFirst(Item(n.postTime, app, title, text.take(300)))
            while (items.size > 60) items.removeLast()
        }
    }
}
