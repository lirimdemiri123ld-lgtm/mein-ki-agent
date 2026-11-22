package ch.lirim.jarvis.core

/** Gesprochene Antwort als Ja / Nein erkennen (Hochdeutsch und Schweizerdeutsch). */
object YesNo {
    private val no = Regex("""\b(nein|nei|nö|nee|ne|nicht|stopp|stop|abbrechen|abbruch|falsch|lass|lieber nicht|auf keinen fall|no)\b""")
    private val yes = Regex("""\b(ja|jo|joa|jä|jap|jawohl|jawoll|genau|klar|okay|ok|o k|mach|machs|gerne|gern|sicher|richtig|bitte|passt|stimmt|korrekt|los|yes|yep|natürlich)\b""")

    /** true = Ja, false = Nein, null = nicht verstanden. */
    fun parse(text: String): Boolean? {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return null
        if (no.containsMatchIn(t)) return false
        if (yes.containsMatchIn(t)) return true
        return null
    }
}
