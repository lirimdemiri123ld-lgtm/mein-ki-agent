package ch.lirim.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Kontakte suchen und anrufen. */
object Contacts {
    data class Hit(val name: String, val number: String)

    fun canRead(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun canCall(c: Context) = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun norm(s: String) = s.lowercase()
        .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("é", "e").replace("ë", "e").replace("ç", "c")
        .replace(Regex("[^a-z0-9 ]"), "").trim()

    fun search(c: Context, query: String, limit: Int = 5): List<Hit> {
        if (!canRead(c)) return emptyList()
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val all = mutableListOf<Hit>()
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                all.add(Hit(name, num))
            }
        }
        val words = q.split(" ").filter { it.length >= 2 }
        fun score(h: Hit): Int {
            val n = norm(h.name)
            val fuzzy = ch.lirim.jarvis.core.Fuzzy.score(q, h.name)
            return maxOf(fuzzy, when {
                words.isNotEmpty() && words.all { n.contains(it) } -> 40
                words.any { w -> n.split(" ").any { it.startsWith(w.take(4)) } } -> 20
                else -> 0
            })
        }
        return all.map { it to score(it) }.filter { it.second >= 20 }
            .sortedByDescending { it.second }.map { it.first }.distinctBy { it.number.filter(Char::isDigit) }.take(limit)
    }

    fun describe(c: Context, query: String): String {
        if (!canRead(c)) return "Kein Zugriff auf Kontakte. Lirim muss in Jarvis unter ZUGRIFF «Kontakte & Anrufe» erlauben."
        val hits = search(c, query)
        if (hits.isEmpty()) return "Kein Kontakt zu «$query» gefunden. Frag Lirim nach dem genauen Namen."
        return "Gefundene Kontakte:\n" + hits.joinToString("\n") { "${it.name}: ${it.number}" }
    }

    /** Ruft an (Nummer oder Kontaktname). Muss vorher bestätigt sein. */
    fun call(c: Context, target: String): String {
        val t = target.trim()
        val digits = t.filter { it.isDigit() || it == '+' }
        val number: String
        val label: String
        if (digits.length >= 6 && digits.length >= t.replace(" ", "").length - 2) {
            number = digits; label = t
        } else {
            val hit = search(c, t).firstOrNull()
                ?: return if (!canRead(c)) "Kein Zugriff auf Kontakte – bitte unter ZUGRIFF erlauben."
                else "Kein Kontakt «$t» gefunden."
            number = hit.number; label = hit.name
        }
        val action = if (canCall(c)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        c.startActivity(Intent(action, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return if (action == Intent.ACTION_CALL) "Rufe $label an ($number)." else "Telefon mit $label ($number) geöffnet – Anruf noch antippen."
    }
}
