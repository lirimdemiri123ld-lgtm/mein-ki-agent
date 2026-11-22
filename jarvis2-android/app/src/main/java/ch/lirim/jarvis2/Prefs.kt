package ch.lirim.jarvis2

import android.content.Context
import java.security.SecureRandom

object Prefs {
    const val PORT = 8765

    private fun p(c: Context) = c.getSharedPreferences("jarvis2", Context.MODE_PRIVATE)

    /** Geheimer Schlüssel im Connector-Link. Wer ihn nicht kennt, bekommt nur «404». */
    fun token(c: Context): String {
        val t = p(c).getString("token", null)
        if (!t.isNullOrBlank()) return t
        return newToken(c)
    }

    fun newToken(c: Context): String {
        val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        val t = (1..32).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        p(c).edit().putString("token", t).apply()
        return t
    }

    fun secretPath(c: Context) = "/mcp/" + token(c)

    fun tunnelUrl(c: Context): String = p(c).getString("tunnel", "") ?: ""
    fun setTunnelUrl(c: Context, v: String) = p(c).edit().putString("tunnel", v.trim().trimEnd('/')).apply()

    /** Pausiert: Jarvis nimmt keine Aufträge von Claude an. */
    fun paused(c: Context) = p(c).getBoolean("paused", false)
    fun setPaused(c: Context, v: Boolean) = p(c).edit().putBoolean("paused", v).apply()

    fun connectorUrl(c: Context): String {
        val base = tunnelUrl(c)
        return if (base.isEmpty()) "" else base + secretPath(c)
    }
}
