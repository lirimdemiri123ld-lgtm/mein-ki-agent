package ch.lirim.jarvis2

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock

/**
 * Die Werkzeuge, die Claude über MCP auf dem Handy aufrufen kann.
 * Jarvis 2.0 denkt nicht selbst – es führt nur aus, was Claude schickt.
 */
class PhoneTools(private val svc: JarvisService) : ToolHost {

    override fun log(line: String) = ActionLog.add(line)

    // ------------------------------------------------------------------ Beschreibung für Claude

    private fun p(type: String, desc: String) = jobj("type" to type, "description" to desc)
    private fun en(desc: String, vararg values: String) = jobj("type" to "string", "description" to desc, "enum" to values.toMutableList())

    private fun tool(name: String, title: String, desc: String, props: Map<String, Any?> = emptyMap(),
                     required: List<String> = emptyList(), readOnly: Boolean = false) = jobj(
        "name" to name,
        "title" to title,
        "description" to desc,
        "inputSchema" to jobj("type" to "object", "properties" to LinkedHashMap(props), "required" to required.toMutableList()),
        "annotations" to jobj("title" to title, "readOnlyHint" to readOnly, "openWorldHint" to false),
    )

    private val defs: List<JMap> = listOf(
        tool("apps_auflisten", "Apps auflisten", "Listet alle Apps auf Lirims Handy mit Namen und Paketnamen.", readOnly = true),
        tool("app_oeffnen", "App öffnen", "Öffnet auf Lirims Handy eine installierte App per Name (z. B. 'WhatsApp', 'Spotify', 'SBB Mobile'). Gibt danach den neuen Bildschirm zurück.",
            mapOf("name" to p("string", "App-Name oder Paketname")), listOf("name")),
        tool("bildschirm_lesen", "Bildschirm lesen", "Liest den aktuellen Bildschirm von Lirims Handy als nummerierte Liste von Elementen [id] mit Text und Eigenschaften (k = klickbar).", readOnly = true),
        tool("screenshot", "Screenshot", "Macht ein Bild vom Bildschirm. Nutzen, wenn die Elementliste leer oder unklar ist (z. B. Spiele, Karten, Bilder).", readOnly = true),
        tool("tippen", "Tippen", "Tippt auf ein Element: per Nummer (id) aus bildschirm_lesen ODER per sichtbarem Text.",
            mapOf("id" to p("integer", "Nummer des Elements"), "text" to p("string", "sichtbarer Text, z. B. Name eines Chats"))),
        tool("tippen_xy", "Auf Koordinaten tippen", "Tippt auf Bildschirm-Koordinaten in echten Pixeln.",
            mapOf("x" to p("number", "x in px"), "y" to p("number", "y in px")), listOf("x", "y")),
        tool("lange_druecken", "Lange drücken", "Drückt lange auf ein Element (z. B. für Kontextmenüs).",
            mapOf("id" to p("integer", "Nummer des Elements")), listOf("id")),
        tool("text_eingeben", "Text eingeben", "Schreibt Text in ein Eingabefeld (ersetzt den Inhalt). Ohne id: aktuell fokussiertes Feld.",
            mapOf("text" to p("string", "Der Text"), "id" to p("integer", "Nummer des Eingabefelds (optional)"),
                "absenden" to p("boolean", "Danach Enter/Suchen drücken")), listOf("text")),
        tool("scrollen", "Scrollen", "Scrollt den Inhalt.",
            mapOf("richtung" to en("Richtung", "runter", "hoch", "links", "rechts"), "id" to p("integer", "Optional: scrollbares Element")),
            listOf("richtung")),
        tool("wischen", "Wischen", "Wischt von (x1,y1) nach (x2,y2) in Pixeln.",
            mapOf("x1" to p("number", ""), "y1" to p("number", ""), "x2" to p("number", ""), "y2" to p("number", ""),
                "dauer_ms" to p("integer", "Dauer, Standard 300")), listOf("x1", "y1", "x2", "y2")),
        tool("taste", "Systemtaste", "Drückt eine Systemtaste.",
            mapOf("taste" to en("Taste", "zurueck", "home", "letzte_apps", "benachrichtigungen", "schnelleinstellungen", "enter")), listOf("taste")),
        tool("warten", "Warten", "Wartet einige Sekunden (z. B. bis etwas lädt) und liest dann den Bildschirm.",
            mapOf("sekunden" to p("integer", "1–15")), listOf("sekunden"), readOnly = true),
        tool("url_oeffnen", "Link öffnen", "Öffnet eine Webadresse oder einen Deep-Link (https:, geo:, mailto:, tel:).",
            mapOf("url" to p("string", "Adresse")), listOf("url")),
        tool("kontakt_suchen", "Kontakt suchen", "Sucht einen Kontakt im Telefonbuch (Name → Nummer). Findet auch ungefähre Schreibweisen.",
            mapOf("name" to p("string", "Name oder Teil davon")), listOf("name"), readOnly = true),
        tool("whatsapp_senden", "WhatsApp senden (Handy)", "Sendet eine ECHTE WhatsApp-Nachricht von Lirims Handy an einen Kontakt (Name, z. B. 'Gruja') oder eine Nummer. " +
            "Immer verwenden, wenn Lirim sagt «schick/schreib X auf WhatsApp» – statt eines Nachrichtenentwurfs. " +
            "Öffnet den Chat, füllt den Text ein und tippt auf Senden. Mit senden=false wird nur vorbereitet.",
            mapOf("an" to p("string", "Kontaktname oder Telefonnummer"), "text" to p("string", "Nachricht"),
                "senden" to p("boolean", "Standard true; false = nur vorbereiten")), listOf("an", "text")),
        tool("kontaktbild_setzen", "Kontaktbild setzen (Handy)", "Setzt in einem Schritt das Profilbild (rundes Bild) eines Kontakts auf Lirims Handy – " +
            "IMMER statt durch die Kontakte-App zu klicken. Nimmt das NEUESTE Bild der Galerie (optional nur aus einem Ordner, z. B. 'WhatsApp'). " +
            "Ablauf bei «nimm das letzte Bild von X»: Bild in WhatsApp öffnen, oben auf Speichern (Pfeil) tippen, dann dieses Werkzeug aufrufen. " +
            "Mit nur_zeigen=true wird das Bild nur angezeigt und nichts geändert. Die Samsung-Profilkarte (grosses Hintergrundbild) kann Android nicht setzen.",
            mapOf("kontakt" to p("string", "Kontaktname, z. B. 'Nana Swisscom'"),
                "ordner" to p("string", "Optional: nur Bilder aus diesem Galerie-Ordner, z. B. 'WhatsApp'"),
                "nur_zeigen" to p("boolean", "true = nur Vorschau des gewählten Bilds, nichts ändern")), listOf("kontakt")),
        tool("anrufen", "Anrufen", "Startet auf Lirims Handy einen Anruf an einen Kontakt (Name) oder eine Nummer.",
            mapOf("wen" to p("string", "Kontaktname oder Nummer")), listOf("wen")),
        tool("nachrichten_lesen", "Benachrichtigungen lesen", "Liest die neuesten Benachrichtigungen (WhatsApp, E-Mail, SMS, andere Apps) mit Absender und Text.",
            mapOf("anzahl" to p("integer", "Wie viele, Standard 15")), readOnly = true),
        tool("wecker_stellen", "Wecker stellen", "Stellt einen Wecker.",
            mapOf("stunde" to p("integer", "0–23"), "minute" to p("integer", "0–59"), "beschriftung" to p("string", "optional")),
            listOf("stunde", "minute")),
        tool("timer_stellen", "Timer stellen", "Stellt einen Timer.",
            mapOf("sekunden" to p("integer", "Dauer in Sekunden"), "beschriftung" to p("string", "optional")), listOf("sekunden")),
    )

    override fun tools(): List<JMap> = defs

    // ------------------------------------------------------------------ Ausführen

    private val screenTools = setOf("bildschirm_lesen", "screenshot", "tippen", "tippen_xy", "lange_druecken",
        "text_eingeben", "scrollen", "wischen", "taste", "warten")

    override fun call(name: String, args: JMap): ToolResult {
        if (Prefs.paused(svc)) return ToolResult("Jarvis 2.0 ist pausiert. Lirim kann es in der App wieder einschalten.", isError = true)
        if (name in screenTools && name != "taste") {
            val fg = svc.foregroundPackage()
            if (Safety.blocked(fg)) return ToolResult(Safety.reason(Apps.labelFor(svc, fg)), isError = true)
        }
        return when (name) {
            "apps_auflisten" -> ToolResult(Apps.list(svc))
            "app_oeffnen" -> {
                val msg = Apps.open(svc, args.str("name"))
                if (msg.startsWith("App ") && msg.endsWith("geöffnet.")) after(msg, 2000) else ToolResult(msg, isError = true)
            }
            "bildschirm_lesen" -> ToolResult(svc.readScreen())
            "screenshot" -> {
                val img = svc.screenshot()
                if (img == null) ToolResult("Screenshot nicht möglich (diese App blockiert Bildschirmaufnahmen).", isError = true)
                else ToolResult(svc.lastShotInfo, img)
            }
            "tippen" -> {
                val id = if (args.has("id")) args.int("id", -1).takeIf { it > 0 } else null
                val txt = args.str("text").takeIf { it.isNotBlank() && it != "null" }
                when {
                    id != null -> after(svc.tap(id))
                    txt != null -> after(svc.tapText(txt))
                    else -> ToolResult("Gib id (Nummer aus der Liste) oder text (sichtbarer Text) an.", isError = true)
                }
            }
            "tippen_xy" -> after(if (svc.tapXY(args.dbl("x").toFloat(), args.dbl("y").toFloat())) "Getippt." else "Tippen fehlgeschlagen.")
            "lange_druecken" -> after(svc.longPress(args.int("id")))
            "text_eingeben" -> {
                val id = if (args.has("id")) args.int("id", -1).takeIf { it > 0 } else null
                after(svc.typeText(id, args.str("text"), args.bool("absenden")))
            }
            "scrollen" -> {
                val id = if (args.has("id")) args.int("id", -1).takeIf { it > 0 } else null
                after(svc.scroll(args.str("richtung", "runter"), id), 600)
            }
            "wischen" -> after(
                if (svc.swipe(args.dbl("x1").toFloat(), args.dbl("y1").toFloat(), args.dbl("x2").toFloat(), args.dbl("y2").toFloat(),
                        args.long("dauer_ms", 300))) "Gewischt." else "Wischen fehlgeschlagen.", 600)
            "taste" -> after(svc.press(args.str("taste")))
            "warten" -> {
                Thread.sleep(args.int("sekunden", 2).coerceIn(1, 15) * 1000L)
                after("Gewartet.", 0)
            }
            "url_oeffnen" -> {
                svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(args.str("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                after("Link geöffnet.", 2000)
            }
            "kontakt_suchen" -> ToolResult(Contacts.describe(svc, args.str("name")))
            "whatsapp_senden" -> whatsapp(args.str("an"), args.str("text"), args.bool("senden", true))
            "kontaktbild_setzen" -> contactPhoto(args.str("kontakt"), args.str("ordner"), args.bool("nur_zeigen"))
            "anrufen" -> ToolResult(Contacts.call(svc, args.str("wen")))
            "nachrichten_lesen" -> ToolResult(
                NotificationListener.asText(args.int("anzahl", 15).coerceIn(1, 60)) +
                    if (!NotificationListener.hasAccess(svc)) "\n(Hinweis: Benachrichtigungszugriff ist nicht erteilt – Lirim muss ihn in Jarvis 2.0 erlauben.)" else ""
            )
            "wecker_stellen" -> {
                svc.startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, args.int("stunde"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, args.int("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("beschriftung", "Jarvis"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult("Wecker auf %02d:%02d gestellt.".format(args.int("stunde"), args.int("minute")))
            }
            "timer_stellen" -> {
                svc.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, args.int("sekunden"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("beschriftung", "Jarvis"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult("Timer über ${args.int("sekunden")} Sekunden gestellt.")
            }
            else -> ToolResult("Unbekanntes Werkzeug: $name", isError = true)
        }
    }

    /** Nach einer Aktion: warten, bis der Bildschirm ruhig ist, und ihn mitschicken (ausser bei gesperrten Apps). */
    private fun after(msg: String, delayMs: Long = 900): ToolResult {
        if (delayMs > 0) svc.waitSettled(delayMs + 1500)
        val fg = svc.foregroundPackage()
        if (Safety.blocked(fg)) return ToolResult("$msg\n\n${Safety.reason(Apps.labelFor(svc, fg))}")
        val failed = msg.startsWith("Fehler") || msg.contains("fehlgeschlagen") || msg.startsWith("Kein")
        return ToolResult("$msg\n\nAktueller Bildschirm:\n${svc.readScreen()}", isError = failed)
    }

    private fun contactPhoto(who: String, folder: String, previewOnly: Boolean): ToolResult {
        if (who.isBlank()) return ToolResult("Kontaktname fehlt.", isError = true)
        if (!ContactPhoto.canReadImages(svc) || !ContactPhoto.canWrite(svc))
            return ToolResult("Jarvis 2.0 darf Galerie oder Kontakte noch nicht ändern. Lirim muss in Jarvis 2.0 «Kontakte & Anrufe erlauben» " +
                "nochmals antippen und «Fotos» sowie «Kontakte» erlauben.", isError = true)
        val (hit, err) = Contacts.resolve(svc, who)
        if (hit == null || Contacts.looksLikeNumber(who)) return ToolResult(err ?: "Bitte einen Kontaktnamen angeben, keine Nummer.", isError = true)
        val img = ContactPhoto.newestImage(svc, folder.takeIf { it.isNotBlank() && it != "null" })
            ?: return ToolResult("Kein Bild in der Galerie gefunden" + (if (folder.isNotBlank()) " (Ordner «$folder»)." else "."), isError = true)
        val ageMin = (System.currentTimeMillis() / 1000 - img.addedSec) / 60
        val info = "Bild: ${img.name} (Ordner ${img.folder}, vor $ageMin Min. gespeichert)"
        val thumb = try { ContactPhoto.preview(svc, img.uri) } catch (_: Exception) { null }
        if (previewOnly) return ToolResult("Vorschau – nichts geändert.\n$info", thumb)
        val (ok, e) = try { ContactPhoto.set(svc, hit.name, img.uri) } catch (ex: Exception) { 0 to (ex.message ?: "Fehler") }
        return if (ok > 0) ToolResult("Profilbild von ${hit.name} gesetzt.\n$info", thumb)
        else ToolResult(e ?: "Bild konnte nicht gesetzt werden.", thumb, isError = true)
    }

    private fun whatsapp(to: String, text: String, send: Boolean): ToolResult {
        if (to.isBlank() || text.isBlank()) return ToolResult("Empfänger und Text sind nötig.", isError = true)
        val (hit, err) = Contacts.resolve(svc, to)
        if (hit == null) return ToolResult(err ?: "Kontakt nicht gefunden.", isError = true)
        val digits = Contacts.intlDigits(hit.number)
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            svc.packageManager.getLaunchIntentForPackage(it) != null
        } ?: return ToolResult("WhatsApp ist nicht installiert.", isError = true)
        val uri = Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text))
        svc.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        svc.waitSettled(4000)
        if (!send) return after("Chat mit ${hit.name} (${hit.number}) geöffnet, Text eingefüllt – noch NICHT gesendet.", 0)

        val sendLabel = Regex("^(senden|send|envoyer|dërgo)$", RegexOption.IGNORE_CASE)
        var button: Int? = null
        repeat(10) {
            if (button == null) {
                button = svc.findClickable(sendLabel, "send")
                if (button == null) Thread.sleep(500)
            }
        }
        val b = button ?: return after("Chat mit ${hit.name} ist offen und der Text eingefüllt, aber ich finde den Senden-Knopf nicht. " +
            "Lies den Bildschirm und tippe selbst auf Senden.", 0)
        svc.tap(b)
        svc.waitSettled(2500)
        val left = svc.editText().orEmpty().trim()
        return if (left.isEmpty() || left != text.trim()) {
            ToolResult("WhatsApp an ${hit.name} (${hit.number}) gesendet: «$text»")
        } else {
            after("Auf Senden getippt, aber der Text steht noch im Feld – bitte prüfen.", 0)
        }
    }
}
