package ch.lirim.jarvis2

/**
 * Apps, die Claude über Jarvis 2.0 NIE bedienen darf: Banking, Bezahlen, Passwörter, Jarvis selbst.
 * Die Prüfung erfolgt vor jeder Bildschirm-Aktion anhand der App im Vordergrund.
 */
object Safety {
    private val blockedParts = listOf(
        "bank", "twint", "postfinance", "ubs", "raiffeisen", "revolut", "yuh", "neon", "zkb", "blkb", "bkb",
        "lukb", "bcge", "bcv", "migrosbank", "credit", "paypal", "wallet", "pay.", ".pay", "wise", "swissquote",
        "viac", "frankly", "kraken", "binance", "coinbase", "password", "1password", "bitwarden", "keepass",
        "authenticator", "ch.admin", "swissid", "spay",
    )
    private val blockedExact = setOf(
        "ch.lirim.jarvis2",                   // Jarvis 2.0 selbst (Einstellungen/Schlüssel)
        "com.android.settings",               // Android-Einstellungen (Bedienungshilfe, Berechtigungen)
        "com.samsung.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.authfw",
    )

    fun blocked(pkg: String?): Boolean {
        val p = (pkg ?: "").lowercase()
        if (p.isEmpty()) return false
        if (p in blockedExact) return true
        return blockedParts.any { p.contains(it) }
    }

    fun reason(pkg: String) =
        "Gesperrt: Die App «$pkg» darf Jarvis 2.0 aus Sicherheitsgründen nicht bedienen (Banking, Bezahlen, Passwörter oder Systemeinstellungen). " +
            "Sag Lirim, dass er das selbst erledigen muss."
}
