package ch.lirim.jarvis

import android.Manifest
import android.app.AlarmManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import ch.lirim.jarvis.core.Body
import ch.lirim.jarvis.core.Channel
import ch.lirim.jarvis.core.ClockTime
import ch.lirim.jarvis.core.Dates
import ch.lirim.jarvis.core.Durations
import ch.lirim.jarvis.core.MediaOp
import ch.lirim.jarvis.core.MessageText
import ch.lirim.jarvis.core.People
import ch.lirim.jarvis.core.PhoneNumbers
import ch.lirim.jarvis.core.Question
import ch.lirim.jarvis.core.Redact
import ch.lirim.jarvis.core.SettingKind
import ch.lirim.jarvis.core.TravelMode
import ch.lirim.jarvis.core.Utt
import ch.lirim.jarvis.core.VolumeOp
import ch.lirim.jarvis.core.YesNo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import ch.lirim.jarvis.core.Intent as Cmd

/**
 * Stufe 1: Direktbefehle ohne KI ausführen – gratis und sofort.
 * Erfolg wird nur gemeldet, wenn die Aktion wirklich ausgeführt (und wo möglich geprüft) wurde.
 */
object Direct {
    enum class Result { DONE, PASS, STOPPED }

    /** Zuletzt angerufene/angeschriebene Person – für «falscher Kontakt». */
    @Volatile private var lastQuery = ""

    fun run(ctx: Context, command: String, i: Cmd): Result = try {
        exec(ctx, command, i)
    } catch (e: Exception) {
        Agent.log("Direktbefehl nicht möglich (${e.message?.take(80)}) – weiter mit dem nächsten Weg.")
        Result.PASS
    }

    private fun done(text: String, speak: Boolean = true): Result {
        Agent.log("⚡ Direkt erledigt – ohne KI, 0 Rp.")
        if (speak) Agent.speak(text) else Agent.log("Jarvis: $text")
        return Result.DONE
    }

    private fun start(c: Context, i: Intent): Boolean = try {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    private fun installed(c: Context, pkg: String) = c.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun exec(ctx: Context, command: String, i: Cmd): Result {
        val svc = JarvisService.instance
        val c: Context = svc ?: ctx
        return when (i) {
            is Cmd.Stop -> { Speaker.stop(); Agent.log("⏹ Gestoppt."); Result.DONE }
            is Cmd.Repeat -> if (Agent.lastSaid.isBlank()) Result.PASS else done(Agent.lastSaid)
            is Cmd.Alarm -> {
                val out = Tools.execute(ctx, "wecker_stellen", JSONObject().put("stunde", i.hour).put("minute", i.minute)).text
                if (out.startsWith("Fehler")) Result.PASS else done("Wecker steht auf ${ClockTime.spoken(i.hour, i.minute)}.")
            }
            is Cmd.Timer -> {
                val args = JSONObject().put("sekunden", i.seconds)
                if (i.label.isNotBlank()) args.put("beschriftung", i.label)
                val out = Tools.execute(ctx, "timer_stellen", args).text
                if (out.startsWith("Fehler")) Result.PASS
                else done("Timer läuft: ${Durations.spoken(i.seconds)}" + (if (i.label.isNotBlank()) " für ${i.label}." else "."))
            }
            is Cmd.Call -> {
                val who = People.resolve(i.who, Memory.factTexts(ctx)) ?: if (People.isRelational(i.who)) return Result.PASS else i.who
                lastQuery = who
                val out = Tools.execute(ctx, "anrufen", JSONObject().put("wen", who)).text.substringBefore("\n\nAktueller Bildschirm").trim()
                done(out)
            }
            is Cmd.WrongContact -> {
                // Nennt Lirim gleich die richtige Nummer («nimm die mit +41 79 …»), wird sie gemerkt
                val num = Regex("""(?:\+|00)?\d[\d ]{7,}\d""").find(command)?.value?.replace(" ", "")
                val facts = Memory.factTexts(ctx)
                val rel = People.relationIn(ch.lirim.jarvis.core.Utt.norm(command))
                val who = rel?.let { People.relations(facts)[it] } ?: lastQuery
                if (who.isBlank()) return Result.PASS
                if (num != null) {
                    val tail = num.filter { it.isDigit() }.takeLast(9)
                    val hit = Contacts.searchScored(c, who, 10).map { it.first }
                        .firstOrNull { it.number.filter { d -> d.isDigit() }.endsWith(tail) }
                    Prefs.setChosenNumber(c, who, hit?.name ?: who, num)
                    lastQuery = who
                    done("Verstanden. Für $who nehme ich ab jetzt ${hit?.name ?: "die Nummer"} mit ${PhoneNumbers.spokenTail(num)} am Ende.")
                } else {
                    Prefs.clearChosenNumber(c, who)
                    done("Verstanden. Ich habe die gemerkte Nummer für $who gelöscht und frage beim nächsten Mal wieder nach.")
                }
            }
            is Cmd.Message -> message(ctx, svc, command, i)
            is Cmd.OpenApp -> {
                val out = Apps.open(c, i.name)
                if (!out.contains("geöffnet")) return Result.PASS
                done((Regex("""App (.+?) geöffnet""").find(out)?.groupValues?.get(1) ?: i.name) + " ist offen.")
            }
            is Cmd.Volume -> {
                val am = c.getSystemService(AudioManager::class.java)
                val s = AudioManager.STREAM_MUSIC
                val max = am.getStreamMaxVolume(s)
                val ui = AudioManager.FLAG_SHOW_UI
                when (i.op) {
                    VolumeOp.UP -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, ui) }
                    VolumeOp.DOWN -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, ui) }
                    VolumeOp.MUTE -> am.setStreamVolume(s, 0, ui)
                    VolumeOp.UNMUTE -> if (am.getStreamVolume(s) == 0) am.setStreamVolume(s, max / 3, ui)
                    VolumeOp.MAX -> am.setStreamVolume(s, max, ui)
                    VolumeOp.SET -> am.setStreamVolume(s, Math.round(max * i.percent / 100f), ui)
                }
                done("Lautstärke ${am.getStreamVolume(s) * 100 / max.coerceAtLeast(1)} Prozent.")
            }
            is Cmd.Torch -> {
                val cm = c.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return Result.PASS
                cm.setTorchMode(id, i.on)
                done(if (i.on) "Taschenlampe an." else "Taschenlampe aus.")
            }
            is Cmd.Media -> {
                val am = c.getSystemService(AudioManager::class.java)
                val (code, label) = when (i.op) {
                    MediaOp.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY to "Wiedergabe läuft."
                    MediaOp.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Pausiert."
                    MediaOp.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT to "Nächster Titel."
                    MediaOp.PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Vorheriger Titel."
                }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                done(label, speak = i.op == MediaOp.PAUSE)   // nicht in die Musik hineinreden
            }
            is Cmd.Setting -> {
                val (action, name) = when (i.kind) {
                    SettingKind.WIFI -> Settings.Panel.ACTION_WIFI to "WLAN"
                    SettingKind.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
                    SettingKind.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "Flugmodus"
                    SettingKind.MOBILE_DATA -> Settings.ACTION_DATA_ROAMING_SETTINGS to "mobile Daten"
                    SettingKind.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Standort"
                    SettingKind.DND -> Settings.ACTION_SOUND_SETTINGS to "Ton und «Nicht stören»"
                    SettingKind.HOTSPOT -> Settings.ACTION_WIRELESS_SETTINGS to "Verbindungen"
                    SettingKind.NFC -> Settings.ACTION_NFC_SETTINGS to "NFC"
                    SettingKind.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS to "Anzeige"
                    SettingKind.SOUND -> Settings.ACTION_SOUND_SETTINGS to "Ton"
                    SettingKind.BATTERY -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "Akku"
                    SettingKind.GENERAL -> Settings.ACTION_SETTINGS to "Einstellungen"
                }
                if (!start(c, Intent(action)) && !start(c, Intent(Settings.ACTION_SETTINGS))) return Result.PASS
                done(if (i.on != null) "Ich habe $name geöffnet. Den Schalter musst du selbst antippen – das lässt Android mich nicht direkt umschalten."
                     else "$name ist offen.")
            }
            is Cmd.Play -> {
                val pkg = when (i.app) {
                    "spotify" -> "com.spotify.music"
                    "youtube music" -> "com.google.android.apps.youtube.music"
                    "youtube" -> "com.google.android.youtube"
                    "deezer" -> "deezer.android.app"
                    "soundcloud" -> "com.soundcloud.android"
                    else -> null
                }
                val it = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(SearchManager.QUERY, i.query)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                if (pkg != null && installed(c, pkg)) it.setPackage(pkg)
                if (!start(c, it)) return Result.PASS
                done("«${i.query}» wird gestartet.", speak = false)
            }
            is Cmd.Navigate -> {
                val d = Uri.encode(i.dest)
                val uri = if (i.start) {
                    val m = when (i.mode) { TravelMode.WALK -> "&mode=w"; TravelMode.BIKE -> "&mode=b"; else -> "" }
                    "google.navigation:q=$d$m"
                } else {
                    val m = when (i.mode) {
                        TravelMode.TRANSIT -> "&travelmode=transit"; TravelMode.WALK -> "&travelmode=walking"
                        TravelMode.BIKE -> "&travelmode=bicycling"; TravelMode.DRIVE -> "&travelmode=driving"; null -> ""
                    }
                    "https://www.google.com/maps/dir/?api=1&destination=$d$m"
                }
                val it = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                if (installed(c, "com.google.android.apps.maps")) it.setPackage("com.google.android.apps.maps")
                if (!start(c, it)) return Result.PASS
                done(if (i.start) "Navigation nach ${i.dest} startet." else "Die Route nach ${i.dest} ist offen.")
            }
            is Cmd.WebSearch -> {
                if (!start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(i.query))))) return Result.PASS
                done("Die Suche nach «${i.query}» ist offen.")
            }
            is Cmd.CalendarAdd -> {
                val begin = i.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val it = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + i.minutes * 60_000L)
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, i.allDay)
                    .putExtra(CalendarContract.Events.TITLE, i.title)
                if (!start(c, it)) return Result.PASS
                done("Ich habe «${i.title}» ${Dates.spoken(i.start.toLocalDate(), LocalDate.now())}" +
                    (if (!i.allDay) " um ${ClockTime.spoken(i.start.hour, i.start.minute)}" else "") +
                    " vorbereitet. Bitte noch auf Speichern tippen.")
            }
            is Cmd.Ask -> {
                val a = answer(c, i.what) ?: return Result.PASS
                done(a)
            }
            is Cmd.Remember -> { Memory.remember(c, i.text, "fakt"); done("Gemerkt.") }
            is Cmd.Relation -> {
                val poss = if (People.spoken(i.relation).startsWith("deine")) "meine" else "mein"
                Memory.remember(c, "${i.name} ist $poss ${i.relation.replaceFirstChar { it.uppercase() }}", "person")
                done("Gemerkt: ${i.name} ist ${People.spoken(i.relation)}.")
            }
            is Cmd.Forget -> done(Memory.forget(c, i.text))
            is Cmd.Recall -> {
                val facts = Memory.factTexts(c)
                val q = Utt.norm(i.query)
                val rel = People.relationIn(q)
                val hits = when {
                    rel != null -> People.relations(facts)[rel]?.let {
                        listOf("${People.spoken(rel).replaceFirstChar { ch -> ch.uppercase() }} heisst $it.")
                    } ?: emptyList()
                    q.isBlank() || q == "mich" -> facts.takeLast(5)
                    else -> facts.filter { Utt.norm(it).contains(q) }.take(4)
                }
                done(if (hits.isEmpty()) "Dazu habe ich nichts gespeichert." else hits.joinToString(". ").replace("..", "."))
            }
            is Cmd.Project -> {
                Memory.updateProject(c, i.name, i.percent, i.status)
                done("Projekt ${i.name} steht bei ${i.percent} Prozent.")
            }
            else -> Result.PASS
        }
    }

    // ---------------------------------------------------------------- Nachricht (WhatsApp / SMS)

    /**
     * Öffnet den Chat direkt mit vorbereitetem Text (ohne KI), liest vor, fragt «Senden?»
     * und tippt erst nach «Ja» auf Senden. Danach wird geprüft, ob der Text wirklich weg ist.
     */
    private fun message(ctx: Context, svc: JarvisService?, command: String, m: Cmd.Message): Result {
        val channel = m.channel ?: Channel.WHATSAPP
        if (channel != Channel.WHATSAPP && channel != Channel.SMS) return Result.PASS
        if (svc == null) return Result.PASS
        val waPkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(svc, it) }
        if (channel == Channel.WHATSAPP && waPkg == null) return Result.PASS

        // 1. Empfänger («meine Frau» → Name → gemerkte oder gewählte Nummer)
        val who = People.resolve(m.to, Memory.factTexts(ctx)) ?: if (People.isRelational(m.to)) return Result.PASS else m.to
        val hit = pickContact(svc, who) ?: return if (m.sure) {
            Agent.speak("Ich finde keinen passenden Kontakt für $who."); Result.STOPPED
        } else Result.PASS
        lastQuery = who

        // 2. Text: per Regel, sonst Mini-Aufruf an Claude, sonst nachfragen (im selben Auftrag)
        var text: String? = MessageText.from(m.body)?.takeIf { it.confident }?.text
        if (text == null && m.body is Body.Clause) text = cloudWording(ctx, command, hit.name)
        if (text.isNullOrBlank()) {
            val ans = VoiceAnswer.askText(svc, "Was soll in der Nachricht an ${hit.name} stehen?")
            if (ans.isNullOrBlank() || Utt.norm(ans) in setOf("stopp", "stop", "abbrechen", "nichts", "vergiss es")) {
                Agent.speak("Dann lasse ich es."); return Result.STOPPED
            }
            text = MessageText.literal(ans)
        }

        // 3. Chat mit Entwurf öffnen
        val isWa = channel == Channel.WHATSAPP
        val intent = if (isWa) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${PhoneNumbers.intl(hit.number)}&text=${Uri.encode(text)}")).setPackage(waPkg)
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + PhoneNumbers.digits(hit.number))).putExtra("sms_body", text)
        }
        if (!start(svc, intent)) return Result.PASS
        Agent.log("→ ${if (isWa) "WhatsApp" else "SMS"}-Chat mit ${hit.name} öffnen")
        svc.overlay?.setStatus("öffne Chat mit ${hit.name} …")
        val probe = text.take(12)
        var ready = false
        for (k in 0 until 14) {
            svc.waitSettled(1200)
            if (svc.editText().orEmpty().contains(probe)) { ready = true; break }
            Thread.sleep(250)
        }
        if (!ready) {
            Agent.speak("Ich habe ${if (isWa) "WhatsApp" else "die Nachrichten-App"} geöffnet, aber der Entwurf an ${hit.name} ist nicht erschienen. Bitte prüf es selbst.")
            return Result.STOPPED
        }

        // 4. Vorlesen und bestätigen lassen
        val q = "An ${hit.name}: «$text» – senden?"
        Speaker.say(q)
        Agent.log("❓ $q")
        if (svc.overlay?.confirm(q) != true) {
            Agent.log("❌ Nein")
            Agent.speak("In Ordnung, nicht gesendet. Der Entwurf steht noch im Eingabefeld.")
            return Result.STOPPED
        }
        Agent.log("✅ Ja")

        // 5. Senden und prüfen
        val send = svc.findClickable(Regex("""^(nachricht |sms |mms )?(senden|send)\b""", RegexOption.IGNORE_CASE), "send")
        if (send == null) {
            Agent.speak("Ich finde den Senden-Knopf nicht. Bitte tippe selbst auf Senden.")
            return Result.STOPPED
        }
        svc.tap(send)
        svc.waitSettled(1800)
        return if (!svc.editText().orEmpty().contains(probe)) done("Gesendet an ${hit.name}.")
        else { Agent.speak("Ich habe auf Senden getippt, aber der Text steht noch im Feld. Bitte prüf es."); Result.STOPPED }
    }

    /** Kontakt wählen; bei mehreren Treffern nacheinander fragen (Schweizer Nummern zuerst) und die Wahl merken. */
    private fun pickContact(svc: JarvisService, who: String): Contacts.Hit? {
        Prefs.chosenNumber(svc, who)?.let { (n, num) -> return Contacts.Hit(n, num) }
        val scored = Contacts.searchScored(svc, who)
        if (scored.isEmpty()) return null
        val best = scored.first().second
        val list = scored.filter { it.second >= best - 25 }.map { it.first }.take(3)
        if (list.size == 1) return list[0]
        for ((i, h) in list.withIndex()) {
            val q = (if (i == 0) "Ich habe ${list.size} passende Kontakte. " else "") + "Meinst du ${h.name}" +
                (if (Contacts.isSwiss(h.number)) "" else " (Ausland)") + ", Nummer endet auf ${PhoneNumbers.spokenTail(h.number)}?"
            Speaker.say(q)
            Agent.log("❓ $q")
            if (svc.overlay?.confirm(q) == true) {
                Agent.log("✅ Ja")
                Prefs.setChosenNumber(svc, who, h.name, h.number)
                return h
            }
            Agent.log("❌ Nein")
        }
        return null
    }

    /** Nachricht mit einem winzigen Claude-Aufruf formulieren (≈ 0.002 Rp.), wenn die Regel nicht sicher ist. */
    private fun cloudWording(ctx: Context, command: String, name: String): String? {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank() || System.currentTimeMillis() < Prefs.cloudBlockedUntil(ctx)) return null
        return try {
            val resp = ClaudeClient.send(key, Prefs.DEFAULT_MODEL,
                "Du formulierst kurze Nachrichten für Lirim. Gib nur den Nachrichtentext aus, ohne Anführungszeichen.", JSONArray(),
                JSONArray().put(JSONObject().put("role", "user").put("content",
                    "Lirim diktiert seinem Assistenten: «$command». Schreib die Nachricht an $name so, wie Lirim sie selbst senden würde: Du-Form, kurz, Schweizer Rechtschreibung (ss statt ß). Nur der Text.")),
                maxTokens = 150)
            val cost = ClaudeClient.costUsd(Prefs.DEFAULT_MODEL, resp)
            Prefs.addCost(ctx, cost)
            Agent.log("💰 Formulieren ≈ ${Agent.rp(cost)}")
            val content = resp.getJSONArray("content")
            (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }
                .joinToString(" ") { it.optString("text") }.trim().trim('«', '»', '"').takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- Antworten ohne KI

    private fun r(d: Double) = "%.0f".format(d)

    private fun answer(c: Context, q: Question): String? = when (q) {
        Question.TIME -> "Es ist " + SimpleDateFormat("H 'Uhr' mm", Locale.GERMAN).format(Date()).removeSuffix(" 00") + "."
        Question.DATE -> "Heute ist ${Dates.full(LocalDate.now())}."
        Question.BATTERY -> SystemStats.snapshot(c).let { "Der Akku ist bei ${it.battery} Prozent" + if (it.charging) " und lädt." else "." }
        Question.WEATHER, Question.WEATHER_TOMORROW -> {
            val wx = try { Weather.fetch(c) } catch (e: Exception) { Weather.last }
            when {
                wx == null -> "Ich erreiche den Wetterdienst gerade nicht."
                q == Question.WEATHER -> "In ${wx.place} sind es ${r(wx.temp)} Grad, ${Weather.describe(wx.code)}. Heute ${r(wx.min)} bis ${r(wx.max)} Grad, " +
                    "Regenwahrscheinlichkeit ${wx.rainProb} Prozent." + if (wx.rainProb >= 50) " Nimm einen Schirm mit." else ""
                wx.tMin.isNaN() -> null
                else -> "Morgen in ${wx.place}: ${r(wx.tMin)} bis ${r(wx.tMax)} Grad" +
                    (if (wx.tRain >= 0) ", Regenwahrscheinlichkeit ${wx.tRain} Prozent." else ".") + if (wx.tRain >= 50) " Schirm einpacken." else ""
            }
        }
        Question.AGENDA, Question.AGENDA_TOMORROW -> agenda(c, q == Question.AGENDA_TOMORROW)
        Question.NOTIFICATIONS -> notifications(c)
        Question.COSTS -> {
            val bal = Prefs.creditBalance(c)
            (if (bal != null) "Dein Guthaben liegt bei etwa ${"%.2f".format(bal)} Dollar. " else "Dein Guthaben ist noch nicht eingetragen. ") +
                "Diesen Monat hat Claude ${Agent.rp(Prefs.monthCost(c))} gekostet, ${Prefs.savedTasks(c)} Aufträge liefen gratis."
        }
        Question.BRIEFING -> briefing(c, fetch = true)
        Question.MARKETS -> {
            val list = try { Stocks.fetch(c) } catch (e: Exception) { Stocks.last }
            if (list.isEmpty()) "Ich erreiche die Kursdaten gerade nicht."
            else list.take(4).joinToString(" ") {
                "${it.name} ${r(it.price)}, ${if (it.change >= 0) "plus" else "minus"} ${"%.1f".format(Math.abs(it.change))} Prozent."
            }
        }
        Question.ALARM -> {
            val a = c.getSystemService(AlarmManager::class.java).nextAlarmClock
            if (a == null) "Es ist kein Wecker gestellt."
            else "Dein nächster Wecker klingelt am " + SimpleDateFormat("EEEE 'um' H 'Uhr' mm", Locale.GERMAN).format(Date(a.triggerTime)).removeSuffix(" 00") + "."
        }
        Question.HELP -> "Ich stelle Wecker und Timer, rufe an, schreibe WhatsApp und SMS, öffne Apps, regle Lautstärke und Licht, starte Musik und Routen " +
            "und sage dir Wetter, Termine und neue Nachrichten – alles gratis. Für alles andere frage ich Claude."
    }

    private fun agenda(c: Context, tomorrow: Boolean): String {
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return "Ich habe noch keinen Kalenderzugriff. Tippe im HUD auf «Heute», um ihn zu erlauben."
        val all = Agenda.next(c, 8)
        val tag = if (tomorrow) SimpleDateFormat("EEE", Locale.GERMAN).format(Date(System.currentTimeMillis() + 24 * 3600_000L)) else "Heute"
        val list = all.filter { it.first.startsWith(tag) }
        val day = if (tomorrow) "morgen" else "heute"
        if (list.isEmpty()) return "Du hast $day keine Termine mehr."
        return "Du hast $day ${if (list.size == 1) "einen Termin" else "${list.size} Termine"}: " +
            list.joinToString("; ") { "${it.first.substringAfter(' ')} ${it.second}" } + "."
    }

    private fun notifications(c: Context): String {
        if (!NotificationStore.hasAccess(c)) return "Ich habe keinen Zugriff auf Benachrichtigungen. Unter ZUGRIFF kannst du ihn erlauben."
        val items = NotificationStore.recent(20).filter { System.currentTimeMillis() - it.time < 12 * 3600_000L }
        if (items.isEmpty()) return "Keine neuen Meldungen."
        return "Du hast ${if (items.size == 1) "eine neue Meldung" else "${items.size} neue Meldungen"}. " +
            items.take(3).joinToString(" ") { "${it.app}, ${it.title}: ${Redact.secrets(it.text).take(110)}." }
    }

    /** Lagebericht – regelbasiert aus Wetter, Wecker, Terminen und Meldungen (gratis). */
    fun briefing(c: Context, fetch: Boolean): String {
        val parts = ArrayList<String>()
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        parts.add(when { h < 11 -> "Guten Morgen, Lirim."; h < 18 -> "Guten Tag, Lirim."; else -> "Guten Abend, Lirim." })
        val wx = try { if (fetch) Weather.fetch(c) else Weather.last } catch (e: Exception) { Weather.last }
        if (wx != null) parts.add("In ${wx.place} sind es ${r(wx.temp)} Grad, ${Weather.describe(wx.code)}; heute ${r(wx.min)} bis ${r(wx.max)} Grad." +
            if (wx.rainProb >= 50) " Regen ist wahrscheinlich – nimm einen Schirm mit." else "")
        try {
            c.getSystemService(AlarmManager::class.java).nextAlarmClock?.let {
                parts.add("Nächster Wecker: " + SimpleDateFormat("EEEE HH:mm", Locale.GERMAN).format(Date(it.triggerTime)) + ".")
            }
        } catch (_: Exception) {}
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) parts.add(agenda(c, false))
        if (NotificationStore.hasAccess(c)) {
            val n = NotificationStore.recent(40).count { System.currentTimeMillis() - it.time < 12 * 3600_000L }
            if (n > 0) parts.add(if (n == 1) "Eine neue Meldung wartet." else "$n neue Meldungen warten.")
        }
        return parts.joinToString(" ")
    }
}
