import com.google.ai.edge.litertlm.*
import java.io.File
import ch.lirim.jarvis.core.ToolTextParser

/** Simuliert genau einen Schritt wie in der App (gleiche Werkzeugliste, ähnlich langer Prompt). */
fun main(args: Array<String>) {
    val toolsJson = File(args[1]).readText()
    val a = org.json.JSONArray(toolsJson)
    val specs = (0 until a.length()).map { a.getJSONObject(it).toString() }
    println("Werkzeuge: ${specs.size}")
    val tools = specs.map { j -> tool(object : OpenApiTool { override fun getToolDescriptionJsonString() = j; override fun execute(p: String) = "{}" }) }
    val memory = "\n\n## Dein Gedächtnis über Lirim\n" + listOf("- Baba = Kontakt «Baba Swisscom» (Vater)", "- Shukrije ist Lirims Frau", "- Arbeitet als Projektleiter bei Migros Online", "- Wohnt in Pratteln", "- Mag kurze, direkte Antworten").joinToString("\n")
    val system = """
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
Jetzt: Sonntag, 22. November 2026, 00:10.
""".trimIndent() + memory
    Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
    val base = system.substringBefore("\n\n## Dein")
    val variants = listOf("APP_WIE_NEU" to Pair(base + "\n\nHintergrundwissen über Lirim:\n" + memory.trim(), ""))
    val known = specs.map { Regex("\"name\":\"([a-z_]+)\"").find(it)!!.groupValues[1] }.toSet()
    Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), maxNumTokens = 8192, cacheDir = "/tmp")).use { e ->
        e.initialize()
        for ((vn, v) in variants) {
            println("=== $vn")
            for (p in listOf("Was kannst du für mich tun?", "Öffne WhatsApp", "Stell einen Wecker um 7 Uhr", "Wie geht es dir?", "Was gibt es Neues?", "Schreib Shukrije auf WhatsApp, dass ich später komme", "Erzähl mir einen Witz")) {
                try {
                    e.createConversation(ConversationConfig(systemInstruction = Contents.of(v.first), tools = tools,
                        automaticToolCalling = false, samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3))).use { c ->
                        val m = c.sendMessage(v.second + "Auftrag von Lirim: $p\n\nWas ist der nächste Schritt?")
                        val call = m.toolCalls.firstOrNull()?.let { "${it.name} ${it.arguments}" }
                            ?: ToolTextParser.parse(m.toString(), known)?.let { "(Text→) ${it.name} ${it.args}" }
                            ?: "ANTWORT: " + ToolTextParser.cleanup(m.toString()).replace("\n", " ").take(200)
                        println("[$p] => $call")
                    }
                } catch (ex: Throwable) { println("FEHLER [$p] ${ex.message?.take(200)}") }
            }
        }
    }
}
