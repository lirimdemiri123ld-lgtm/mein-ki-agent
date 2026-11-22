package ch.lirim.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class IntentsTest {
    private val now = LocalDateTime.of(2026, 11, 22, 19, 14)   // Sonntag
    private fun p(s: String) = Intents.parse(s, now)

    @Test fun cleanRemovesWakeWordAndPoliteness() {
        assertEquals("stell einen wecker um 7", Intents.clean("Hallo Jarvis, stell bitte einen Wecker um 7").text.replace("bitte ", ""))
        assertEquals("öffne spotify", Intents.clean("Hey Jarvis öffne Spotify bitte").text)
        assertEquals("ali anrufen", Intents.clean("Jarvis, kannst du mal Ali anrufen?").text)
        assertEquals("einen timer auf 10 minuten", Intents.clean("Ich möchte einen Timer auf 10 Minuten").text)
        assertEquals("", Intents.clean("Hallo Jarvis").text)
    }

    @Test fun alarms() {
        assertEquals(Intent.Alarm(6, 30), p("Wecker um halb 7"))
        assertEquals(Intent.Alarm(6, 30), p("Hallo Jarvis, stell einen Wecker auf 6:30 Uhr"))
        assertEquals(Intent.Alarm(6, 30), p("Weck mich um halb sieben"))
        assertEquals(Intent.Alarm(7, 0), p("Stell mir bitte den Wecker auf 7"))
        assertEquals(Intent.Alarm(5, 45), p("kannst du mich morgen früh um viertel vor 6 wecken"))
        assertEquals(Intent.Alarm(19, 34), p("weck mich in 20 Minuten"))
        assertEquals(Intent.Alarm(20, 14), p("Wecker in einer Stunde"))
        assertEquals(Intent.AlarmNoTime, p("Stell einen Wecker"))
        assertEquals(Intent.Ask(Question.ALARM), p("Wann klingelt mein Wecker?"))
        assertNull(p("Lösch den Wecker um 7"))
        assertNull(p("Wecker ausschalten"))
    }

    @Test fun timers() {
        assertEquals(Intent.Timer(600), p("Timer 10 Minuten"))
        assertEquals(Intent.Timer(600), p("Stell einen Timer auf zehn Minuten"))
        assertEquals(Intent.Timer(600), p("Timer 10"))
        assertEquals(Intent.Timer(5400), p("Timer auf anderthalb Stunden"))
        assertEquals(Intent.Timer(180, "Eier"), p("Timer 3 Minuten für Eier"))
        assertEquals(Intent.Timer(900, "die Wäsche"), p("Erinnere mich in 15 Minuten an die Wäsche"))
        assertEquals(Intent.TimerNoDuration, p("Stell einen Timer"))
        assertNull(p("Stopp den Timer"))
    }

    @Test fun calls() {
        assertEquals(Intent.Call("meine Frau"), p("Hallo Jarvis, ruf meine Frau an"))
        assertEquals(Intent.Call("Gruja"), p("Ruf Gruja an"))
        assertEquals(Intent.Call("Ali"), p("Ruf mal den Ali an"))
        assertEquals(Intent.Call("Ali Bellikaya"), p("Ali Bellikaya anrufen"))
        assertEquals(Intent.Call("meinem Chef"), p("Telefonier mit meinem Chef"))
        assertEquals(Intent.Call("079 123 45 67"), p("Wähle 079 123 45 67"))
        assertEquals(Intent.Call("Baba"), p("Ruf bei Baba an"))
        assertEquals(Intent.Call("Ali"), p("Ruf Ali zurück"))
        assertNull(p("Ruf Ali auf WhatsApp an"))
        assertNull(p("Ruf Ali morgen an"))
        assertNull(p("Ruf mich an"))
    }

    @Test fun messagesWithClause() {
        val m = p("Schreib meiner Frau auf WhatsApp, dass wir zu Pronto gehen") as Intent.Message
        assertEquals(Channel.WHATSAPP, m.channel)
        assertEquals("meiner Frau", m.to)
        assertEquals(Body.Clause("dass", "wir zu Pronto gehen"), m.body)
        assertTrue(m.sure)

        val m2 = p("Sag Ali dass ich 10 Minuten später komme") as Intent.Message
        assertNull(m2.channel)
        assertEquals("Ali", m2.to)
        assertEquals(Body.Clause("dass", "ich 10 Minuten später komme"), m2.body)

        val m3 = p("Schick Gruja eine WhatsApp, dass ich im Stau stehe und später komme.") as Intent.Message
        assertEquals("Gruja", m3.to)
        assertEquals(Body.Clause("dass", "ich im Stau stehe und später komme"), m3.body)

        val m4 = p("Frag meine Frau per SMS ob sie Milch kaufen kann") as Intent.Message
        assertEquals(Channel.SMS, m4.channel)
        assertEquals("meine Frau", m4.to)
        assertEquals(Body.Clause("ob", "sie Milch kaufen kann"), m4.body)

        val m5 = p("Kannst du meiner Frau auf WhatsApp schreiben, dass ich unterwegs bin") as Intent.Message
        assertEquals("meiner Frau", m5.to)
        assertEquals(Channel.WHATSAPP, m5.channel)
        assertEquals(Body.Clause("dass", "ich unterwegs bin"), m5.body)
    }

    @Test fun messagesLiteralAndMissingText() {
        val m = p("WhatsApp an Ali: Komme um 6:30, ok?") as Intent.Message
        assertEquals("Ali", m.to)
        assertEquals(Body.Literal("Komme um 6:30, ok?"), m.body)

        val m2 = p("Schreib meiner Frau auf WhatsApp") as Intent.Message
        assertEquals("meiner Frau", m2.to)
        assertEquals(Body.None, m2.body)

        val m3 = p("Schreib Ali auf WhatsApp bin gleich da") as Intent.Message
        assertEquals("Ali", m3.to)
        assertEquals(Body.Literal("bin gleich da"), m3.body)

        val m4 = p("Schreib eine Nachricht an meine Frau") as Intent.Message
        assertEquals("meine Frau", m4.to)
        assertNull(m4.channel)

        val m5 = p("Schick eine SMS an Ali Bellikaya: Bin in 5 Minuten da") as Intent.Message
        assertEquals(Channel.SMS, m5.channel)
        assertEquals("Ali Bellikaya", m5.to)
        assertEquals(Body.Literal("Bin in 5 Minuten da"), m5.body)

        val m6 = p("Schreib Ali auf Telegram dass ich komme") as Intent.Message
        assertEquals(Channel.OTHER, m6.channel)
        assertEquals("Telegram", m6.channelName)

        val m7 = p("Schreib eine Mail an Sebastian, dass der Bericht fertig ist") as Intent.Message
        assertEquals(Channel.MAIL, m7.channel)
        assertEquals("Sebastian", m7.to)
    }

    @Test fun notMessages() {
        assertNull(p("Sag mir einen Witz"))
        assertNull(p("Sag mal, wie hoch ist der Eiffelturm"))
        assertNull(p("Frag Ali"))
        assertNull(p("Schreib das auf"))
        assertNull(p("Wie schreibt man Rhythmus"))
        // «schreib Ali» ohne Kanal: unsicher – wird erst mit bekanntem Kontakt zur Nachricht
        val m = p("Schreib Ali ich komme später") as Intent.Message
        assertTrue(!m.sure)
    }

    @Test fun compoundGoesToCloud() {
        assertNull(p("Öffne WhatsApp und schreib Ali"))
        assertNull(p("Stell einen Wecker um 7 und dann öffne Spotify"))
        // «und» im Nachrichtentext ist kein zusammengesetzter Auftrag
        assertTrue(p("Schreib Ali auf WhatsApp, dass ich komme und schreib ihm dann nochmal") is Intent.Message)
    }

    @Test fun openApps() {
        assertEquals(Intent.OpenApp("Spotify"), p("Öffne Spotify"))
        assertEquals(Intent.OpenApp("WhatsApp"), p("Jarvis, starte bitte WhatsApp"))
        assertEquals(Intent.OpenApp("Google Maps"), p("Öffne Google Maps"))
        assertEquals(Intent.OpenApp("Kamera"), p("Mach die Kamera auf"))
        assertEquals(Intent.OpenApp("SBB"), p("Kannst du die SBB App öffnen"))
        assertEquals(Intent.OpenApp("YouTube Music"), p("YouTube Music starten"))
        assertNull(p("Öffne den Chat mit Ali"))
        assertNull(p("Öffne die letzte Mail von Sebastian"))
    }

    @Test fun deviceControls() {
        assertEquals(Intent.Volume(VolumeOp.UP), p("lauter"))
        assertEquals(Intent.Volume(VolumeOp.UP), p("Mach mal etwas lauter"))
        assertEquals(Intent.Volume(VolumeOp.DOWN), p("Mach leiser"))
        assertEquals(Intent.Volume(VolumeOp.SET, 50), p("Lautstärke auf 50 Prozent"))
        assertEquals(Intent.Volume(VolumeOp.SET, 30), p("Stell die Lautstärke auf 30%"))
        assertEquals(Intent.Volume(VolumeOp.MUTE), p("Ton aus"))
        assertEquals(Intent.Volume(VolumeOp.MAX), p("Volle Lautstärke"))
        assertEquals(Intent.Torch(true), p("Taschenlampe an"))
        assertEquals(Intent.Torch(false), p("Mach die Taschenlampe aus"))
        assertEquals(Intent.Torch(true), p("Licht"))
        assertEquals(Intent.Torch(false), p("Taschenlampe ausschalten"))
        assertEquals(Intent.Media(MediaOp.PAUSE), p("Pause"))
        assertEquals(Intent.Media(MediaOp.PAUSE), p("Stopp die Musik"))
        assertEquals(Intent.Media(MediaOp.PLAY), p("Musik weiter"))
        assertEquals(Intent.Media(MediaOp.NEXT), p("Nächstes Lied"))
        assertEquals(Intent.Media(MediaOp.PREV), p("Vorheriges Lied"))
        assertEquals(Intent.Setting(SettingKind.WIFI, false), p("WLAN aus"))
        assertEquals(Intent.Setting(SettingKind.BLUETOOTH, true), p("Schalte Bluetooth ein"))
        assertEquals(Intent.Setting(SettingKind.WIFI, null), p("Öffne die WLAN Einstellungen"))
        assertEquals(Intent.Setting(SettingKind.GENERAL, null), p("Öffne die Einstellungen"))
        assertEquals(Intent.Setting(SettingKind.AIRPLANE, true), p("Flugmodus an"))
    }

    @Test fun musicNavigationSearch() {
        assertEquals(Intent.Play("Eminem", "spotify"), p("Spiel Eminem auf Spotify"))
        assertEquals(Intent.Play("Bohemian Rhapsody", ""), p("Spiel das Lied Bohemian Rhapsody"))
        assertEquals(Intent.Media(MediaOp.PLAY), p("Spiel weiter"))
        assertEquals(Intent.Navigate("Zürich Hauptbahnhof", null, true), p("Navigiere nach Zürich Hauptbahnhof"))
        assertEquals(Intent.Navigate("Regensdorf", TravelMode.TRANSIT, false), p("Wie komme ich nach Regensdorf mit dem Zug?"))
        assertEquals(Intent.Navigate("Zuhause", null, true), p("Bring mich nach Hause"))
        assertEquals(Intent.Navigate("Basel SBB", null, false), p("Wie komme ich am schnellsten nach Basel SBB"))
        assertEquals(Intent.WebSearch("Öffnungszeiten Migros Pratteln"), p("Such im Internet nach Öffnungszeiten Migros Pratteln"))
        assertEquals(Intent.WebSearch("Wetter Basel"), p("Google Wetter Basel"))
    }

    @Test fun calendarEntries() {
        val a = p("Termin morgen um 15 Uhr Zahnarzt") as Intent.CalendarAdd
        assertEquals("Zahnarzt", a.title)
        assertEquals(LocalDateTime.of(2026, 11, 23, 15, 0), a.start)
        assertEquals(60, a.minutes)

        val b = p("Trag Zahnarzt am Freitag um 10 Uhr in den Kalender ein") as Intent.CalendarAdd
        assertEquals("Zahnarzt", b.title)
        assertEquals(LocalDateTime.of(2026, 11, 27, 10, 0), b.start)

        val c = p("Erstelle einen Termin am 3. Dezember von 14 bis 16 Uhr Teammeeting") as Intent.CalendarAdd
        assertEquals("Teammeeting", c.title)
        assertEquals(LocalDateTime.of(2026, 12, 3, 14, 0), c.start)
        assertEquals(120, c.minutes)

        val d = p("Neuer Termin übermorgen um 3 Coiffeur") as Intent.CalendarAdd
        assertEquals(LocalDateTime.of(2026, 11, 24, 15, 0), d.start)
        assertEquals("Coiffeur", d.title)
        assertNull(p("Wann ist mein nächster Termin beim Zahnarzt"))
    }

    @Test fun questionsWithoutAi() {
        assertEquals(Intent.Ask(Question.TIME), p("Wie spät ist es?"))
        assertEquals(Intent.Ask(Question.DATE), p("Welcher Tag ist heute"))
        assertEquals(Intent.Ask(Question.BATTERY), p("Wie viel Akku habe ich noch?"))
        assertEquals(Intent.Ask(Question.WEATHER), p("Wie wird das Wetter heute?"))
        assertEquals(Intent.Ask(Question.WEATHER_TOMORROW), p("Regnet es morgen?"))
        assertEquals(Intent.Ask(Question.WEATHER), p("Brauche ich einen Schirm"))
        assertEquals(Intent.Ask(Question.AGENDA), p("Was steht heute an?"))
        assertEquals(Intent.Ask(Question.AGENDA_TOMORROW), p("Was habe ich morgen vor"))
        assertEquals(Intent.Ask(Question.NOTIFICATIONS), p("Habe ich neue Nachrichten?"))
        assertEquals(Intent.Ask(Question.NOTIFICATIONS), p("Lies mir meine Nachrichten vor"))
        assertEquals(Intent.Ask(Question.COSTS), p("Wie viel Guthaben habe ich noch"))
        assertEquals(Intent.Ask(Question.BRIEFING), p("Guten Morgen"))
        assertEquals(Intent.Ask(Question.MARKETS), p("Wie steht der SMI?"))
        assertEquals(Intent.Ask(Question.HELP), p("Was kannst du alles"))
        assertNull(p("Wie wird das Wetter nächste Woche in Rom"))
        assertNull(p("Wie hoch ist der Eiffelturm"))
    }

    @Test fun memoryCommands() {
        assertEquals(Intent.Remember("ich montags Homeoffice habe"), p("Merk dir, dass ich montags Homeoffice habe"))
        assertEquals(Intent.Relation("Gruja", "frau"), p("Gruja ist meine Frau"))
        assertEquals(Intent.Relation("Gruja", "frau"), p("Meine Frau heisst Gruja"))
        assertEquals(Intent.Relation("Gruja", "frau"), p("Merk dir dass Gruja meine Frau ist"))
        assertEquals(Intent.Relation("Sebastian Lorenzen", "chef"), p("Mein Chef ist Sebastian Lorenzen"))
        assertEquals(Intent.Forget("Homeoffice"), p("Vergiss das mit Homeoffice"))
        assertEquals(Intent.Stop, p("Vergiss es"))
        assertEquals(Intent.Recall("Gruja"), p("Was weisst du über Gruja?"))
        assertEquals(Intent.Recall("mein frau"), p("Wie heisst meine Frau"))
        assertNull(p("Mein Chef ist krank"))
        assertNull(p("Das ist meine Frau"))
    }

    @Test fun controlAndCloud() {
        assertEquals(Intent.Stop, p("Stopp"))
        assertEquals(Intent.Stop, p("Jarvis, abbrechen"))
        assertEquals(Intent.Repeat, p("Wie bitte?"))
        assertEquals(Intent.WrongContact, p("Falscher Kontakt"))
        assertEquals(Intent.WrongContact, p("Nein, das war die falsche Nummer"))
        assertEquals(Intent.Cloud("fass meine Mails zusammen", true), p("Mit Claude Sonnet fass meine Mails zusammen"))
        assertEquals(Intent.Cloud("buche einen Tisch bei Pronto", true), p("Gründlich: buche einen Tisch bei Pronto"))
        assertEquals(Intent.Cloud("was ist ein Schwarzes Loch", false), p("Frag Claude was ist ein Schwarzes Loch"))
        assertEquals(Intent.Project("Weihnachtsfeier", 40, ""), p("Projekt Weihnachtsfeier ist zu 40 Prozent fertig"))
    }

    @Test fun unknownGoesOn() {
        assertNull(p("Fass meine ungelesenen Outlook-Mails zusammen"))
        assertNull(p("Such mir die nächste Verbindung nach Regensdorf"))
        assertNull(p("Erzähl mir etwas über Pratteln"))
        assertNull(p(""))
    }
}
