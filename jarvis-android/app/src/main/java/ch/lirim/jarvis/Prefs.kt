package ch.lirim.jarvis

import android.content.Context

object Prefs {
    const val DEFAULT_MODEL = "claude-haiku-5-5"

    private fun p(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = p(c).getString("api_key", "") ?: ""
    fun setApiKey(c: Context, v: String) = p(c).edit().putString("api_key", v.trim()).apply()

    /** Spar-Umstellung (einmalig): Sonnet → Haiku, Websuche aus, Hybrid-Modus. */
    fun migrate(c: Context) {
        if (p(c).getBoolean("spar_v1", false)) return
        val e = p(c).edit().putBoolean("spar_v1", true)
        if ((p(c).getString("model", "") ?: "").let { it.isBlank() || it.contains("sonnet") }) e.putString("model", DEFAULT_MODEL)
        e.putBoolean("web_search", false).putString("brain", "hybrid").apply()
    }

    fun model(c: Context): String {
        val m = p(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        return m.ifBlank { DEFAULT_MODEL }
    }
    fun setModel(c: Context, v: String) = p(c).edit().putString("model", v.trim()).apply()

    fun autoListen(c: Context): Boolean = p(c).getBoolean("auto_listen", true)
    fun setAutoListen(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_listen", v).apply()

    fun bubble(c: Context): Boolean = p(c).getBoolean("bubble", true)
    fun setBubble(c: Context, v: Boolean) = p(c).edit().putBoolean("bubble", v).apply()

    fun wakeWord(c: Context): Boolean = p(c).getBoolean("wake_word", false)
    fun setWakeWord(c: Context, v: Boolean) = p(c).edit().putBoolean("wake_word", v).apply()

    fun voiceName(c: Context): String = p(c).getString("voice", "") ?: ""
    fun pitch(c: Context): Float = p(c).getFloat("pitch", 1.0f)
    fun rate(c: Context): Float = p(c).getFloat("rate", 1.0f)
    fun voiceStyle(c: Context): Int = p(c).getInt("voice_style", 0)
    fun setVoice(c: Context, name: String, pitch: Float, rate: Float, style: Int) =
        p(c).edit().putString("voice", name).putFloat("pitch", pitch).putFloat("rate", rate)
            .putInt("voice_style", style).apply()

    fun city(c: Context): String = (p(c).getString("city", "Pratteln") ?: "Pratteln").ifBlank { "Pratteln" }
    fun setCity(c: Context, v: String) = p(c).edit().putString("city", v.trim()).apply()

    fun watchlist(c: Context): List<String> =
        (p(c).getString("watchlist", "^SSMI,^GSPC,NESN.SW,BTC-USD") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.take(8)
    fun setWatchlist(c: Context, v: String) = p(c).edit().putString("watchlist", v).apply()

    fun neuralVoice(c: Context): String = p(c).getString("neural_voice", "") ?: ""
    fun setNeuralVoice(c: Context, id: String) = p(c).edit().putString("neural_voice", id).apply()

    fun proactive(c: Context): Boolean = p(c).getBoolean("proactive", true)
    fun setProactive(c: Context, v: Boolean) = p(c).edit().putBoolean("proactive", v).apply()

    /** "hybrid" (Standard), "local" oder "cloud". */
    fun brain(c: Context): String = p(c).getString("brain", "hybrid") ?: "hybrid"
    fun setBrain(c: Context, v: String) = p(c).edit().putString("brain", v).apply()

    fun localModel(c: Context): String = p(c).getString("local_model", "gemma4-e2b") ?: "gemma4-e2b"
    fun setLocalModel(c: Context, v: String) = p(c).edit().putString("local_model", v).apply()

    fun download(c: Context, modelId: String): Long = p(c).getLong("dl_$modelId", -1L)
    fun setDownload(c: Context, modelId: String, id: Long) = p(c).edit().putLong("dl_$modelId", id).apply()

    fun cloudBlockedUntil(c: Context): Long = p(c).getLong("cloud_blocked", 0L)
    fun setCloudBlockedUntil(c: Context, t: Long) = p(c).edit().putLong("cloud_blocked", t).apply()

    fun lastInsightTry(c: Context): Long = p(c).getLong("insight_try", 0L)
    fun setLastInsightTry(c: Context, t: Long) = p(c).edit().putLong("insight_try", t).apply()

    fun askedContacts(c: Context) = p(c).getBoolean("asked_contacts", false)
    fun setAskedContacts(c: Context) = p(c).edit().putBoolean("asked_contacts", true).apply()

    fun autoModel(c: Context): Boolean = p(c).getBoolean("auto_model", true)
    fun setAutoModel(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_model", v).apply()

    /** Klangfilter: "none", "jarvis", "kitt" */
    fun voiceFx(c: Context): String = p(c).getString("voice_fx", "none") ?: "none"
    fun setVoiceFx(c: Context, v: String) = p(c).edit().putString("voice_fx", v).apply()

    fun webSearch(c: Context): Boolean = p(c).getBoolean("web_search", false)
    fun setWebSearch(c: Context, v: Boolean) = p(c).edit().putBoolean("web_search", v).apply()

    // ---- Kosten (Schätzung in US-Dollar) pro Monat
    private fun monthKey() = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
    fun monthCost(c: Context): Double =
        if (p(c).getString("cost_month", "") == monthKey()) p(c).getFloat("cost_usd", 0f).toDouble() else 0.0
    fun addCost(c: Context, usd: Double) {
        val cur = monthCost(c)
        val e = p(c).edit().putString("cost_month", monthKey()).putFloat("cost_usd", (cur + usd).toFloat())
        creditBalance(c)?.let { e.putFloat("credit", (it - usd).toFloat()) }
        e.apply()
    }

    /** Selbst eingetragenes Claude-Guthaben in $ (null = unbekannt). Wird bei jeder Aufgabe reduziert. */
    fun creditBalance(c: Context): Double? =
        if (p(c).contains("credit")) p(c).getFloat("credit", 0f).toDouble() else null
    fun setCreditBalance(c: Context, usd: Double) = p(c).edit().putFloat("credit", usd.toFloat()).apply()
    fun savedTasks(c: Context): Int =
        if (p(c).getString("saved_month", "") == monthKey()) p(c).getInt("saved_n", 0) else 0
    fun addSaved(c: Context) =
        p(c).edit().putString("saved_month", monthKey()).putInt("saved_n", savedTasks(c) + 1).apply()

    /** Gemerkte Kontaktwahl («gruja» → Name + Nummer). */
    private fun key(q: String) = "call_" + q.lowercase().replace(Regex("[^a-zäöü0-9]"), "")
    fun chosenNumber(c: Context, q: String): Pair<String, String>? =
        p(c).getString(key(q), null)?.split("|")?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun setChosenNumber(c: Context, q: String, name: String, number: String) =
        p(c).edit().putString(key(q), "$name|$number").apply()
}
