import com.google.ai.edge.litertlm.*
import java.io.File

/** Simuliert genau einen Schritt wie in der App (gleiche Werkzeugliste, ähnlich langer Prompt). */
fun main(args: Array<String>) {
    val toolsJson = File(args[1]).readText()
    val a = org.json.JSONArray(toolsJson)
    val specs = (0 until a.length()).map { a.getJSONObject(it).toString() }
    println("Werkzeuge: ${specs.size}")
    val tools = specs.map { j -> tool(object : OpenApiTool { override fun getToolDescriptionJsonString() = j; override fun execute(p: String) = "{}" }) }
    val memory = "\n\n## Dein Gedächtnis über Lirim\n" + (1..25).joinToString("\n") { "- Erkenntnis $it: Lirim arbeitet als Projektleiter bei Migros Online und mag effiziente Abläufe." }.take(1500)
    val system = """
Du bist Jarvis, Lirims persönlicher KI-Assistent auf seinem Samsung Galaxy S25. Du läufst lokal auf dem Handy.
Antworte immer auf Deutsch (Schweiz, «ss» statt «ß»), kurz, natürlich, souverän, mit feinem Humor. Kein Markdown.
Du bedienst das Handy mit Werkzeugen: app_oeffnen, bildschirm_lesen, tippen (id aus der Bildschirmliste), text_eingeben, scrollen, taste, warten.
Regeln: Rufe pro Antwort genau EIN Werkzeug auf. Vor Senden/Kaufen/Löschen/Anrufen immer zuerst bestaetigen. Nie Passwörter oder PINs.
Wenn der Auftrag erledigt ist oder es nur eine Frage/Unterhaltung ist: rufe fertig mit einer kurzen Antwort auf.
Merke dir Dauerhaftes über Lirim mit merken.
Jetzt: Sonntag, 22. November 2026, 00:10.
""".trimIndent() + memory
    Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
    val base = system.substringBefore("\n\n## Dein")
    val variants = listOf(
        "A_ohne_Gedaechtnis" to Pair(base, ""),
        "B_mit_Gedaechtnis_im_System" to Pair(system, ""),
        "C_Gedaechtnis_im_Prompt" to Pair(base, memory + "\n\n"),
    )
    Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), maxNumTokens = 8192, cacheDir = "/tmp")).use { e ->
        e.initialize()
        for ((vn, v) in variants) {
            println("=== $vn")
            for (p in listOf("Was kannst du für mich tun?", "Öffne WhatsApp", "Stell einen Wecker um 7 Uhr")) {
                try {
                    e.createConversation(ConversationConfig(systemInstruction = Contents.of(v.first), tools = tools,
                        automaticToolCalling = false, samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3))).use { c ->
                        val m = c.sendMessage(v.second + "Auftrag von Lirim: $p\n\nWas ist der nächste Schritt?")
                        println("[$p] CALLS=${m.toolCalls} TEXT=${m.toString().replace("\n", " ").take(220)}")
                    }
                } catch (ex: Throwable) { println("FEHLER [$p] ${ex.message?.take(200)}") }
            }
        }
    }
}
