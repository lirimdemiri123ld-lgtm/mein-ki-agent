package ch.lirim.jarvis.core

/** Unscharfer Namensvergleich: «gruya» findet «Gruja», «schukrie» findet «Shukrije» usw. */
object Fuzzy {
    fun phon(s: String): String {
        var t = s.lowercase()
            .replace("ä", "e").replace("ö", "o").replace("ü", "u").replace("é", "e").replace("è", "e")
            .replace("ë", "e").replace("ç", "c").replace("ß", "ss")
        t = t.replace("sch", "s").replace("sh", "s").replace("ch", "k").replace("ph", "f")
            .replace("ck", "k").replace("qu", "kw").replace("th", "t").replace("dj", "j").replace("tsch", "c")
        t = t.map { ch ->
            when (ch) {
                'y', 'i' -> 'j'
                'w', 'v' -> 'v'
                'z' -> 's'
                'c' -> 'k'
                'd' -> 't'
                'b' -> 'p'
                'g' -> 'k'
                else -> ch
            }
        }.joinToString("")
        t = t.replace(Regex("[^a-z0-9 ]"), "")
        t = t.replace(Regex("(.)\\1+"), "$1")           // Doppelbuchstaben
        t = t.replace(Regex("(?<=[^aeoujh ])h"), "")      // stummes h
        return t.trim()
    }

    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    /** 0..100: wie gut passt die Anfrage zu einem Namen/Label. */
    fun score(query: String, label: String): Int {
        val q = query.lowercase().trim()
        val l = label.lowercase().trim()
        if (q.isEmpty() || l.isEmpty()) return 0
        if (l == q) return 100
        if (l.startsWith(q)) return 90
        if (l.split(Regex("\\s+")).any { it == q }) return 85
        if (l.contains(q)) return 70
        val pq = phon(q)
        val words = l.split(Regex("[\\s,.:;()\\-–]+")).filter { it.isNotBlank() }
        var best = 0
        for (w in words + l) {
            val pw = phon(w)
            if (pw.isEmpty()) continue
            if (pw == pq) best = maxOf(best, 80)
            else {
                val dist = distance(pq, pw)
                val allowed = if (pq.length >= 7) 2 else if (pq.length >= 4) 1 else 0
                if (dist <= allowed) best = maxOf(best, 65 - dist * 5)
            }
        }
        return best
    }
}
