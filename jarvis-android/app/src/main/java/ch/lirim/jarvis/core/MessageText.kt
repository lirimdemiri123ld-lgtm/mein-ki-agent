package ch.lirim.jarvis.core

/**
 * Nachrichtentext ohne KI ableiten:
 * «…, dass wir zu Pronto gehen» → «Wir gehen zu Pronto.»
 *
 * Deutsche Nebensätze haben das Verb am Schluss; für die Nachricht muss es an die zweite Stelle.
 * Sichere Fälle (ich/wir als Subjekt) werden per Regel gelöst – gratis und sofort.
 * Unsichere Fälle (Perspektivwechsel wie «dass sie mich anrufen soll») werden als «nicht sicher»
 * markiert; dann formuliert Claude mit einem Mini-Aufruf oder Jarvis fragt nach dem Wortlaut.
 */
object MessageText {
    data class Result(val text: String, val confident: Boolean)

    private val subjects = setOf("ich", "wir", "du", "er", "sie", "es", "man", "ihr")
    private val thirdPerson = Regex("""^(er|sie|ihn|ihm|ihr|ihnen|ihre[mnrs]?|sein|seine[mnrs]?)$""")
    private val subordinators = setOf("weil", "wenn", "obwohl", "damit", "bevor", "nachdem", "sobald", "falls", "da", "solange", "bis")
    private val commonFinite = setOf(
        "bin", "bist", "ist", "sind", "seid", "habe", "hab", "hast", "hat", "haben", "habt", "werde", "wirst", "wird", "werden",
        "kann", "kannst", "können", "muss", "musst", "müssen", "will", "willst", "wollen", "soll", "sollst", "sollen", "sollte",
        "darf", "darfst", "dürfen", "mag", "möchte", "möchtest", "möchten", "weiss", "weisst", "wissen", "war", "waren",
        "hatte", "hatten", "wurde", "wäre", "hätte", "würde", "könnte", "müsste", "bleibe", "bleiben", "brauche", "brauchen"
    )
    private val prefixes = listOf("zusammen", "zurück", "herein", "heraus", "hinaus", "vorbei", "weiter", "runter", "rüber",
        "fest", "fort", "heim", "hoch", "rein", "raus", "nach", "auf", "aus", "bei", "ein", "her", "hin", "los", "mit", "vor",
        "weg", "ab", "an", "zu", "um")
    private val stems = setOf("komm", "geh", "fahr", "fähr", "ruf", "hol", "kauf", "steh", "fang", "fäng", "hör", "mach", "nehm",
        "nimm", "geb", "gib", "seh", "sieh", "schau", "bring", "zieh", "lad", "läd", "lass", "leg", "stell", "setz", "sag",
        "schreib", "schick", "meld", "pass", "treff", "triff", "halt", "hält", "lauf", "läuf", "flieg", "steig", "pack", "räum",
        "denk", "frag", "zahl", "schalt", "dreh", "heb", "ruh", "schlaf", "schläf", "wach", "probier", "richt", "teil", "biet",
        "kündig", "schliess", "les", "lies", "spiel", "hab", "bleib", "arbeit", "lief", "send", "reis", "guck", "kling", "wart")
    private val umStems = setOf("zieh", "kehr", "dreh", "steig", "schalt", "buch", "stell", "pack", "räum")
    private val endings = listOf("est", "et", "en", "st", "e", "t", "")

    /** Wörtlicher Text: Satzanfang gross, Schlusspunkt, Schweizer «ss». */
    fun literal(text: String): String {
        var t = text.trim().trim('«', '»', '"').trim().replace("ß", "ss")
        if (t.isEmpty()) return t
        t = t.replaceFirstChar { it.uppercase() }
        if (t.last().isLetterOrDigit()) t += "."
        return t
    }

    fun from(body: Body): Result? = when (body) {
        is Body.None -> null
        is Body.Literal -> Result(literal(body.text), true)
        is Body.Clause -> fromClause(body.kind, body.text)
    }

    fun fromClause(kind: String, clause: String): Result {
        val words = clause.trim().trimEnd('.', '!', '?', ',').split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2 || kind != "dass") return Result("", false)
        // Hauptteil vom angehängten Nebensatz trennen («…, weil ich im Stau stehe»)
        var cut = words.size
        for (i in 2 until words.size) if (words[i].trim(',').lowercase() in subordinators) { cut = i; break }
        val main = words.subList(0, cut).map { it.trim(',') }
        val tail = words.subList(cut, words.size)

        var confident = true
        val firstLc = main[0].lowercase()
        var subjLen = 1
        if (firstLc !in subjects) {
            confident = false
            if (firstLc in setOf("der", "die", "das", "ein", "eine", "mein", "meine", "unser", "unsere", "dein", "deine") && main.size >= 3) subjLen = 2
        }
        if (firstLc != "ich" && firstLc != "wir") confident = false
        if (words.drop(1).any { thirdPerson.matches(it.trim(',').lowercase()) }) confident = false

        // In Teilsätze zerlegen: «ich im Stau stehe | und | später komme»
        val parts = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for ((i, w) in main.withIndex()) {
            if (w.lowercase() == "und" && cur.size >= 2 && i < main.size - 2 && looksFinite(cur.last())) {
                parts.add(cur); cur = ArrayList()
            } else cur.add(w)
        }
        parts.add(cur)

        val out = ArrayList<String>()
        for ((pi, part) in parts.withIndex()) {
            if (part.size < 2) return Result("", false)
            val hasSubj = if (pi == 0) true else part[0].lowercase() in subjects
            val sLen = if (pi == 0) subjLen else if (hasSubj) 1 else 0
            val subj = part.subList(0, sLen)
            val verbTok = part.last()
            val mid = part.subList(sLen, part.size - 1)
            if (!looksFinite(verbTok)) confident = false
            val (verb, particle) = split(verbTok)
            val seq = ArrayList<String>()
            seq.addAll(subj); seq.add(verb); seq.addAll(mid)
            if (particle.isNotEmpty()) seq.add(particle)
            if (pi > 0) out.add("und")
            out.addAll(seq)
        }
        var text = out.joinToString(" ")
        if (tail.isNotEmpty()) text += ", " + tail.joinToString(" ")
        return Result(literal(text), confident)
    }

    private fun looksFinite(tok: String): Boolean {
        val t = tok.trim(',', '.')
        if (t.isEmpty() || t[0].isUpperCase() || t[0].isDigit()) return false
        val lc = t.lowercase()
        if (lc in commonFinite) return true
        return lc.length >= 3 && (lc.endsWith("e") || lc.endsWith("en") || lc.endsWith("t") || lc.endsWith("st"))
    }

    /** Trennbares Verb aufspalten: «anrufe» → («rufe», «an»). */
    private fun split(tok: String): Pair<String, String> {
        val lc = tok.lowercase()
        if (lc in commonFinite) return tok to ""
        for (p in prefixes) {
            if (!lc.startsWith(p) || lc.length < p.length + 3) continue
            val rest = lc.substring(p.length)
            val pool = if (p == "um") umStems else stems
            val ok = pool.any { s -> rest.startsWith(s) && rest.substring(s.length) in endings }
            if (ok) return tok.substring(p.length) to p
        }
        return tok to ""
    }
}
