package ch.lirim.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : RuntimeException(message)

/** Ruft die Claude-API (Anthropic) auf. */
object ClaudeClient {
    private const val URL_MESSAGES = "https://api.anthropic.com/v1/messages"

    fun send(
        apiKey: String,
        model: String,
        system: String,
        tools: JSONArray,
        messages: JSONArray
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("system", system)
            .put("tools", tools)
            .put("messages", messages)

        val conn = URL(URL_MESSAGES).openConnection() as HttpURLConnection
        try {
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
        } finally {
            conn.disconnect()
        }
    }
}
