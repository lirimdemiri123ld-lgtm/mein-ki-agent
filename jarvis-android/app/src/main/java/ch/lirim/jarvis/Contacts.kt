package ch.lirim.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract

/** Kontakte suchen und anrufen. */
object Contacts {
    /** Läuft der Anruf wirklich? (Audio-Modus wechselt auf «Anruf», oder eine SIM-Auswahl erscheint) */
    private fun waitForCall(c: Context): Boolean {
        val am = c.getSystemService(android.media.AudioManager::class.java)
        repeat(12) {
            Thread.sleep(400)
            val m = am.mode
            if (m == android.media.AudioManager.MODE_IN_CALL || m == android.media.AudioManager.MODE_IN_COMMUNICATION ||
                m == android.media.AudioManager.MODE_RINGTONE || m == android.media.AudioManager.MODE_CALL_SCREENING) return true
            // Samsung: Telefon-App/SIM-Auswahl ist im Vordergrund → zählt als gestartet
            val pkg = JarvisService.instance?.rootInActiveWindow?.packageName?.toString().orEmpty()
            if (pkg.contains("incallui") || pkg.contains("telecom") || pkg.contains("dialer")) return true
        }
        return false
    }

    /** Wird gerade telefoniert? (dann nicht zuhören) */
    fun inCall(c: Context): Boolean {
        val m = c.getSystemService(android.media.AudioManager::class.java).mode
        return m == android.media.AudioManager.MODE_IN_CALL || m == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            m == android.media.AudioManager.MODE_RINGTONE
    }

    data class Hit(val name: String, val number: String)

    fun canRead(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun canCall(c: Context) = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun norm(s: String) = s.lowercase()
        .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("é", "e").replace("ë", "e").replace("ç", "c")
        .replace(Regex("[^a-z0-9 ]"), "").trim()

    /** Schweizer Nummer? (+41, 0041 oder 0…) */
    fun isSwiss(number: String): Boolean {
        val d = number.filter { it.isDigit() || it == '+' }
        return d.startsWith("+41") || d.startsWith("0041") || (d.startsWith("0") && !d.startsWith("00"))
    }

    fun search(c: Context, query: String, limit: Int = 5): List<Hit> = searchScored(c, query, limit).map { it.first }

    /** Treffer mit Punktzahl; Schweizer Nummern werden bevorzugt. */
    fun searchScored(c: Context, query: String, limit: Int = 5): List<Pair<Hit, Int>> {
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
            .map { it.first to it.second + if (isSwiss(it.first.number)) 8 else 0 }
            .sortedByDescending { it.second }.distinctBy { it.first.number.filter(Char::isDigit).takeLast(9) }.take(limit)
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
        val uri = Uri.parse("tel:" + Uri.encode(number))
        if (canCall(c)) {
            // 1. Über das Telefonsystem (funktioniert auch aus dem Hintergrund)
            try {
                val tm = c.getSystemService(android.telecom.TelecomManager::class.java)
                tm.placeCall(uri, Bundle())
                if (waitForCall(c)) return "Rufe $label an ($number)."
            } catch (_: Exception) {}
            // 2. Klassisch per Anruf-Befehl
            try {
                c.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (waitForCall(c)) return "Rufe $label an ($number)."
            } catch (_: Exception) {}
        }
        // 3. Notlösung: Telefon-App mit Nummer öffnen
        try { c.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        return "Ich konnte den Anruf nicht selbst starten. Die Telefon-App ist mit $label ($number) offen – tippe auf den grünen Hörer." +
            if (!canCall(c)) " (Tipp: Unter ZUGRIFF «Anrufe» erlauben, dann geht es automatisch.)" else ""
    }
}
