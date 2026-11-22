package ch.lirim.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LangTest {

    // ---------------------------------------------------------------- JSON

    @Test fun jsonRoundTrip() {
        val src = """{"a":1,"b":[true,false,null,"x\ny"],"c":{"d":1.5,"e":"ä\"ö"},"f":-3,"g":1.25e1}"""
        val o = Json.obj(src)
        assertEquals(1, o.int("a"))
        assertEquals(listOf(true, false, null, "x\ny"), o.list("b"))
        assertEquals(1.5, o.obj("c")!!.dbl("d"), 0.0)
        assertEquals("ä\"ö", o.obj("c")!!.str("e"))
        assertEquals(-3, o.int("f"))
        assertEquals(12.5, o.dbl("g"), 0.0)
        val again = Json.obj(Json.write(o))
        assertEquals(o, again)
    }

    @Test fun jsonKeepsOrderAndEscapes() {
        val o = jobj("z" to 1, "a" to "Zeile\n«Text»", "m" to jarr(1, 2.5, "x"))
        assertEquals("""{"z":1,"a":"Zeile\n«Text»","m":[1,2.5,"x"]}""", Json.write(o))
        assertEquals("ü", Json.obj("""{"k":"ü"}""").str("k"))
    }

    @Test fun jsonBadInputGivesEmpty() {
        assertTrue(Json.obj("kaputt").isEmpty())
        assertTrue(Json.obj("""{"a":""").isEmpty())
        assertTrue(Json.arr(null).isEmpty())
    }

    // ---------------------------------------------------------------- Äusserung

    @Test fun uttNormalizesAndKeepsOriginal() {
        val u = Utt("Schreib meiner Frau auf WhatsApp, dass wir zu Pronto gehen.")
        assertEquals("schreib meiner frau auf whatsapp dass wir zu pronto gehen", u.text)
        val i = u.text.indexOf("wir")
        assertEquals("wir zu Pronto gehen.", u.rawFrom(i))
        assertEquals("meiner Frau", u.rawOf(8..18))
    }

    @Test fun uttColonAndSpecialTokens() {
        val u = Utt("WhatsApp an Ali: Komme um 6:30, ok?")
        assertEquals("whatsapp an ali komme um 6:30 ok", u.text)
        assertEquals(2, u.colonAfter)
        assertEquals("Komme um 6:30, ok?", u.raw.substring(u.toks[3].start))
        assertEquals("strasse 100% +41791234567", Utt("Straße 100% +41791234567").text)
    }

    // ---------------------------------------------------------------- Zahlen

    @Test fun numberWords() {
        assertEquals(7, Numbers.parse("sieben"))
        assertEquals(21, Numbers.parse("einundzwanzig"))
        assertEquals(35, Numbers.parse("fünfunddreissig"))
        assertEquals(30, Numbers.parse("dreißig"))
        assertEquals(12, Numbers.parse("12"))
        assertNull(Numbers.parse("wecker"))
        assertEquals(2, Numbers.ordinal("zweiten"))
        assertEquals(1, Numbers.ordinal("erste"))
        assertEquals(-1, Numbers.ordinal("letzte"))
    }

    // ---------------------------------------------------------------- Uhrzeit

    private fun clock(s: String): String? = ClockTime.find(Utt.norm(s))?.let { ClockTime.hhmm(it.hour, it.minute) }

    @Test fun clockTimes() {
        assertEquals("06:30", clock("Wecker um halb 7"))
        assertEquals("06:30", clock("weck mich um halb sieben"))
        assertEquals("06:30", clock("stell einen Wecker auf 6:30 Uhr"))
        assertEquals("06:30", clock("Wecker um 6.30"))
        assertEquals("06:30", clock("Wecker auf 6 Uhr 30"))
        assertEquals("06:30", clock("Wecker um sechs Uhr dreissig"))
        assertEquals("06:30", clock("Wecker um 6 30"))
        assertEquals("07:00", clock("Wecker um 7"))
        assertEquals("07:00", clock("weck mich um sieben Uhr"))
        assertEquals("19:00", clock("Wecker um 7 Uhr abends"))
        assertEquals("19:30", clock("Wecker heute Abend um halb 8"))
        assertEquals("06:15", clock("viertel nach 6"))
        assertEquals("06:45", clock("viertel vor 7"))
        assertEquals("06:45", clock("dreiviertel 7"))
        assertEquals("06:10", clock("10 nach 6"))
        assertEquals("06:55", clock("fünf vor sieben"))
        assertEquals("18:05", clock("um 18:05"))
        assertEquals("06:00", clock("morgen früh um 6"))
        assertEquals("23:00", clock("um 11 nachts"))
        assertEquals("02:00", clock("um 2 nachts"))
        assertEquals("14:00", clock("um 2 Uhr nachmittags"))
        assertEquals("06:30", clock("Wecker am halbi 7"))
        assertNull(clock("Timer auf 10 Minuten"))
        assertNull(clock("Öffne Spotify"))
    }

    // ---------------------------------------------------------------- Dauer

    private fun dur(s: String, bare: Boolean = false) = Durations.find(Utt.norm(s), bare)?.seconds

    @Test fun durations() {
        assertEquals(600, dur("Timer 10 Minuten"))
        assertEquals(600, dur("Timer auf zehn Minuten"))
        assertEquals(5400, dur("Timer 1 Stunde 30 Minuten"))
        assertEquals(5400, dur("anderthalb Stunden"))
        assertEquals(5400, dur("1,5 Stunden"))
        assertEquals(1800, dur("eine halbe Stunde"))
        assertEquals(900, dur("Timer Viertelstunde"))
        assertEquals(2700, dur("eine Dreiviertelstunde"))
        assertEquals(90, dur("90 Sekunden"))
        assertEquals(150, dur("2 Minuten 30 Sekunden"))
        assertEquals(9000, dur("zweieinhalb Stunden"))
        assertEquals(300, dur("5 min"))
        assertEquals(7200, dur("2h"))
        assertEquals(4500, dur("1 Stunde 15"))
        assertEquals(600, dur("Timer 10", bare = true))
        assertNull(dur("Timer 10"))
        assertNull(dur("Öffne den Timer"))
        assertEquals("1 Stunde und 30 Minuten", Durations.spoken(5400))
        assertEquals("10 Minuten", Durations.spoken(600))
    }

    // ---------------------------------------------------------------- Datum

    private val sunday = LocalDate.of(2026, 11, 22)
    private fun date(s: String) = Dates.find(Utt.norm(s), sunday)?.date

    @Test fun dates() {
        assertEquals(sunday, date("Termin heute um 15 Uhr"))
        assertEquals(sunday.plusDays(1), date("Termin morgen um 15 Uhr"))
        assertEquals(sunday.plusDays(1), date("morgen früh um 6"))
        assertEquals(sunday.plusDays(2), date("übermorgen"))
        assertEquals(LocalDate.of(2026, 11, 27), date("am Freitag um 10"))
        assertEquals(LocalDate.of(2026, 11, 29), date("nächsten Sonntag"))
        assertEquals(LocalDate.of(2026, 12, 3), date("am 3. Dezember"))
        assertEquals(LocalDate.of(2027, 1, 5), date("am 5. Januar"))
        assertEquals(LocalDate.of(2026, 12, 3), date("am 3.12. um 15 Uhr"))
        assertEquals(LocalDate.of(2027, 3, 1), date("1.3.2027"))
        assertEquals(sunday.plusDays(3), date("in drei Tagen"))
        assertEquals(sunday.plusDays(14), date("in 2 Wochen"))
        assertEquals(sunday, date("heute morgen"))
        assertNull(date("guten Morgen"))
        assertNull(date("Wecker am Morgen um 6"))
        assertEquals("morgen", Dates.spoken(sunday.plusDays(1), sunday))
        assertEquals("am Freitag, 27. November", Dates.spoken(LocalDate.of(2026, 11, 27), sunday))
    }

    // ---------------------------------------------------------------- Ja / Nein / Auswahl

    @Test fun yesNo() {
        for (y in listOf("Ja", "jo mach", "ja bitte", "genau", "okay", "Jawohl", "klar doch", "natürlich", "senden", "jä", "warum nicht"))
            assertEquals(y, true, YesNo.parse(y))
        for (n in listOf("nein", "nei danke", "lieber nicht", "nö", "stopp", "auf keinen Fall", "nein, die mit +41", "bitte nicht", "abbrechen"))
            assertEquals(n, false, YesNo.parse(n))
        assertNull(YesNo.parse("was?"))
        assertNull(YesNo.parse(""))
        assertNull(YesNo.parse("Gruja"))
    }

    @Test fun choices() {
        val sims = listOf("SIM 1 Swisscom", "SIM 2 Salt")
        assertEquals(0, Choice.parse("SIM 1", sims))
        assertEquals(1, Choice.parse("die zweite", sims))
        assertEquals(1, Choice.parse("zwei", sims))
        assertEquals(1, Choice.parse("Salt", sims))
        assertEquals(0, Choice.parse("nimm die erste", sims))
        assertEquals(1, Choice.parse("die letzte", sims))
        assertNull(Choice.parse("keine Ahnung", sims))
        val people = listOf("Gruja Demiri", "Gruja Krasniqi", "Ali Bellikaya")
        assertEquals(2, Choice.parse("Ali", people))
        assertEquals(1, Choice.parse("Krasniqi", people))
        assertEquals(2, Choice.parse("Nummer drei", people))
        assertNull(Choice.parse("Gruja", people))
    }

    // ---------------------------------------------------------------- Kontakte / Nummern

    @Test fun phoneNumbers() {
        assertEquals("41791234567", PhoneNumbers.intl("079 123 45 67"))
        assertEquals("41791234567", PhoneNumbers.intl("+41 79 123 45 67"))
        assertEquals("41791234567", PhoneNumbers.intl("0041791234567"))
        assertEquals("37744123456", PhoneNumbers.intl("+377 44 123 456"))
        assertTrue(PhoneNumbers.isSwiss("079 123 45 67"))
        assertTrue(!PhoneNumbers.isSwiss("+377 44 123 456"))
        assertTrue(PhoneNumbers.same("079 123 45 67", "+41791234567"))
        assertEquals("4 5 6 7", PhoneNumbers.spokenTail("+41 79 123 45 67"))
        assertTrue(PhoneNumbers.looksLikeNumber("+41 79 123 45 67"))
        assertTrue(!PhoneNumbers.looksLikeNumber("Gruja"))
    }

    @Test fun contactCandidatesSwissFirst() {
        val book = listOf(
            Contact("Gruja Kosovo", "+377 44 123 456"),
            Contact("Gruja Demiri", "+41 79 111 22 33"),
            Contact("Gruja Arbeit", "+41 61 999 88 77"),
            Contact("Grubenmann AG", "+41 61 555 00 00"),
            Contact("Ali Bellikaya", "+41 79 000 00 01"),
        )
        val c = ContactMatch.candidates("gruya", book)
        assertEquals(3, c.size)
        assertTrue("Schweizer Nummern zuerst", PhoneNumbers.isSwiss(c[0].number) && PhoneNumbers.isSwiss(c[1].number))
        assertEquals("Gruja Kosovo", c[2].name)
        assertEquals(listOf("Ali Bellikaya"), ContactMatch.candidates("Ali", book).map { it.name })
        assertEquals(listOf("Gruja Demiri"), ContactMatch.candidates("Gruja Demiri", book).map { it.name })
        assertTrue(ContactMatch.candidates("Sebastian", book).isEmpty())
    }

    @Test fun redactSecrets() {
        assertEquals("Ihr Code lautet [Code]", Redact.secrets("Ihr Code lautet 123456"))
        assertEquals("Treffen um 18 Uhr bei Hausnummer 1234", Redact.secrets("Treffen um 18 Uhr bei Hausnummer 1234"))
        assertTrue(Redact.secrets("IBAN CH93 0076 2011 6238 5295 7").contains("[Kontonummer]"))
        assertTrue(Redact.secrets("Karte 4111 1111 1111 1111").contains("[Kartennummer]"))
        assertNotNull(Redact.secrets(""))
    }
}
