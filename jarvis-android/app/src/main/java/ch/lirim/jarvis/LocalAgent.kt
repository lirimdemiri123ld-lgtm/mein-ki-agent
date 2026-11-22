package ch.lirim.jarvis

import android.content.Context
import android.util.Base64
import ch.lirim.jarvis.core.TaskLogic
import ch.lirim.jarvis.core.ToolTextParser
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent mit dem lokalen Gehirn.
 * - Jarvis schätzt selbst ein, wie komplex der Auftrag ist, und wählt das passende Modell.
 * - Komplexe Aufträge: erst Plan, dann Schritt für Schritt mit Kontrolle, Notizen und Bildschirmfoto.
 * - Bleibt das kleine Modell hängen, übernimmt automatisch das grosse.
 */
object LocalAgent {
    enum class Result { DONE, ESCALATE, STOPPED, FAILED }

    @Volatile var lastError = ""
        private set
    private val recentChat = ArrayDeque<String>()

    private val extraTools = listOf(
        """{"name":"schritt_erledigt","description":"Der aktuelle Planschritt ist erledigt. Weiter zum nächsten.","parameters":{"type":"object","properties":{"ergebnis":{"type":"string","description":"kurz, was herauskam"}}}}""",
        """{"name":"notiz","description":"Merkt sich eine Information für diesen Auftrag (z. B. Inhalt einer Mail, eine Uhrzeit, einen Preis).","parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}""",
        """{"name":"plan_aendern","description":"Ersetzt die restlichen Schritte, wenn der Plan nicht passt.","parameters":{"type":"object","properties":{"schritte":{"type":"string","description":"neue Schritte, durch ; getrennt"}},"required":["schritte"]}}""",
        """{"name":"bildschirm_ansehen","description":"Schaut sich ein Foto des Bildschirms an, wenn die Elementliste unklar ist.","parameters":{"type":"object","properties":{}}}""",
        """{"name":"tippen_bild","description":"Tippt auf eine Stelle im zuletzt angesehenen Bildschirmfoto (Koordinaten im Bild).","parameters":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}}""",
    )

    private fun baseSpecs(c: Context): List<String> {
        val arr = JSONArray(c.assets.open("local_tools.json").bufferedReader().use { it.readText() })
        return (0 until arr.length()).map { arr.getJSONObject(it).toString() }
    }

    private fun toolSpecs(c: Context, hybrid: Boolean, complex: Boolean): List<String> {
        val out = baseSpecs(c).toMutableList()
        if (complex) out.addAll(extraTools)
        if (hybrid) {
            out.add("""{"name":"claude_fragen","description":"Nur wenn die Aufgabe trotz Plan zu schwierig ist. Übergibt an die Cloud-KI.","parameters":{"type":"object","properties":{"grund":{"type":"string"}}}}""")
        }
        return out
    }

    private fun paramsOf(specs: List<String>): Map<String, List<String>> = specs.associate { s ->
        val o = JSONObject(s)
        val props = o.optJSONObject("parameters")?.optJSONObject("properties")
        val req = o.optJSONObject("parameters")?.optJSONArray("required")
        val names = mutableListOf<String>()
        if (req != null) for (i in 0 until req.length()) names.add(req.getString(i))
        props?.keys()?.forEach { if (it !in names) names.add(it) }
        o.getString("name") to names
    }

    private fun system(c: Context, complex: Boolean): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN).format(Date())
        val memory = Memory.promptBlock(c).take(1500).trim()
        val people = ch.lirim.jarvis.core.People.promptLines(Memory.factTexts(c))
        return """
Du bist Jarvis, Lirims persönlicher KI-Assistent auf seinem Samsung Galaxy S25. Du läufst lokal auf dem Handy.
Antworte immer auf Deutsch (Schweiz, «ss» statt «ß»), kurz, natürlich, souverän, mit feinem Humor. Kein Markdown.
Du bedienst das Handy mit Werkzeugen. Die Bildschirmliste zeigt Elemente als [Nummer]. tippen geht per Nummer oder per sichtbarem Text (z. B. tippen text="Ali" für einen Chat).
Regeln:
- Ist keine Aktion nötig (Frage, Plaudern, Erklärung): antworte direkt mit normalem Text, ohne Werkzeug.
- Sonst rufe genau EIN Werkzeug auf. Apps öffnen, lesen, scrollen und Wecker stellen geht ohne Rückfrage.
- Nur vor Senden, Kaufen, Bezahlen oder Löschen zuerst bestaetigen. anrufen fragt selbst nach.
- Personen wie «meine Frau» oder «Baba» löst du mit dem Hintergrundwissen auf.
- Sagt Lirim, wer jemand ist, speichere es mit merken (kategorie person).
- Die Spracherkennung schreibt Namen manchmal falsch (z. B. «gruya» statt «Gruja»). Korrigiert Lirim einen Namen, speichere die richtige Schreibweise mit merken.
${if (complex) "- Arbeite den Plan ab. Ist ein Schritt erledigt: schritt_erledigt. Wichtige Infos unterwegs mit notiz festhalten. Am Ende fertig mit Zusammenfassung.\n- Wenn eine Aktion nichts bewirkt: etwas anderes versuchen (scrollen, zurück, anderes Element, bildschirm_ansehen)." else "- Ist eine Aktion erledigt, rufe fertig auf (nachricht = was du getan hast)."}
- Das Hintergrundwissen unten nutzt du still. Erwähne es nur, wenn es passt.
- Nie Passwörter oder PINs.
Jetzt: $now.
""".trimIndent() + (if (people.isNotEmpty()) "\n\nWICHTIGE PERSONEN (nutze diese Namen für anrufen/schreiben):\n$people" else "") +
            (if (memory.isNotEmpty()) "\n\nHintergrundwissen über Lirim:\n$memory" else "")
    }

    fun run(c: Context, command: String, hybrid: Boolean, shouldStop: () -> Boolean, say: (String) -> Unit): Result {
        val complex = TaskLogic.isComplex(command)
        // Einfache Fragen → schnell; alles mit Handy-Aktion → schlaues Modell (wenn vorhanden)
        val wantBig = complex || !TaskLogic.isChatOnly(command)
        var model = LocalBrain.pick(c, wantBig) ?: return Result.FAILED.also { lastError = "Kein Modell installiert." }
        if (complex && !LocalBrain.isBig(model) && LocalBrain.ensureBigDownloaded(c)) {
            Agent.log("🧠 Lade mir im Hintergrund ein grösseres Gehirn (${LocalBrain.big.gb}) für solche Aufgaben.")
        }
        Agent.log("🧠 ${if (complex) "Komplexer Auftrag" else if (wantBig) "Aktion" else "Frage"} → ${model.title.substringBefore(" ·")}")
        var r = attempt(c, model, command, complex, hybrid, shouldStop, say)
        // Kleines Modell gescheitert → grosses versuchen
        if ((r == Result.FAILED || r == Result.ESCALATE) && !LocalBrain.isBig(model) &&
            LocalBrain.isInstalled(c, LocalBrain.big) && !shouldStop()
        ) {
            Agent.log("🧠 Wechsle zum grösseren Gehirn …")
            model = LocalBrain.big
            r = attempt(c, model, command, true, hybrid, shouldStop, say)
        }
        return r
    }

    private fun attempt(
        c: Context, model: LocalBrain.Model, command: String, complex: Boolean, hybrid: Boolean,
        shouldStop: () -> Boolean, say: (String) -> Unit
    ): Result {
        val specs = toolSpecs(c, hybrid, complex)
        val params = paramsOf(specs)
        val sys = system(c, complex)

        // 1) Plan (nur für komplexe Aufträge)
        val plan = mutableListOf<String>()
        if (complex) {
            JarvisService.instance?.overlay?.setStatus("plane den Auftrag …")
            val hints = Memory.hintsFor(c, command)
            try {
                plan.addAll(TaskLogic.parsePlan(LocalBrain.chat(c, TaskLogic.PLAN_SYSTEM, TaskLogic.planPrompt(command, hints), 20_000, model)))
            } catch (e: Throwable) {
                lastError = (e.message ?: "Planungsfehler").take(200)
                Agent.log("⚠️ Plan: $lastError")
                return Result.FAILED
            }
            if (plan.isNotEmpty()) Agent.log("📋 Plan:\n" + plan.mapIndexed { i, p -> "  ${i + 1}. $p" }.joinToString("\n"))
        }

        val st = TaskLogic.State(command, plan)
        if (recentChat.isNotEmpty() && !complex) st.notes.add("Gesprächsverlauf: " + recentChat.joinToString(" | "))
        val maxActions = if (complex) 40 else 12
        var lastFp = 0
        val actionCount = HashMap<String, Int>()
        var image: ByteArray? = null
        var imageFactor = 1f
        var textStreak = 0
        var actionsSinceAdvance = 0

        for (step in 1..maxActions) {
            if (shouldStop()) return Result.STOPPED
            val label = if (plan.isNotEmpty() && st.current < plan.size) "Schritt ${st.current + 1}/${plan.size}: ${plan[st.current].take(40)}" else "Aktion $step"
            JarvisService.instance?.overlay?.setStatus(label)

            // Kaum lesbare Elemente → automatisch Foto ansehen
            if (complex && image == null && st.screen.isNotBlank() && st.screen.lines().count { it.startsWith("[") } < 3 && LocalBrain.visionOk) {
                grabScreenshot()?.let { (img, f) -> image = img; imageFactor = f; st.hint = (st.hint + " Die Liste ist fast leer; schau dir das Bildschirmfoto an.").trim() }
            }

            val r = try {
                LocalBrain.step(c, model, sys, TaskLogic.execPrompt(st), specs, image)
            } catch (e: Throwable) {
                lastError = (e.message ?: e.javaClass.simpleName).take(200)
                Agent.log("⚠️ Lokales Gehirn: $lastError")
                return Result.FAILED
            }
            image = null
            st.hint = ""

            var name = r.toolName
            var args = r.args
            if (name == null) ToolTextParser.parse(r.text, params)?.let { name = it.name; args = it.args }
            val tool = name
            if (tool == null) {
                val answer = ToolTextParser.cleanup(r.text).ifBlank { TaskLogic.fallbackAnswer(st) }
                textStreak++
                if (complex && !TaskLogic.textMeansFinish(st, answer, textStreak)) {
                    st.hint = TaskLogic.textHint(st)
                    continue
                }
                finish(c, command, answer, st, complex, say)
                return Result.DONE
            }
            textStreak = 0
            val input = JSONObject(args.filterValues { it != null })
            // Personen deterministisch auflösen («meine Frau» → Name aus dem Gedächtnis)
            val personKey = when (tool) { "anrufen" -> "wen"; "kontakt_suchen" -> "name"; "tippen" -> "text"; else -> null }
            if (personKey != null && (tool != "tippen" || input.has("text"))) {
                val cur = input.optString(personKey).takeIf { it != "null" } ?: ""
                if (ch.lirim.jarvis.core.People.isRelational(cur)) {
                    val who = ch.lirim.jarvis.core.People.resolve(cur.ifBlank { command }, Memory.factTexts(c))
                        ?: ch.lirim.jarvis.core.People.resolve(command, Memory.factTexts(c))
                    if (who != null) { input.put(personKey, who); Agent.log("👤 «${cur.ifBlank { "?" }}» → $who") }
                    else if (cur.isBlank() && tool == "anrufen") {
                        // Name direkt aus dem Satz nehmen («Ruf Ali an»)
                        Regex("""(?i)ruf\s+(.+?)\s+an\b""").find(command)?.groupValues?.get(1)?.let { input.put(personKey, it) }
                    }
                }
            }
            Agent.log("→ [${model.title.substringBefore(" ·").substringAfter("Gemma 4 ")}] $tool $input")

            // Schleifen-Schutz
            val key = TaskLogic.actionKey(tool, args, st.screen)
            val n = (actionCount[key] ?: 0) + 1
            actionCount[key] = n
            if (n >= 2 && tool !in setOf("scrollen", "warten", "schritt_erledigt", "notiz")) {
                st.hint = TaskLogic.repeatHint(tool, n - 1)
                if (n >= 4) {
                    if (complex && st.notes.isNotEmpty()) {
                        finish(c, command, "Weiter bin ich nicht gekommen. Bisher habe ich: " + TaskLogic.fallbackAnswer(st), st, false, say)
                        return Result.DONE
                    }
                    lastError = "Ich drehe mich im Kreis."; return Result.FAILED
                }
                continue
            }

            when (tool) {
                "fertig" -> {
                    val msg = input.optString("nachricht").ifBlank {
                        ToolTextParser.cleanup(r.text).takeIf { it.isNotBlank() && !it.lowercase().startsWith("fertig") }
                            ?: if (st.notes.isNotEmpty()) st.notes.joinToString(". ") else if (st.history.isNotEmpty()) "Erledigt." else "Wie kann ich dir helfen?"
                    }
                    finish(c, command, msg, st, complex, say)
                    return Result.DONE
                }
                "frage_stellen" -> {
                    val q = input.optString("frage").ifBlank { "Kannst du das genauer sagen?" }
                    remember(command, q); say(q); return Result.DONE
                }
                "claude_fragen" -> {
                    Agent.log("🧠 Lokal zu schwierig: ${input.optString("grund")}")
                    return Result.ESCALATE
                }
                "schritt_erledigt" -> {
                    if (plan.isNotEmpty() && st.current >= plan.size) {
                        finish(c, command, TaskLogic.fallbackAnswer(st), st, complex, say)
                        return Result.DONE
                    }
                    if (!TaskLogic.stepDoneAllowed(st, actionsSinceAdvance)) {
                        st.hint = TaskLogic.stepDoneRejected(st)
                        continue
                    }
                    actionsSinceAdvance = 0
                    val e = input.optString("ergebnis")
                    if (e.isNotBlank() && st.notes.none { it == e }) st.notes.add(e)
                    st.history.add("✓ Schritt ${st.current + 1} erledigt${if (e.isNotBlank()) ": $e" else ""}")
                    st.current++
                    if (plan.isNotEmpty() && st.current >= plan.size) {
                        st.hint = "Alle Planschritte sind erledigt. Rufe jetzt fertig mit einer Zusammenfassung auf."
                    }
                }
                "notiz" -> {
                    val t = input.optString("text").take(300)
                    val dup = st.notes.any { it.take(40).equals(t.take(40), ignoreCase = true) }
                    if (dup) {
                        st.hint = "Diese Info hast du schon notiert. Mach mit dem nächsten Punkt weiter (z. B. zurück und die nächste Mail öffnen)."
                        continue
                    }
                    actionsSinceAdvance++
                    st.notes.add(t)
                    st.history.add("notiz gespeichert")
                }
                "plan_aendern" -> {
                    val neu = input.optString("schritte").split(";", "\n").map { it.trim() }.filter { it.length > 2 }
                    if (neu.isNotEmpty()) {
                        while (plan.size > st.current) plan.removeAt(plan.size - 1)
                        plan.addAll(neu)
                        Agent.log("📋 Neuer Plan ab Schritt ${st.current + 1}: ${neu.joinToString(" → ")}")
                    }
                }
                "bildschirm_ansehen" -> {
                    val shot = grabScreenshot()
                    if (shot == null || !LocalBrain.visionOk) {
                        st.history.add("bildschirm_ansehen → nicht möglich")
                        st.hint = "Bildschirmfoto nicht möglich. Nutze die Elementliste."
                    } else {
                        image = shot.first; imageFactor = shot.second
                        st.history.add("bildschirm_ansehen → Foto liegt bei (Breite 720)")
                    }
                }
                "tippen_bild" -> {
                    actionsSinceAdvance++
                    val svc = JarvisService.instance
                    val ok = svc?.tapXY(input.optDouble("x").toFloat() * imageFactor, input.optDouble("y").toFloat() * imageFactor) ?: false
                    Thread.sleep(900)
                    st.screen = svc?.readScreen() ?: ""
                    st.history.add("$step. tippen_bild $input → ${if (ok) "getippt" else "fehlgeschlagen"}")
                }
                else -> {
                    actionsSinceAdvance++
                    val out = Tools.execute(c, tool, input)
                    val idx = out.text.indexOf("Aktueller Bildschirm:")
                    val result: String
                    if (idx >= 0) {
                        val newScreen = out.text.substring(idx + "Aktueller Bildschirm:".length).trim()
                        result = out.text.substring(0, idx).trim()
                        val fp = TaskLogic.fingerprint(newScreen)
                        if (fp == lastFp && tool in setOf("tippen", "text_eingeben", "taste", "scrollen")) {
                            st.hint = "Deine letzte Aktion hat am Bildschirm nichts verändert."
                        }
                        lastFp = fp
                        st.screen = newScreen
                    } else {
                        result = out.text
                        if (tool in setOf("nachrichten_lesen", "live_daten", "kontakt_suchen")) st.notes.add(out.text.take(600))
                    }
                    st.history.add("$step. $tool $input → ${result.take(160)}")
                    if (!complex && tool in setOf("wecker_stellen", "timer_stellen", "merken", "anrufen", "projekt_aktualisieren", "url_oeffnen")) {
                        // Einfache Einzelaktion: direkt bestätigen
                        finish(c, command, result.lines().first().take(160), st, false, say)
                        return Result.DONE
                    }
                }
            }
        }
        say("Das hat zu viele Schritte gebraucht, ich höre hier auf.")
        return Result.FAILED.also { lastError = "Zu viele Schritte." }
    }

    /**
     * Sicherheitsnetz: Das Modell hat nur geredet, aber eindeutig verlangt war eine Aktion.
     * Dann führt die App sie selbst aus. @return Ergebnistext oder null
     */
    private fun intentFallback(c: Context, command: String): String? {
        val lc = command.lowercase().trim()
        val facts = Memory.factTexts(c)
        if (Regex("""\bruf\b.*\ban\b|anrufen|telefonier""").containsMatchIn(lc)) {
            val who = ch.lirim.jarvis.core.People.resolve(command, facts)
                ?: Regex("""(?i)ruf\s+(.+?)\s+an\b""").find(command)?.groupValues?.get(1)?.takeIf { !ch.lirim.jarvis.core.People.isRelational(it) }
                ?: return null
            Agent.log("🛟 Sicherheitsnetz: rufe $who an")
            return Tools.execute(c, "anrufen", JSONObject().put("wen", who)).text.substringBefore("\n\nAktueller Bildschirm").trim()
        }
        Regex("""^(?:hallo jarvis[,\s]*)?(?:öffne|starte|oeffne)\s+(?:die\s+app\s+)?(.+?)$""").find(lc)?.let { m ->
            val app = m.groupValues[1].trim().removeSuffix(".").removeSuffix("app").trim()
            if (app.isNotEmpty() && app.split(" ").size <= 3) {
                Agent.log("🛟 Sicherheitsnetz: öffne $app")
                return Tools.execute(c, "app_oeffnen", JSONObject().put("name", app)).text.substringBefore("\n\nAktueller Bildschirm").trim()
            }
        }
        return null
    }

    private fun finish(c: Context, command: String, msg: String, st: TaskLogic.State, complex: Boolean, say: (String) -> Unit) {
        // Wurde gar nichts getan, obwohl eine Aktion verlangt war? → Sicherheitsnetz
        if (!complex && st.history.isEmpty() && !TaskLogic.isChatOnly(command)) {
            intentFallback(c, command)?.let { done ->
                remember(command, done); say(done); return
            }
        }
        remember(command, msg)
        say(msg)
        // Erfolgreichen Ablauf als Erfahrung speichern → nächstes Mal schneller
        if (complex && st.plan.isNotEmpty() && st.history.size >= 2) {
            Memory.saveSkill(c, command, st.plan.take(st.current.coerceAtLeast(1) + 1))
        }
    }

    private fun grabScreenshot(): Pair<ByteArray, Float>? {
        val svc = JarvisService.instance ?: return null
        val b64 = svc.screenshot() ?: return null
        val factor = Regex("""mit ([\d.]+) multiplizieren""").find(svc.lastShotInfo)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.5f
        return Base64.decode(b64, Base64.NO_WRAP) to factor
    }

    private fun remember(cmd: String, answer: String) {
        recentChat.addLast("Lirim: ${cmd.take(150)}")
        recentChat.addLast("Jarvis: ${answer.take(150)}")
        while (recentChat.size > 6) recentChat.removeFirst()
    }

    fun reset() = recentChat.clear()
}
