package de.luwify.stundenplanmd.core.ical

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextTest {
    @Test
    fun lsfParagraphsWithNbspSpacer() {
        val html = "<p><strong>Bitte</strong> beachten.</p>\r\n<p> </p>\r\n<p><strong>Please</strong> note.</p>"
        assertEquals("Bitte beachten.\n\nPlease note.", HtmlText.toPlain(html))
    }

    @Test
    fun spansLinksAndEntities() {
        val html = "<p><span style=\"font-size: 14pt;\"><strong>Do 13:00</strong> </span></p>" +
            "<p>Mail an <a href=\"mailto:x@example.org\">x@example.org</a> .</p><p>a &amp; b &lt;c&gt; &#228;&#x00FC;&nbsp;!</p>"
        assertEquals("Do 13:00\nMail an x@example.org .\na & b <c> äü !", HtmlText.toPlain(html))
    }

    @Test
    fun brAndLists() {
        assertEquals("a\nb\n• c\n• d", HtmlText.toPlain("a<br>b<br/><ul><li>c</li><li>d</li></ul>"))
    }

    @Test
    fun plainTextKeepsLines() {
        assertEquals("Zwischentest\n\nWiederholung", HtmlText.toPlain("Zwischentest\r\n\r\n\r\nWiederholung\n"))
        assertEquals("", HtmlText.toPlain(""))
    }
}
