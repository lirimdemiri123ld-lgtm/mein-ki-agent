package ch.lirim.jarvis

import android.content.Context
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

    private val excluded = setOf("screenshot", "tippen_xy", "wischen", "lange_druecken")

    private fun toolSpecs(hybrid: Boolean): List<String> {
        val defs = Tools.definitions(false)
        val out = mutableListOf<String>()
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            val name = d.optString("name")
            if (name.isEmpty() || name in excluded) continue
            out.add(JSONObject().put("name", name).put("description", d.optString("description"))
                .put("parameters", d.getJSONObject("input_schema")).toString())
        }
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
Du bedienst das Handy mit Werkzeugen: app_oeffnen, bildschirm_lesen, tippen (id aus der Bildschirmliste), text_eingeben, scrollen, taste, warten.
Regeln: Rufe pro Antwort genau EIN Werkzeug auf. Vor Senden/Kaufen/Löschen/Anrufen immer zuerst bestaetigen. Nie Passwörter oder PINs.
Wenn der Auftrag erledigt ist oder es nur eine Frage/Unterhaltung ist: rufe fertig mit einer kurzen Antwort auf.
Merke dir Dauerhaftes über Lirim mit merken.
Jetzt: $now.
""".trimIndent() + Memory.promptBlock(c).take(2500)
    }

    fun run(c: Context, command: String, hybrid: Boolean, shouldStop: () -> Boolean, say: (String) -> Unit): Result {
        val specs = toolSpecs(hybrid)
        val sys = system(c)
        val steps = mutableListOf<String>()
        var screen = ""
        for (step in 1..MAX_STEPS) {
            if (shouldStop()) return Result.STOPPED
            JarvisService.instance?.overlay?.setStatus("denke lokal nach … (Schritt $step)")
            val prompt = buildString {
                if (recentChat.isNotEmpty()) append("Letzter Gesprächsverlauf:\n").append(recentChat.joinToString("\n")).append("\n\n")
                append("Auftrag von Lirim: ").append(command).append('\n')
                if (steps.isNotEmpty()) append("\nBisher erledigt:\n").append(steps.joinToString("\n")).append('\n')
                if (screen.isNotEmpty()) append("\nAktueller Bildschirm:\n").append(screen.take(3500)).append('\n')
                append("\nWas ist der nächste Schritt?")
            }
            val r = try {
                LocalBrain.step(c, sys, prompt, specs)
            } catch (e: Throwable) {
                Agent.log("⚠️ Lokales Gehirn: ${e.message?.take(160)}")
                return Result.FAILED
            }
            val name = r.toolName
            if (name == null) {
                val answer = r.text.ifBlank { "Erledigt." }
                remember(command, answer)
                say(answer)
                return Result.DONE
            }
            val input = JSONObject(r.args.filterValues { it != null })
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
