package de.luwify.stundenplanmd.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import de.luwify.stundenplanmd.ui.TimeDigits.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TimeDigitsTest {
    @Test
    fun parse_threeOrFourDigits_lastTwoAreMinutes() {
        assertEquals(LocalTime.of(10, 15), TimeDigits.parse("1015"))
        assertEquals(LocalTime.of(9, 15), TimeDigits.parse("915"))
        assertEquals(LocalTime.of(9, 15), TimeDigits.parse("0915"))
        assertEquals(LocalTime.of(0, 0), TimeDigits.parse("0000"))
        assertEquals(LocalTime.of(23, 59), TimeDigits.parse("2359"))
    }

    @Test
    fun parse_outOfRangeOrIncomplete_isNullNotCorrected() {
        assertNull(TimeDigits.parse("2400"))
        assertNull(TimeDigits.parse("1260"))
        assertNull(TimeDigits.parse("975"))
        assertNull(TimeDigits.parse(""))
        assertNull(TimeDigits.parse("9"))
        assertNull(TimeDigits.parse("10"))
        assertNull(TimeDigits.parse("10155"))
        assertNull(TimeDigits.parse("9:15"))
    }

    @Test
    fun of_givesFourDigits_andParsesBack() {
        assertEquals("0915", TimeDigits.of(LocalTime.of(9, 15)))
        assertEquals("0000", TimeDigits.of(LocalTime.MIDNIGHT))
        for (h in 0..23) for (m in 0..59) assertEquals(LocalTime.of(h, m), TimeDigits.parse(TimeDigits.of(LocalTime.of(h, m))))
    }

    @Test
    fun format_showsTheFixedColon() {
        assertEquals("", TimeDigits.format(""))
        assertEquals("9", TimeDigits.format("9"))
        assertEquals("10:", TimeDigits.format("10"))
        assertEquals("09:15", TimeDigits.format("915"))
        assertEquals("10:15", TimeDigits.format("1015"))
        assertEquals("24:00", TimeDigits.format("2400")) // shown as typed, the field marks it
        assertEquals("12:60", TimeDigits.format("1260"))
    }

    @Test
    fun check_errorsOnlyWhenMoreDigitsCantHelp() {
        assertEquals(Check.EMPTY, TimeDigits.check(""))
        assertEquals(Check.VALID, TimeDigits.check("915"))
        assertEquals(Check.VALID, TimeDigits.check("1015"))
        assertEquals(Check.PARTIAL, TimeDigits.check("9"))
        assertEquals(Check.PARTIAL, TimeDigits.check("91")) // on the way to 915
        assertEquals(Check.PARTIAL, TimeDigits.check("091")) // on the way to 0915
        assertEquals(Check.PARTIAL, TimeDigits.check("175")) // on the way to 1750
        assertEquals(Check.INVALID, TimeDigits.check("99"))
        assertEquals(Check.INVALID, TimeDigits.check("176"))
        assertEquals(Check.INVALID, TimeDigits.check("2400"))
        assertEquals(Check.INVALID, TimeDigits.check("1260"))
    }

    private fun value(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    @Test
    fun edited_digitsPass_cursorAsTyped() {
        assertEquals(value("91", 2), TimeDigits.edited(value("9", 1), value("91", 2)))
        assertEquals(value("115", 1), TimeDigits.edited(value("1015", 2), value("115", 1))) // backspace in the middle
        assertEquals(value("", 0), TimeDigits.edited(value("9", 1), value("", 0)))
        assertEquals(value("1015", 1), TimeDigits.edited(value("1015", 4), value("1015", 1))) // cursor moved
    }

    @Test
    fun edited_aFifthDigitIsRefused_cursorStays() {
        assertEquals(value("1815", 2), TimeDigits.edited(value("1815", 2), value("18715", 3)))
    }

    @Test
    fun edited_otherCharactersAreRefused_cursorStays() {
        assertEquals(value("91", 1), TimeDigits.edited(value("91", 1), value("9.1", 2)))
    }

    @Test
    fun edited_pasteKeepsTheDigits_cursorBehindThem() {
        assertEquals(value("1015", 4), TimeDigits.edited(value("", 0), value("10:15", 5)))
        assertEquals(value("1015", 3), TimeDigits.edited(value("5", 0), value("10:15", 4))) // pasted before the 5
        assertEquals(value("1015", 4), TimeDigits.edited(value("1015", 4), value("1015123", 7))) // too long: refused
    }

    @Test
    fun cursor_digitsToShownText() {
        assertEquals(listOf(0, 1, 3, 4, 5), (0..4).map { TimeDigits.shownOffset(it, 4) }) // 10:15
        assertEquals(listOf(1, 3, 4, 5), (0..3).map { TimeDigits.shownOffset(it, 3) }) // 09:15
        assertEquals(listOf(0, 1, 3), (0..2).map { TimeDigits.shownOffset(it, 2) }) // 10:
        assertEquals(listOf(0, 1), (0..1).map { TimeDigits.shownOffset(it, 1) })
        assertEquals(0, TimeDigits.shownOffset(0, 0))
    }

    @Test
    fun cursor_shownTextToDigits_bothSidesOfTheColonAreOnePlace() {
        assertEquals(listOf(0, 1, 2, 2, 3, 4), (0..5).map { TimeDigits.digitOffset(it, 4) })
        assertEquals(listOf(0, 0, 1, 1, 2, 3), (0..5).map { TimeDigits.digitOffset(it, 3) })
        assertEquals(listOf(0, 1, 2, 2), (0..3).map { TimeDigits.digitOffset(it, 2) })
        assertEquals(listOf(0, 1), (0..1).map { TimeDigits.digitOffset(it, 1) })
    }

    @Test
    fun cursor_roundTripsAndStaysInBounds() {
        for (digits in listOf("", "9", "10", "915", "1015")) {
            val n = digits.length
            val shown = TimeDigits.format(digits).length
            for (o in 0..n) {
                assertEquals(o, TimeDigits.digitOffset(TimeDigits.shownOffset(o, n), n))
                assertTrue(TimeDigits.shownOffset(o, n) in 0..shown)
            }
            for (t in 0..shown) assertTrue(TimeDigits.digitOffset(t, n) in 0..n)
        }
    }
}
