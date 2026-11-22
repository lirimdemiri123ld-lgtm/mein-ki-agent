package ch.lirim.jarvis.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Sprach-Grundlagen für Deutsch (inkl. Schweizer Schreibweise):
 * Äusserung in Wörter zerlegen, Zahlwörter, Uhrzeiten, Dauern und Daten erkennen.
 * Alles reine Logik ohne Android – wird mit Unit-Tests geprüft.
 */

/**
 * Regex für normalisierten Text (Wörter durch je ein Leerzeichen getrennt).
 * «\b» wird zur Wortgrenze «Rand oder Leerzeichen» – das verhält sich auf Android (ICU)
 * und auf dem PC (OpenJDK) gleich, auch neben Umlauten.
 */
internal fun rx(pattern: String): Regex = Regex(pattern.replace("\\b", "(?:(?<![^ ])|(?![^ ]))"))

/** Eine Äusserung: normalisierter Text zum Erkennen, Originaltext für Namen und Nachrichten. */
class Utt(val raw: String) {
    class Tok(val norm: String, val start: Int, val end: Int)

    val toks: List<Tok>
    /** Kleinbuchstaben, «ß»→«ss», ohne Satzzeichen, einfache Leerzeichen. */
    val text: String
    private val offs: IntArray
    /** Index des Worts, nach dem der erste Doppelpunkt steht (−1 = keiner). */
    val colonAfter: Int

    init {
        val list = ArrayList<Tok>()
        var colon = -1
        var i = 0
        val n = raw.length
        while (i < n) {
            while (i < n && raw[i].isWhitespace()) i++
            if (i >= n) break
            var j = i
            while (j < n && !raw[j].isWhitespace()) j++
            // Satzzeichen am Rand entfernen, innen behalten (6:30, 3.12, e-mail)
            var a = i
            var b = j
            while (a < b && raw[a] in LEAD) a++
            while (b > a && raw[b - 1] in TRAIL) b--
            val hadColon = (b until j).any { raw[it] == ':' }
            if (b > a) {
                list.add(Tok(normWord(raw.substring(a, b)), a, b))
                if (hadColon && colon < 0) colon = list.size - 1
            } else if (hadColon && colon < 0 && list.isNotEmpty()) {
                colon = list.size - 1
            }
            i = j
        }
        toks = list
        colonAfter = colon
        val sb = StringBuilder()
        offs = IntArray(list.size)
        for ((k, t) in list.withIndex()) {
            if (k > 0) sb.append(' ')
            offs[k] = sb.length
            sb.append(t.norm)
        }
        text = sb.toString()
    }

    /** Wort-Index zur Position im normalisierten Text. */
    fun tokAt(pos: Int): Int {
        var idx = 0
        for (k in offs.indices) if (offs[k] <= pos) idx = k else break
        return idx
    }

    /** Originaltext (mit Gross-/Kleinschreibung) für einen Bereich des normalisierten Texts. */
    fun rawOf(range: IntRange): String {
        if (toks.isEmpty() || range.isEmpty()) return ""
        val a = tokAt(range.first)
        val b = tokAt(range.last)
        return raw.substring(toks[a].start, toks[b].end)
    }

    fun rawOf(g: MatchGroup?): String = if (g == null || g.value.isBlank()) "" else rawOf(trimRange(g))

    /** Originaltext ab dem Wort an dieser Position bis zum Schluss (inkl. Satzzeichen). */
    fun rawFrom(pos: Int): String {
        if (toks.isEmpty() || pos >= text.length) return ""
        return raw.substring(toks[tokAt(pos)].start).trim()
    }

    /** Originaltext der Wörter [from, to). */
    fun rawToks(from: Int, to: Int): String {
        if (from >= to || from >= toks.size) return ""
        return raw.substring(toks[from].start, toks[minOf(to, toks.size) - 1].end)
    }

    /** Normalisierter Text der Wörter [from, to). */
    fun normToks(from: Int, to: Int): String =
        if (from >= to) "" else toks.subList(from.coerceAtLeast(0), to.coerceAtMost(toks.size)).joinToString(" ") { it.norm }

    private fun trimRange(g: MatchGroup): IntRange {
        var a = g.range.first
        var b = g.range.last
        while (a <= b && text[a] == ' ') a++
        while (b >= a && text[b] == ' ') b--
        return a..b
    }

    companion object {
        private const val LEAD = ".,;:!?\"'«»“”„‚‘’()[]{}…–—-*"
        private const val TRAIL = ".,;:!?\"'«»“”„‚‘’()[]{}…–—-*"

        fun normWord(w: String): String = w.lowercase().replace("ß", "ss").replace('’', '\'')

        /** Ganzen Text normalisieren (für Vergleiche). */
        fun norm(s: String): String = Utt(s).text
    }
}

object Numbers {
    private val base = mapOf(
        "null" to 0, "ein" to 1, "eins" to 1, "eine" to 1, "einen" to 1, "einem" to 1, "einer" to 1,
        "zwei" to 2, "zwo" to 2, "drei" to 3, "vier" to 4, "fünf" to 5, "fuenf" to 5, "sechs" to 6,
        "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12, "zwoelf" to 12,
        "dreizehn" to 13, "vierzehn" to 14, "fünfzehn" to 15, "fuenfzehn" to 15, "sechzehn" to 16,
        "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19, "zwanzig" to 20, "dreissig" to 30,
        "vierzig" to 40, "fünfzig" to 50, "fuenfzig" to 50, "sechzig" to 60, "siebzig" to 70,
        "achtzig" to 80, "neunzig" to 90, "hundert" to 100, "einhundert" to 100
    )
    private val compound = Regex("^(ein|zwei|drei|vier|fünf|fuenf|sechs|sieben|acht|neun)und(zwanzig|dreissig|vierzig|fünfzig|fuenfzig|sechzig|siebzig|achtzig|neunzig)$")

    /** Zahl aus Ziffern oder Zahlwort («einundzwanzig» → 21). */
    fun parse(tok: String): Int? {
        val t = tok.trim().lowercase().replace("ß", "ss")
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() }) return if (t.length <= 6) t.toInt() else null
        base[t]?.let { return it }
        compound.find(t)?.let { return base[it.groupValues[2]]!! + base[it.groupValues[1]]!! }
        return null
    }

    /** Ordnungszahl («erste», «zweiten», «3.») → 1-basiert. */
    fun ordinal(tok: String): Int? {
        val t = tok.trim().lowercase().trimEnd('.')
        t.toIntOrNull()?.let { return it }
        val stem = t.replace(Regex("(e|er|en|es|em)$"), "")
        return when (stem) {
            "erst" -> 1; "zweit" -> 2; "dritt" -> 3; "viert" -> 4; "fünft", "fuenft" -> 5
            "sechst" -> 6; "siebt", "siebent" -> 7; "acht" -> 8; "neunt" -> 9; "zehnt" -> 10
            "letzt" -> -1
            else -> null
        }
    }

    /** Ein Zahl-Token für reguläre Ausdrücke (wird danach mit parse geprüft). */
    const val TOK = """(\d{1,4}|[a-zäöü]{3,22})"""
}

/** Gefundene Uhrzeit im Text. */
data class ClockHit(val hour: Int, val minute: Int, val range: IntRange, val daypartGiven: Boolean)

object ClockTime {
    private val N = Numbers.TOK
    private val colon = rx("""\b(\d{1,2}):(\d{2})\b""")
    private val dotted = rx("""(?:\b(?:um|auf|für|fuer|ab|bis|gegen|von) )(\d{1,2})\.(\d{2})\b|\b(\d{1,2})\.(\d{2})(?= uhr\b)""")
    private val quarter = rx("""\b(drei)?viertel (?:(nach|vor|ab|über|ueber) )?$N\b""")
    private val half = rx("""\bhalb(?:i)? $N\b""")
    private val minutesRel = rx("""\b$N (?:minuten? )?(nach|vor|ab) $N\b""")
    private val uhr = rx("""\b$N uhr(?: $N)?\b""")
    private val spaced = rx("""\b(?:um|auf|für|fuer|gegen|ab|von) (\d{1,2}) (\d{2})\b(?! (?:minuten?|min|stunden?|std|sekunden?|sek|prozent|tagen?|wochen?)\b)""")
    private val bare = rx("""\b(?:um|auf|für|fuer|gegen|ab|von) $N\b(?! (?:minuten?|min|stunden?|std|sekunden?|sek|prozent|tagen?|wochen?|uhr)\b)""")

    private val am = rx("""\b(morgens|frühmorgens|früh|frueh|am morgen|in der früh|vormittags|am vormittag)\b""")
    private val pm = rx("""\b(nachmittags|am nachmittag|abends|am abend|heute abend|morgen abend|nachts|in der nacht)\b""")
    private val noon = rx("""\b(mittags|am mittag|über mittag)\b""")

    /** Erste Uhrzeit im normalisierten Text, oder null. */
    fun find(t: String): ClockHit? {
        val hit = raw(t) ?: return null
        var h = hit.hour
        val isPm = pm.containsMatchIn(t)
        val isAm = am.containsMatchIn(t)
        val isNoon = noon.containsMatchIn(t)
        when {
            isPm && h in 1..11 -> if (!(t.contains("nachts") || t.contains("in der nacht")) || h >= 6) h += 12
            isNoon && h in 1..5 -> h += 12
            isAm && h == 12 -> h = 0
        }
        if (h == 24) h = 0
        if (h !in 0..23 || hit.minute !in 0..59) return null
        return hit.copy(hour = h, daypartGiven = isPm || isAm || isNoon)
    }

    private fun raw(t: String): ClockHit? {
        colon.find(t)?.let { m ->
            val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            if (h in 0..24 && mi in 0..59) return ClockHit(h, mi, m.range, false)
        }
        dotted.find(t)?.let { m ->
            val h = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toInt()
            val mi = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toInt()
            if (h in 0..24 && mi in 0..59) return ClockHit(h, mi, m.range, false)
        }
        for (m in quarter.findAll(t)) {
            val x = Numbers.parse(m.groupValues[3]) ?: continue
            if (x !in 0..24) continue
            val three = m.groupValues[1].isNotEmpty()
            val prep = m.groupValues[2]
            return when {
                three -> ClockHit(prev(x), 45, m.range, false)
                prep == "vor" -> ClockHit(prev(x), 45, m.range, false)
                prep.isEmpty() -> ClockHit(prev(x), 15, m.range, false)
                else -> ClockHit(x, 15, m.range, false)
            }
        }
        for (m in half.findAll(t)) {
            val x = Numbers.parse(m.groupValues[1]) ?: continue
            if (x in 1..24) return ClockHit(prev(x), 30, m.range, false)
        }
        for (m in minutesRel.findAll(t)) {
            val mins = Numbers.parse(m.groupValues[1]) ?: continue
            val x = Numbers.parse(m.groupValues[3]) ?: continue
            if (mins !in 1..59 || x !in 0..24) continue
            return if (m.groupValues[2] == "vor") ClockHit(prev(x), 60 - mins, m.range, false) else ClockHit(x, mins, m.range, false)
        }
        for (m in uhr.findAll(t)) {
            val h = Numbers.parse(m.groupValues[1]) ?: continue
            if (h !in 0..24) continue
            val mi = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { Numbers.parse(it) }
            // «6 uhr morgens»: zweites Wort ist keine Zahl → nur die Stunde gehört zur Zeit
            return if (mi != null && mi in 0..59) ClockHit(h, mi, m.range, false)
            else ClockHit(h, 0, m.range.first..(m.groups[1]!!.range.last + 4), false)
        }
        spaced.find(t)?.let { m ->
            val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            if (h in 0..24 && mi in 0..59) return ClockHit(h, mi, m.range, false)
        }
        for (m in bare.findAll(t)) {
            val h = Numbers.parse(m.groupValues[1]) ?: continue
            if (h in 0..24) return ClockHit(h, 0, m.range, false)
        }
        return null
    }

    private fun prev(h: Int) = if (h <= 0) 23 else h - 1

    /** «06:30» fürs Anzeigen. */
    fun hhmm(h: Int, m: Int) = "%02d:%02d".format(h, m)

    /** Fürs Vorlesen: «6 Uhr 30», «7 Uhr». */
    fun spoken(h: Int, m: Int) = if (m == 0) "$h Uhr" else "$h Uhr $m"
}

data class DurationHit(val seconds: Int, val ranges: List<IntRange>)

object Durations {
    private val unit = rx("""\b(\d+(?:[.,]\d+)?|[a-zäöü]{3,22})\s?(stunden?|std|h|minuten?|min|sekunden?|sek|sec|s)\b""")
    private val halfHour = rx("""\b(?:eine? )?halben? stunde\b""")
    private val quarterHour = rx("""\b(?:eine? )?(drei)?viertelstunde\b""")
    private val oneAndHalf = rx("""\b(anderthalb|eineinhalb|einundeinhalb) (stunden?|minuten?)\b""")
    private val nAndHalf = rx("""\b([a-zäöü]{3,10})einhalb (stunden?|minuten?)\b""")
    private val hourAndHalf = rx("""\b(\d+|[a-zäöü]{3,10}) stunden? und (?:eine? )?halbe\b""")
    private val trailingMin = rx("""\b(?:stunden?|std|h) (\d{1,2})\b(?! ?(?:minuten?|min|sekunden?|sek|s|uhr)\b)""")
    private val bareNumber = rx("""\b(?:auf|für|fuer|von|mit)? ?(\d{1,3})\b""")

    private fun unitSeconds(u: String) = when {
        u.startsWith("st") || u == "h" -> 3600
        u.startsWith("m") -> 60
        else -> 1
    }

    /** Dauer in Sekunden; [bareMinutes] erlaubt «Timer 10» = 10 Minuten. */
    fun find(t: String, bareMinutes: Boolean = false): DurationHit? {
        var total = 0.0
        val ranges = ArrayList<IntRange>()
        var s = t
        fun consume(r: IntRange) { ranges.add(r); s = s.replaceRange(r, "#".repeat(r.last - r.first + 1)) }

        oneAndHalf.find(s)?.let { total += 1.5 * unitSeconds(it.groupValues[2]); consume(it.range) }
        nAndHalf.find(s)?.let { m ->
            Numbers.parse(m.groupValues[1])?.let { total += (it + 0.5) * unitSeconds(m.groupValues[2]); consume(m.range) }
        }
        hourAndHalf.find(s)?.let { m ->
            Numbers.parse(m.groupValues[1])?.let { total += (it + 0.5) * 3600; consume(m.range) }
        }
        quarterHour.find(s)?.let { total += if (it.groupValues[1].isNotEmpty()) 2700 else 900; consume(it.range) }
        halfHour.find(s)?.let { total += 1800; consume(it.range) }
        trailingMin.find(s)?.let { m -> total += m.groupValues[1].toInt() * 60; consume(m.groups[1]!!.range) }
        while (true) {
            val m = unit.findAll(s).firstOrNull { x ->
                val raw = x.groupValues[1]
                raw.replace(',', '.').toDoubleOrNull() != null || Numbers.parse(raw) != null ||
                    raw == "halbe" || raw == "halben"
            } ?: break
            val raw = m.groupValues[1]
            val n = raw.replace(',', '.').toDoubleOrNull() ?: Numbers.parse(raw)?.toDouble() ?: 0.5
            total += n * unitSeconds(m.groupValues[2])
            consume(m.range)
        }
        if (total == 0.0 && bareMinutes) {
            bareNumber.findAll(s).firstOrNull()?.let { m -> total = m.groupValues[1].toInt() * 60.0; consume(m.range) }
        }
        val sec = Math.round(total).toInt()
        return if (sec > 0) DurationHit(sec, ranges) else null
    }

    /** «10 Minuten», «1 Stunde 30 Minuten», «45 Sekunden». */
    fun spoken(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add(if (h == 1) "1 Stunde" else "$h Stunden")
        if (m > 0) parts.add(if (m == 1) "1 Minute" else "$m Minuten")
        if (s > 0) parts.add(if (s == 1) "1 Sekunde" else "$s Sekunden")
        return when (parts.size) {
            0 -> "0 Sekunden"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(" ") + " und " + parts.last()
        }
    }
}

data class DateHit(val date: LocalDate, val range: IntRange)

object Dates {
    private val months = listOf("januar", "februar", "märz", "april", "mai", "juni", "juli", "august",
        "september", "oktober", "november", "dezember")
    private val monthAlt = mapOf("maerz" to 3, "jänner" to 1, "feber" to 2)
    private val days = mapOf(
        "montag" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY, "mittwoch" to DayOfWeek.WEDNESDAY,
        "donnerstag" to DayOfWeek.THURSDAY, "freitag" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY,
        "sonntag" to DayOfWeek.SUNDAY
    )
    private val dayAfter = rx("""\b(übermorgen|uebermorgen)\b""")
    private val tomorrow = rx("""(?<!am )(?<!guten )(?<!jeden )(?<!heute )\bmorgen\b""")
    private val today = rx("""\bheute\b""")
    private val weekday = rx("""\b(?:am |an einem |nächsten |naechsten |kommenden |diesen )?(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\b""")
    private val dayMonth = rx("""\b(?:am )?(\d{1,2}) (januar|jänner|februar|feber|märz|maerz|april|mai|juni|juli|august|september|oktober|november|dezember)(?: (\d{4}))?\b""")
    private val numeric = rx("""\bam (\d{1,2})\.(\d{1,2})(?:\.(\d{2,4}))?\b|\b(\d{1,2})\.(\d{1,2})\.(\d{2,4})\b""")
    private val inDays = rx("""\bin (\d{1,3}|[a-zäöü]{3,12}) (tag|tagen|woche|wochen)\b""")
    private val nextWeek = rx("""\bnächste woche\b|\bnaechste woche\b""")

    fun find(t: String, now: LocalDate): DateHit? {
        dayAfter.find(t)?.let { return DateHit(now.plusDays(2), it.range) }
        dayMonth.find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val name = m.groupValues[2]
            val mo = monthAlt[name] ?: (months.indexOf(name) + 1)
            val y = m.groupValues[3].toIntOrNull()
            safe(y ?: now.year, mo, d)?.let { date ->
                return DateHit(if (y == null && date.isBefore(now)) date.plusYears(1) else date, m.range)
            }
        }
        numeric.find(t)?.let { m ->
            val d = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toInt()
            val mo = (m.groupValues[2].ifEmpty { m.groupValues[5] }).toInt()
            val yRaw = m.groupValues[3].ifEmpty { m.groupValues[6] }
            val y = yRaw.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            safe(y ?: now.year, mo, d)?.let { date ->
                return DateHit(if (y == null && date.isBefore(now)) date.plusYears(1) else date, m.range)
            }
        }
        inDays.find(t)?.let { m ->
            Numbers.parse(m.groupValues[1])?.let { n ->
                val days = if (m.groupValues[2].startsWith("woche")) n * 7L else n.toLong()
                return DateHit(now.plusDays(days), m.range)
            }
        }
        nextWeek.find(t)?.let { return DateHit(now.plusDays(7), it.range) }
        weekday.find(t)?.let { m ->
            return DateHit(now.with(TemporalAdjusters.next(days[m.groupValues[1]]!!)), m.range)
        }
        tomorrow.find(t)?.let { m ->
            // «morgen früh/abend» und «morgen um …» = Folgetag; «am morgen» wurde oben ausgeschlossen
            return DateHit(now.plusDays(1), m.range)
        }
        today.find(t)?.let { return DateHit(now, it.range) }
        return null
    }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (e: Exception) { null }

    private val weekdayNames = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
    private val monthNames = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August",
        "September", "Oktober", "November", "Dezember")

    fun weekdayName(d: LocalDate) = weekdayNames[d.dayOfWeek.value - 1]
    fun monthName(month: Int) = monthNames[month - 1]

    /** «heute», «morgen», «am Freitag, 27. November». */
    fun spoken(d: LocalDate, now: LocalDate): String = when (d) {
        now -> "heute"
        now.plusDays(1) -> "morgen"
        now.plusDays(2) -> "übermorgen"
        else -> "am ${weekdayName(d)}, ${d.dayOfMonth}. ${monthName(d.monthValue)}"
    }

    /** «Sonntag, 22. November 2026». */
    fun full(d: LocalDate) = "${weekdayName(d)}, ${d.dayOfMonth}. ${monthName(d.monthValue)} ${d.year}"
}

/** Codes, PINs und Passwörter unkenntlich machen, bevor Text vorgelesen oder an die Cloud geschickt wird. */
object Redact {
    private val context = Regex("""(code|pin|tan|passwort|kennwort|password|passcode|verifizier|bestätigungs|bestaetigungs|sicherheits|einmal|otp|login)""", RegexOption.IGNORE_CASE)
    private val digits = Regex("""(?<![\d+.:/-])\d(?:[ -]?\d){3,7}(?![\d.:/]|\s?(?:uhr|chf|fr\.|franken|eur|€|%|prozent|km|kg|g\b|m\b))""")
    private val card = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val iban = Regex("""\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,4})?\b""")

    fun secrets(text: String): String {
        var t = iban.replace(text, "[Kontonummer]")
        t = card.replace(t) { m -> if (m.value.count { it.isDigit() } >= 13) "[Kartennummer]" else m.value }
        if (context.containsMatchIn(t)) t = digits.replace(t, "[Code]")
        return t
    }
}
