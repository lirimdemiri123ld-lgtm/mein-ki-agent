package ch.lirim.jarvis.core

/**
 * Einfache Befehle ohne KI erkennen (gratis, sofort):
 * Wecker, Timer, Anrufen, App öffnen.
 */
object QuickIntents {
    sealed class Intent {
        data class Alarm(val hour: Int, val minute: Int) : Intent()
        data class Timer(val seconds: Int) : Intent()
        data class Call(val who: String) : Intent()
        data class OpenApp(val app: String) : Intent()
    }

    private val numberWords = mapOf(
        "eine" to 1, "einen" to 1, "ein" to 1, "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5,
        "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12,
        "fünfzehn" to 15, "zwanzig" to 20, "dreissig" to 30, "dreißig" to 30, "vierzig" to 40, "fünfzig" to 50,
        "sechzig" to 60, "neunzig" to 90, "halbe" to -1
    )

    fun clean(command: String): String = command.lowercase()
        .replace(Regex("""^\s*(hallo|hey|hi|ok)?\s*jarvis[,!.:\s]*"""), "")
        .replace(Regex("""\bbitte\b"""), " ")
        .replace(Regex("""[!?.]+\s*$"""), "")
        .replace(Regex("""\s+"""), " ").trim()

    fun parse(command: String): Intent? {
        val c = clean(command)
        if (c.isEmpty()) return null
        // Zusammengesetzte Aufträge («… und dann …») gehören dem grossen Gehirn
        if (Regex("""\b(und dann|danach|anschliessend|ausserdem)\b""").containsMatchIn(c)) return null
        alarm(c)?.let { return it }
        timer(c)?.let { return it }
        call(c)?.let { return it }
        open(c)?.let { return it }
        return null
    }

    private fun alarm(c: String): Intent? {
        if (!Regex("""\b(wecker|weck mich|wecke mich|alarm)\b""").containsMatchIn(c)) return null
        if (Regex("""\b(lösch|entfern|aus\b|ausschalten|deaktivier)""").containsMatchIn(c)) return null
        val m = Regex("""\b(?:um|auf|für)?\s*(\d{1,2})(?:[:.h](\d{2}))?\s*(?:uhr)?\s*(\d{1,2})?\b""").findAll(c)
            .firstOrNull { it.groupValues[1].isNotEmpty() } ?: return null
        var h = m.groupValues[1].toInt()
        var min = m.groupValues[2].toIntOrNull() ?: m.groupValues[3].toIntOrNull() ?: 0
        Regex("""\bhalb\s+(\d{1,2})""").find(c)?.let { h = (it.groupValues[1].toInt() + 23) % 24; min = 30 }
        if (Regex("""\b(abends|nachmittags|am abend|am nachmittag)\b""").containsMatchIn(c) && h in 1..11) h += 12
        if (h !in 0..23 || min !in 0..59) return null
        return Intent.Alarm(h, min)
    }

    private fun timer(c: String): Intent? {
        if (!Regex("""\btimer\b|\bstoppuhr\b|\berinnere mich in\b""").containsMatchIn(c)) return null
        if (c.contains("erinnere")) return null // Erinnerungen mit Text → grosses Gehirn
        var total = 0
        val unit = Regex("""(\d+|[a-zäöü]+)\s*(stunden?|std|minuten?|min|sekunden?|sek|s)\b""")
        for (m in unit.findAll(c)) {
            val raw = m.groupValues[1]
            val n = raw.toIntOrNull() ?: numberWords[raw] ?: continue
            val u = m.groupValues[2]
            total += when {
                n == -1 && u.startsWith("st") -> 1800
                n == -1 -> 30
                u.startsWith("st") -> n * 3600
                u.startsWith("min") -> n * 60
                else -> n
            }
        }
        if (Regex("""\bhalbe stunde\b""").containsMatchIn(c) && total == 0) total = 1800
        if (Regex("""\bviertelstunde\b""").containsMatchIn(c) && total == 0) total = 900
        return if (total in 1..86_400) Intent.Timer(total) else null
    }

    private fun call(c: String): Intent? {
        val m = Regex("""^(?:ruf|rufe)\s+(.+?)\s+an$""").find(c)
            ?: Regex("""^(.+?)\s+anrufen$""").find(c)
            ?: Regex("""^(?:anrufen|telefoniere mit|telefonier mit)\s+(.+)$""").find(c)
            ?: return null
        val who = m.groupValues[1].trim()
        if (who.isEmpty() || who.split(" ").size > 4) return null
        if (Regex("""\b(und|whatsapp|später|morgen|um \d)\b""").containsMatchIn(who)) return null
        return Intent.Call(who)
    }

    private fun open(c: String): Intent? {
        val m = Regex("""^(?:öffne|oeffne|starte|start|öffnen|mach)\s+(?:mir\s+)?(?:die\s+|das\s+|den\s+)?(?:app\s+)?(.+?)(?:\s+app)?(?:\s+auf)?$""").find(c)
            ?: Regex("""^(.+?)\s+(?:öffnen|starten)$""").find(c) ?: return null
        val app = m.groupValues[1].trim()
        if (app.isEmpty() || app.split(" ").size > 3) return null
        if (Regex("""\b(und|chat|mit|von|nachricht|mail|an)\b""").containsMatchIn(app)) return null
        return Intent.OpenApp(app)
    }
}
