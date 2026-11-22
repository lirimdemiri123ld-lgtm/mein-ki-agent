package ch.lirim.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import ch.lirim.jarvis.core.Intents
import ch.lirim.jarvis.core.People
import ch.lirim.jarvis.core.Recipes
import ch.lirim.jarvis.core.Intent as Cmd
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Das "Gehirn": Schleife aus Claude-Antwort → Werkzeug ausführen → Ergebnis zurück. */
object Agent {
    private const val MAX_STEPS = 25
    private const val CONVERSATION_TIMEOUT_MS = 10 * 60 * 1000L

    @Volatile var running = false
        private set
    @Volatile private var stopRequested = false

    private var messages = JSONArray()
    private var pendingResults: JSONArray? = null
    private var lastActivity = 0L
    private var currentCommand = ""

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

    fun lastActivityAt(): Long = maxOf(lastActivity, if (running) System.currentTimeMillis() else 0L)

    fun fullLog(): String = synchronized(logBuf) { logBuf.toString() }

    fun reset() {
        if (running) return
        messages = JSONArray()
        pendingResults = null
        awaitingAnswer = false
        lastExchange = null
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
            awaitingAnswer = false
            messages = JSONArray()
            pendingResults = null
        }
        log("Du: $command")
        currentCommand = command
        running = true
        stopRequested = false
        Thread {
            try {
                route(app, command)
            } catch (e: Throwable) {
                log("⚠️ ${e.message ?: e.javaClass.simpleName}")
                Speaker.say("Es gab einen Fehler.")
                messages = JSONArray()
                pendingResults = null
                awaitingAnswer = false
            } finally {
                running = false
                lastActivity = System.currentTimeMillis()
                JarvisService.instance?.overlay?.hideStatus()
                WakeWordService.resume(app)
            }
        }.start()
    }

    // ------------------------------------------------------------------ Weiche

    /**
     * Spar-Weiche (die erste passende Stufe gewinnt):
     *  0. Antwort auf eine Rückfrage → gehört zum laufenden Auftrag
     *  1. Direktbefehle (Wecker, Anruf, Nachricht, Lautstärke, Wetter …) → ohne KI, gratis
     *  2. Gelernte Abläufe → selbst abspielen, gratis
     *  3. Alles andere → Claude Haiku (sparsam); danach wird der Ablauf gelernt
     * Kein lokales Sprachmodell mehr: es war langsam, heizte das Handy und erfand Erfolge.
     */
    private fun route(ctx: Context, command: String) {
        val hasKey = Prefs.apiKey(ctx).isNotBlank()
        val cloudOk = hasKey && System.currentTimeMillis() >= Prefs.cloudBlockedUntil(ctx)

        if (awaitingAnswer && messages.length() > 0) {
            awaitingAnswer = false
            if (cloudOk) { cloudContinue(ctx, command); return }
        }
        awaitingAnswer = false
        messages = JSONArray(); pendingResults = null
        thorough = false
        var text = command
        var forceCloud = false

        val intent = try { Intents.parse(command) } catch (e: Exception) { null }
        if (intent is Cmd.Cloud) {
            forceCloud = true; thorough = intent.thorough; text = intent.text; currentCommand = text
        } else if (intent != null) {
            when (Direct.run(ctx, command, intent)) {
                Direct.Result.DONE -> { Prefs.addSaved(ctx); Memory.addHistory(ctx, command, "(direkt erledigt, 0 Rp.)"); return }
                Direct.Result.STOPPED -> return
                Direct.Result.PASS -> {}
            }
        }

        var prefix: List<Recipes.Step> = emptyList()
        if (!forceCloud) {
            when (replay(ctx, text, cloudOk)) {
                Replay.DONE -> { Prefs.addSaved(ctx); return }
                Replay.STOPPED -> return
                Replay.PARTIAL -> prefix = replayDone
                Replay.NONE -> {}
            }
        }
        if (cloudOk) { cloudFresh(ctx, text, prefix); return }
        say(if (!hasKey) "Dafür brauche ich Claude. Trag unter SYSTEM deinen API-Schlüssel ein."
            else "Dein Claude-Guthaben ist leer – bitte aufladen und im HUD unter Guthaben eintragen. Direktbefehle und gelernte Abläufe laufen weiter.")
    }

    // ------------------------------------------------------------------ 2. Gelernte Abläufe

    private enum class Replay { NONE, DONE, PARTIAL, STOPPED }
    private var replayDone: List<Recipes.Step> = emptyList()

    private fun replay(ctx: Context, command: String, cloudOk: Boolean): Replay {
        val (r, b0) = RecipeStore.find(ctx, command) ?: return Replay.NONE
        val svc = JarvisService.instance ?: return Replay.NONE
        val facts = Memory.factTexts(ctx)
        val b = b0.mapValues { (_, v) -> if (People.isRelational(v)) People.resolve(v, facts) ?: v else v }
        val steps = Recipes.instantiate(r, b)
        log("📚 Gelernter Ablauf (${r.successes}× geklappt) – ohne Claude")
        val done = mutableListOf<Recipes.Step>()
        replayDone = emptyList()
        for ((i, s0) in steps.withIndex()) {
            if (stopRequested) { log("⏹ Gestoppt."); return Replay.STOPPED }
            status("gelernt · Schritt ${i + 1}/${steps.size}")
            var s = s0
            if (s.args["text"] == Recipes.GEN) {
                val t = compose(ctx, command, s.args["beispiel_auftrag"].orEmpty(), s.args["beispiel"].orEmpty(), cloudOk)
                if (t.isNullOrBlank()) { replayDone = done; RecipeStore.result(ctx, r.template, false); return Replay.PARTIAL }
                s = Recipes.Step(s.tool, s.args.filterKeys { !it.startsWith("beispiel") } + ("text" to t))
            }
            log("↻ ${s.tool} ${s.args.filterKeys { !it.startsWith("beispiel") }}")
            when (runStep(ctx, svc, s)) {
                true -> done.add(s)
                null -> { say("In Ordnung, dann lasse ich es."); return Replay.STOPPED }
                false -> {
                    log("Gelernter Ablauf hakt bei Schritt ${i + 1} – Claude übernimmt.")
                    RecipeStore.result(ctx, r.template, false)
                    replayDone = done
                    return Replay.PARTIAL
                }
            }
        }
        RecipeStore.result(ctx, r.template, true)
        val answer = Recipes.fill(r.answer, b)
        Memory.addHistory(ctx, command, answer)
        say(answer)
        return Replay.DONE
    }

    /** Führt einen gelernten Schritt aus. true = ok, false = hakt, null = Lirim hat Nein gesagt. */
    private fun runStep(ctx: Context, svc: JarvisService, s: Recipes.Step): Boolean? {
        val a = s.args
        fun bad(t: String) = t.startsWith("Fehler") || t.contains("fehlgeschlagen", true) ||
            t.contains("Kein Element", true) || t.contains("nicht gefunden", true) || t.contains("nicht möglich", true)
        return when (s.tool) {
            "tippen" -> {
                val q = a["text"].orEmpty()
                var out = svc.tapText(q)
                if (bad(out)) { Thread.sleep(1500); svc.readScreen(); out = svc.tapText(q) }
                Thread.sleep(900)
                !bad(out)
            }
            "text_eingeben" -> {
                val out = svc.typeAny(a["text"].orEmpty(), a["absenden"] == "true")
                Thread.sleep(500)
                out.startsWith("Text eingegeben")
            }
            "bestaetigen", "anrufen" -> {
                val out = Tools.execute(ctx, s.tool, toJson(a)).text
                if (out.contains("NEIN")) null else !bad(out)
            }
            else -> !bad(Tools.execute(ctx, s.tool, toJson(a)).text)
        }
    }

    private fun toJson(a: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in a) {
            if (k.startsWith("beispiel")) continue
            o.put(k, v.toIntOrNull() ?: v.toBooleanStrictOrNull() ?: v)
        }
        return o
    }

    /** Neuen Text (z. B. Nachricht) nach gelerntem Muster formulieren – winziger, sehr günstiger Aufruf. */
    private fun compose(ctx: Context, command: String, exCmd: String, exText: String, cloudOk: Boolean): String? {
        val prompt = "Früherer Auftrag: «$exCmd»\nDamals eingetippter Text: «$exText»\n\nNeuer Auftrag: «$command»\n" +
            "Welcher Text muss jetzt eingetippt werden? Gleicher Stil wie damals, Schweizer Rechtschreibung. Gib NUR den Text aus."
        if (cloudOk) try {
            val resp = ClaudeClient.send(Prefs.apiKey(ctx), Prefs.model(ctx), "Du formulierst kurze Texte für Lirim.", JSONArray(),
                JSONArray().put(JSONObject().put("role", "user").put("content", prompt)), maxTokens = 200)
            Prefs.addCost(ctx, ClaudeClient.costUsd(Prefs.model(ctx), resp))
            val c = resp.getJSONArray("content")
            val t = (0 until c.length()).map { c.getJSONObject(it) }.filter { it.optString("type") == "text" }
                .joinToString(" ") { it.optString("text") }.trim().trim('«', '»', '"')
            if (t.isNotBlank()) return t
        } catch (e: Exception) { log("Formulieren fehlgeschlagen: ${e.message?.take(80)}") }
        return null
    }

    // ------------------------------------------------------------------ 4. Claude (sparsam)

    private fun now(): String = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN).format(Date())

    private fun cloudFresh(ctx: Context, command: String, prefix: List<Recipes.Step>) {
        messages = JSONArray(); pendingResults = null
        val sb = StringBuilder("[Jetzt: ${now()}]\n")
        lastExchange?.let { sb.append("[Vorheriger Auftrag: $it]\n") }
        sb.append(command)
        if (prefix.isNotEmpty()) {
            sb.append("\n\n(Ich habe schon angefangen: ")
                .append(prefix.joinToString(" → ") { "${it.tool} ${it.args}" })
                .append(". Mach vom aktuellen Bildschirm aus weiter.)\n\nAktueller Bildschirm:\n")
                .append(JarvisService.instance?.readScreen() ?: "")
        }
        messages.put(JSONObject().put("role", "user").put("content", sb.toString()))
        recorder = prefix.toMutableList()
        cloud(ctx)
    }

    private fun cloudContinue(ctx: Context, answer: String) {
        val content = JSONArray()
        pendingResults?.let { pr -> for (i in 0 until pr.length()) content.put(pr.get(i)) }
        pendingResults = null
        content.put(JSONObject().put("type", "text").put("text", answer))
        messages.put(JSONObject().put("role", "user").put("content", content))
        recorder = null // Rückfragen-Dialoge nicht als Ablauf lernen
        cloud(ctx)
    }

    private fun cloud(ctx: Context) {
        try {
            loop(ctx)
        } catch (e: ApiException) {
            messages = JSONArray(); pendingResults = null; awaitingAnswer = false
            val msg = e.message.orEmpty()
            if (e.code == 402 || msg.contains("credit", true) || msg.contains("billing", true)) {
                // Nur kurz sperren – der nächste Versuch prüft neu, Aufladen wird also sofort erkannt
                Prefs.setCloudBlockedUntil(ctx, System.currentTimeMillis() + 90_000L)
                Prefs.setCreditBalance(ctx, 0.0)
                say("Dein Claude-Guthaben ist leer – bitte aufladen. Direktbefehle und gelernte Abläufe laufen weiter gratis.")
            } else if (e.code == 401) {
                say("Der API-Schlüssel wird nicht akzeptiert. Bitte unter SYSTEM prüfen.")
            } else throw e
        }
    }

    @Volatile var lastSaid = ""
        private set

    /** Spricht und protokolliert (auch für Direktbefehle). */
    fun speak(text: String) = say(text)

    private fun say(text: String) {
        if (text.isBlank()) return
        lastSaid = text
        log("Jarvis: $text")
        Speaker.say(text)
    }

    private fun status(text: String) {
        JarvisService.instance?.overlay?.setStatus(text)
    }

    private var recorder: MutableList<Recipes.Step>? = null
    private var lastExchange: String? = null
    /** Jarvis hat eine Rückfrage gestellt – die nächste Eingabe gehört zum selben Auftrag. */
    @Volatile var awaitingAnswer = false
        private set
    @Volatile var questionId = 0
        private set
    private var thorough = false

    private fun loop(ctx: Context) {
        val key = Prefs.apiKey(ctx)
        val model = if (thorough) "claude-sonnet-5-5" else Prefs.model(ctx)
        // Websuche nur, wenn du ausdrücklich danach fragst (spart Tokens)
        val lc = currentCommand.lowercase()
        // Eine Websuche kostet ≈ 1 Rp. – nur wenn erlaubt UND ausdrücklich verlangt
        var webSearch = Prefs.webSearch(ctx) && Regex("""im internet|im web|im netz|websuche|recherchier|online nach""").containsMatchIn(lc)
        val staticSys = systemPrompt()
        val dynSys = Memory.promptBlock(ctx)
        var cost = 0.0

        try {
            for (step in 1..MAX_STEPS) {
                if (stopRequested) {
                    log("⏹ Gestoppt.")
                    messages = JSONArray()
                    return
                }
                status("denke nach … (Schritt $step)")
                prune()

                val resp = try {
                    ClaudeClient.send(key, model, staticSys, Tools.definitions(webSearch), messages, dynSys)
                } catch (e: ApiException) {
                    if (webSearch && e.message?.contains("web_search", ignoreCase = true) == true) {
                        log("Websuche nicht verfügbar – ohne weiter.")
                        webSearch = false
                        ClaudeClient.send(key, model, staticSys, Tools.definitions(false), messages, dynSys)
                    } else throw e
                }
                cost += ClaudeClient.costUsd(model, resp)

                val content = resp.getJSONArray("content")
                messages.put(JSONObject().put("role", "assistant").put("content", content))

                val results = JSONArray()
                val texts = StringBuilder()
                var endMessage: String? = null
                var question = false

                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    when (b.optString("type")) {
                        "text" -> {
                            val t = b.optString("text").trim()
                            if (t.isNotEmpty()) texts.append(t).append(' ')
                        }
                        "server_tool_use" -> { log("🔎 Websuche …"); recorder?.add(Recipes.Step("web_search", emptyMap())) }
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
                                    question = true
                                    results.put(toolResult(id, ToolOutput("Frage gestellt. Die Antwort folgt im nächsten Text.")))
                                }
                                else -> {
                                    if (stopRequested) {
                                        results.put(toolResult(id, ToolOutput("Abgebrochen.")))
                                    } else {
                                        log("→ $name ${short(input)}")
                                        status(describe(name, input))
                                        val rec = recordable(name, input)
                                        val out = Tools.execute(ctx, name, input)
                                        record(rec, out.text)
                                        results.put(toolResult(id, out))
                                    }
                                }
                            }
                        }
                    }
                }

                val end = endMessage
                if (end != null) {
                    pendingResults = results
                    if (question) { awaitingAnswer = true; questionId++ }
                    else learn(ctx, end)
                    Memory.addHistory(ctx, currentCommand, end)
                    lastExchange = "${currentCommand.take(100)} → ${end.take(100)}"
                    say(end)
                    return
                }
                if (results.length() == 0) {
                    val answer = texts.toString().trim()
                    Memory.addHistory(ctx, currentCommand, answer)
                    lastExchange = "${currentCommand.take(100)} → ${answer.take(100)}"
                    // Endet die Antwort mit einer Frage, läuft die nächste Eingabe im selben Auftrag weiter
                    if (answer.trimEnd().endsWith("?")) { awaitingAnswer = true; pendingResults = null; questionId++ }
                    say(answer)
                    return
                }
                if (texts.isNotEmpty()) log("Jarvis denkt: ${texts.toString().trim().take(200)}")
                messages.put(JSONObject().put("role", "user").put("content", results))
            }
            say("Ich habe die maximale Anzahl Schritte erreicht und höre hier auf.")
            messages = JSONArray()
        } finally {
            if (cost > 0) {
                Prefs.addCost(ctx, cost)
                log("💰 ≈ ${rp(cost)} · diesen Monat ≈ ${rp(Prefs.monthCost(ctx))}")
            }
        }
    }

    /** Ungefähr in Rappen/Franken (1 $ ≈ 0.80 Fr.). */
    fun rp(usd: Double): String {
        val chf = usd * 0.80
        return if (chf < 1.0) "%.2f Rp.".format(Locale.GERMAN, chf * 100).replace(',', '.') else "%.2f Fr.".format(Locale.US, chf)
    }

    // ---- Aufzeichnen, um Abläufe zu lernen

    private fun recordable(name: String, input: JSONObject): Recipes.Step {
        val args = mutableMapOf<String, String>()
        input.keys().forEach { k -> args[k] = input.opt(k)?.toString() ?: "" }
        if (name == "tippen" && input.has("id") && input.optString("id") != "null") {
            val id = input.optInt("id", -1)
            val label = JarvisService.instance?.labelOf(id)
            return if (label.isNullOrBlank()) Recipes.Step("tippen_xy", args)   // nicht wiederholbar
            else Recipes.Step("tippen", mapOf("text" to label))
        }
        if (name == "text_eingeben") {
            return Recipes.Step(name, mapOf("text" to input.optString("text"), "absenden" to input.optBoolean("absenden", false).toString()))
        }
        return Recipes.Step(name, args)
    }

    private fun record(step: Recipes.Step, result: String) {
        val r = recorder ?: return
        val failed = result.startsWith("Fehler") || result.contains("fehlgeschlagen", true) ||
            result.contains("Kein Element", true) || result.contains("nicht gefunden", true)
        if (result.contains("NEIN")) { recorder = null; return }   // abgelehnt → nichts lernen
        if (!failed) r.add(step)
    }

    private fun learn(ctx: Context, answer: String) {
        val rec = recorder ?: return
        recorder = null
        val r = Recipes.build(currentCommand, rec, answer, System.currentTimeMillis()) ?: return
        val isNew = RecipeStore.add(ctx, r)
        log(if (isNew) "📚 Gelernt: «${r.template}» – nächstes Mal mache ich das selbst, gratis."
            else "📚 Ablauf aktualisiert: «${r.template}»")
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

        // Verlauf NICHT kürzen: er wird aus dem Cache gelesen (≈10 % Preis) – Kürzen würde den Cache zerstören.
        // Nur alte Bilder entfernen und bei sehr langen Aufgaben notbremsen.
        val total = messages.toString().length
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
                    is String -> if (total > 200_000 && rc.length > 400) b.put("content", rc.take(160) + " … (gekürzt)")
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

    private fun systemPrompt(): String = """
Du bist Jarvis, der persönliche Sprachassistent von Lirim auf seinem Samsung Galaxy S25 (Android). Du bedienst das Handy wie ein Mensch: Apps öffnen, Bildschirm lesen, tippen, Text eingeben, scrollen, wischen.

Regeln:
- Antworte immer auf Deutsch (Schweizer Rechtschreibung, «ss» statt «ß»), kurz und natürlich. Deine Antworten werden vorgelesen – keine Aufzählungen, kein Markdown.
- Arbeite Schritt für Schritt und effizient (jeder Schritt kostet Geld). Nach jeder Aktion bekommst du automatisch den neuen Bildschirm – lies ihn nicht extra.
- Bildschirmformat: [Nummer] "Text" (Beschreibung) {Hinweis}; k = klickbar, Eingabe = Eingabefeld, @x,y nur bei Elementen ohne Text.
- tippen: Nimm wenn möglich den sichtbaren text (z. B. tippen text="Gruja"), sonst die id. So kann ich den Ablauf lernen und nächstes Mal selbst erledigen.
- Übernimm Texte für Nachrichten möglichst wörtlich aus dem Auftrag.
- screenshot nur, wenn die Liste wirklich leer/unbrauchbar ist (Bilder sind teuer).
- Öffne Apps direkt mit app_oeffnen statt über den Startbildschirm.
- Vor allem Unumkehrbaren – Nachricht/E-Mail senden, kaufen, bezahlen, buchen, bestellen, löschen, posten, Sicherheitseinstellungen ändern – rufst du IMMER zuerst bestaetigen auf und machst nur bei JA weiter. anrufen fragt selbst.
- Gib niemals Passwörter, PINs, Codes oder Kartendaten ein und lies sie nicht vor. Bei Anmeldungen bittest du Lirim, das selbst zu machen.
- Manche Apps (Banking, TWINT) blockieren die Bedienung. Wenn etwas nicht geht, sag es ehrlich, statt zu raten.
- Bei reinen Wissensfragen antworte direkt über fertig (Websuche nur, wenn vorhanden und nötig).
- Fehlen dir Infos, nutze frage_stellen.
- Bedien nicht die Jarvis-App selbst (ch.lirim.jarvis).
- Beende jeden Auftrag mit fertig und einer Zusammenfassung in einem Satz.

Persönlichkeit: Du bist eine hochentwickelte KI im Stil von J.A.R.V.I.S. – souverän, vorausschauend, mit trockenem, feinem Humor.

Lernen und Gedächtnis:
- Nutze dein Langzeitgedächtnis (unten): Spitznamen, Personen, Vorlieben und Routinen gelten, ohne dass Lirim sie wiederholen muss.
- Erzählt Lirim etwas Dauerhaftes über sich, speichere es mit merken. Sagt er «vergiss …», nutze vergessen.
- Hast du in einer App einen Umweg oder Fehler erlebt, speichere VOR fertig einen kurzen Tipp mit erfahrung_speichern.
- Speichere nie Passwörter, PINs, Codes, Karten- oder Kontonummern.
""".trimIndent()
}
