package ch.lirim.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Das "Gehirn": Schleife aus Claude-Antwort → Werkzeug ausführen → Ergebnis zurück. */
object Agent {
    private const val MAX_STEPS = 40
    private const val CONVERSATION_TIMEOUT_MS = 10 * 60 * 1000L

    @Volatile var running = false
        private set
    @Volatile private var stopRequested = false

    private var messages = JSONArray()
    private var pendingResults: JSONArray? = null
    private var lastActivity = 0L

    var listener: ((String) -> Unit)? = null
    private val logBuf = StringBuilder()
    private val main = Handler(Looper.getMainLooper())

    fun log(s: String) {
        synchronized(logBuf) {
            logBuf.append(s).append('\n')
            if (logBuf.length > 30000) logBuf.delete(0, 10000)
        }
        main.post { listener?.invoke(s) }
    }

    fun fullLog(): String = synchronized(logBuf) { logBuf.toString() }

    fun reset() {
        if (running) return
        messages = JSONArray()
        pendingResults = null
        log("— Neues Gespräch —")
    }

    fun stop() {
        stopRequested = true
        Speaker.stop()
    }

    fun start(ctx: Context, command: String) {
        if (running) {
            log("Ich arbeite noch – zuerst «Stopp» drücken.")
            return
        }
        val app = ctx.applicationContext
        if (System.currentTimeMillis() - lastActivity > CONVERSATION_TIMEOUT_MS) {
            messages = JSONArray()
            pendingResults = null
        }
        val content = JSONArray()
        pendingResults?.let { pr -> for (i in 0 until pr.length()) content.put(pr.get(i)) }
        pendingResults = null
        content.put(JSONObject().put("type", "text").put("text", command))
        messages.put(JSONObject().put("role", "user").put("content", content))

        log("Du: $command")
        running = true
        stopRequested = false
        Thread {
            try {
                loop(app)
            } catch (e: Exception) {
                log("⚠️ ${e.message}")
                Speaker.say("Es gab einen Fehler.")
                messages = JSONArray()
                pendingResults = null
            } finally {
                running = false
                lastActivity = System.currentTimeMillis()
                JarvisService.instance?.overlay?.hideStatus()
            }
        }.start()
    }

    private fun say(text: String) {
        if (text.isBlank()) return
        log("Jarvis: $text")
        Speaker.say(text)
    }

    private fun status(text: String) {
        JarvisService.instance?.overlay?.setStatus(text)
    }

    private fun loop(ctx: Context) {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) {
            say("Bitte trag zuerst deinen API-Schlüssel in den Einstellungen ein.")
            messages = JSONArray()
            return
        }
        var webSearch = Prefs.webSearch(ctx)

        for (step in 1..MAX_STEPS) {
            if (stopRequested) {
                log("⏹ Gestoppt.")
                messages = JSONArray()
                return
            }
            status("denke nach … (Schritt $step)")
            prune()

            val resp = try {
                ClaudeClient.send(key, Prefs.model(ctx), systemPrompt(), Tools.definitions(webSearch), messages)
            } catch (e: ApiException) {
                if (webSearch && e.message?.contains("web_search", ignoreCase = true) == true) {
                    log("Websuche nicht verfügbar – ohne weiter.")
                    webSearch = false
                    ClaudeClient.send(key, Prefs.model(ctx), systemPrompt(), Tools.definitions(false), messages)
                } else throw e
            }

            val content = resp.getJSONArray("content")
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            val results = JSONArray()
            val texts = StringBuilder()
            var endMessage: String? = null

            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> {
                        val t = b.optString("text").trim()
                        if (t.isNotEmpty()) texts.append(t).append(' ')
                    }
                    "server_tool_use" -> log("🔎 Websuche …")
                    "tool_use" -> {
                        val id = b.getString("id")
                        val name = b.getString("name")
                        val input = b.optJSONObject("input") ?: JSONObject()
                        when (name) {
                            "fertig" -> {
                                endMessage = input.optString("nachricht")
                                results.put(toolResult(id, ToolOutput("ok")))
                            }
                            "frage_stellen" -> {
                                endMessage = input.optString("frage")
                                results.put(toolResult(id, ToolOutput("Frage gestellt. Die Antwort folgt im nächsten Text.")))
                            }
                            else -> {
                                if (stopRequested) {
                                    results.put(toolResult(id, ToolOutput("Abgebrochen.")))
                                } else {
                                    log("→ $name ${short(input)}")
                                    status(describe(name, input))
                                    results.put(toolResult(id, Tools.execute(ctx, name, input)))
                                }
                            }
                        }
                    }
                }
            }

            val end = endMessage
            if (end != null) {
                pendingResults = results
                say(end)
                return
            }
            if (results.length() == 0) {
                // Nur Text, kein Werkzeug → fertig
                say(texts.toString().trim())
                return
            }
            if (texts.isNotEmpty()) log("Jarvis denkt: ${texts.toString().trim().take(200)}")
            messages.put(JSONObject().put("role", "user").put("content", results))
        }
        say("Ich habe die maximale Anzahl Schritte erreicht und höre hier auf.")
        messages = JSONArray()
    }

    private fun toolResult(id: String, out: ToolOutput): JSONObject {
        val r = JSONObject().put("type", "tool_result").put("tool_use_id", id)
        if (out.imageB64 != null) {
            val arr = JSONArray()
            arr.put(
                JSONObject().put("type", "image").put(
                    "source", JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/jpeg")
                        .put("data", out.imageB64)
                )
            )
            arr.put(JSONObject().put("type", "text").put("text", out.text))
            r.put("content", arr)
        } else {
            r.put("content", out.text)
        }
        return r
    }

    /** Alte Bildschirm-Inhalte kürzen, damit es schnell und günstig bleibt. */
    private fun prune() {
        // Denk-Blöcke entfernen: sie sind an den exakten Verlauf gebunden,
        // der durch das Kürzen unten verändert wird (sonst API-Fehler 400).
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") != "assistant") continue
            val c = m.opt("content") as? JSONArray ?: continue
            val cleaned = JSONArray()
            for (j in 0 until c.length()) {
                val b = c.optJSONObject(j)
                val t = b?.optString("type")
                if (t == "thinking" || t == "redacted_thinking") continue
                cleaned.put(c.get(j))
            }
            if (cleaned.length() == 0) cleaned.put(JSONObject().put("type", "text").put("text", "…"))
            m.put("content", cleaned)
        }

        val keepFrom = messages.length() - 2
        for (i in 0 until keepFrom) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") != "user") continue
            val c = m.opt("content") as? JSONArray ?: continue
            for (j in 0 until c.length()) {
                val b = c.optJSONObject(j) ?: continue
                if (b.optString("type") != "tool_result") continue
                when (val rc = b.opt("content")) {
                    is JSONArray -> b.put("content", "(altes Bild entfernt)")
                    is String -> if (rc.length > 400) b.put("content", rc.take(160) + " … (alter Bildschirm gekürzt)")
                }
            }
        }
    }

    private fun short(o: JSONObject): String {
        val s = o.toString()
        return if (s.length > 120) s.take(120) + "…" else s
    }

    private fun describe(name: String, input: JSONObject): String = when (name) {
        "app_oeffnen" -> "öffne ${input.optString("name")}"
        "tippen", "tippen_xy" -> "tippe …"
        "text_eingeben" -> "schreibe …"
        "scrollen" -> "scrolle …"
        "screenshot" -> "schaue mir den Bildschirm an …"
        "bildschirm_lesen" -> "lese den Bildschirm …"
        "bestaetigen" -> "warte auf deine Bestätigung"
        else -> "$name …"
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN).format(Date())
        return """
Du bist Jarvis, der persönliche Sprachassistent von Lirim auf seinem Samsung Galaxy S25 (Android). Du bedienst das Handy wie ein Mensch: Apps öffnen, Bildschirm lesen, tippen, Text eingeben, scrollen, wischen.

Regeln:
- Antworte immer auf Deutsch (Schweizer Rechtschreibung, «ss» statt «ß»), kurz und natürlich. Deine Antworten werden vorgelesen – keine Aufzählungen, kein Markdown.
- Arbeite Schritt für Schritt. Nach jeder Aktion bekommst du den neuen Bildschirm als Liste mit Nummern [id]. Benutze diese Nummern für tippen, text_eingeben und lange_druecken. Die Nummern gelten nur für den zuletzt gelesenen Bildschirm.
- Ist die Liste leer oder unklar (Bilder, Karten, Spiele, Web-Ansichten), mach einen screenshot und nutze tippen_xy.
- Öffne Apps direkt mit app_oeffnen statt über den Startbildschirm.
- Vor allem Unumkehrbaren – Nachricht/E-Mail senden, kaufen, bezahlen, buchen, bestellen, löschen, posten, anrufen, Sicherheitseinstellungen ändern – rufst du IMMER zuerst bestaetigen auf und machst nur bei JA weiter.
- Gib niemals Passwörter, PINs, Codes oder Kartendaten ein und lies sie nicht vor. Bei Anmeldungen bittest du Lirim, das selbst zu machen.
- Manche Apps (Banking, TWINT, Bezahl-Apps) blockieren die Bedienung. Wenn etwas nicht geht, sag es ehrlich, statt zu raten.
- Bei reinen Wissensfragen antworte direkt (bei Bedarf mit web_search) über fertig.
- Fehlen dir Infos, nutze frage_stellen.
- Bedien nicht die Jarvis-App selbst (ch.lirim.jarvis).
- Beende jeden Auftrag mit fertig und einer Zusammenfassung in 1–2 Sätzen.

Jetzt ist: $now.
""".trimIndent()
    }
}
