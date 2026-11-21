import com.google.ai.edge.litertlm.*

fun spec(name: String, desc: String, props: String, req: String) =
    """{"name":"$name","description":"$desc","parameters":{"type":"object","properties":$props,"required":[$req]}}"""

fun main(args: Array<String>) {
    val tools = listOf(
        spec("app_oeffnen", "Öffnet eine installierte App per Name.", """{"name":{"type":"string","description":"App-Name"}}""", "\"name\""),
        spec("tippen", "Tippt auf ein Element aus der Bildschirmliste.", """{"id":{"type":"integer","description":"Nummer"}}""", "\"id\""),
        spec("text_eingeben", "Schreibt Text in ein Eingabefeld.", """{"id":{"type":"integer"},"text":{"type":"string"},"absenden":{"type":"boolean"}}""", "\"text\""),
        spec("wecker_stellen", "Stellt einen Wecker.", """{"stunde":{"type":"integer"},"minute":{"type":"integer"}}""", "\"stunde\",\"minute\""),
        spec("fertig", "Beendet den Auftrag mit einer kurzen gesprochenen Antwort.", """{"nachricht":{"type":"string"}}""", "\"nachricht\""),
        spec("claude_fragen", "Nur wenn die Aufgabe zu schwierig ist.", """{"grund":{"type":"string"}}""", ""),
    ).map { j -> tool(object : OpenApiTool { override fun getToolDescriptionJsonString() = j; override fun execute(p: String) = "{}" }) }
    val system = "Du bist Jarvis, Lirims Assistent auf dem Handy. Antworte auf Deutsch, kurz. Rufe pro Antwort genau EIN Werkzeug auf. Ist es nur eine Frage, rufe fertig mit der Antwort auf."
    Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
    val t0 = System.currentTimeMillis()
    Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = "/tmp")).use { e ->
        e.initialize()
        println("Geladen in ${(System.currentTimeMillis() - t0) / 1000}s")
        val tests = listOf(
            "Auftrag von Lirim: Öffne WhatsApp\n\nWas ist der nächste Schritt?",
            "Auftrag von Lirim: Stell mir einen Wecker um 6 Uhr 30\n\nWas ist der nächste Schritt?",
            "Auftrag von Lirim: Wie geht es dir?\n\nWas ist der nächste Schritt?",
            "Auftrag von Lirim: Schreib Ali auf WhatsApp, dass ich später komme\n\nBisher erledigt:\n1. app_oeffnen {\"name\":\"WhatsApp\"} → App WhatsApp geöffnet.\n\nAktueller Bildschirm:\nApp im Vordergrund: WhatsApp\n[1] TextView \"WhatsApp\"\n[2] ImageButton (Suchen) [klickbar]\n[3] View \"Ali\" [klickbar] @540,600\n[4] View \"Shukrije\" [klickbar] @540,800\n\nWas ist der nächste Schritt?",
        )
        for (p in tests) {
            val s = System.currentTimeMillis()
            e.createConversation(ConversationConfig(systemInstruction = Contents.of(system), tools = tools,
                automaticToolCalling = false, samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3))).use { c ->
                val m = c.sendMessage(p)
                println("---\nPROMPT: ${p.lines().first()}\nTEXT: ${m.toString().take(200)}\nCALLS: ${m.toolCalls}\nZeit: ${(System.currentTimeMillis() - s) / 1000}s")
            }
        }
    }
}
