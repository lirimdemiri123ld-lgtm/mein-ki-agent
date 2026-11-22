package ch.lirim.jarvis.core

import java.time.LocalDateTime

/**
 * Stufe 1: Direktbefehle ohne KI erkennen (gratis, sofort).
 * Reine Mustererkennung auf dem normalisierten Text; Namen und Nachrichtentexte
 * werden aus dem Original übernommen (Gross-/Kleinschreibung bleibt).
 */
enum class Channel { WHATSAPP, SMS, MAIL, OTHER }
enum class SettingKind { WIFI, BLUETOOTH, AIRPLANE, MOBILE_DATA, LOCATION, DND, HOTSPOT, NFC, DISPLAY, SOUND, BATTERY, GENERAL }
enum class VolumeOp { UP, DOWN, SET, MUTE, UNMUTE, MAX }
enum class MediaOp { PLAY, PAUSE, NEXT, PREV }
enum class TravelMode { DRIVE, TRANSIT, WALK, BIKE }
enum class Question { TIME, DATE, BATTERY, WEATHER, WEATHER_TOMORROW, AGENDA, AGENDA_TOMORROW, NOTIFICATIONS, COSTS, BRIEFING, MARKETS, ALARM, HELP }

sealed class Body {
    object None : Body()
    /** Wörtlich diktierter Text. */
    data class Literal(val text: String) : Body()
    /** Indirekte Rede: «dass ich später komme» / «ob er morgen kommt». */
    data class Clause(val kind: String, val text: String) : Body()
}

sealed class Intent {
    object Stop : Intent()
    object Repeat : Intent()
    data class Alarm(val hour: Int, val minute: Int, val label: String = "") : Intent()
    object AlarmNoTime : Intent()
    data class Timer(val seconds: Int, val label: String = "") : Intent()
    object TimerNoDuration : Intent()
    data class Call(val who: String) : Intent()
    /** [sure] = eindeutig eine Nachricht (Kanal oder «dass» genannt). */
    data class Message(val channel: Channel?, val channelName: String, val to: String, val body: Body, val sure: Boolean) : Intent()
    data class OpenApp(val name: String) : Intent()
    data class Setting(val kind: SettingKind, val on: Boolean?) : Intent()
    data class Volume(val op: VolumeOp, val percent: Int = -1) : Intent()
    data class Torch(val on: Boolean) : Intent()
    data class Media(val op: MediaOp) : Intent()
    data class Play(val query: String, val app: String) : Intent()
    data class Navigate(val dest: String, val mode: TravelMode?, val start: Boolean) : Intent()
    data class CalendarAdd(val title: String, val start: LocalDateTime, val minutes: Int, val allDay: Boolean) : Intent()
    data class WebSearch(val query: String) : Intent()
    data class Ask(val what: Question) : Intent()
    data class Remember(val text: String) : Intent()
    data class Relation(val name: String, val relation: String) : Intent()
    data class Forget(val text: String) : Intent()
    data class Recall(val query: String) : Intent()
    object WrongContact : Intent()
    data class Project(val name: String, val percent: Int, val status: String) : Intent()
    /** Ausdrücklich an Claude («mit Claude …», «gründlich …»). */
    data class Cloud(val text: String, val thorough: Boolean) : Intent()
}

object Intents {
    // ---------------------------------------------------------------- Vorbereitung

    private val wake = rx("""^(?:(?:hallo|hey|hi|ok|okay|he|hoi|sali|salü|grüezi|guten tag) )?(?:jarvis|jarwis|dschawis|tschawis|javis|service|jervis)\b ?""")
    private val leadFiller = rx("""^(?:bitte|mal|und|also|äh|ähm|du|so) """)
    private val polite = rx("""^(?:kannst|könntest|koenntest|würdest|wuerdest|magst|willst) du (?:mir |uns |mal |bitte |kurz |schnell )*""")
    private val wish = rx("""^(?:ich (?:möchte|moechte|will|würde|wuerde) (?:gern |gerne |bitte )?(?:dass du )?|ich brauche? |mach mir |ich hätte gern(?:e)? )""")
    private val tailFiller = rx(""" (?:bitte|danke|dankeschön|merci|jetzt bitte|bitte jetzt)$""")

    /** Entfernt Anrede und Höflichkeitsfloskeln; liefert die bereinigte Äusserung. */
    fun clean(raw: String): Utt {
        var u = Utt(raw)
        fun cutHead(chars: Int) {
            if (chars <= 0 || u.toks.isEmpty()) return
            val first = u.tokAt(minOf(chars, u.text.length - 1))
            val startTok = if (chars >= u.text.length) u.toks.size else first
            u = if (startTok >= u.toks.size) Utt("") else Utt(u.raw.substring(u.toks[startTok].start))
        }
        fun cutTail(m: MatchResult) {
            val keep = u.tokAt(m.range.first + 1)   // erstes Wort des Anhängsels
            u = if (keep <= 0) Utt("") else Utt(u.raw.substring(0, u.toks[keep - 1].end))
        }
        var changed = true
        var guard = 0
        while (changed && guard++ < 8) {
            changed = false
            for (r in listOf(wake, leadFiller, polite, wish)) {
                val m = r.find(u.text) ?: continue
                if (m.value.isNotEmpty() && m.range.first == 0) { cutHead(m.range.last + 1); changed = true }
            }
            tailFiller.find(u.text)?.let { cutTail(it); changed = true }
        }
        return u
    }

    // ---------------------------------------------------------------- Einstieg

    private val compound = rx("""\b(und dann|danach|anschliessend|ausserdem|und (?:öffne|oeffne|schreib|schreibe|schick|schicke|ruf|rufe|stell|stelle|sag|sage|spiel|spiele|such|suche|mach|mache|zeig|zeige|lies|sende|starte|frag|frage|erinner|erinnere) )""")

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): Intent? {
        val pre = Utt(raw).text.let { wake.replace(it, "") }.trim()
        control(pre)?.let { return it }
        val u = clean(raw)
        val t = u.text
        if (t.isEmpty()) return null

        control(t)?.let { return it }
        forcedCloud(u)?.let { return it }
        if (wrongContact.containsMatchIn(t)) return Intent.WrongContact

        // Zusammengesetzte Aufträge («… und dann …») gehören dem Cloud-Agenten.
        // Ein «und» innerhalb des Nachrichtentexts zählt nicht.
        val bodyStart = bodyMarker(u)
        val head = if (bodyStart >= 0) u.normToks(0, bodyStart) else t
        val isCompound = compound.containsMatchIn(head)

        memory(u)?.let { return it }
        openThenMessage(u)?.let { return it }
        if (isCompound) return null
        message(u)?.let { return it }
        looseMessage(u, null)?.let { return it }
        call(u)?.let { return it }
        alarm(u, now)?.let { return it }
        timer(u)?.let { return it }
        calendar(u, now)?.let { return it }
        volume(t)?.let { return it }
        torch(t)?.let { return it }
        media(t)?.let { return it }
        setting(t)?.let { return it }
        play(u)?.let { return it }
        navigate(u)?.let { return it }
        webSearch(u)?.let { return it }
        ask(t)?.let { return it }
        project(u)?.let { return it }
        openApp(u)?.let { return it }
        return null
    }

    // ---------------------------------------------------------------- Steuerung

    private val stop = rx("""^(?:stopp?|halt|abbrechen|abbruch|aufhören|aufhoeren|hör auf|hoer auf|vergiss es|lass es|lass das|lass es sein|lass gut sein|ruhe|sei still|schon gut|egal|nichts)$""")
    private val repeat = rx("""^(?:nochmal|nochmals|noch einmal|wiederhol(?:e)?(?: das| bitte| das bitte)?|wie bitte|was hast du gesagt|sag das nochmal)$""")
    private val help = rx("""^(?:hilfe|was kannst du(?: alles| so)?|was kann ich (?:dich )?fragen|wie funktionierst du)$""")

    private fun control(t: String): Intent? = when {
        stop.matches(t) -> Intent.Stop
        repeat.matches(t) -> Intent.Repeat
        help.matches(t) -> Intent.Ask(Question.HELP)
        else -> null
    }

    private val cloudThorough = rx("""^(?:(?:mit|frag|frage) (?:claude )?sonnet|gründlich|gruendlich|sorgfältig|ganz genau)\b[ ]?""")
    private val cloudPlain = rx("""^(?:(?:mit|frag|frage) claude|claude)\b[ ]?""")

    private fun forcedCloud(u: Utt): Intent? {
        cloudThorough.find(u.text)?.let { m ->
            val rest = if (m.range.last + 1 >= u.text.length) "" else u.rawFrom(m.range.last + 1)
            if (rest.isNotBlank()) return Intent.Cloud(rest, true)
        }
        cloudPlain.find(u.text)?.let { m ->
            val rest = if (m.range.last + 1 >= u.text.length) "" else u.rawFrom(m.range.last + 1)
            if (rest.isNotBlank()) return Intent.Cloud(rest, false)
        }
        return null
    }

    private val wrongContact = rx("""^(?:nein )?(?:(?:das (?:ist|war) )?(?:der |die |ein |eine )?falsch(?:e|er|en)? (?:kontakt|nummer|person)|das (?:ist|war) (?:der|die) falsche|falsch(?:e|er)? (?:kontakt|nummer)|nicht (?:der|die|diese|dieser)|(?:die|der) andere(?: nummer| kontakt)?|nimm (?:die|den) andere[n]?)\b""")

    // ---------------------------------------------------------------- Gedächtnis

    private val remember = rx("""^(?:merk(?:e)? dir|merk(?:e)? es dir|merke|speicher(?:e)?(?: dir)?|notier(?:e)?(?: dir)?|denk daran|denke daran|vergiss nicht)(?: bitte)?(?: mal)? ?(?:dass |das |folgendes )?(.+)$""")
    private val forget = rx("""^(?:vergiss|lösch(?:e)? (?:die erinnerung|den eintrag|aus (?:dem|deinem) gedächtnis)|entfern(?:e)? (?:die erinnerung|den eintrag)) (?:bitte )?(?:dass |das mit |an |alles über |alles zu |über |ueber )?(.+)$""")
    private val recall = rx("""^(?:was weisst du (?:alles )?(?:über|ueber|von|zu) (.+)|was hast du dir (?:alles )?(?:über (.+) )?gemerkt|was weisst du noch|zeig (?:mir )?(?:dein|das) gedächtnis)$""")
    private val whoIs = rx("""^(?:wer ist|wie heisst) mein(?:e)? ([a-zäöü]+)$""")

    private fun memory(u: Utt): Intent? {
        val t = u.text
        remember.find(t)?.let { m ->
            val text = u.rawOf(m.groups[1]).trim().trimEnd('.', '!')
            if (text.isNotBlank()) {
                People.extractRelation(text)?.let { (name, rel) -> return Intent.Relation(name, rel) }
                return Intent.Remember(text)
            }
        }
        // «Gruja ist meine Frau» / «Meine Frau heisst Gruja» – auch ohne «merk dir»
        if (u.toks.size <= 7) People.extractRelation(u.raw)?.let { (name, rel) ->
            if (!t.startsWith("wer ") && !t.startsWith("wie ") && !t.contains(" nicht ")) return Intent.Relation(name, rel)
        }
        forget.find(t)?.let { m ->
            val text = u.rawOf(m.groups[1]).trim().trimEnd('.', '!')
            if (text.isNotBlank() && Utt.norm(text) !in setOf("es", "das", "alles")) return Intent.Forget(text)
        }
        recall.find(t)?.let { m ->
            val q = m.groups[1] ?: m.groups[2]
            return Intent.Recall(if (q != null) u.rawOf(q) else "")
        }
        whoIs.find(t)?.let { m -> if (People.canon(m.groupValues[1]) != null) return Intent.Recall("mein " + m.groupValues[1]) }
        return null
    }

    // ---------------------------------------------------------------- Nachrichten

    private val msgVerbs = setOf("schreib", "schreibe", "schick", "schicke", "sende", "send", "sag", "sage", "antworte",
        "frag", "frage", "melde", "simse", "texte")
    private val msgInfinitives = setOf("schreiben", "schicken", "senden", "sagen", "antworten", "fragen", "ausrichten", "mitteilen")
    private val channelWords = mapOf(
        "whatsapp" to Channel.WHATSAPP, "whats" to Channel.WHATSAPP, "wa" to Channel.WHATSAPP, "whatsappnachricht" to Channel.WHATSAPP,
        "sms" to Channel.SMS, "textnachricht" to Channel.SMS, "kurznachricht" to Channel.SMS,
        "mail" to Channel.MAIL, "email" to Channel.MAIL, "e-mail" to Channel.MAIL,
        "signal" to Channel.OTHER, "telegram" to Channel.OTHER, "threema" to Channel.OTHER, "teams" to Channel.OTHER,
        "instagram" to Channel.OTHER, "messenger" to Channel.OTHER, "slack" to Channel.OTHER, "snapchat" to Channel.OTHER
    )
    private val msgNouns = setOf("nachricht", "message", "mitteilung", "text")
    private val headFillers = setOf("auf", "per", "über", "ueber", "via", "mit", "in", "im", "bei", "eine", "einen", "ein",
        "einer", "die", "der", "den", "mal", "bitte", "kurz", "schnell", "noch", "app", "an", "für", "fuer", "zu", "nachricht",
        "message", "mitteilung", "neue", "neuen")
    private val notRecipients = setOf("mir", "uns", "dir", "mich", "bescheid", "es", "das", "etwas", "was", "alles", "allen",
        "jemandem", "mal", "auf", "ab", "hallo", "danke", "ja", "nein", "nichts", "ihm", "ihr", "ihnen", "wie", "warum", "wann")
    private val literalIntro = rx("""^(?:mit dem text|mit text|den text|folgenden text|folgendes|nachricht|text) """)

    /** Index des ersten Worts des Nachrichtentexts (nach «dass»/«ob»/Doppelpunkt), sonst −1. */
    private fun bodyMarker(u: Utt): Int {
        val colon = if (u.colonAfter >= 0 && u.colonAfter < u.toks.size - 1) u.colonAfter + 1 else -1
        val conj = u.toks.indexOfFirst { it.norm == "dass" || it.norm == "ob" }.let { if (it in 1 until u.toks.size - 1) it + 1 else -1 }
        return when {
            colon >= 0 && conj >= 0 -> minOf(colon, conj)
            colon >= 0 -> colon
            else -> conj
        }
    }

    private fun message(u: Utt): Intent? {
        val toks = u.toks
        if (toks.size < 2) return null
        val first = toks[0].norm
        var headFrom: Int
        var verb = ""
        var leadChannel: String? = null
        when {
            first in msgVerbs -> { headFrom = 1; verb = first }
            first in channelWords || first in msgNouns || (first == "neue" && toks[1].norm in msgNouns) -> {
                // «WhatsApp an Ali: …», «SMS an meine Frau, dass …», «Nachricht an Ali»
                val hasAn = toks.drop(1).take(4).any { it.norm == "an" || it.norm == "für" || it.norm == "fuer" }
                if (!hasAn) return null
                headFrom = 0
                leadChannel = first
            }
            else -> {
                // Nebensatz-Stellung: «meiner Frau auf WhatsApp schreiben, dass …»
                val vi = toks.indexOfFirst { it.norm in msgInfinitives }
                if (vi < 1 || vi > 6) return null
                val markerAt = bodyMarker(u)
                if (markerAt >= 0 && markerAt < vi) return null
                // Verb aus dem Kopf entfernen: wir bauen die Äusserung als «schreib <Kopf ohne Verb> <Rest>» neu
                val rebuilt = "schreib " + u.rawToks(0, vi) + (if (vi + 1 < toks.size) " " + u.raw.substring(toks[vi + 1].start) else "")
                return message(Utt(rebuilt))
            }
        }
        val marker = bodyMarker(u)
        val headTo = if (marker >= 0) {
            // Kopf endet vor «dass»/«ob» bzw. beim Doppelpunkt
            if (toks[marker - 1].norm == "dass" || toks[marker - 1].norm == "ob") marker - 1 else marker
        } else toks.size
        if (headTo <= headFrom) return null

        // Kanal im Kopf suchen
        var channel: Channel? = null
        var channelName = ""
        var channelIdx = -1
        for (i in headFrom until headTo) {
            val w = toks[i].norm
            val c = channelWords[w]
            if (c != null) {
                // «in Teams/Signal» nur als Kanal werten, wenn eine Präposition davor steht oder es am Anfang steht
                channel = c; channelName = u.rawToks(i, i + 1); channelIdx = i
                break
            }
        }
        if (channelIdx >= 0 && channelIdx + 1 < headTo && toks[channelIdx].norm == "whats" && toks[channelIdx + 1].norm == "app") {
            channelName = "WhatsApp"
        }
        if (channel == Channel.WHATSAPP) channelName = "WhatsApp"
        val hasNoun = (headFrom until headTo).any { toks[it].norm in msgNouns }

        fun recipient(from: Int, to: Int): String {
            var a = from
            var b = to
            while (a < b && (toks[a].norm in headFillers || toks[a].norm in channelWords || toks[a].norm == "app")) a++
            while (b > a && (toks[b - 1].norm in headFillers || toks[b - 1].norm in channelWords || toks[b - 1].norm == "app")) b--
            return u.rawToks(a, b)
        }

        var to: String
        var body: Body = Body.None
        if (marker >= 0) {
            to = recipient(headFrom, headTo)
            val kind = toks[marker - 1].norm
            val rest = u.raw.substring(toks[marker].start).trim()
            body = if (kind == "dass" || kind == "ob") Body.Clause(kind, rest.trimEnd('.', '!', ' ')) else Body.Literal(rest)
        } else if (channelIdx >= 0) {
            val left = recipient(headFrom, channelIdx)
            var rightFrom = channelIdx + 1
            if (rightFrom < headTo && toks[rightFrom].norm == "app") rightFrom++
            while (rightFrom < headTo && toks[rightFrom].norm in msgNouns) rightFrom++
            if (left.isNotBlank()) {
                to = left
                if (rightFrom < headTo) {
                    var lit = u.raw.substring(toks[rightFrom].start).trim()
                    literalIntro.find(Utt.norm(lit))?.let { lit = Utt(lit).let { x -> x.rawFrom(it.range.last + 1) } }
                    if (lit.isNotBlank()) body = Body.Literal(lit)
                }
            } else {
                to = recipient(rightFrom, headTo)
            }
        } else {
            to = recipient(headFrom, headTo)
        }
        to = to.trim().trimEnd(',', '.', ':')
        val toNorm = Utt.norm(to)
        if (toNorm.isEmpty() || toNorm.split(' ').first() in notRecipients && !toNorm.startsWith("mein")) return null
        if (toNorm.split(' ').size > 6) return null
        // «schreib Ali ich komme später» – kein Kanal, kein «dass»: Empfänger ist nicht der ganze Satz → lockere Form versuchen
        if (body is Body.None && channel == null && !hasNoun && toNorm.split(' ').size > 2 && !toNorm.startsWith("mein")) return null
        // «frag»/«sag» ohne Kanal und ohne «dass/ob» ist meist keine Nachricht («sag mir …», «frag Claude»)
        val explicit = channel != null || hasNoun || leadChannel != null
        if (!explicit && marker < 0 && verb in setOf("sag", "sage", "frag", "frage", "melde", "antworte")) return null
        if (verb in setOf("frag", "frage") && body is Body.Clause && body.kind == "dass") return null
        val sure = explicit || (marker >= 0 && toNorm.split(' ').size <= 3)
        return Intent.Message(channel, channelName, to, body, sure)
    }

    /** «Öffne WhatsApp (und) schreib Gruja …» → Nachricht über WhatsApp. */
    private val openPrefix = rx("""^(?:öffne|oeffne|geh auf|gehe auf|mach|starte) (?:mir )?(whatsapp|whats app|sms|signal|telegram)(?: auf)?(?: und)? (.+)$""")

    private fun openThenMessage(u: Utt): Intent? {
        val m = openPrefix.find(u.text) ?: return null
        val ch = channelWords[m.groupValues[1].replace(" ", "")] ?: return null
        val restNorm = m.groupValues[2]
        if (restNorm.split(' ').first() !in msgVerbs) return null
        // Rohtext ab dem Verb behalten (Gross-/Kleinschreibung der Nachricht)
        val vi = u.toks.indexOfFirst { it.norm in msgVerbs }
        if (vi < 0) return null
        val sub = Utt(u.raw.substring(u.toks[vi].start))
        val viaMsg = message(sub) as? Intent.Message
        if (viaMsg != null && (viaMsg.body !is Body.None || viaMsg.channel != null))
            return viaMsg.copy(channel = viaMsg.channel ?: ch, channelName = viaMsg.channelName.ifBlank { channelLabel(ch) }, sure = true)
        return looseMessage(sub, ch)
    }

    private fun channelLabel(c: Channel) = when (c) { Channel.WHATSAPP -> "WhatsApp"; Channel.SMS -> "SMS"; else -> "" }

    private val relWords = setOf("meine", "meiner", "meinem", "meinen", "mein")
    private val correction = setOf("also", "sprich", "bzw", "beziehungsweise", "respektive", "resp", "äh", "ähm", "ich meine", "das heisst")

    /**
     * Gesprochene Form ohne «dass» / Doppelpunkt: «schreib Gruja also meine Frau gehen wir zu Pronto».
     * Empfänger = 1 Wort oder «meine(r) <Person>»; Korrektur nach «also/sprich» hat Vorrang; der Rest ist der Text.
     */
    private fun looseMessage(u: Utt, ch: Channel?): Intent? {
        val toks = u.toks
        if (toks.size < 4 || toks[0].norm !in setOf("schreib", "schreibe", "schick", "schicke", "sende", "texte")) return null
        if (bodyMarker(u) >= 0) return null
        var i = 1
        var channel = ch
        fun skipChannel() {
            while (i < toks.size && (toks[i].norm in setOf("auf", "per", "über", "via", "eine", "nachricht", "app") || channelWords.containsKey(toks[i].norm))) {
                channelWords[toks[i].norm]?.let { channel = it }; i++
            }
        }
        fun person(): Pair<Int, Int>? {
            if (i >= toks.size) return null
            if (toks[i].norm in relWords && i + 1 < toks.size) return i to i + 2
            if (toks[i].norm in notRecipients || toks[i].norm in headFillers) return null
            return i to i + 1
        }
        skipChannel()
        var who = person() ?: return null
        i = who.second
        skipChannel()
        // Korrektur: «Gruja also meine Frau»
        if (i < toks.size && toks[i].norm in correction) {
            i++
            if (i < toks.size && toks[i].norm in relWords && i + 1 < toks.size) { who = i to i + 2; i += 2 }
        }
        skipChannel()
        if (toks.size - i < 2) return null
        val to = u.rawToks(who.first, who.second)
        val text = u.raw.substring(toks[i].start).trim().trimEnd('.', '!', ' ')
        return Intent.Message(channel, channel?.let { channelLabel(it) } ?: "", to, Body.Literal(text), sure = channel != null)
    }

    // ---------------------------------------------------------------- Anrufen

    private val callA = rx("""^(?:ruf|rufe)(?: mal| bitte| doch| schnell| kurz)* (?:bei )?(.+?) (?:an|zurück|zurueck)$""")
    private val callB = rx("""^(.+?) (?:mal |bitte |kurz )?(?:anrufen|zurückrufen|zurueckrufen|anläuten|aalüüte)$""")
    private val callC = rx("""^(?:telefonier(?:e)?|telefonieren) mit (.+)$""")
    private val callD = rx("""^(?:wähl(?:e)?|waehl(?:e)?|anruf (?:an|bei|zu)|anrufen bei|ruf an bei|lüt|lüüt) (.+?)(?: aa| an)?$""")
    private val callBad = rx("""\b(und|whatsapp|video|videoanruf|später|spaeter|morgen|facetime|teams|skype|um \d|in \d)\b""")

    private fun call(u: Utt): Intent? {
        val t = u.text
        val m = callA.find(t) ?: callB.find(t) ?: callC.find(t) ?: callD.find(t) ?: return null
        var who = u.rawOf(m.groups[1]).trim()
        var wn = Utt.norm(who)
        if (wn.isEmpty() || wn.split(' ').size > 5) return null
        if (callBad.containsMatchIn(wn)) return null
        for (art in listOf("den ", "dem ", "der ", "die ", "das ", "bei ", "mal ")) {
            if (wn.startsWith(art)) { who = who.substring(art.length).trim(); wn = Utt.norm(who) }
        }
        if (wn in setOf("mich", "uns", "an", "ihn", "sie", "es", "zurück", "zurueck", "")) return null
        return Intent.Call(who)
    }

    // ---------------------------------------------------------------- Wecker & Timer

    private val alarmWord = rx("""\b(wecker|weck(?:e)? mich|wecke|aufwecken|wecken|alarm|weckruf)\b""")
    private val alarmOff = rx("""\b(lösch|loesch|entfern|ausschalten|deaktivier|abstellen|stoppen|ausmachen|abschalten|wecker aus|alarm aus|ändere|aendere|verschieb)""")
    private val alarmQuestion = rx("""^(?:wann|welche[rn]?|ist|habe ich|hab ich|wie viele)\b""")
    private val relIn = rx("""\bin (?:einer |einem )?""")

    private fun alarm(u: Utt, now: LocalDateTime): Intent? {
        val t = u.text
        if (!alarmWord.containsMatchIn(t)) return null
        if (alarmOff.containsMatchIn(t)) return null
        if (alarmQuestion.containsMatchIn(t)) return Intent.Ask(Question.ALARM)
        // «weck mich in 20 Minuten»
        if (relIn.containsMatchIn(t)) {
            Durations.find(t)?.let { d ->
                val at = now.plusSeconds(d.seconds.toLong() + 30)
                return Intent.Alarm(at.hour, at.minute)
            }
        }
        val c = ClockTime.find(t) ?: return Intent.AlarmNoTime
        return Intent.Alarm(c.hour, c.minute)
    }

    private val timerWord = rx("""\b(timer|countdown|kurzzeitwecker|eieruhr)\b""")
    private val remindIn = rx("""^erinner(?:e)? mich in\b""")
    private val timerOff = rx("""\b(lösch|loesch|stopp|stop|abbrechen|beend|entfern|pausier|timer aus)""")
    private val timerLabel = rx(""" (?:an|ans|daran) (?:dass )?(.+)$| (?:für|fuer|namens|mit dem namen) (?:die |den |das )?([a-zäöü][a-zäöü -]{2,30})$""")

    private fun timer(u: Utt): Intent? {
        val t = u.text
        val remind = remindIn.containsMatchIn(t)
        if (!timerWord.containsMatchIn(t) && !remind) return null
        if (timerOff.containsMatchIn(t)) return null
        val d = Durations.find(t, bareMinutes = !remind) ?: return if (remind) null else Intent.TimerNoDuration
        if (d.seconds !in 1..86_400) return null
        var label = ""
        timerLabel.find(t)?.let { m ->
            val g = m.groups[1] ?: m.groups[2]
            if (g != null && d.ranges.none { r -> g.range.first <= r.last && r.first <= g.range.last }) {
                label = u.rawOf(g).trim().trimEnd('.', '!')
            }
        }
        if (Utt.norm(label).let { it.startsWith("minute") || it.startsWith("stunde") || it.startsWith("sekunde") }) label = ""
        return Intent.Timer(d.seconds, label)
    }

    // ---------------------------------------------------------------- Kalender

    private val calStart = rx("""^(?:(?:neuer|neuen|einen|ein) )?(?:termin|kalendereintrag|kalender eintrag)\b|^(?:erstell(?:e)?|mach(?:e)?|leg(?:e)?|plan(?:e)?|setz(?:e)?) (?:mir )?(?:einen |den )?(?:neuen )?(?:termin|kalendereintrag)\b|^trag(?:e)? .*\bein$|\bin (?:den|meinen) kalender\b""")
    private val calQuestion = rx("""^(?:wann|was|welche|habe ich|hab ich|wie viele|zeig|wo)\b""")
    private val calDuration = rx("""\b(?:für|fuer|dauer|dauert) (.+?(?:stunden?|minuten?|std|min))\b""")
    private val calRange = rx("""\bbis (\d{1,2}(?:[:.]\d{2})?|[a-zäöü]{3,12})(?: uhr(?: (\d{1,2}))?)?\b""")
    private val calNoise = setOf("termin", "kalendereintrag", "kalender", "eintrag", "neuer", "neuen", "einen", "ein", "eine", "erstelle",
        "erstell", "mache", "mach", "lege", "leg", "plane", "plan", "setze", "setz", "trag", "trage", "mir", "den", "meinen", "in",
        "am", "um", "uhr", "an", "ab", "von", "mit", "dem", "titel", "namens", "betreff", "für", "fuer", "zum", "zur", "auf",
        "früh", "frueh", "morgens", "abends", "nachmittags", "vormittags", "mittags", "nachts", "abend", "nachmittag", "vormittag")

    private fun calendar(u: Utt, now: LocalDateTime): Intent? {
        val t = u.text
        if (!calStart.containsMatchIn(t)) return null
        if (calQuestion.containsMatchIn(t)) return null
        val used = ArrayList<IntRange>()
        val date = Dates.find(t, now.toLocalDate())
        date?.let { used.add(it.range) }
        var minutes = 60
        var masked = t
        fun mask(r: IntRange) { used.add(r); masked = masked.replaceRange(r, "#".repeat(r.last - r.first + 1)) }
        date?.let { mask(it.range) }
        calDuration.find(masked)?.let { m ->
            Durations.find(m.groupValues[1])?.let { d -> minutes = (d.seconds / 60).coerceIn(5, 24 * 60); mask(m.range) }
        }
        var endH = -1
        var endM = 0
        calRange.find(masked)?.let { m ->
            val raw = m.groupValues[1]
            val hm = Regex("""^(\d{1,2})[:.](\d{2})$""").find(raw)
            if (hm != null) { endH = hm.groupValues[1].toInt(); endM = hm.groupValues[2].toInt() }
            else Numbers.parse(raw)?.let { endH = it; endM = m.groupValues[2].toIntOrNull() ?: 0 }
            if (endH in 0..24) mask(m.range) else endH = -1
        }
        val clock = ClockTime.find(masked)
        clock?.let { mask(it.range) }
        if (date == null && clock == null) return null
        var hour = clock?.hour ?: 0
        // Termine: «um 3» meint fast immer 15 Uhr
        if (clock != null && !clock.daypartGiven && hour in 1..6) hour += 12
        val day = date?.date ?: now.toLocalDate().let { d ->
            if (clock != null && (hour * 60 + clock.minute) <= now.hour * 60 + now.minute) d.plusDays(1) else d
        }
        val start = day.atTime(hour, clock?.minute ?: 0)
        if (clock != null && endH >= 0) {
            var eh = endH
            if (eh < hour && eh + 12 <= 23) eh += 12
            val diff = (eh * 60 + endM) - (hour * 60 + clock.minute)
            if (diff in 5..(16 * 60)) minutes = diff
        }
        // Titel = alles, was nicht Datum/Zeit/Füllwort ist
        val titleToks = ArrayList<String>()
        var pos = 0
        for (tok in u.toks) {
            val at = pos
            pos += tok.norm.length + 1
            if (used.any { at >= it.first && at <= it.last }) continue
            if (tok.norm in calNoise) continue
            titleToks.add(u.raw.substring(tok.start, tok.end))
        }
        val title = titleToks.joinToString(" ").trim().trim(',', ':', '-').ifBlank { "Termin" }
        return Intent.CalendarAdd(title.replaceFirstChar { it.uppercase() }, start, minutes, clock == null)
    }

    // ---------------------------------------------------------------- Lautstärke, Licht, Musik, Einstellungen

    private val volSet = rx("""\b(?:lautstärke|lautstaerke|volume|ton|lauti)\b.*?\b(\d{1,3}) ?(?:%|prozent)?""")
    private val volUp = rx("""^(?:mach (?:es |das |mal |bitte )*)?(?:(?:etwas |ein bisschen |viel |noch |bitte )*lauter|(?:lautstärke|lautstaerke|ton) (?:hoch|rauf|erhöhen|höher|hoeher|lauter))(?: stellen| machen| bitte)?$|^dreh (?:mal |die lautstärke )?(?:auf|hoch|lauter)$""")
    private val volDown = rx("""^(?:mach (?:es |das |mal |bitte )*)?(?:(?:etwas |ein bisschen |viel |noch |bitte )*leiser|(?:lautstärke|lautstaerke|ton) (?:runter|tiefer|leiser|senken|reduzieren))(?: stellen| machen| bitte)?$""")
    private val volMute = rx("""^(?:(?:ton|sound|lautstärke|lautstaerke|handy|telefon) (?:aus|stumm|lautlos)|stumm(?:schalten)?|lautlos|schalt(?:e)? (?:das handy |den ton )?(?:stumm|lautlos)|mach (?:das handy |den ton )?(?:stumm|lautlos|aus))$""")
    private val volUnmute = rx("""^(?:(?:ton|sound) (?:an|ein)|ton wieder an|stumm(?:schaltung)? aus|laut stellen)$""")
    private val volMax = rx("""^(?:volle lautstärke|maximale lautstärke|ganz laut|lautstärke (?:auf )?(?:voll|maximum|max))$""")

    private fun volume(t: String): Intent? {
        if (volMax.matches(t)) return Intent.Volume(VolumeOp.MAX)
        volSet.find(t)?.let { m ->
            val p = m.groupValues[1].toInt()
            if (p in 0..100) return Intent.Volume(VolumeOp.SET, p)
        }
        if (volUp.matches(t)) return Intent.Volume(VolumeOp.UP)
        if (volDown.matches(t)) return Intent.Volume(VolumeOp.DOWN)
        if (volMute.matches(t)) return Intent.Volume(VolumeOp.MUTE)
        if (volUnmute.matches(t)) return Intent.Volume(VolumeOp.UNMUTE)
        return null
    }

    private val torchRx = rx("""^(?:mach (?:mal |bitte )?(?:die |das )?|schalt(?:e)? (?:mal |bitte )?(?:die |das )?)?(?:taschenlampe|lampe|licht|blitzlicht)(?: (an|ein|aus|ab))?(?: ?(?:schalten|machen))?$""")
    private val torchInf = rx("""^(?:taschenlampe|lampe|licht) (einschalten|anschalten|anmachen|ausschalten|ausmachen|abschalten)$""")

    private fun torch(t: String): Intent? {
        torchInf.find(t)?.let { return Intent.Torch(!it.groupValues[1].startsWith("aus") && !it.groupValues[1].startsWith("ab")) }
        val m = torchRx.find(t) ?: return null
        return Intent.Torch(m.groupValues[1] != "aus" && m.groupValues[1] != "ab")
    }

    private val mediaPause = rx("""^(?:pause|pausier(?:e|en)?(?: (?:die |das )?(?:musik|lied|wiedergabe|video|podcast))?|(?:musik|wiedergabe|lied|podcast|video) (?:aus|stopp?|pause|anhalten|pausieren|stoppen)|stopp? (?:die |das )?(?:musik|wiedergabe|lied|podcast)|halt(?:e)? (?:die )?musik an|mach (?:die )?musik aus)$""")
    private val mediaPlay = rx("""^(?:play|musik (?:an|weiter|fortsetzen|abspielen)|spiel(?:e)? (?:die musik )?weiter|weiterspielen|wiedergabe (?:starten|fortsetzen)|mach (?:die )?musik (?:an|weiter)|spiel(?:e)? musik|musik)$""")
    private val mediaNext = rx("""^(?:(?:nächste[rs]?|naechste[rs]?) (?:lied|song|titel|track|stück|folge)|(?:lied |song |titel )?(?:überspringen|ueberspringen|skip|skippen)|weiter zum nächsten|nächstes|naechstes|next)$""")
    private val mediaPrev = rx("""^(?:(?:vorherige[rs]?|letzte[rs]?|voriges?) (?:lied|song|titel|track|stück)|(?:ein )?lied zurück|zurück zum (?:letzten|vorherigen)(?: lied| song)?|previous)$""")

    private fun media(t: String): Intent? = when {
        mediaPause.matches(t) -> Intent.Media(MediaOp.PAUSE)
        mediaNext.matches(t) -> Intent.Media(MediaOp.NEXT)
        mediaPrev.matches(t) -> Intent.Media(MediaOp.PREV)
        mediaPlay.matches(t) -> Intent.Media(MediaOp.PLAY)
        else -> null
    }

    private val settingNames = listOf(
        SettingKind.WIFI to """(?:wlan|w-lan|wifi|wi-fi|wlan einstellungen|wlaneinstellungen)""",
        SettingKind.BLUETOOTH to """(?:bluetooth|bluetooth einstellungen)""",
        SettingKind.AIRPLANE to """(?:flugmodus|flugzeugmodus|offline modus|offlinemodus)""",
        SettingKind.MOBILE_DATA to """(?:mobile daten|mobilen daten|datenverbindung|mobiles internet)""",
        SettingKind.LOCATION to """(?:standort|gps|ortung)""",
        SettingKind.DND to """(?:nicht stören|nicht stoeren|bitte nicht stören|ruhemodus)""",
        SettingKind.HOTSPOT to """(?:hotspot|mobiler hotspot|tethering)""",
        SettingKind.NFC to """(?:nfc)""",
        SettingKind.DISPLAY to """(?:display einstellungen|anzeige einstellungen|helligkeit|bildschirmhelligkeit|dunkelmodus|dark mode)""",
        SettingKind.SOUND to """(?:ton einstellungen|toneinstellungen|klingelton)""",
        SettingKind.BATTERY to """(?:akku einstellungen|energiesparmodus|stromsparmodus)""",
        SettingKind.GENERAL to """(?:einstellungen|die einstellungen|handy einstellungen|systemeinstellungen)"""
    )
    private val settingRx = settingNames.map { (k, n) ->
        k to rx("""^(?:mach (?:mal |bitte )?(?:das |den |die )?|schalt(?:e)? (?:mal |bitte )?(?:das |den |die )?|stell(?:e)? (?:das |den |die )?|öffne (?:mir )?(?:die |das )?|oeffne (?:die |das )?|zeig (?:mir )?(?:die |das )?|aktivier(?:e)? (?:das |den |die )?|deaktivier(?:e)? (?:das |den |die )?)?$n(?: einstellungen)?(?: (an|ein|aus|ab|einschalten|anschalten|ausschalten|abschalten|aktivieren|deaktivieren|öffnen|oeffnen|anmachen|ausmachen))?$""")
    }

    private fun setting(t: String): Intent? {
        for ((kind, r) in settingRx) {
            val m = r.find(t) ?: continue
            val w = m.groupValues[1]
            val deact = t.startsWith("deaktivier")
            val on: Boolean? = when {
                deact -> false
                w in setOf("an", "ein", "einschalten", "anschalten", "aktivieren", "anmachen") || t.startsWith("aktivier") -> true
                w in setOf("aus", "ab", "ausschalten", "abschalten", "deaktivieren", "ausmachen") -> false
                else -> null
            }
            // «Einstellungen» allein braucht ein Verb («öffne Einstellungen») oder steht ganz allein
            return Intent.Setting(kind, if (kind == SettingKind.GENERAL || kind == SettingKind.DISPLAY || kind == SettingKind.SOUND || kind == SettingKind.BATTERY) null else on)
        }
        return null
    }

    // ---------------------------------------------------------------- Musik, Navigation, Websuche

    private val playRx = rx("""^(?:spiel(?:e)?|spiel mir|spiele mir|hör(?:e)?|hoer(?:e)?|mach) (?:mir )?(?:mal |bitte )?(?:das lied |den song |die playlist |das album |musik von |etwas von |was von |lieder von |songs von )?(.+?)(?: (?:auf|in|bei|mit|über|ueber|via) (spotify|youtube music|youtube|apple music|deezer|soundcloud|tidal))?(?: ab| an)?$""")
    private val playBad = rx("""^(?:weiter|musik|die musik|lauter|leiser|an|aus|das|es|pause|mit|mal|ein spiel)$""")

    private fun play(u: Utt): Intent? {
        val t = u.text
        if (!(t.startsWith("spiel") || t.startsWith("hör") || t.startsWith("hoer"))) return null
        val m = playRx.find(t) ?: return null
        val q = u.rawOf(m.groups[1]).trim()
        val qn = Utt.norm(q)
        if (qn.isEmpty() || playBad.matches(qn)) return null
        if (t.startsWith("hör") && (qn.startsWith("auf") || qn.startsWith("zu") || qn.startsWith("mir zu"))) return null
        return Intent.Play(q, m.groups[2]?.value.orEmpty())
    }

    private val navA = rx("""^(?:navigier(?:e)?|navigation|navi|route|routenplaner|fahr(?:e)?|bring|führ(?:e)?|fuehr(?:e)?|lotse?)(?: mich| uns)? (?:nach|zu|zum|zur|in|ins|an|auf|bis) (.+)$""")
    private val navB = rx("""^wie (?:komme ich|kommen wir|komm ich|lange (?:brauche ich|habe ich|dauert es)|weit ist es)(?: am schnellsten| am besten| jetzt)* (?:nach|zu|zum|zur|in|ins|bis|bis nach|an) (.+)$""")
    private val navC = rx("""^(?:zeig(?:e)? (?:mir )?(?:den|die) (?:weg|route)|weg|route|wegbeschreibung) (?:nach|zu|zum|zur|in|ins) (.+)$""")
    private val navMode = rx(""" (?:mit (?:dem |der |den )?(auto|wagen|zug|bahn|öv|oev|bus|tram|öffentlichen verkehr|oeffentlichen|öffentlichen|velo|fahrrad|rad|e-bike)|(zu fuss|zu fuß|laufen))$""")

    private fun navigate(u: Utt): Intent? {
        val t = u.text
        val (m, start) = navA.find(t)?.let { it to true } ?: navB.find(t)?.let { it to false } ?: navC.find(t)?.let { it to false } ?: return null
        var dest = u.rawOf(m.groups[1]).trim().trimEnd('?', '.', '!')
        var mode: TravelMode? = null
        navMode.find(Utt.norm(dest).let { " $it" })?.let { mm ->
            val w = mm.groupValues[1].ifEmpty { mm.groupValues[2] }
            mode = when {
                w.startsWith("auto") || w.startsWith("wagen") -> TravelMode.DRIVE
                w.startsWith("velo") || w.contains("rad") || w.contains("bike") -> TravelMode.BIKE
                w.startsWith("zu fu") || w == "laufen" -> TravelMode.WALK
                else -> TravelMode.TRANSIT
            }
            val du = Utt(dest)
            val cutTok = du.tokAt((mm.range.first).coerceAtMost(du.text.length - 1).coerceAtLeast(0))
            dest = du.rawToks(0, cutTok + if (mm.range.first >= du.text.length) 1 else 0).ifBlank { du.rawToks(0, du.toks.size - mm.value.trim().split(' ').size) }
        }
        dest = dest.trim().trimEnd(',', '.')
        val dn = Utt.norm(dest)
        if (dn.isEmpty() || dn.split(' ').size > 8) return null
        if (dn in setOf("hause", "nach hause", "heim", "daheim")) dest = "Zuhause"
        return Intent.Navigate(dest, mode, start && mode != TravelMode.TRANSIT)
    }

    private val webA = rx("""^(?:such(?:e)?|schau|guck|finde?)(?: mal| bitte| mir)* (?:im internet|im web|bei google|auf google|online|im netz|im browser)(?: nach)? (.+)$""")
    private val webB = rx("""^(?:google|googel|googeln|googlen|googele)(?: mal| bitte)*(?: nach)? (.+)$""")
    private val webC = rx("""^such(?:e)?(?: mal| bitte| mir)*(?: nach)? (.+?) (?:im internet|im web|bei google|auf google|online|im netz)$""")

    private fun webSearch(u: Utt): Intent? {
        val t = u.text
        val m = webA.find(t) ?: webB.find(t) ?: webC.find(t) ?: return null
        val q = u.rawOf(m.groups[1]).trim().trimEnd('.', '!')
        if (Utt.norm(q) in setOf("maps", "play", "chrome", "kalender", "fotos", "drive")) return null
        return if (q.isBlank()) null else Intent.WebSearch(q)
    }

    // ---------------------------------------------------------------- Fragen ohne KI

    private val qTime = rx("""^(?:wie spät (?:ist es|haben wir|ist)|wie spaet ist es|wieviel uhr (?:ist es|haben wir)|wie viel uhr (?:ist es|haben wir)|was ist es für zeit|uhrzeit|sag mir die uhrzeit|wie spät|was für zeit isch|wieviel uhr)$""")
    private val qDate = rx("""^(?:welche[rsn]? (?:tag|datum|wochentag)(?: (?:ist|haben wir))?(?: heute)?|der wievielte ist heute|den wievielten haben wir(?: heute)?|was für ein tag ist heute|was ist heute für ein tag|welches datum haben wir|datum|welcher tag ist heute)$""")
    private val qBattery = rx("""^(?:wie (?:viel|ist|steht|hoch ist)? ?(?:es um )?(?:der |den |mein(?:en)? )?(?:akku|akkustand|batterie)(?:stand)?(?: habe ich| hab ich| noch)?|akku|akkustand|batterie|batteriestand|wie voll ist (?:der|mein) akku|wie viel akku(?: habe ich| hab ich)?(?: noch)?)$""")
    private val qWeather = rx("""\b(wetter|regnet|regnen|regen|schirm|regenschirm|schneit|schnee|sonne|sonnig|wie warm|wie kalt|temperatur draussen|grad draussen|jacke)\b""")
    private val qWeatherBad = rx("""\b(in [a-zäöü]{3,}|nächste woche|wochenende|samstag|sonntag|montag|dienstag|mittwoch|donnerstag|freitag|übermorgen)\b""")
    private val qAgenda = rx("""^(?:was (?:steht|liegt) (?:heute|morgen)? ?(?:alles )?an|was habe ich (?:heute|morgen)(?: alles)?(?: vor| geplant| für termine)?|was hab ich (?:heute|morgen)(?: vor)?|(?:meine |die )?termine(?: (?:heute|morgen|von heute|für heute|für morgen))?|nächster termin|naechster termin|wann ist mein nächster termin|(?:zeig|lies) (?:mir )?(?:meine |die )?termine(?: vor)?(?: (?:heute|morgen))?|kalender (?:heute|morgen)|habe ich (?:heute|morgen) (?:termine|etwas vor))$""")
    private val qNotif = rx("""^(?:(?:was gibt es|was gibts|gibt es(?: was| etwas)?) neues|(?:habe ich|hab ich|gibt es) (?:neue |ungelesene )?(?:nachrichten|meldungen|benachrichtigungen|mails)|(?:lies|lese|zeig)(?: mir)? (?:meine |die )?(?:neuen |ungelesenen |letzten )?(?:nachrichten|benachrichtigungen|meldungen)(?: vor)?|(?:neue|ungelesene|meine) (?:nachrichten|benachrichtigungen|meldungen)|wer hat (?:mir )?geschrieben)$""")
    private val qCosts = rx("""\b(guthaben|was hat das gekostet|wie viel hat das gekostet|kosten (?:heute|diesen monat|bisher)|wie viel (?:habe ich |hab ich )?(?:verbraucht|ausgegeben)|was kostest du|verbrauch)\b""")
    private val qBriefing = rx("""^(?:guten morgen|morgen|briefing|morgenbriefing|morgen briefing|tagesbriefing|lagebericht|statusbericht|status|tagesübersicht|tagesueberblick|tagesüberblick|was liegt an|übersicht|mein tag)$""")
    private val qMarkets = rx("""\b(börse|boerse|aktien|aktienkurse|märkte|maerkte|kurse|smi|dax|nasdaq|bitcoin|s&p|dow jones)\b""")

    private fun ask(t: String): Intent? {
        if (qTime.matches(t)) return Intent.Ask(Question.TIME)
        if (qDate.matches(t)) return Intent.Ask(Question.DATE)
        if (qBattery.matches(t)) return Intent.Ask(Question.BATTERY)
        if (qBriefing.matches(t)) return Intent.Ask(Question.BRIEFING)
        if (qAgenda.matches(t)) return Intent.Ask(if (t.contains("morgen")) Question.AGENDA_TOMORROW else Question.AGENDA)
        if (qNotif.matches(t)) return Intent.Ask(Question.NOTIFICATIONS)
        val words = t.split(' ').size
        if (words <= 9 && qWeather.containsMatchIn(t) && !qWeatherBad.containsMatchIn(t)) {
            return Intent.Ask(if (rx("""(?<!heute )\bmorgen\b""").containsMatchIn(t)) Question.WEATHER_TOMORROW else Question.WEATHER)
        }
        if (words <= 8 && qCosts.containsMatchIn(t)) return Intent.Ask(Question.COSTS)
        if (words <= 7 && qMarkets.containsMatchIn(t) && (t.startsWith("wie") || t.startsWith("was") || words <= 3)) return Intent.Ask(Question.MARKETS)
        return null
    }

    // ---------------------------------------------------------------- Projekte, Apps

    private val projectRx = rx("""^(?:das )?projekt (.+?) (?:ist|steht|liegt)?(?: jetzt| nun)? ?(?:zu|bei|auf) (\d{1,3}) ?(?:%|prozent)(?: fertig| erledigt| abgeschlossen)?(?: (.+))?$""")
    private val projectDone = rx("""^(?:das )?projekt (.+?) ist (?:fertig|erledigt|abgeschlossen)$""")

    private fun project(u: Utt): Intent? {
        val t = u.text
        projectRx.find(t)?.let { m ->
            val p = m.groupValues[2].toInt()
            if (p in 0..100) return Intent.Project(u.rawOf(m.groups[1]), p, m.groups[3]?.let { u.rawOf(it) }.orEmpty())
        }
        projectDone.find(t)?.let { m -> return Intent.Project(u.rawOf(m.groups[1]), 100, "abgeschlossen") }
        return null
    }

    private val openA = rx("""^(?:öffne|oeffne|öffnen|starte|start|launch|geh (?:auf|zu|in)|wechsle? (?:zu|auf|in))(?: mir)?(?: mal| bitte)* (?:die |das |den |meine |mein )?(?:app )?(.+?)(?: app)?$""")
    private val openB = rx("""^mach(?:e)?(?: mir)?(?: mal| bitte)* (?:die |das |den )?(?:app )?(.+?)(?: app)? auf$""")
    private val openC = rx("""^(?:die |das |den )?(?:app )?(.+?)(?: app)? (?:öffnen|oeffnen|starten|aufmachen)$""")
    private val openBad = rx("""\b(und|chat|mit|von|nachricht|mail an|an|für|dass|wo|wie|datei|link|seite|fenster|tür|tuer|garage|letzte|neue|neuen|foto von|den chat)\b""")

    private fun openApp(u: Utt): Intent? {
        val t = u.text
        val m = openA.find(t) ?: openB.find(t) ?: openC.find(t) ?: return null
        val app = u.rawOf(m.groups[1]).trim().trimEnd('.', '!')
        val an = Utt.norm(app)
        if (an.isEmpty() || an.split(' ').size > 3) return null
        if (openBad.containsMatchIn(an)) return null
        return Intent.OpenApp(app)
    }
}
