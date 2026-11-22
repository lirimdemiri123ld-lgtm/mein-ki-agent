package ch.lirim.jarvis.core

/**
 * Löst Beziehungen wie «meine Frau», «mein Chef», «Baba» aus gemerkten Fakten deterministisch auf.
 * Fakten-Beispiele: «gruya ist meine Frau», «Baba = Kontakt Baba Swisscom (Vater)», «Mein Chef ist Sebastian».
 */
object People {
    private val relationWords = listOf(
        "frau", "ehefrau", "mann", "ehemann", "partnerin", "partner", "freundin", "freund",
        "vater", "papa", "mutter", "mama", "bruder", "schwester", "sohn", "tochter",
        "chef", "chefin", "vorgesetzter", "vorgesetzte", "kollege", "kollegin", "teamleiter", "teamleiterin",
        "onkel", "tante", "cousin", "cousine", "opa", "oma", "schwager", "schwägerin", "nachbar", "nachbarin"
    )
    private val synonyms = mapOf("ehefrau" to "frau", "ehemann" to "mann", "papa" to "vater", "mama" to "mutter",
        "vorgesetzter" to "chef", "vorgesetzte" to "chef", "chefin" to "chef")

    private fun canon(r: String) = synonyms[r] ?: r

    /** Relation → Name aus den Fakten. */
    fun relations(facts: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val rel = relationWords.joinToString("|")
        val a = Regex("""^\s*([\p{L}][\p{L} .'-]{0,40}?)\s+ist\s+mein(?:e|en|er)?\s+($rel)\b""", RegexOption.IGNORE_CASE)
        val b = Regex("""mein(?:e|en|er)?\s+($rel)\s+(?:ist|heisst|heißt)\s+([\p{L}][\p{L} .'-]{0,40})""", RegexOption.IGNORE_CASE)
        val c = Regex("""^\s*([\p{L}][\p{L} .'-]{0,30}?)\s*=\s*.*?\b($rel)\b""", RegexOption.IGNORE_CASE)
        for (f in facts) {
            a.find(f)?.let { out[canon(it.groupValues[2].lowercase())] = clean(it.groupValues[1]) }
            b.find(f)?.let { out[canon(it.groupValues[1].lowercase())] = clean(it.groupValues[2]) }
            c.find(f)?.let { out.putIfAbsent(canon(it.groupValues[2].lowercase()), clean(it.groupValues[1])) }
        }
        return out
    }

    private fun clean(s: String) = s.trim().trim('.', ',', '«', '»', '"').replace(Regex("\\s+(im|in|als|aus)\\s.*$"), "").trim()

    /** Findet in einem Text («Ruf meine Frau an») die gemeinte Person. */
    fun resolve(text: String, facts: List<String>): String? {
        val t = text.lowercase()
        val rels = relations(facts)
        for ((r, name) in rels) {
            val forms = listOf(r) + synonyms.filterValues { it == r }.keys
            if (forms.any { f -> Regex("""\bmein(?:e|er|en|em)?\s+$f\b""").containsMatchIn(t) }) return name
        }
        // Spitznamen-Fakten «Baba = …»
        for (f in facts) {
            val m = Regex("""^\s*([\p{L}]{2,20})\s*=\s*(.+)$""").find(f) ?: continue
            if (Regex("""\b${Regex.escape(m.groupValues[1].lowercase())}\b""").containsMatchIn(t)) {
                return Regex("""«([^»]+)»""").find(m.groupValues[2])?.groupValues?.get(1) ?: m.groupValues[1]
            }
        }
        return null
    }

    /** Ist das Argument nur eine Beziehung oder leer? */
    fun isRelational(arg: String) =
        arg.isBlank() || relationWords.any { Regex("""\b(mein\w*\s+)?$it\b""", RegexOption.IGNORE_CASE).matches(arg.trim()) }

    /** Kurze Liste für den Prompt: «meine frau = gruya». */
    fun promptLines(facts: List<String>): String =
        relations(facts).entries.joinToString("\n") { "- mein(e) ${it.key} = ${it.value}" }
}
