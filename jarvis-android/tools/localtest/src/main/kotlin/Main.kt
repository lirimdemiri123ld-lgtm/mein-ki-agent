import com.google.ai.edge.litertlm.*
import ch.lirim.jarvis.core.TaskLogic
import ch.lirim.jarvis.core.ToolTextParser
import java.io.File

/**
 * Simulierter Handy-Test: echte Gemma-Modelle, echte Plan-/Ausführungslogik, nachgebaute App-Bildschirme.
 * args: <E2B-Datei> <E4B-Datei> <local_tools.json>
 */
val screens = mapOf(
    "home" to "App im Vordergrund: Jarvis (ch.lirim.jarvis)\n[1] TextView \"J.A.R.V.I.S.\"\n[2] EditText {Hinweis: Befehl eingeben} [klickbar, Eingabefeld]",
    "wa_list" to "App im Vordergrund: WhatsApp (com.whatsapp)\n[1] TextView \"WhatsApp\"\n[2] ImageButton (Suchen) [klickbar]\n[3] View \"Ali Bellikaya\" (Letzte Nachricht: Ok bis morgen) [klickbar]\n[4] View \"Shukrije\" (Letzte Nachricht: Kaufst du Milch?) [klickbar]\n[5] View \"Baba Swisscom\" [klickbar]\n[6] ImageButton (Neuer Chat) [klickbar]",
    "wa_chat_ali" to "App im Vordergrund: WhatsApp (com.whatsapp)\n[1] TextView \"Ali Bellikaya\"\n[2] TextView \"Ok bis morgen\"\n[3] EditText {Hinweis: Nachricht} [klickbar, Eingabefeld]\n[4] ImageButton (Sprachnachricht) [klickbar]",
    "wa_chat_ali_typed" to "App im Vordergrund: WhatsApp (com.whatsapp)\n[1] TextView \"Ali Bellikaya\"\n[2] TextView \"Ok bis morgen\"\n[3] EditText \"%TEXT%\" [klickbar, Eingabefeld]\n[4] ImageButton (Senden) [klickbar]",
    "wa_chat_ali_sent" to "App im Vordergrund: WhatsApp (com.whatsapp)\n[1] TextView \"Ali Bellikaya\"\n[2] TextView \"Ok bis morgen\"\n[3] TextView \"%TEXT%\" (gesendet)\n[4] EditText {Hinweis: Nachricht} [klickbar, Eingabefeld]",
    "ol_mail_seb" to "App im Vordergrund: Outlook (com.microsoft.office.outlook)\n[1] ImageButton (Zurück) [klickbar]\n[2] TextView \"Audit Regensdorf verschoben auf Donnerstag 9 Uhr\"\n[3] TextView \"Von: Sebastian Lorenzen\"\n[4] TextView \"Hallo Lirim, das Audit in Regensdorf wird auf Donnerstag 9 Uhr verschoben. Bitte bring die Auswertung der Kundenreklamationen mit. Gruss Sebastian\"",
    "ol_mail_ing" to "App im Vordergrund: Outlook (com.microsoft.office.outlook)\n[1] ImageButton (Zurück) [klickbar]\n[2] TextView \"Perfect Order Rate KW 47: 97,8 %\"\n[3] TextView \"Von: Ingmar Gehlen\"\n[4] TextView \"Hoi Lirim, die POR in KW 47 liegt bei 97,8 %. Hauptursache der Fehler: falsche Taschen in Cold Full. Lg Ingmar\"",
    "ol_inbox" to "App im Vordergrund: Outlook (com.microsoft.office.outlook)\n[1] TextView \"Posteingang\"\n[2] View \"Sebastian Lorenzen – Audit Regensdorf verschoben auf Donnerstag 9 Uhr\" (ungelesen) [klickbar]\n[3] View \"Ingmar Gehlen – Perfect Order Rate KW 47: 97,8 %\" (ungelesen) [klickbar]\n[4] View \"Newsletter Migros – Weihnachtsangebote\" [klickbar]\n[5] Button \"Filter\" [klickbar]",
)

class Sim {
    var screen = "home"; var typed = ""; var sent = false; val log = mutableListOf<String>()
    fun show() = screens[screen]!!.replace("%TEXT%", typed)
    fun exec(tool: String, a: Map<String, Any?>): String {
        log.add("$tool $a")
        val s = a.values.joinToString(" ").lowercase()
        return when (tool) {
            "app_oeffnen" -> when {
                s.contains("whatsapp") -> { screen = "wa_list"; "App WhatsApp geöffnet." }
                s.contains("outlook") -> { screen = "ol_inbox"; "App Outlook geöffnet." }
                else -> "App nicht gefunden."
            }
            "tippen" -> {
                val id = (a["id"] as? Number)?.toInt() ?: a["id"].toString().toIntOrNull() ?: -1
                when {
                    screen == "wa_list" && id == 3 -> { screen = "wa_chat_ali"; "Auf [3] getippt." }
                    screen == "wa_chat_ali_typed" && id == 4 -> { screen = "wa_chat_ali_sent"; sent = true; "Auf [4] getippt." }
                    screen == "ol_inbox" && id == 2 -> { screen = "ol_mail_seb"; "Auf [2] getippt." }
                    screen == "ol_inbox" && id == 3 -> { screen = "ol_mail_ing"; "Auf [3] getippt." }
                    screen.startsWith("ol_mail") && id == 1 -> { screen = "ol_inbox"; "Auf [1] getippt." }
                    else -> "Auf [$id] getippt."
                }
            }
            "text_eingeben" -> {
                if (screen.startsWith("wa_chat_ali")) { typed = (a["text"] ?: "").toString(); screen = "wa_chat_ali_typed" }
                if (a["absenden"] == true && screen == "wa_chat_ali_typed") { screen = "wa_chat_ali_sent"; sent = true }
                "Text eingegeben."
            }
            "bestaetigen" -> "Lirim hat JA getippt. Du darfst fortfahren."
            "bildschirm_lesen" -> "ok"
            "taste" -> { if (s.contains("zurueck") && screen.startsWith("wa_chat")) screen = "wa_list"
                if (s.contains("zurueck") && screen.startsWith("ol_mail")) screen = "ol_inbox"; "Taste gedrückt." }
            else -> "ok"
        }
    }
}

fun main(args: Array<String>) {
    val toolsJson = org.json.JSONArray(File(args[2]).readText())
    val base = (0 until toolsJson.length()).map { toolsJson.getJSONObject(it).toString() }
    val extra = listOf(
        """{"name":"schritt_erledigt","description":"Der aktuelle Planschritt ist erledigt. Weiter zum nächsten.","parameters":{"type":"object","properties":{"ergebnis":{"type":"string","description":"kurz, was herauskam"}}}}""",
        """{"name":"notiz","description":"Merkt sich eine Information für diesen Auftrag (z. B. Inhalt einer Mail, eine Uhrzeit, einen Preis).","parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}""",
        """{"name":"plan_aendern","description":"Ersetzt die restlichen Schritte, wenn der Plan nicht passt.","parameters":{"type":"object","properties":{"schritte":{"type":"string","description":"neue Schritte, durch ; getrennt"}},"required":["schritte"]}}""",
    )
    val specs = base + extra
    val params = specs.associate { sp ->
        val o = org.json.JSONObject(sp); val names = mutableListOf<String>()
        o.optJSONObject("parameters")?.optJSONArray("required")?.let { r -> for (i in 0 until r.length()) names.add(r.getString(i)) }
        o.optJSONObject("parameters")?.optJSONObject("properties")?.keys()?.forEach { if (it !in names) names.add(it) }
        o.getString("name") to names.toList()
    }
    val tools = specs.map { j -> tool(object : OpenApiTool { override fun getToolDescriptionJsonString() = j; override fun execute(p: String) = "{}" }) }
    val system = """
Du bist Jarvis, Lirims persönlicher KI-Assistent auf seinem Samsung Galaxy S25. Du läufst lokal auf dem Handy.
Antworte immer auf Deutsch (Schweiz, «ss» statt «ß»), kurz, natürlich, souverän, mit feinem Humor. Kein Markdown.
Du bedienst das Handy mit Werkzeugen. Die Bildschirmliste zeigt Elemente als [Nummer]; tippen und text_eingeben brauchen diese Nummer.
Regeln:
- Ist keine Aktion nötig (Frage, Plaudern, Erklärung): antworte direkt mit normalem Text, ohne Werkzeug.
- Sonst rufe genau EIN Werkzeug auf. Apps öffnen, lesen, scrollen und Wecker stellen geht ohne Rückfrage.
- Nur vor Senden, Kaufen, Bezahlen oder Löschen zuerst bestaetigen. anrufen fragt selbst nach.
- Personen wie «meine Frau» oder «Baba» löst du mit dem Hintergrundwissen auf.
- Arbeite den Plan ab. Ist ein Schritt erledigt: schritt_erledigt. Wichtige Infos unterwegs mit notiz festhalten. Am Ende fertig mit Zusammenfassung.
- Wenn eine Aktion nichts bewirkt: etwas anderes versuchen (scrollen, zurück, anderes Element).
- Nie Passwörter oder PINs.

Hintergrundwissen über Lirim:
- Shukrije ist Lirims Frau
- Ali = Ali Bellikaya, Teamleiter
""".trimIndent()

    println("=== Fuzzy")
    for ((q, l) in listOf("gruya" to "Gruja", "gruja" to "Gruja Demiri", "schukrie" to "Shukrije", "ali" to "Ali Bellikaya", "baba" to "Baba Swisscom", "gruya" to "Grubenmann AG", "sebastian" to "Sebastian Lorenzen", "ingmar" to "Ingrid Muster"))
        println("  $q ~ $l = ${ch.lirim.jarvis.core.Fuzzy.score(q, l)}")
    println("=== Personen")
    val facts = listOf("gruya ist meine Frau", "Baba = Kontakt «Baba Swisscom» (Vater)", "Mein Chef ist Sebastian Lorenzen", "Ali ist mein Teamleiter")
    println("  Relationen: ${ch.lirim.jarvis.core.People.relations(facts)}")
    for (t in listOf("Ruf meine Frau an", "ruf meiner frau an", "Schreib meinem Chef", "Ruf Baba an", "ruf meinen Teamleiter an", "Ruf Ali an", "meine Frau"))
        println("  «$t» → ${ch.lirim.jarvis.core.People.resolve(t, facts)}   chatOnly=${TaskLogic.isChatOnly(t)}")
    println("=== Router")
    for (c in listOf("Wie geht es dir?", "Öffne WhatsApp", "Ruf meine Frau an", "Stell einen Wecker um 7",
        "Schreib Ali auf WhatsApp, dass ich 10 Minuten später komme", "Fass meine ungelesenen Outlook-Mails zusammen",
        "Such mir die nächste Verbindung nach Regensdorf"))
        println("  ${if (TaskLogic.isComplex(c)) "KOMPLEX" else "einfach"}  $c")

    Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
    if (args.size > 3) return
    for ((label, path) in listOf("E4B" to args[1], "E2B" to args[0])) {
        Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = 8192, cacheDir = "/tmp")).use { e ->
            e.initialize()
            for (cmd in listOf("Schreib Ali auf WhatsApp, dass ich 10 Minuten später komme", "Fass meine ungelesenen Outlook-Mails zusammen")) {
                println("\n=== [$label] $cmd")
                val t0 = System.currentTimeMillis()
                val planText = e.createConversation(ConversationConfig(systemInstruction = Contents.of(TaskLogic.PLAN_SYSTEM),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6))).use { it.sendMessage(TaskLogic.planPrompt(cmd, "")).toString() }
                val plan = TaskLogic.parsePlan(planText).toMutableList()
                println("PLAN: " + plan.mapIndexed { i, p -> "${i + 1}. $p" }.joinToString(" | "))
                val sim = Sim()
                val st = TaskLogic.State(cmd, plan, screen = sim.show())
                var result = "ABBRUCH"
                var textStreak = 0
                var since = 0
                val counts = HashMap<String, Int>()
                for (step in 1..25) {
                    val m = e.createConversation(ConversationConfig(systemInstruction = Contents.of(system), tools = tools,
                        automaticToolCalling = false, samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3))).use { it.sendMessage(TaskLogic.execPrompt(st)) }
                    var name = m.toolCalls.firstOrNull()?.name; var a: Map<String, Any?> = m.toolCalls.firstOrNull()?.arguments ?: emptyMap()
                    if (name == null) ToolTextParser.parse(m.toString(), params)?.let { name = it.name; a = it.args }
                    st.hint = ""
                    if (name == null) {
                        val t = ToolTextParser.cleanup(m.toString()); textStreak++
                        if (!TaskLogic.textMeansFinish(st, t, textStreak)) { println("  $step. (nur Text: ${t.take(60)}) → Hinweis"); st.hint = TaskLogic.textHint(st); continue }
                        result = "TEXT: " + t.take(250); break
                    }
                    textStreak = 0
                    println("  $step. $name $a")
                    val key = TaskLogic.actionKey(name!!, a, st.screen); val n = (counts[key] ?: 0) + 1; counts[key] = n
                    if (n >= 2 && name !in setOf("scrollen", "warten", "schritt_erledigt", "notiz")) {
                        st.hint = TaskLogic.repeatHint(name!!, n - 1); if (n >= 4) { result = "KREIS"; break }; continue
                    }
                    when (name) {
                        "fertig" -> { result = "FERTIG: ${a["nachricht"]}"; break }
                        "schritt_erledigt" -> { if (st.current >= plan.size) { result = "FERTIG(auto): " + TaskLogic.fallbackAnswer(st); break }
                            if (!TaskLogic.stepDoneAllowed(st, since)) { st.hint = TaskLogic.stepDoneRejected(st); println("     ↳ abgelehnt (noch nichts getan)"); continue }
                            since = 0; a["ergebnis"]?.toString()?.takeIf { it.isNotBlank() }?.let { st.notes.add(it) }; st.current++; st.history.add("✓ Schritt erledigt")
                            if (st.current >= plan.size) st.hint = "Alle Planschritte sind erledigt. Rufe jetzt fertig mit einer Zusammenfassung auf." }
                        "notiz" -> { since++; st.notes.add(a["text"].toString()); st.history.add("notiz gespeichert") }
                        "plan_aendern" -> { val neu = a["schritte"].toString().split(";").map { it.trim() }.filter { it.length > 2 }
                            while (plan.size > st.current) plan.removeAt(plan.size - 1); plan.addAll(neu) }
                        else -> { since++; val before = TaskLogic.fingerprint(st.screen); val r = sim.exec(name!!, a); st.screen = sim.show()
                            if (TaskLogic.fingerprint(st.screen) == before && name in setOf("tippen", "text_eingeben")) st.hint = "Deine letzte Aktion hat am Bildschirm nichts verändert."
                            st.history.add("$step. $name $a → $r") }
                    }
                }
                println("ERGEBNIS: $result")
                println("WhatsApp gesendet: ${sim.sent} (Text: «${sim.typed}»)   Notizen: ${st.notes}   Zeit: ${(System.currentTimeMillis() - t0) / 1000}s")
            }
        }
    }
}
