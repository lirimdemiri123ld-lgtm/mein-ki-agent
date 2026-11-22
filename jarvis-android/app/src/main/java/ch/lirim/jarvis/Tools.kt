package ch.lirim.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject

class ToolOutput(val text: String, val imageB64: String? = null)

/** Werkzeuge, die Claude aufrufen kann. */
object Tools {
    @Volatile private var lastYes = 0L

    private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "input_schema", JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray(required))
            )

    fun definitions(webSearch: Boolean): JSONArray {
        val a = JSONArray()
        a.put(tool("app_oeffnen", "Öffnet eine installierte App per Name (z. B. 'WhatsApp', 'Spotify', 'Google Maps'). Danach kommt der neue Bildschirm zurück.",
            JSONObject().put("name", prop("string", "App-Name oder Paketname")), listOf("name")))
        a.put(tool("apps_auflisten", "Listet alle installierten Apps mit Namen auf."))
        a.put(tool("bildschirm_lesen", "Liest den aktuellen Bildschirm als nummerierte Liste von Elementen [id]."))
        a.put(tool("screenshot", "Macht ein Bild vom Bildschirm. Nutzen, wenn die Elementliste leer/unklar ist."))
        a.put(tool("tippen", "Tippt auf ein Element: per Nummer (id) aus der Bildschirmliste ODER per sichtbarem Text.",
            JSONObject().put("id", prop("integer", "Nummer des Elements")).put("text", prop("string", "sichtbarer Text, z. B. Name eines Chats"))))
        a.put(tool("tippen_xy", "Tippt auf Bildschirm-Koordinaten in echten Pixeln.",
            JSONObject().put("x", prop("number", "x in px")).put("y", prop("number", "y in px")), listOf("x", "y")))
        a.put(tool("lange_druecken", "Drückt lange auf ein Element.",
            JSONObject().put("id", prop("integer", "Nummer des Elements")), listOf("id")))
        a.put(tool("text_eingeben", "Schreibt Text in ein Eingabefeld (ersetzt den Inhalt). Ohne id: aktuell fokussiertes Feld.",
            JSONObject()
                .put("id", prop("integer", "Nummer des Eingabefelds (optional)"))
                .put("text", prop("string", "Der Text"))
                .put("absenden", prop("boolean", "Danach Enter/Suchen drücken")),
            listOf("text")))
        a.put(tool("scrollen", "Scrollt den Inhalt.",
            JSONObject()
                .put("richtung", JSONObject().put("type", "string").put("enum", JSONArray(listOf("runter", "hoch", "links", "rechts"))))
                .put("id", prop("integer", "Optional: scrollbares Element")),
            listOf("richtung")))
        a.put(tool("wischen", "Wischt von (x1,y1) nach (x2,y2) in Pixeln.",
            JSONObject()
                .put("x1", prop("number", "")).put("y1", prop("number", ""))
                .put("x2", prop("number", "")).put("y2", prop("number", ""))
                .put("dauer_ms", prop("integer", "Dauer, Standard 300")),
            listOf("x1", "y1", "x2", "y2")))
        a.put(tool("taste", "Drückt eine Systemtaste.",
            JSONObject().put("taste", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("zurueck", "home", "letzte_apps", "benachrichtigungen", "schnelleinstellungen", "enter")))),
            listOf("taste")))
        a.put(tool("warten", "Wartet einige Sekunden (z. B. bis etwas lädt) und liest dann den Bildschirm.",
            JSONObject().put("sekunden", prop("integer", "1–15")), listOf("sekunden")))
        a.put(tool("url_oeffnen", "Öffnet eine Webadresse oder einen Link (auch Deep-Links wie geo:, mailto:, whatsapp:).",
            JSONObject().put("url", prop("string", "Adresse")), listOf("url")))
        a.put(tool("wecker_stellen", "Stellt einen Wecker.",
            JSONObject().put("stunde", prop("integer", "0–23")).put("minute", prop("integer", "0–59"))
                .put("beschriftung", prop("string", "optional")),
            listOf("stunde", "minute")))
        a.put(tool("timer_stellen", "Stellt einen Timer.",
            JSONObject().put("sekunden", prop("integer", "Dauer")).put("beschriftung", prop("string", "optional")),
            listOf("sekunden")))
        a.put(tool("anrufen_vorbereiten", "Öffnet die Telefon-App mit der Nummer (Anruf muss danach noch angetippt werden).",
            JSONObject().put("nummer", prop("string", "Telefonnummer")), listOf("nummer")))
        a.put(tool("bestaetigen", "Fragt Lirim um Erlaubnis (Ja/Nein) VOR heiklen Aktionen: senden, kaufen, bezahlen, buchen, löschen, posten, anrufen.",
            JSONObject().put("frage", prop("string", "Kurze Frage, z. B. 'Soll ich die Nachricht an Ali senden?'")), listOf("frage")))
        a.put(tool("frage_stellen", "Stellt Lirim eine Rückfrage, wenn Infos fehlen. Beendet den Durchgang; die Antwort kommt als nächster Auftrag.",
            JSONObject().put("frage", prop("string", "Die Frage")), listOf("frage")))
        a.put(tool("fertig", "Beendet den Auftrag mit einer kurzen gesprochenen Antwort/Zusammenfassung.",
            JSONObject().put("nachricht", prop("string", "1–2 Sätze")), listOf("nachricht")))
        a.put(tool("merken", "Speichert dauerhaft etwas über Lirim (Person/Spitzname eines Kontakts, Vorliebe, Routine, Adresse, wiederkehrende Info). Kurz und eindeutig formulieren.",
            JSONObject()
                .put("text", prop("string", "z. B. 'Baba = Kontakt «Baba Swisscom» (Vater)'"))
                .put("kategorie", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("person", "vorliebe", "routine", "fakt")))),
            listOf("text", "kategorie")))
        a.put(tool("vergessen", "Löscht gespeicherte Einträge, die diesen Text enthalten.",
            JSONObject().put("text", prop("string", "Suchtext")), listOf("text")))
        a.put(tool("erfahrung_speichern", "Speichert einen kurzen Tipp, wie man in einer App etwas erledigt oder welchen Fehler man vermeiden soll. Wird beim nächsten Öffnen der App automatisch angezeigt.",
            JSONObject()
                .put("app", prop("string", "App-Name, z. B. 'WhatsApp'"))
                .put("tipp", prop("string", "z. B. 'Neuer Chat: Stift-Symbol unten rechts, dann Name ins Suchfeld'")),
            listOf("app", "tipp")))
        a.put(tool("kontakt_suchen", "Sucht einen Kontakt im Telefonbuch (Name → Nummer).",
            JSONObject().put("name", prop("string", "Name oder Teil davon")), listOf("name")))
        a.put(tool("anrufen", "Ruft einen Kontakt (Name) oder eine Nummer an. Fragt selbst nach Bestätigung.",
            JSONObject().put("wen", prop("string", "Kontaktname oder Nummer")), listOf("wen")))
        a.put(tool("nachrichten_lesen", "Liest die neuesten Benachrichtigungen (WhatsApp, E-Mail, SMS, andere Apps) mit Absender und Text."))
        a.put(tool("live_daten", "Aktuelles Wetter, Börsenkurse und Handy-Status (Akku, Speicher, RAM)."))
        a.put(tool("projekt_aktualisieren", "Legt ein Projekt von Lirim an oder aktualisiert Fortschritt/Status (erscheint im HUD). fortschritt -1 entfernt das Projekt.",
            JSONObject()
                .put("name", prop("string", "Projektname"))
                .put("fortschritt", prop("integer", "0–100, optional"))
                .put("status", prop("string", "kurzer Status, optional")),
            listOf("name")))
        if (webSearch) {
            a.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3))
        }
        return a
    }

    private val screenTools = setOf(
        "bildschirm_lesen", "screenshot", "tippen", "tippen_xy", "lange_druecken",
        "text_eingeben", "scrollen", "wischen", "taste"
    )

    fun execute(ctx: Context, name: String, input: JSONObject): ToolOutput {
        val svc = JarvisService.instance
        if (name in screenTools && svc == null) {
            return ToolOutput("Fehler: Die Bedienungshilfe 'Jarvis' ist nicht eingeschaltet. Sag Lirim, er soll sie in der Jarvis-App einschalten.")
        }
        val c: Context = svc ?: ctx
        return try {
            when (name) {
                "app_oeffnen" -> {
                    val appName = input.getString("name")
                    val res = after(svc, Apps.open(c, appName), 2000)
                    val tips = Memory.lessonsFor(c, appName)
                    if (tips.isEmpty()) res
                    else ToolOutput(res.text + "\n\nDeine gespeicherten Erfahrungen zu dieser App:\n" + tips)
                }
                "kontakt_suchen" -> ToolOutput(Contacts.describe(c, input.optString("name")))
                "anrufen" -> {
                    val wen = input.optString("wen").ifBlank { input.optString("name").ifBlank { input.optString("nummer") } }
                    if (wen.isBlank()) return ToolOutput("Wen soll ich anrufen?")
                    callWithChoice(c, svc, wen)
                }
                "nachrichten_lesen" -> ToolOutput(NotificationStore.asText(20) +
                    if (!NotificationStore.hasAccess(c)) "\n(Hinweis: Benachrichtigungszugriff ist nicht erteilt.)" else "")
                "live_daten" -> {
                    try { Weather.fetch(c) } catch (_: Exception) {}
                    try { Stocks.fetch(c) } catch (_: Exception) {}
                    val s = SystemStats.snapshot(c)
                    ToolOutput("Wetter: ${Weather.summary()}\nBörse: ${Stocks.summary()}\n" +
                        "Handy: Akku ${s.battery}%${if (s.charging) " lädt" else ""}, ${"%.0f".format(s.batteryTemp)}°C, RAM ${s.ramGb}, Speicher ${s.storageText}")
                }
                "projekt_aktualisieren" -> ToolOutput(Memory.updateProject(c, input.getString("name"),
                    if (input.has("fortschritt")) input.getInt("fortschritt") else null,
                    input.optString("status", "")))
                "merken" -> ToolOutput(Memory.remember(c, input.getString("text"), input.optString("kategorie", "fakt")))
                "vergessen" -> ToolOutput(Memory.forget(c, input.getString("text")))
                "erfahrung_speichern" -> ToolOutput(Memory.learn(c, input.getString("app"), input.getString("tipp")))
                "apps_auflisten" -> ToolOutput(Apps.list(c))
                "bildschirm_lesen" -> ToolOutput(svc!!.readScreen())
                "screenshot" -> {
                    val img = svc!!.screenshot()
                    if (img == null) ToolOutput("Screenshot nicht möglich (diese App blockiert Bildschirmaufnahmen).")
                    else ToolOutput(svc.lastShotInfo, img)
                }
                "tippen" -> {
                    val id = input.opt("id").let { (it as? Number)?.toInt() ?: it?.toString()?.toIntOrNull() }
                    val txt = input.optString("text").takeIf { it.isNotBlank() && it != "null" }
                    when {
                        id != null -> after(svc, svc!!.tap(id))
                        txt != null -> after(svc, svc!!.tapText(txt))
                        else -> ToolOutput("Fehler: Gib id (Nummer aus der Liste) oder text (sichtbarer Text) an.")
                    }
                }
                "tippen_xy" -> {
                    val ok = svc!!.tapXY(input.getDouble("x").toFloat(), input.getDouble("y").toFloat())
                    after(svc, if (ok) "Getippt." else "Tippen fehlgeschlagen.")
                }
                "lange_druecken" -> after(svc, svc!!.longPress(input.getInt("id")))
                "text_eingeben" -> {
                    val id = if (input.has("id")) input.getInt("id") else null
                    after(svc, svc!!.typeText(id, input.getString("text"), input.optBoolean("absenden", false)))
                }
                "scrollen" -> {
                    val id = if (input.has("id")) input.getInt("id") else null
                    after(svc, svc!!.scroll(input.optString("richtung", "runter"), id), 700)
                }
                "wischen" -> {
                    val ok = svc!!.swipe(
                        input.getDouble("x1").toFloat(), input.getDouble("y1").toFloat(),
                        input.getDouble("x2").toFloat(), input.getDouble("y2").toFloat(),
                        input.optLong("dauer_ms", 300)
                    )
                    after(svc, if (ok) "Gewischt." else "Wischen fehlgeschlagen.")
                }
                "taste" -> after(svc, svc!!.press(input.getString("taste")))
                "warten" -> {
                    val s = input.optInt("sekunden", 2).coerceIn(1, 15)
                    Thread.sleep(s * 1000L)
                    after(svc, "$s Sekunden gewartet.", 0)
                }
                "url_oeffnen" -> {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(input.getString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(i)
                    after(svc, "Link geöffnet.", 2000)
                }
                "wecker_stellen" -> {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, input.getInt("stunde"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, input.getInt("minute"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("beschriftung", "Jarvis"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(i)
                    ToolOutput("Wecker gestellt.")
                }
                "timer_stellen" -> {
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, input.getInt("sekunden"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("beschriftung", "Jarvis"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(i)
                    ToolOutput("Timer gestellt.")
                }
                "anrufen_vorbereiten" -> {
                    val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + input.getString("nummer")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(i)
                    after(svc, "Telefon-App mit Nummer geöffnet.", 1500)
                }
                "bestaetigen" -> {
                    val q = input.getString("frage")
                    val ov = svc?.overlay
                        ?: return ToolOutput("Bestätigung nicht möglich (Bedienungshilfe aus). Aktion NICHT ausführen.")
                    Speaker.say(q)
                    Agent.log("❓ $q")
                    val ok = ov.confirm(q)
                    if (ok) lastYes = System.currentTimeMillis()
                    Agent.log(if (ok) "✅ Ja" else "❌ Nein")
                    ToolOutput(if (ok) "Lirim hat JA getippt. Du darfst fortfahren." else "Lirim hat NEIN getippt oder nicht reagiert. Aktion NICHT ausführen.")
                }
                else -> ToolOutput("Unbekanntes Werkzeug: $name")
            }
        } catch (e: Exception) {
            ToolOutput("Fehler bei $name: ${e.message}")
        }
    }

    /**
     * Anrufen mit Auswahl: Gibt es mehrere passende Kontakte (z. B. zwei «Gruja»), fragt Jarvis
     * nacheinander per Stimme – Schweizer Nummern zuerst. Die Wahl wird gemerkt.
     */
    private fun callWithChoice(c: Context, svc: JarvisService?, wen: String): ToolOutput {
        val digits = wen.filter { it.isDigit() || it == '+' }
        val candidates = mutableListOf<Contacts.Hit>()
        if (digits.length >= 6) candidates.add(Contacts.Hit(wen, digits))
        else {
            Prefs.chosenNumber(c, wen)?.let { (n, num) -> candidates.add(Contacts.Hit(n, num)) }
            // Nummer im Gedächtnis («Gruja = +41 79 …»)?
            Memory.factTexts(c).filter { f -> ch.lirim.jarvis.core.Fuzzy.score(wen, f) >= 70 }
                .mapNotNull { f -> Regex("""(\+|00)?\d[\d ]{7,}\d""").find(f)?.value?.let { Contacts.Hit(wen, it.replace(" ", "")) } }
                .forEach { candidates.add(it) }
            val scored = Contacts.searchScored(c, wen)
            val best = scored.firstOrNull()?.second ?: 0
            scored.filter { it.second >= best - 25 }.forEach { candidates.add(it.first) }
        }
        val list = candidates.distinctBy { it.number.filter(Char::isDigit).takeLast(9) }.take(3)
        if (list.isEmpty()) return ToolOutput(if (!Contacts.canRead(c)) "Kein Zugriff auf Kontakte – bitte unter ZUGRIFF erlauben." else "Kein Kontakt «$wen» gefunden.")
        val ov = svc?.overlay
        for ((i, h) in list.withIndex()) {
            val where = if (Contacts.isSwiss(h.number)) "" else " (Ausland)"
            val spoken = "${h.name}$where, ${h.number.takeLast(4).toList().joinToString(" ")} am Ende"
            if (ov != null && (list.size > 1 || System.currentTimeMillis() - lastYes > 90_000)) {
                val q = if (list.size > 1) "${if (i == 0) "Ich habe ${list.size} passende Kontakte. " else ""}Soll ich $spoken anrufen?"
                        else "Soll ich ${h.name} anrufen?"
                Speaker.say(q)
                Agent.log("❓ ${h.name} (${h.number}) anrufen?")
                if (!ov.confirm("${h.name} (${h.number}) anrufen?")) { Agent.log("❌ Nein"); continue }
                Agent.log("✅ Ja")
            }
            if (list.size > 1) Prefs.setChosenNumber(c, wen, h.name, h.number)
            return after(svc, Contacts.call(c, h.number).replace(h.number, "${h.name}, ${h.number}"), 1500)
        }
        return ToolOutput("Lirim hat NEIN gesagt. Nicht anrufen. " +
            if (list.size > 1) "Frag ihn nach dem genauen Namen oder der Nummer." else "")
    }

    private fun after(svc: JarvisService?, msg: String, delayMs: Long = 900): ToolOutput {
        if (delayMs > 0) Thread.sleep(delayMs)
        val screen = svc?.readScreen() ?: "(Bildschirm nicht lesbar – Bedienungshilfe ist aus.)"
        return ToolOutput("$msg\n\nAktueller Bildschirm:\n$screen")
    }
}
