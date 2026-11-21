package ch.lirim.jarvis

import android.content.Context

object Prefs {
    const val DEFAULT_MODEL = "claude-sonnet-5-5"

    private fun p(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = p(c).getString("api_key", "") ?: ""
    fun setApiKey(c: Context, v: String) = p(c).edit().putString("api_key", v.trim()).apply()

    fun model(c: Context): String {
        val m = p(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        return m.ifBlank { DEFAULT_MODEL }
    }
    fun setModel(c: Context, v: String) = p(c).edit().putString("model", v.trim()).apply()

    fun autoListen(c: Context): Boolean = p(c).getBoolean("auto_listen", true)
    fun setAutoListen(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_listen", v).apply()

    fun bubble(c: Context): Boolean = p(c).getBoolean("bubble", true)
    fun setBubble(c: Context, v: Boolean) = p(c).edit().putBoolean("bubble", v).apply()

    fun webSearch(c: Context): Boolean = p(c).getBoolean("web_search", true)
    fun setWebSearch(c: Context, v: Boolean) = p(c).edit().putBoolean("web_search", v).apply()
}
