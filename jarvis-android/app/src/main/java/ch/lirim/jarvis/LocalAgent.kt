package ch.lirim.jarvis

import android.content.Context
import ch.lirim.jarvis.core.ToolTextParser
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent mit dem lokalen Gehirn. Jeder Schritt wird kompakt neu aufgebaut
 * (Auftrag + bisherige Schritte + aktueller Bildschirm), damit das kleine Modell
 * nicht überläuft.
 */
object LocalAgent {
    enum class Result { DONE, ESCALATE, STOPPED, FAILED }

    private const val MAX_STEPS = 15
    private val recentChat = ArrayDeque<String>()

    @Volatile var lastError = ""
        private set

    /** Kompakte Werkzeugliste für das kleine Modell (assets/local_tools.json). */
    private fun toolSpecs(c: Context, hybrid: Boolean): List<String> {
        val arr = org.json.JSONArray(c.assets.open("local_tools.json").bufferedReader().use { it.readText() })
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).toString())
        if (hybrid) {
            out.add(JSONObject().put("name", "claude_fragen")
                .put("description", "Nur wenn die Aufgabe für dich zu schwierig ist (viele Schritte in komplexen Apps, Bildanalyse, aktuelle Infos aus dem Web). Übergibt an die stärkere Cloud-KI.")
                .put("parameters", JSONObject().put("type", "object").put("properties",
                    JSONObject().put("grund", JSONObject().put("type", "string").put("description", "kurzer Grund"))))
                .toString())
        }
        return out
    }

    private fun system(c: Context): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN).format(Date())
        return """
Du bist Jarvis, Lirims persönlicher KI-Assistent auf seinem Samsung Galaxy S25. Du läufst lokal auf dem Handy.
Antworte immer auf Deutsch (Schweiz, «ss» statt «ß»), kurz, natürlich, souverän, mit feinem Humor. Kein Markdown.
Du bedienst das Handy mit Werkzeugen: app_oeffnen, bildschirm_lesen, tippen (id aus der Bildschirmliste), text_eingeben, scrollen, taste, warten, wecker_stellen, nachrichten_lesen, live_daten.
Regeln:
- Ist keine Aktion nötig (Frage, Plaudern, Erklärung): antworte direkt mit normalem Text, ohne Werkzeug.
- Sonst rufe genau EIN Werkzeug auf. Apps öffnen, lesen, scrollen und Wecker stellen geht ohne Rückfrage.
- Nur vor Senden, Kaufen, Bezahlen, Löschen oder Anrufen zuerst bestaetigen.
- Ist eine Aktion erledigt, rufe das Werkzeug fertig auf (nachricht = was du getan hast).
- Das Hintergrundwissen unten nutzt du still. Erwähne es nur, wenn es direkt zur Frage passt.
- Nie Passwörter oder PINs. Merke dir Dauerhaftes über Lirim mit merken.
Jetzt: $now.
""".trimIndent()
    }

    fun run(c: Context, command: String, hybrid: Boolean, shouldStop: () -> Boolean, say: (String) -> Unit): Result {
        val specs = toolSpecs(c, hybrid)
        val memory = Memory.promptBlock(c).take(1500).trim()
        val sys = system(c) + if (memory.isNotEmpty()) "\n\nHintergrundwissen über Lirim:\n$memory" else ""
        val known = specs.mapNotNull { Regex("\"name\":\"([a-z_]+)\"").find(it)?.groupValues?.get(1) }.toSet()
        val steps = mutableListOf<String>()
        var screen = ""
        for (step in 1..MAX_STEPS) {
            if (shouldStop()) return Result.STOPPED
            JarvisService.instance?.overlay?.setStatus("denke lokal nach … (Schritt $step)")
            val prompt = buildString {
                if (recentChat.isNotEmpty()) append("Letzter Gesprächsverlauf:\n").append(recentChat.joinToString("\n")).append("\n\n")
                append("Auftrag von Lirim: ").append(command).append('\n')
                if (steps.isNotEmpty()) append("\nBisher erledigt:\n").append(steps.joinToString("\n")).append('\n')
                if (screen.isNotEmpty()) append("\nAktueller Bildschirm:\n").append(screen.take(2500)).append('\n')
                append("\nWas ist der nächste Schritt?")
            }
            val r = try {
                LocalBrain.step(c, sys, prompt, specs)
            } catch (e: Throwable) {
                lastError = (e.message ?: e.javaClass.simpleName).take(200)
                Agent.log("⚠️ Lokales Gehirn: $lastError")
                return Result.FAILED
            }
            var name = r.toolName
            var args = r.args
            if (name == null) {
                val parsed = ToolTextParser.parse(r.text, known)
                if (parsed != null) { name = parsed.name; args = parsed.args }
            }
            if (name == null) {
                val answer = ToolTextParser.cleanup(r.text).ifBlank { "Erledigt." }
                remember(command, answer)
                say(answer)
                return Result.DONE
            }
            val input = JSONObject(args.filterValues { it != null })
            Agent.log("→ [lokal] $name $input")
            when (name) {
                "fertig" -> {
                    val msg = input.optString("nachricht").ifBlank { r.text.ifBlank { "Erledigt." } }
                    remember(command, msg); say(msg); return Result.DONE
                }
                "frage_stellen" -> {
                    val q = input.optString("frage").ifBlank { "Kannst du das genauer sagen?" }
                    remember(command, q); say(q); return Result.DONE
                }
                "claude_fragen" -> {
                    Agent.log("🧠 Lokal zu schwierig: ${input.optString("grund")}")
                    return Result.ESCALATE
                }
                else -> {
                    val out = Tools.execute(c, name, input)
                    val idx = out.text.indexOf("Aktueller Bildschirm:")
                    if (idx >= 0) {
                        screen = out.text.substring(idx + "Aktueller Bildschirm:".length).trim()
                        steps.add("$step. $name $input → ${out.text.substring(0, idx).trim().take(150)}")
                    } else {
                        steps.add("$step. $name $input → ${out.text.take(400)}")
                    }
                }
            }
        }
        say("Das hat zu viele Schritte gebraucht, ich höre hier auf.")
        return if (hybrid) Result.ESCALATE else Result.FAILED
    }

    private fun remember(cmd: String, answer: String) {
        recentChat.addLast("Lirim: ${cmd.take(200)}")
        recentChat.addLast("Jarvis: ${answer.take(200)}")
        while (recentChat.size > 8) recentChat.removeFirst()
    }

    fun reset() = recentChat.clear()
}
