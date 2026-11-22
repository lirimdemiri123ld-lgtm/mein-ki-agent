package ch.lirim.jarvis2

/**
 * Kleiner JSON-Leser und -Schreiber ohne Abhängigkeiten.
 * Läuft identisch auf dem Handy und im Test auf dem PC (kein org.json nötig).
 *
 * Objekte sind LinkedHashMap (Reihenfolge bleibt stabil – wichtig für den Prompt-Cache),
 * Listen sind ArrayList, Zahlen Long oder Double.
 */
object Json {
    class ParseError(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.pos < text.length) throw ParseError("Unerwartetes Zeichen an Stelle ${p.pos}")
        return v
    }

    /** Liest ein Objekt; bei Fehlern oder anderem Typ kommt ein leeres Objekt zurück. */
    @Suppress("UNCHECKED_CAST")
    fun obj(text: String?): MutableMap<String, Any?> = try {
        (parse(text ?: "") as? MutableMap<String, Any?>) ?: linkedMapOf()
    } catch (e: RuntimeException) {
        linkedMapOf()
    }

    @Suppress("UNCHECKED_CAST")
    fun arr(text: String?): MutableList<Any?> = try {
        (parse(text ?: "") as? MutableList<Any?>) ?: mutableListOf()
    } catch (e: RuntimeException) {
        mutableListOf()
    }

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> writeDouble(v.toDouble(), sb)
            is Double -> writeDouble(v, sb)
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(x, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(x, sb)
                }
                sb.append(']')
            }
            is Array<*> -> write(v.toList(), sb)
            else -> quote(v.toString(), sb)
        }
    }

    private fun writeDouble(d: Double, sb: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toString())
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun value(): Any? {
            if (pos >= s.length) throw ParseError("Unerwartetes Ende")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw ParseError("Unerwartetes Zeichen '$c' an Stelle $pos")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseError("Ungültiger Wert an Stelle $pos")
            pos += word.length
            return v
        }

        private fun obj(): MutableMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw ParseError("Schlüssel erwartet an Stelle $pos")
                val k = string()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw ParseError("':' erwartet an Stelle $pos")
                pos++
                skipWs()
                m[k] = value()
                skipWs()
                if (pos >= s.length) throw ParseError("Unerwartetes Ende im Objekt")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw ParseError("',' oder '}' erwartet an Stelle $pos")
                }
            }
        }

        private fun arr(): MutableList<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                if (pos >= s.length) throw ParseError("Unerwartetes Ende in der Liste")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> throw ParseError("',' oder ']' erwartet an Stelle $pos")
                }
            }
        }

        private fun string(): String {
            val sb = StringBuilder()
            pos++
            while (true) {
                if (pos >= s.length) throw ParseError("Zeichenkette nicht abgeschlossen")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw ParseError("Zeichenkette nicht abgeschlossen")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseError("Ungültiges \\u an Stelle $pos")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: throw ParseError("Ungültiges \\u an Stelle $pos")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseError("Ungültiges Escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') { isDouble = true; pos++; while (pos < s.length && s[pos] in '0'..'9') pos++ }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val t = s.substring(start, pos)
            if (!isDouble) t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull() ?: throw ParseError("Ungültige Zahl '$t'")
        }
    }
}

typealias JMap = Map<String, Any?>

/** Objekt bauen: jobj("a" to 1, "b" to "x") */
fun jobj(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    for ((k, v) in pairs) m[k] = v
    return m
}

fun jarr(vararg items: Any?): MutableList<Any?> = items.toMutableList()

fun JMap.str(key: String, def: String = ""): String = when (val v = this[key]) {
    null -> def
    is String -> v
    is Map<*, *>, is List<*> -> Json.write(v)
    else -> v.toString()
}

fun JMap.int(key: String, def: Int = 0): Int = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt() ?: def
    else -> def
}

fun JMap.long(key: String, def: Long = 0L): Long = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull() ?: def
    else -> def
}

fun JMap.dbl(key: String, def: Double = 0.0): Double = when (val v = this[key]) {
    is Number -> v.toDouble()
    is String -> v.trim().replace(',', '.').toDoubleOrNull() ?: def
    else -> def
}

fun JMap.bool(key: String, def: Boolean = false): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is String -> when (v.trim().lowercase()) { "true", "ja", "1" -> true; "false", "nein", "0" -> false; else -> def }
    is Number -> v.toInt() != 0
    else -> def
}

fun JMap.has(key: String): Boolean = this[key] != null

@Suppress("UNCHECKED_CAST")
fun JMap.obj(key: String): JMap? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun JMap.list(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun JMap.objs(key: String): List<JMap> = list(key).filterIsInstance<Map<*, *>>() as List<JMap>

fun JMap.strs(key: String): List<String> = list(key).filterIsInstance<String>()
