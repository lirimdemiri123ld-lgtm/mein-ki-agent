package ch.lirim.jarvis.core

/**
 * Beziehungen erkennen: «meine Frau», «mein Chef», «Gruja ist meine Frau».
 * Die eigentlichen Personen liegen im Gedächtnis (MemoryBook).
 */
object People {
    val relations = listOf(
        "frau", "mann", "partnerin", "partner", "freundin", "freund", "verlobte", "verlobter",
        "vater", "mutter", "bruder", "schwester", "sohn", "tochter",
        "chef", "kollege", "kollegin", "teamleiter", "teamleiterin", "stellvertreter", "stellvertreterin",
        "onkel", "tante", "cousin", "cousine", "opa", "oma", "grossvater", "grossmutter",
        "schwager", "schwägerin", "schwiegermutter", "schwiegervater", "nachbar", "nachbarin",
        "mitbewohner", "mitbewohnerin", "hausarzt", "zahnarzt", "arzt", "trainer", "vermieter", "vermieterin"
    )
    private val synonyms = mapOf(
        "ehefrau" to "frau", "gattin" to "frau", "ehemann" to "mann", "gatte" to "mann",
        "papa" to "vater", "papi" to "vater", "dad" to "vater", "mama" to "mutter", "mami" to "mutter", "mum" to "mutter",
        "chefin" to "chef", "boss" to "chef", "vorgesetzter" to "chef", "vorgesetzte" to "chef", "vorgesetzten" to "chef",
        "grosi" to "grossmutter", "grossmami" to "grossmutter", "grosspapi" to "grossvater", "omi" to "oma", "opi" to "opa",
        "schwaegerin" to "schwägerin", "teamleader" to "teamleiter", "ärztin" to "arzt", "hausärztin" to "hausarzt"
    )
    private val all = (relations + synonyms.keys).sortedByDescending { it.length }
    private val REL = all.joinToString("|")
    private val inText = rx("""\bmein(?:e|er|em|en|es)? ($REL)(?:s|n)?\b""")
    private val only = rx("""^(?:mit |bei |an |für |fuer )?mein(?:e|er|em|en|es)? ($REL)(?:s|n)?$""")

    private val aIs = rx("""^(.+?) ist mein(?:e|er|en)? ($REL)$""")
    private val bNamed = rx("""^mein(?:e|er|en)? ($REL) (heisst|heisst mit vornamen|ist|nennt sich) (.+)$""")
    private val cSub = rx("""^(.+?) mein(?:e|er|en)? ($REL) ist$""")
    private val dNick = rx("""^([\p{L}][\p{L} .'-]{0,30}?) ?= ?.*\b($REL)\b""")
    private val notNames = setOf("das", "dies", "dieser", "diese", "er", "sie", "es", "wer", "hier", "heute", "da", "dort",
        "jetzt", "was", "wie", "wo", "krank", "müde", "weg", "nicht", "auch", "schon", "noch", "sehr", "gerade", "bald",
        "zuhause", "daheim", "unterwegs", "schwanger", "nett", "toll", "super", "gut", "böse", "wütend", "glücklich", "ja", "nein")

    /** Kanonische Beziehung («ehefrau» → «frau») oder null. */
    fun canon(word: String): String? {
        val w = Utt.normWord(word.trim())
        if (w in relations) return w
        synonyms[w]?.let { return it }
        // Beugung: «vaters», «kollegen»
        val stem = w.removeSuffix("s").removeSuffix("n")
        if (stem in relations) return stem
        return synonyms[stem]
    }

    /** Beziehung, die im (normalisierten) Text vorkommt: «ruf meine frau an» → «frau». */
    fun relationIn(textNorm: String): String? = inText.find(textNorm)?.let { canon(it.groupValues[1]) }

    /** Besteht der Text nur aus einer Beziehung? («meiner Frau») */
    fun onlyRelation(textNorm: String): String? = only.find(textNorm)?.let { canon(it.groupValues[1]) }

    /** «Gruja ist meine Frau» / «Meine Frau heisst Gruja» → (Name, Beziehung). */
    fun extractRelation(raw: String, strict: Boolean = true): Pair<String, String>? {
        var u = Utt(raw.trim().trimEnd('.', '!'))
        if (u.text.startsWith("dass ")) u = Utt(u.rawFrom(5))
        val t = u.text
        fun name(g: MatchGroup?, needCapital: Boolean): String? {
            val n = u.rawOf(g).trim().trim('«', '»', '"', ',', '.')
            val toks = Utt.norm(n).split(' ')
            if (n.isEmpty() || toks.size > 3) return null
            if (toks.any { it in notNames } || toks.first().startsWith("mein")) return null
            if (!n.any { it.isLetter() }) return null
            if (strict && needCapital && !n.first().isUpperCase()) return null
            return n
        }
        aIs.find(t)?.let { m -> name(m.groups[1], true)?.let { return it to canon(m.groupValues[2])!! } }
        bNamed.find(t)?.let { m ->
            val strict = m.groupValues[2] == "ist"
            name(m.groups[3], strict)?.let { return it to canon(m.groupValues[1])!! }
        }
        cSub.find(t)?.let { m -> name(m.groups[1], true)?.let { return it to canon(m.groupValues[2])!! } }
        dNick.find(raw.trim().lowercase().replace("ß", "ss"))?.let { m ->
            val n = raw.trim().substring(0, m.groups[1]!!.range.last + 1).trim()
            if (n.isNotEmpty() && Utt.norm(n).split(' ').size <= 3) return n to canon(m.groupValues[2])!!
        }
        return null
    }

    /** Beziehung → Name aus gemerkten Fakten («Gruja ist meine Frau»). */
    fun relations(facts: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (f in facts) extractRelation(f, strict = false)?.let { (name, rel) -> out[rel] = name }
        return out
    }

    /** Findet in einem Text («Ruf meine Frau an») die gemeinte Person. */
    fun resolve(text: String, facts: List<String>): String? {
        val t = Utt.norm(text)
        relationIn(t)?.let { rel -> relations(facts)[rel]?.let { return it } }
        // Spitznamen-Fakten «Baba = Kontakt «Baba Swisscom»»
        val words = t.split(' ')
        for (f in facts) {
            val m = Regex("""^\s*([\p{L}]{2,20})\s*=\s*(.+)$""").find(f) ?: continue
            if (Utt.normWord(m.groupValues[1]) in words) {
                return Regex("""«([^»]+)»""").find(m.groupValues[2])?.groupValues?.get(1) ?: m.groupValues[1]
            }
        }
        return null
    }

    /** Ist das Argument nur eine Beziehung oder leer? */
    fun isRelational(arg: String) = arg.isBlank() || onlyRelation(Utt.norm(arg)) != null || canon(arg.trim()) != null

    /** «frau» → «Frau» (fürs Vorlesen: «deine Frau»). */
    fun spoken(rel: String): String {
        val fem = setOf("frau", "partnerin", "freundin", "verlobte", "mutter", "schwester", "tochter", "kollegin",
            "teamleiterin", "stellvertreterin", "tante", "cousine", "oma", "grossmutter", "schwägerin", "schwiegermutter",
            "nachbarin", "mitbewohnerin", "vermieterin")
        return (if (rel in fem) "deine " else "dein ") + rel.replaceFirstChar { it.uppercase() }
    }
}
