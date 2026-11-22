package ch.lirim.jarvis.core

/**
 * Gelernte Abläufe («Rezepte»): Was Claude einmal erfolgreich erledigt hat,
 * spielt Jarvis beim nächsten gleichen oder ähnlichen Auftrag selbst ab – gratis.
 *
 * Beispiel: «Schreib Gruja auf WhatsApp, dass ich später komme»
 *   → Vorlage «schreib {1} auf whatsapp dass {2}»
 *   → passt auch auf «Schreib Ali auf WhatsApp, dass ich im Stau stehe».
 */
object Recipes {
    data class Step(val tool: String, val args: Map<String, String>)

    /** Platzhalter für Text, der beim Abspielen neu formuliert wird. */
    const val GEN = "{gen}"
    private const val PENDING = "{?}"

    data class Recipe(
        val template: String,          // normalisiert, mit {1}, {2} …
        val steps: List<Step>,         // Argumente dürfen {n} enthalten
        val answer: String,            // gesprochene Antwort, darf {n} enthalten
        val successes: Int = 0,
        val failures: Int = 0,
        val ts: Long = 0L
    ) {
        val fixedWords: Int get() = template.split(" ").count { !it.matches(Regex("""\{\d+\}""")) }
    }

    /** Werkzeuge, die nur lesen/denken – werden nicht aufgezeichnet. */
    val ignored = setOf("bildschirm_lesen", "kontakt_suchen", "apps_auflisten", "merken", "vergessen",
        "erfahrung_speichern", "projekt_aktualisieren", "fertig")

    /** Werkzeuge, die man nicht zuverlässig wiederholen kann → kein Rezept. */
    val notReplayable = setOf("tippen_xy", "wischen", "lange_druecken", "screenshot", "live_daten",
        "nachrichten_lesen", "frage_stellen", "web_search")

    fun normalize(s: String): String = s.lowercase()
        .replace(Regex("""^\s*(hallo|hey|hi|ok)?\s*jarvis\b"""), " ")
        .replace(Regex("""\bbitte\b"""), " ")
        .replace(Regex("""[^\p{L}\p{N}{} ]"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun containsWords(hay: String, needle: String): Boolean =
        Regex("""(^| )${Regex.escape(needle)}( |$)""").containsMatchIn(hay)

    /**
     * Aus einem erfolgreichen Ablauf ein Rezept bauen.
     * Liefert null, wenn der Ablauf nicht sicher wiederholbar ist.
     */
    fun build(command: String, recorded: List<Step>, answer: String, now: Long = 0L): Recipe? {
        val cmd = normalize(command)
        if (cmd.split(" ").size < 2) return null
        val steps = recorded.filter { it.tool !in ignored }
        if (steps.isEmpty()) return null
        if (steps.any { it.tool in notReplayable }) return null
        if (steps.size > 25) return null

        // Kandidaten für Platzhalter: Texte, die aus dem Auftrag stammen
        val slotValues = linkedMapOf<String, String>() // normalisierter Wert → Platzhalter
        val cmdWords = cmd.split(" ")
        fun slotFor(raw: String): String? {
            val v = normalize(raw)
            if (v.isEmpty()) return null
            slotValues[v]?.let { return it }
            val inCmd = when {
                containsWords(cmd, v) -> v
                // Spracherkennung: «gruya» im Auftrag, «Gruja» auf dem Bildschirm
                !v.contains(' ') && v.length >= 3 -> cmdWords.filter { it.length >= 3 }
                    .maxByOrNull { Fuzzy.score(it, v) }?.takeIf { Fuzzy.score(it, v) >= 80 }
                else -> null
            } ?: return null
            // ganze Vorlage darf nicht nur aus Platzhaltern bestehen
            if (inCmd == cmd) return null
            val key = "{${slotValues.values.toSet().size + 1}}"
            slotValues[inCmd] = key
            if (inCmd != v) slotValues[v] = key
            return key
        }

        val out = mutableListOf<Step>()
        for (s in steps) {
            val args = s.args.toMutableMap()
            when (s.tool) {
                "tippen" -> {
                    val t = args["text"] ?: return null
                    if (t.isBlank() || t.length > 50) return null
                    slotFor(t)?.let { args["text"] = it }
                }
                "text_eingeben" -> {
                    val t = args["text"] ?: return null
                    val slot = slotFor(t)
                    if (slot != null) args["text"] = slot
                    else { args["text"] = PENDING; args["beispiel"] = t; args["beispiel_auftrag"] = command }
                }
                "anrufen" -> args["wen"] = slotFor(args["wen"] ?: return null) ?: return null
                "bestaetigen" -> args["frage"]?.let { args["frage"] = fillAnswer(it, slotValues) }
                "app_oeffnen", "url_oeffnen", "scrollen", "taste", "warten",
                "wecker_stellen", "timer_stellen", "anrufen_vorbereiten" -> {}
                else -> return null
            }
            out.add(Step(s.tool, args))
        }

        // Nachrichteninhalt («…, dass ich später komme») als Platzhalter, damit ähnliche Aufträge passen
        val clause = Regex("""\b(?:dass|ob|mit dem text|folgendes)\s+(.+)$""").find(cmd)?.groupValues?.get(1)?.trim()
        fun words(x: String) = normalize(x).split(" ").filter { it.length >= 3 }.toSet()
        var hasGen = false
        for (i in out.indices) {
            val s = out[i]
            if (s.args["text"] != PENDING) continue
            val t = s.args["beispiel"] ?: ""
            // Frei formulierter Text, der vom Auftrag abhängt (z. B. eine Nachricht) → beim Abspielen neu formulieren.
            // Sonst (z. B. Suchwort «Gruja» bei «meiner Frau») ist er fest.
            val dependent = if (clause != null) (words(t) intersect words(clause)).isNotEmpty() || words(t).size >= 3
                            else words(t).size >= 3
            out[i] = if (dependent) { hasGen = true; Step(s.tool, s.args + ("text" to GEN)) }
                     else Step(s.tool, mapOf("text" to t) + s.args.filterKeys { it != "text" && !it.startsWith("beispiel") })
        }
        if (hasGen && clause != null && clause != cmd && slotValues[clause] == null)
            slotValues[clause] = "{${slotValues.values.toSet().size + 1}}"
        // Vorlage: Slot-Werte (längste zuerst) im Auftrag ersetzen
        var template = cmd
        val ordered = slotValues.entries.filter { containsWords(cmd, it.key) }.sortedByDescending { it.key.length }
        for ((v, key) in ordered) template = template.replace(Regex("""(^| )${Regex.escape(v)}( |$)"""), "$1$key$2")
        // Jeder benutzte Platzhalter muss in der Vorlage vorkommen
        val used = out.flatMap { it.args.values }.flatMap { Regex("""\{\d+\}""").findAll(it).map { m -> m.value }.toList() }.toSet()
        if (used.any { !template.contains(it) }) return null
        // Bei frei formulierten Texten keine alte Zusammenfassung wiederholen
        val ans = if (hasGen) "Erledigt." else fillAnswer(answer, slotValues)
        val r = Recipe(template, out, ans, ts = now)
        if (r.fixedWords < 2) return null
        return r
    }

    private fun fillAnswer(answer: String, slots: Map<String, String>): String {
        var a = answer
        for ((v, key) in slots.entries.sortedByDescending { it.key.length }) {
            if (v.length < 3) continue
            a = a.replace(Regex("""(?i)\b${Regex.escape(v)}\b"""), key)
        }
        return a
    }

    /** Prüft, ob ein Auftrag auf ein Rezept passt. Liefert die Platzhalter-Werte. */
    fun match(r: Recipe, command: String): Map<String, String>? {
        val cmd = normalize(command)
        val parts = r.template.split(" ")
        val keys = mutableListOf<String>()
        val rx = StringBuilder("^")
        parts.forEachIndexed { i, p ->
            if (i > 0) rx.append(" ")
            if (p.matches(Regex("""\{\d+\}"""))) { keys.add(p); rx.append("(.+?)") } else rx.append(Regex.escape(p))
        }
        rx.append("$")
        val m = Regex(rx.toString()).find(cmd) ?: return null
        val out = mutableMapOf<String, String>()
        keys.forEachIndexed { i, k ->
            val v = m.groupValues[i + 1].trim()
            if (v.isEmpty()) return null
            if (out[k] != null && out[k] != v) return null
            out[k] = v
        }
        return out
    }

    /** Bestes passendes Rezept (meiste feste Wörter, dann zuverlässigste). */
    fun find(all: List<Recipe>, command: String): Pair<Recipe, Map<String, String>>? =
        all.mapNotNull { r -> match(r, command)?.let { r to it } }
            .filter { it.first.failures < 2 || it.first.successes > it.first.failures }
            .sortedWith(compareByDescending<Pair<Recipe, Map<String, String>>> { it.first.fixedWords }
                .thenByDescending { it.first.successes - it.first.failures })
            .firstOrNull()

    fun fill(text: String, b: Map<String, String>): String {
        var t = text
        for ((k, v) in b) t = t.replace(k, v)
        return t
    }

    fun instantiate(r: Recipe, b: Map<String, String>): List<Step> =
        r.steps.map { s -> Step(s.tool, s.args.mapValues { if (it.key.startsWith("beispiel")) it.value else fill(it.value, b) }) }

    /** Erster Buchstabe gross (für eingetippte Nachrichten). */
    fun sentence(s: String): String = s.replaceFirstChar { it.uppercase() }
}
