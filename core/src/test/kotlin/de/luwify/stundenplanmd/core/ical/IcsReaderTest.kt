package de.luwify.stundenplanmd.core.ical

import org.junit.Assert.assertEquals
import org.junit.Test

class IcsReaderTest {
    private fun props(vararg lines: String, sep: String = "\r\n") = IcsReader.properties(lines.joinToString(sep))

    @Test
    fun properFoldingJoinsWithoutNewline() {
        val p = props("SUMMARY:Grundlagen der ", " Aktorik", "\tVorlesung").single()
        assertEquals("Grundlagen der AktorikVorlesung", p.value)
    }

    @Test
    fun unfoldedContinuationLinesKeepNewlines() {
        val p = props("DESCRIPTION:<p>eins</p>\r", "<p>zwei</p>", "UID:1")
        assertEquals("<p>eins</p>\n<p>zwei</p>", p[0].value)
        assertEquals("1", p[1].value)
    }

    @Test
    fun lsfDoubleCarriageReturnIsOneLineBreak() {
        val p = IcsReader.properties("DESCRIPTION:a\r\r\nb\r\nUID:1\r\n")
        assertEquals(listOf("a\nb", "1"), p.map { it.value })
    }

    @Test
    fun textThatLooksLikeAPropertyStaysInTheDescription() {
        val p = props("DESCRIPTION:Erster Satz", "Hinweis: kommt mit", "WICHTIG: auch", "X-FOO:bar", sep = "\n")
        assertEquals(listOf("DESCRIPTION", "X-FOO"), p.map { it.name })
        assertEquals("Erster Satz\nHinweis: kommt mit\nWICHTIG: auch", p[0].value)
    }

    @Test
    fun paramsIncludingQuotedColons() {
        val p = props("""DESCRIPTION;ALTREP="http://x.de/a:b";LANGUAGE=de:Text""").single()
        assertEquals("http://x.de/a:b", p.params["ALTREP"])
        assertEquals("de", p.params["LANGUAGE"])
        assertEquals("Text", p.value)
    }

    @Test
    fun valueMayContainColons() {
        assertEquals("Zeit: 13:00", props("SUMMARY:Zeit: 13:00").single().value)
    }

    @Test
    fun bomAndBareLfAndEmptyValues() {
        val p = IcsReader.properties("﻿BEGIN:VCALENDAR\nEXDATE;TZID=Europe/Berlin:\nEND:VCALENDAR\n")
        assertEquals(listOf("BEGIN", "EXDATE", "END"), p.map { it.name })
        assertEquals("", p[1].value)
        assertEquals("Europe/Berlin", p[1].params["TZID"])
    }

    @Test
    fun componentsNestAndToleratesMissingEnd() {
        val roots = IcsReader.read(
            "BEGIN:VCALENDAR\nBEGIN:VTIMEZONE\nTZID:Europe/Berlin\nEND:VTIMEZONE\nBEGIN:VEVENT\nUID:1\n",
        )
        val cal = roots.single()
        assertEquals(listOf("VTIMEZONE", "VEVENT"), cal.children.map { it.name })
        assertEquals("1", cal.children[1].first("UID")?.value)
    }

    @Test
    fun unescapesRfcText() {
        assertEquals("a,b;c\nd\\e", IcsReader.unescapeText("a\\,b\\;c\\nd\\\\e"))
        assertEquals("Hahn, Pohl", IcsReader.unescapeText("Hahn, Pohl"))
    }
}
