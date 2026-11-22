package ch.lirim.jarvis.core

/** Ein Telefonbuch-Eintrag (eine Nummer). */
data class Contact(val name: String, val number: String, val kind: String = "", val email: String = "")

object PhoneNumbers {
    fun digits(n: String): String = n.filter { it.isDigit() || it == '+' }

    /** Schweizer Nummer? (+41, 0041 oder 0…) */
    fun isSwiss(n: String): Boolean {
        val d = digits(n)
        return d.startsWith("+41") || d.startsWith("0041") || (d.startsWith("0") && !d.startsWith("00"))
    }

    /** Internationale Form ohne «+» (für WhatsApp): 079 123 45 67 → 41791234567. */
    fun intl(n: String, countryCode: String = "41"): String {
        val d = digits(n)
        return when {
            d.startsWith("+") -> d.drop(1)
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") -> countryCode + d.drop(1)
            else -> d
        }.filter { it.isDigit() }
    }

    /** Gleiche Nummer? (Vergleich der letzten 9 Ziffern) */
    fun same(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }.takeLast(9)
        val y = b.filter { it.isDigit() }.takeLast(9)
        return x.isNotEmpty() && x == y
    }

    fun looksLikeNumber(s: String): Boolean {
        val t = s.trim()
        val d = t.count { it.isDigit() }
        return d >= 5 && t.all { it.isDigit() || it in "+ -/()" }
    }

    /** Fürs Vorlesen: letzte vier Ziffern einzeln. */
    fun spokenTail(n: String): String = n.filter { it.isDigit() }.takeLast(4).toList().joinToString(" ")

    /** Land für die Ansage, leer bei Schweizer Nummern. */
    fun region(n: String): String = if (isSwiss(n)) "" else "Ausland"
}

object ContactMatch {
    private fun fold(s: String) = s.lowercase()
        .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("é", "e").replace("è", "e")
        .replace("ë", "e").replace("ç", "c").replace("ß", "ss")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun score(query: String, name: String): Int {
        val q = fold(query)
        val n = fold(name)
        if (q.isEmpty() || n.isEmpty()) return 0
        val fuzzy = Fuzzy.score(q, n)
        val words = q.split(' ').filter { it.length >= 2 }
        val nWords = n.split(' ')
        val wordScore = when {
            words.isNotEmpty() && words.all { w -> nWords.any { it == w } } -> 88
            words.isNotEmpty() && words.all { w -> nWords.any { it.startsWith(w) } } -> 75
            words.size > 1 && words.all { w -> nWords.any { Fuzzy.score(w, it) >= 65 } } -> 68
            else -> 0
        }
        return maxOf(fuzzy, wordScore)
    }

    /**
     * Passende Kontakte, beste zuerst. Schweizer Nummern werden bei Gleichstand bevorzugt.
     * Nur Treffer in der Nähe des besten Treffers bleiben drin.
     */
    fun candidates(query: String, all: List<Contact>, limit: Int = 4): List<Contact> {
        val scored = all.map { it to score(query, it.name) }.filter { it.second >= 60 }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second }
        return scored.filter { it.second >= best - 22 }
            .sortedWith(compareByDescending<Pair<Contact, Int>> { it.second / 10 }
                .thenByDescending { PhoneNumbers.isSwiss(it.first.number) }
                .thenByDescending { it.second })
            .map { it.first }
            .distinctBy { it.number.filter(Char::isDigit).takeLast(9) }
            .take(limit)
    }
}
