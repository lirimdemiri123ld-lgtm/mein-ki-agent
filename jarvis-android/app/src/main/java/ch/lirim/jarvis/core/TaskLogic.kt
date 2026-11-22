package ch.lirim.jarvis.core

/**
 * Reine Logik (ohne Android) für das lokale Gehirn:
 * Komplexität einschätzen, Plan lesen, Arbeits-Prompt bauen, Schleifen erkennen.
 */
object TaskLogic {

    // ---------------------------------------------------------------- Komplexität

    private val multiWords = listOf(
        " und dann", " danach", " anschliessend", " und schick", " und schreib", " und sag", " und such",
        "alle ", "zusammenfass", "vergleich", "durchgeh", "durchsuch", "sortier", "organisier", "plane ",
        "buch ", "buche", "bestell", "reservier", "recherch", "finde heraus", "prüf ob", "checke",
        "nachricht an", "schreib ", "schick ", "sende ", "antworte ", "e-mail", "email", "mail an",
        "verbindung", "route", "termin", "kalender", "ungelesen", "liste "
    )
    private val simpleStarts = listOf("wie ", "was ", "wer ", "warum ", "wieso ", "weshalb ", "wann ", "erzähl", "erklär", "hallo", "hi ", "danke")
    private val simpleActions = listOf("öffne ", "starte ", "wecker", "timer", "ruf ", "anrufen", "merk dir", "vergiss")

    /** true = mehrschrittige Aufgabe → Planen + grosses Gehirn. */
    fun isComplex(command: String): Boolean {
        val c = " " + command.lowercase().trim() + " "
        val words = c.trim().split(Regex("\\s+")).size
        val hasMulti = multiWords.any { c.contains(it) }
        val simpleQ = simpleStarts.any { c.trim().startsWith(it.trim()) } && !hasMulti
        val simpleAct = simpleActions.any { c.contains(it) } && !hasMulti && words <= 8
        return when {
            simpleQ || simpleAct -> false
            hasMulti -> true
            words >= 14 -> true
            else -> false
        }
    }

    // ---------------------------------------------------------------- Plan

    const val PLAN_SYSTEM = """Du planst Aufgaben für Jarvis, einen Assistenten, der ein Android-Handy bedient (Apps öffnen, tippen, Text eingeben, scrollen, lesen).
Zerlege den Auftrag in 2 bis 6 kurze, konkrete Schritte. Jeder Schritt ist eine sichtbare Teilaufgabe auf dem Handy.
Regeln für gute Pläne:
- Ein Text wird in einem Schritt eingegeben (kein eigener Schritt fürs Antippen des Textfelds).
- Erfinde keine Knöpfe, die es vielleicht nicht gibt (z. B. «Zusammenfassen»). Zusammenfassen, zählen oder vergleichen machst du selbst aus dem, was du gelesen und notiert hast.
- Infos, die auf einem Bildschirm stehen, werden gelesen und notiert (z. B. «Betreff und Absender der ungelesenen Mails notieren»).
- Der letzte Schritt ist immer «Lirim das Ergebnis mitteilen».
Antworte NUR mit einer nummerierten Liste, ohne Einleitung."""

    fun planPrompt(command: String, hints: String): String = buildString {
        if (hints.isNotBlank()) append("Erfahrungen aus früheren Aufgaben:\n").append(hints).append("\n\n")
        append("Auftrag: ").append(command)
    }

    fun parsePlan(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val numbered = lines.mapNotNull { l ->
            Regex("""^(?:\*\*)?(?:schritt\s*)?\d+[.):]\s*(?:\*\*)?\s*(.+)$""", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)
                ?: Regex("""^[-•*]\s+(.+)$""").find(l)?.groupValues?.get(1)
        }.map { it.replace("**", "").trim() }.filter { it.length > 2 }
        return numbered.take(10)
    }

    // ---------------------------------------------------------------- Arbeits-Prompt

    data class State(
        val command: String,
        val plan: MutableList<String>,
        var current: Int = 0,
        val history: MutableList<String> = mutableListOf(),
        val notes: MutableList<String> = mutableListOf(),
        var screen: String = "",
        var hint: String = "",
    )

    fun execPrompt(s: State): String = buildString {
        append("Auftrag von Lirim: ").append(s.command).append('\n')
        if (s.plan.isNotEmpty()) {
            append("\nPlan:\n")
            s.plan.forEachIndexed { i, p ->
                val mark = when {
                    i < s.current -> "✓"
                    i == s.current -> "▶"
                    else -> " "
                }
                append(" $mark ${i + 1}. $p").append(if (i == s.current) "   ← AKTUELLER SCHRITT" else "").append('\n')
            }
        }
        if (s.notes.isNotEmpty()) append("\nGesammelte Infos:\n").append(s.notes.joinToString("\n") { "- $it" }).append('\n')
        if (s.history.isNotEmpty()) append("\nLetzte Aktionen:\n").append(s.history.takeLast(5).joinToString("\n")).append('\n')
        if (s.hint.isNotBlank()) append("\nACHTUNG: ").append(s.hint).append('\n')
        if (s.screen.isNotBlank()) append("\nAktueller Bildschirm:\n").append(s.screen.take(2600)).append('\n')
        append(
            if (s.plan.isEmpty()) "\nWähle die nächste Aktion."
            else "\nPrüfe zuerst am Bildschirm: Ist der aktuelle Schritt schon erledigt? Dann schritt_erledigt. " +
                "Sonst wähle EIN Werkzeug für diesen Schritt. Stehen nötige Infos auf dem Bildschirm, halte sie mit notiz fest. " +
                "Ist alles erledigt: fertig mit der Antwort an Lirim."
        )
    }

    // ---------------------------------------------------------------- Schleifen erkennen

    /** Bildschirm-«Fingerabdruck» ohne Nummern, um «nichts hat sich geändert» zu erkennen. */
    fun fingerprint(screen: String): Int = screen.lines()
        .map { it.replace(Regex("""^\[\d+]\s*"""), "").replace(Regex("""@\d+,\d+"""), "") }
        .joinToString("\n").hashCode()

    fun actionKey(name: String, args: Map<String, Any?>) = name + args.toSortedMap().toString()

    /** Was tun, wenn das Modell mitten in einer Planaufgabe nur Text liefert? */
    fun textMeansFinish(s: State, text: String, textStreak: Int): Boolean {
        if (s.plan.isEmpty()) return true
        if (s.current >= s.plan.size) return true
        if (s.current >= s.plan.size - 1 && text.length > 60) return true   // letzter Schritt = Ergebnis mitteilen
        return textStreak >= 3
    }

    fun textHint(s: State) =
        "Du hast nur geschrieben, aber nichts getan. Der aktuelle Schritt ist «${s.plan.getOrElse(s.current) { "" }}». Rufe jetzt ein Werkzeug auf."

    /** Gleiche Aktion ohne Wirkung wiederholt → wahrscheinlich ist der Schritt schon erledigt. */
    fun repeatHint(tool: String, times: Int) =
        "Du hast «$tool» mit denselben Angaben schon ${times}× gemacht, ohne dass sich etwas ändert. Ist der aktuelle Schritt vielleicht schon erledigt? Dann schritt_erledigt. Sonst versuche etwas anderes."

    /** Zusammenfassung aus Notizen, falls das Modell ohne Antwort aufhört. */
    fun fallbackAnswer(s: State): String =
        if (s.notes.isNotEmpty()) s.notes.joinToString(". ").take(400) else "Erledigt."
}
