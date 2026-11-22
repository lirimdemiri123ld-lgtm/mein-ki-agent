package ch.lirim.jarvis.core

/**
 * Kleine Modelle schreiben Werkzeugaufrufe oft als Text statt als echten Aufruf, z. B.
 *   bestaetigen{frage:<|"|>Soll ich …?<|"|>}
 *   merken:kategorie=person,text=Shukrije ist meine Frau
 *   bestaetigen:frage:Ruf meine Frau an
 *   app_oeffnen(name="WhatsApp")
 *   fertig: Hallo Lirim …
 * Dieser Parser erkennt solche Fälle. `params` = Werkzeugname → Parameternamen (erster = Hauptparameter).
 */
object ToolTextParser {
    private const val Q = '\u0001'
    private val braceRe = Regex("""^\s*`*\s*([a-z_]+)\s*[\{\(](.*)[\}\)]\s*`*\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val colonRe = Regex("""^\s*`*\s*([a-z_]+)\s*:\s*(.*?)\s*`*\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val fertigRe = Regex("""^\s*(fertig|antwort)\s*[:\-–]?\s+(.+)$""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    data class Call(val name: String, val args: Map<String, Any?>)

    fun parse(text: String, params: Map<String, List<String>>): Call? {
        val t = text.replace("<|\"|>", Q.toString()).trim()
        if (t.isEmpty()) return null
        val first = t.lineSequence().first().trim()

        braceRe.find(t)?.let { m ->
            val name = m.groupValues[1]
            if (name in params) return Call(name, normalize(name, parseArgs(m.groupValues[2], params[name]!!), params))
        }
        colonRe.find(first)?.let { m ->
            val name = m.groupValues[1]
            if (name in params) {
                val rest = (m.groupValues[2] + t.removePrefix(first)).trim()
                return Call(name, normalize(name, parseLoose(rest, params[name]!!), params))
            }
        }
        if (first.lowercase() in params) return Call(first.lowercase(), emptyMap())
        fertigRe.find(t)?.let { m -> return Call("fertig", mapOf("nachricht" to cleanup(m.groupValues[2]))) }
        return null
    }

    /** Entfernt Steuerzeichen aus einer Textantwort. */
    fun cleanup(text: String): String =
        text.replace("<|\"|>", "").replace(Q.toString(), "").replace(Regex("<\\|[^|]*\\|>"), "").trim()

    private fun normalize(name: String, args: Map<String, Any?>, params: Map<String, List<String>>): Map<String, Any?> {
        val known = params[name] ?: return args
        // Unbekannte Schlüssel auf den Hauptparameter legen
        if (args.isNotEmpty() && args.keys.none { it in known } && known.isNotEmpty()) {
            return mapOf(known[0] to args.values.joinToString(" ") { it.toString() })
        }
        return args.mapValues { (_, v) -> if (v is String) cleanup(v) else v }
    }

    /** Formate wie «k=v, k2=v2», «k:v» oder einfach «Wert». */
    private fun parseLoose(rest: String, known: List<String>): Map<String, Any?> {
        val r = rest.trim().trim('`')
        if (r.isEmpty()) return emptyMap()
        val keyAlt = if (known.isEmpty()) "[a-z_]+" else known.joinToString("|") { Regex.escape(it) }
        val kv = Regex("""(?:^|,\s*)($keyAlt)\s*[=:]\s*""")
        val hits = kv.findAll(r).toList()
        if (hits.isEmpty() || hits.first().range.first != 0) {
            return if (known.isNotEmpty()) mapOf(known[0] to value(r)) else emptyMap()
        }
        val out = LinkedHashMap<String, Any?>()
        hits.forEachIndexed { i, h ->
            val end = if (i + 1 < hits.size) hits[i + 1].range.first else r.length
            out[h.groupValues[1]] = value(r.substring(h.range.last + 1, end).trim().trim(',').trim())
        }
        return out
    }

    private fun value(raw: String): Any? {
        val s = raw.trim().trim(Q, '"', '\'')
        return s.toLongOrNull() ?: s.toDoubleOrNull()?.takeIf { raw.trim().first().isDigit() } ?: when (s.lowercase()) {
            "true", "ja" -> true; "false", "nein" -> false; "null" -> null; else -> s
        }
    }

    private fun parseArgs(body: String, known: List<String>): Map<String, Any?> {
        if (!body.contains(':') && !body.contains('=')) return if (known.isNotEmpty() && body.isNotBlank()) mapOf(known[0] to value(body)) else emptyMap()
        val out = LinkedHashMap<String, Any?>()
        var i = 0
        val n = body.length
        while (i < n) {
            while (i < n && (body[i] == ',' || body[i].isWhitespace())) i++
            if (i >= n) break
            val ks = i
            while (i < n && body[i] != ':' && body[i] != '=') i++
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
                    v = value(body.substring(i, e))
                    i = e + 1
                }
            }
            if (key.isNotEmpty()) out[key] = v
        }
        return out
    }
}
