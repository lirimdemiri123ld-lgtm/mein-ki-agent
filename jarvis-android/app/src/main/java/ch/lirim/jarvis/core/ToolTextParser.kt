package ch.lirim.jarvis.core

/**
 * Kleine Modelle schreiben Werkzeugaufrufe manchmal als Text, z. B.
 *   bestaetigen{frage:<|"|>Soll ich …?<|"|>}   oder   fertig: Hallo Lirim …
 * Dieser Parser erkennt solche Fälle.
 */
object ToolTextParser {
    private const val Q = '\u0001'
    private val callRe = Regex("""^\s*`*\s*([a-z_]+)\s*\{(.*)\}\s*`*\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val fertigRe = Regex("""^\s*(fertig|antwort)\s*[:\-–]?\s+(.+)$""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    data class Call(val name: String, val args: Map<String, Any?>)

    fun parse(text: String, known: Set<String>): Call? {
        val t = text.replace("<|\"|>", Q.toString()).trim()
        callRe.find(t)?.let { m ->
            val name = m.groupValues[1]
            if (name in known) return Call(name, parseArgs(m.groupValues[2]))
        }
        if (t.lowercase() in known) return Call(t.lowercase(), emptyMap())
        fertigRe.find(t)?.let { m -> return Call("fertig", mapOf("nachricht" to cleanup(m.groupValues[2]))) }
        return null
    }

    /** Entfernt Steuerzeichen aus einer Textantwort. */
    fun cleanup(text: String): String =
        text.replace("<|\"|>", "").replace(Q.toString(), "").replace(Regex("<\\|[^|]*\\|>"), "").trim()

    private fun parseArgs(body: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        var i = 0
        val n = body.length
        while (i < n) {
            while (i < n && (body[i] == ',' || body[i].isWhitespace())) i++
            if (i >= n) break
            val ks = i
            while (i < n && body[i] != ':') i++
            if (i >= n) break
            val key = body.substring(ks, i).trim().trim('"', '\'')
            i++
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n) { out[key] = ""; break }
            val v: Any?
            when (body[i]) {
                Q, '"' -> {
                    val end = body[i]
                    val e = body.indexOf(end, i + 1).let { if (it < 0) n else it }
                    v = body.substring(i + 1, e)
                    i = e + 1
                }
                else -> {
                    val e = body.indexOf(',', i).let { if (it < 0) n else it }
                    val raw = body.substring(i, e).trim()
                    v = raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: when (raw.lowercase()) {
                        "true" -> true; "false" -> false; "null" -> null; else -> raw
                    }
                    i = e + 1
                }
            }
            if (key.isNotEmpty()) out[key] = v
        }
        return out
    }
}
