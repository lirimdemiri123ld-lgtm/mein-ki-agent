package ch.lirim.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextTest {
    private fun dass(s: String) = MessageText.fromClause("dass", s)

    @Test fun simpleFirstPerson() {
        assertEquals(MessageText.Result("Wir gehen zu Pronto.", true), dass("wir zu Pronto gehen"))
        assertEquals(MessageText.Result("Ich komme später.", true), dass("ich später komme"))
        assertEquals(MessageText.Result("Ich komme 10 Minuten später.", true), dass("ich 10 Minuten später komme"))
        assertEquals(MessageText.Result("Ich bin unterwegs.", true), dass("ich unterwegs bin"))
    }

    @Test fun modalAndPerfect() {
        assertEquals("Ich kann heute nicht kommen.", dass("ich heute nicht kommen kann").text)
        assertEquals("Ich bin angekommen.", dass("ich angekommen bin").text)
        assertEquals("Wir haben schon gegessen.", dass("wir schon gegessen haben").text)
        assertTrue(dass("ich heute nicht kommen kann").confident)
    }

    @Test fun separableVerbs() {
        assertEquals("Ich rufe dich später an.", dass("ich dich später anrufe").text)
        assertEquals("Wir fahren um 8 los.", dass("wir um 8 losfahren").text)
        assertEquals("Ich hole die Kinder ab.", dass("ich die Kinder abhole").text)
        assertEquals("Ich komme um 6 an.", dass("ich um 6 ankomme").text)
        // nicht trennbar
        assertEquals("Ich antworte später.", dass("ich später antworte").text)
        assertEquals("Ich bekomme das Paket morgen.", dass("ich das Paket morgen bekomme").text)
    }

    @Test fun conjunctionsAndSubclauses() {
        assertEquals("Ich stehe im Stau und komme später.", dass("ich im Stau stehe und später komme").text)
        assertEquals("Ich kaufe Brot und Milch.", dass("ich Brot und Milch kaufe").text)
        assertEquals("Ich komme später, weil ich im Stau stehe.", dass("ich später komme, weil ich im Stau stehe").text)
        assertEquals("Ich komme später, weil ich im Stau stehe.", dass("ich später komme weil ich im Stau stehe").text)
    }

    @Test fun unsureCasesAreFlagged() {
        assertTrue(!dass("sie mich anrufen soll").confident)
        assertTrue(!dass("ich sie liebe").confident)
        assertEquals("Der Zug hat Verspätung.", dass("der Zug Verspätung hat").text)
        assertTrue(!dass("der Zug Verspätung hat").confident)
        assertTrue(!MessageText.fromClause("ob", "sie Milch kaufen kann").confident)
    }

    @Test fun literalText() {
        assertEquals("Bin gleich da.", MessageText.literal("bin gleich da"))
        assertEquals("Komme um 6:30, ok?", MessageText.literal("Komme um 6:30, ok?"))
        assertEquals("Viele Grüsse.", MessageText.literal("viele Grüße"))
        assertEquals(MessageText.Result("Bin in 5 Minuten da.", true), MessageText.from(Body.Literal("Bin in 5 Minuten da")))
    }
}
