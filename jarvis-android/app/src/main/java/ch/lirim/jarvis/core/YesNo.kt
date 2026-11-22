package ch.lirim.jarvis.core

/** Gesprochene Antwort als Ja / Nein erkennen (Hochdeutsch und Schweizerdeutsch). */
object YesNo {
    private val yesFirst = rx("""^(warum nicht|wieso nicht|ja klar|ja bitte|ja gerne|aber ja|na klar|auf jeden fall|sehr gerne|nicht schlecht)\b""")
    private val no = rx("""\b(nein|nei|nö|noe|nee|ne|nicht|nöd|noed|stopp|stop|abbrechen|abbruch|falsch|lass|lieber nicht|auf keinen fall|keinesfalls|niemals|no|nope|negativ|vergiss es)\b""")
    private val yes = rx("""\b(ja|jo|joa|jä|jae|jep|jap|jup|jawohl|jawoll|genau|klar|okay|ok|o k|mach|machs|mach es|gerne|gern|sicher|richtig|bitte|passt|stimmt|korrekt|los|yes|yep|natürlich|unbedingt|einverstanden|in ordnung|positiv|senden|abschicken|schick|schicken|anrufen|weiter|weitermachen|tu es|go|sowieso|scho)\b""")

    /** true = Ja, false = Nein, null = nicht verstanden. */
    fun parse(text: String): Boolean? {
        val t = Utt.norm(text)
        if (t.isEmpty()) return null
        if (yesFirst.containsMatchIn(t)) return true
        if (no.containsMatchIn(t)) return false
        if (yes.containsMatchIn(t)) return true
        return null
    }
}

/** Auswahl aus mehreren Möglichkeiten: «der zweite», «SIM 1», «Nummer drei», «den letzten» oder der Name selbst. */
object Choice {
    private val numbered = rx("""\b(?:sim|nummer|nr|option|variante|karte|kontakt|eintrag|punkt)? ?(\d{1,2}|[a-zäöü]{3,12})\b""")
    private val ordinal = rx("""\b(?:der|die|das|den|dem|am|beim|zum|zur)? ?([a-zäöü]{4,10}|\d{1,2})\b""")

    /** 0-basierter Index oder null. */
    fun parse(text: String, options: List<String>): Int? {
        val t = Utt.norm(text)
        if (t.isEmpty() || options.isEmpty()) return null
        val n = options.size
        // 1. Ordnungszahlen: «der zweite», «die letzte»
        for (w in t.split(' ')) {
            if (w.all { it.isDigit() }) continue
            val o = Numbers.ordinal(w) ?: continue
            if (w == "acht") continue
            if (o == -1) return n - 1
            if (o in 1..n) return o - 1
        }
        // 2. Zahlen: «SIM 1», «zwei», «Nummer 3»
        for (m in numbered.findAll(t)) {
            val v = Numbers.parse(m.groupValues[1]) ?: continue
            if (v in 1..n) return v - 1
        }
        // 3. Name der Option (auch unscharf)
        val scored = options.mapIndexed { i, o -> i to Fuzzy.score(t, o) }
        val best = scored.maxByOrNull { it.second } ?: return null
        if (best.second >= 65 && scored.count { it.second == best.second } == 1) return best.first
        // einzelnes Wort der Antwort passt eindeutig zu genau einer Option
        val hits = options.indices.filter { i ->
            val on = Utt.norm(options[i])
            t.split(' ').any { w -> w.length >= 3 && on.split(' ').any { it == w } }
        }
        return hits.singleOrNull()
    }
}
