package ch.lirim.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : RuntimeException(message)

/** Ruft die Claude-API (Anthropic) auf – sparsam: Prompt-Cache, bei Haiku ohne Nachdenk-Phase. */
object ClaudeClient {
    private const val URL_MESSAGES = "https://api.anthropic.com/v1/messages"

    /** true, falls die API die Spar-Parameter einmal abgelehnt hat (dann ohne weiter). */
    @Volatile private var plainMode = false

    fun send(
        apiKey: String,
        model: String,
        system: String,
        tools: JSONArray,
        messages: JSONArray,
        systemDynamic: String = "",
        maxTokens: Int = 1024
    ): JSONObject {
        // Prompt-Caching: Werkzeuge + feste Anweisungen + Gedächtnis werden nur 1× voll verrechnet,
        // danach zu ~10 % des Preises gelesen.
        val eph = JSONObject().put("type", "ephemeral")
        val sys = JSONArray().put(JSONObject().put("type", "text").put("text", system).put("cache_control", eph))
        if (systemDynamic.isNotBlank()) sys.put(JSONObject().put("type", "text").put("text", systemDynamic).put("cache_control", eph))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", sys)
            .put("messages", withCacheMark(messages, eph))
        if (tools.length() > 0) {
            val t = JSONArray(tools.toString())
            // Markierung auf das letzte eigene Werkzeug (nicht auf die Websuche)
            for (i in t.length() - 1 downTo 0) {
                val o = t.getJSONObject(i)
                if (!o.has("type")) { o.put("cache_control", eph); break }
            }
            body.put("tools", t)
        }
        // Haiku 5.5 denkt standardmässig vor jeder Antwort nach – für einfache Bedienschritte unnötig.
        // Abschalten macht jeden Schritt schneller und spart Ausgabe-Tokens.
        val saver = model.contains("haiku") && !plainMode
        if (saver) {
            body.put("thinking", JSONObject().put("type", "disabled"))
            body.put("output_config", JSONObject().put("effort", "low"))
        }
        return try {
            post(apiKey, body)
        } catch (e: ApiException) {
            if (saver && e.code == 400 && Regex("thinking|output_config|effort").containsMatchIn(e.message.orEmpty())) {
                plainMode = true
                body.remove("thinking"); body.remove("output_config")
                post(apiKey, body)
            } else throw e
        }
    }

    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val conn = URL(URL_MESSAGES).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        // Antwort ganz lesen und Verbindung offen lassen: der nächste Schritt spart den Verbindungsaufbau
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).getJSONObject("error").optString("message", text)
            } catch (e: Exception) {
                text
            }
            throw ApiException(code, "API-Fehler $code: ${msg.take(300)}")
        }
        return JSONObject(text)
    }

    /** Letzten Block markieren → der ganze bisherige Verlauf wird beim nächsten Schritt günstig aus dem Cache gelesen. */
    private fun withCacheMark(messages: JSONArray, eph: JSONObject): JSONArray {
        if (messages.length() == 0) return messages
        val copy = JSONArray(messages.toString())
        val last = copy.getJSONObject(copy.length() - 1)
        when (val c = last.opt("content")) {
            is String -> last.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", c).put("cache_control", eph)))
            is JSONArray -> if (c.length() > 0) c.getJSONObject(c.length() - 1).put("cache_control", eph)
        }
        return copy
    }

    /**
     * Kosten einer Antwort in US-Dollar (offizielle Preise, geprüft am 22.11.2026, pro Million Tokens):
     * Haiku 5.5: 0.10 / 0.50 / Cache-Schreiben 0.125 / Cache-Lesen 0.01 (über 100 000 Prompt-Tokens ×5).
     * Sonnet 5.5: 2 / 10 / 2.50 / 0.10. Websuche: 0.01 $ pro Suche.
     */
    fun costUsd(model: String, resp: JSONObject): Double {
        val u = resp.optJSONObject("usage") ?: return 0.0
        val inp = u.optLong("input_tokens"); val out = u.optLong("output_tokens")
        val cw = u.optLong("cache_creation_input_tokens"); val cr = u.optLong("cache_read_input_tokens")
        val haiku = model.contains("haiku")
        val p = if (haiku) doubleArrayOf(0.10, 0.50, 0.125, 0.01) else doubleArrayOf(2.0, 10.0, 2.5, 0.10)
        val factor = if (haiku && inp + cw + cr > 100_000) 5.0 else 1.0
        val searches = u.optJSONObject("server_tool_use")?.optInt("web_search_requests") ?: 0
        return (inp * p[0] + out * p[1] + cw * p[2] + cr * p[3]) * factor / 1_000_000.0 + searches * 0.01
    }
}
