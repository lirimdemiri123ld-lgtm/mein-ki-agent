package ch.lirim.jarvis

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Liest eingehende Nachrichten (WhatsApp, E-Mail, SMS …) für das HUD und für Jarvis. */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName || n.isOngoing) return
        val ex = n.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        if (n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        NotificationStore.add(Apps.labelFor(this, n.packageName), n.packageName, title, text)
        TopUp.check(this, title, text)
    }
}

object NotificationStore {
    data class Item(val app: String, val pkg: String, val title: String, val text: String, val time: Long)

    private val items = ArrayDeque<Item>()
    var listener: (() -> Unit)? = null

    @Synchronized
    fun add(app: String, pkg: String, title: String, text: String) {
        if (items.any { it.pkg == pkg && it.title == title && it.text == text }) return
        items.addFirst(Item(app, pkg, title, text.take(300), System.currentTimeMillis()))
        while (items.size > 40) items.removeLast()
        listener?.invoke()
    }

    @Synchronized
    fun recent(n: Int = 40): List<Item> = items.take(n)

    fun asText(n: Int = 20): String {
        val f = SimpleDateFormat("HH:mm", Locale.GERMAN)
        val list = recent(n)
        if (list.isEmpty()) return "Keine neuen Benachrichtigungen (oder Benachrichtigungszugriff nicht erteilt)."
        return list.joinToString("\n") { "${f.format(Date(it.time))} [${it.app}] ${it.title}: ${it.text}" }
    }

    fun hasAccess(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(c, JarvisNotificationListener::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }
}

/**
 * Aufladung bei Anthropic erkennen (Quittungs-Mail «Your receipt from Anthropic …»).
 * Steht der Betrag in der Meldung, wird er automatisch zum Guthaben addiert; sonst fragt Jarvis beim nächsten Öffnen.
 */
object TopUp {
    private val receipt = Regex("""(?i)anthropic""")
    private val word = Regex("""(?i)receipt|quittung|beleg|payment|zahlung|credit""")
    private val amount = Regex("""(?:US\$|USD|\$)\s?(\d{1,4}(?:[.,]\d{2})?)""")

    fun check(c: android.content.Context, title: String, text: String) {
        val all = "$title $text"
        if (!receipt.containsMatchIn(all) || !word.containsMatchIn(all)) return
        val id = Regex("""#[\d-]{6,}""").find(all)?.value ?: all.take(60)
        if (Prefs.lastTopUpId(c) == id) return
        Prefs.setLastTopUpId(c, id)
        val usd = amount.find(all)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        if (usd != null && usd > 0) {
            add(c, usd)
            Agent.log("💳 Aufladung erkannt: +%.2f \$ – Guthaben jetzt ≈ %.2f \$".format(java.util.Locale.US, usd, Prefs.creditBalance(c) ?: usd))
        } else {
            Prefs.setPendingTopUp(c, true)
            Agent.log("💳 Anthropic-Quittung erkannt – beim nächsten Öffnen frage ich nach dem Betrag.")
        }
    }

    fun add(c: android.content.Context, usd: Double) {
        Prefs.setCreditBalance(c, (Prefs.creditBalance(c)?.coerceAtLeast(0.0) ?: 0.0) + usd)
        Prefs.setCloudBlockedUntil(c, 0L)
        Prefs.setPendingTopUp(c, false)
    }
}
