package ch.lirim.jarvis2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Kontakte suchen, Nummern ins internationale Format bringen, anrufen. */
object Contacts {
    data class Hit(val name: String, val number: String)

    fun canRead(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun canCall(c: Context) = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun norm(s: String) = s.lowercase()
        .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("é", "e").replace("ë", "e").replace("ç", "c")
        .replace(Regex("[^a-z0-9 ]"), "").trim()

    fun isSwiss(number: String): Boolean {
        val d = number.filter { it.isDigit() || it == '+' }
        return d.startsWith("+41") || d.startsWith("0041") || (d.startsWith("0") && !d.startsWith("00"))
    }

    /** «079 123 45 67» → «41791234567» (für wa.me-Links). */
    fun intlDigits(number: String): String {
        val d = number.filter { it.isDigit() || it == '+' }
        return when {
            d.startsWith("+") -> d.drop(1)
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") -> "41" + d.drop(1)
            else -> d
        }.filter { it.isDigit() }
    }

    fun looksLikeNumber(s: String): Boolean {
        val t = s.replace(" ", "")
        val digits = t.count { it.isDigit() }
        return digits >= 6 && digits >= t.length - 2
    }

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
            val fuzzy = Fuzzy.score(q, h.name)
            return maxOf(fuzzy, when {
                words.isNotEmpty() && words.all { n.contains(it) } -> 40
                words.any { w -> n.split(" ").any { it.startsWith(w.take(4)) } } -> 20
                else -> 0
            })
        }
        return all.map { it to score(it) }.filter { it.second >= 20 }
            .map { it.first to it.second + if (isSwiss(it.first.number)) 8 else 0 }
            .sortedByDescending { it.second }
            .distinctBy { it.first.number.filter(Char::isDigit).takeLast(9) }
            .take(limit).map { it.first }
    }

    fun describe(c: Context, query: String): String {
        if (!canRead(c)) return "Kein Zugriff auf Kontakte. Lirim muss in Jarvis 2.0 «Kontakte & Anrufe erlauben» antippen."
        val hits = search(c, query)
        if (hits.isEmpty()) return "Kein Kontakt zu «$query» gefunden. Frag Lirim nach dem genauen Namen."
        return "Gefundene Kontakte:\n" + hits.joinToString("\n") { "${it.name}: ${it.number}" }
    }

    /** Name oder Nummer → (Anzeigename, Nummer) oder Fehlertext. */
    fun resolve(c: Context, target: String): Pair<Hit?, String?> {
        val t = target.trim()
        if (looksLikeNumber(t)) return Hit(t, t.filter { it.isDigit() || it == '+' }) to null
        if (!canRead(c)) return null to "Kein Zugriff auf Kontakte. Lirim muss in Jarvis 2.0 «Kontakte & Anrufe erlauben» antippen."
        val hits = search(c, t, 3)
        if (hits.isEmpty()) return null to "Kein Kontakt «$t» gefunden."
        return hits.first() to null
    }

    fun call(c: Context, target: String): String {
        val (hit, err) = resolve(c, target)
        if (hit == null) return err ?: "Kontakt nicht gefunden."
        val uri = Uri.parse("tel:" + Uri.encode(hit.number))
        if (canCall(c)) {
            try {
                c.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Rufe ${hit.name} an (${hit.number})."
            } catch (_: Exception) {}
        }
        c.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Telefon-App mit ${hit.name} (${hit.number}) geöffnet – Lirim muss noch auf den grünen Hörer tippen." +
            if (!canCall(c)) " (Tipp: In Jarvis 2.0 «Kontakte & Anrufe erlauben», dann startet der Anruf direkt.)" else ""
    }
}
