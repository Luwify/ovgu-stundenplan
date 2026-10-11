package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ExpansionTest {
    private fun dt(s: String) = LocalDateTime.parse(s)
    private fun dates(e: Event) = Expansion.occurrences(e).map { it.start.toLocalDate().toString() }

    private fun event(start: String, rule: Recurrence?, hours: Long = 2) =
        Event(uid = "1", summary = "x", title = "x", start = dt(start), end = dt(start).plusHours(hours), recurrence = rule)

    @Test
    fun fixtureOccurrenceCounts() {
        val expected = mapOf(
            "900011500666" to 14, "900022500481" to 14, "900033500037" to 14, "900044500592" to 14,
            "900055500111" to 13, "900066500518" to 6, "900077500333" to 6, "900088500296" to 13,
            "900066500555" to 13, "900099500703" to 7, "900110500259" to 13, "900121500777" to 1,
            "900132500629" to 14, "900143500370" to 3, "900154500185" to 13, "900165500222" to 14,
            "900154500814" to 1, "900121500407" to 12, "900176500740" to 12, "900187500444" to 6,
            "900198500148" to 6, "900209500074" to 6, "900121500851" to 2, "900176500888" to 2,
        )
        assertEquals(expected, Fixture.events.associate { it.uid to Expansion.occurrences(it).size })
    }

    @Test
    fun fixtureTwoWeekRhythmWithOffRhythmExdates() {
        // GDL Übung: DTSTART itself and off-rhythm dates are in EXDATE.
        assertEquals(listOf("2026-10-29", "2027-01-07", "2027-01-21"), dates(Fixture.event("900143500370")))
        // N4 VL Tue every 2 weeks, UNTIL 19.01., 29.12. excluded.
        assertEquals(
            listOf("2026-10-20", "2026-11-03", "2026-11-17", "2026-12-01", "2026-12-15", "2027-01-12"),
            dates(Fixture.event("900066500518")),
        )
    }

    @Test
    fun fixtureKeepsClockTimeOverDst() {
        val slots = Expansion.occurrences(Fixture.event("900011500666"))
        assertEquals(dt("2026-10-26T13:00"), slots[2].start)
        assertEquals(dt("2026-10-26T15:00"), slots[2].end)
        // UNTIL 2027-01-25T23:59Z is inclusive: the last Monday is in.
        assertEquals(dt("2027-01-25T13:00"), slots.last().start)
    }

    @Test
    fun fixtureShortReplacementSeries() {
        assertEquals(listOf("2026-11-13", "2026-11-20"), dates(Fixture.event("900121500851")))
    }

    @Test
    fun slotsIncludeCancelledRhythmDatesOnly() {
        val e = event(
            "2026-10-15T09:00",
            Recurrence(interval = 2, until = Instant.parse("2026-11-30T00:00:00Z"), exdates = setOf(dt("2026-10-15T09:00"), dt("2026-10-22T09:00"))),
        )
        assertEquals(
            listOf("2026-10-15" to true, "2026-10-29" to false, "2026-11-12" to false, "2026-11-26" to false),
            Expansion.slots(e).map { it.start.toLocalDate().toString() to it.cancelled },
        )
    }

    @Test
    fun untilIsInclusiveAndComparedAsInstant() {
        val rule = Recurrence(until = Instant.parse("2026-10-26T12:00:00Z")) // 13:00 CET
        assertEquals(listOf("2026-10-12", "2026-10-19", "2026-10-26"), dates(event("2026-10-12T13:00", rule)))
        assertEquals(listOf("2026-10-12", "2026-10-19"), dates(event("2026-10-12T13:01", rule)))
    }

    @Test
    fun countIncludesExcludedDates() {
        val rule = Recurrence(count = 3, exdays = setOf(LocalDate.parse("2026-10-19")))
        assertEquals(listOf("2026-10-12", "2026-10-26"), dates(event("2026-10-12T08:00", rule)))
    }

    @Test
    fun byDayWithIntervalIsAnchoredOnTheWeekOfDtstart() {
        // DTSTART Wednesday; Monday of the first week is before DTSTART and skipped.
        val rule = Recurrence(interval = 2, byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), count = 4)
        assertEquals(listOf("2026-10-14", "2026-10-26", "2026-10-28", "2026-11-09"), dates(event("2026-10-14T10:00", rule)))
    }

    @Test
    fun dailyAndUnboundedCap() {
        val daily = Recurrence(frequency = Recurrence.Frequency.DAILY, interval = 3, count = 3)
        assertEquals(listOf("2026-10-12", "2026-10-15", "2026-10-18"), dates(event("2026-10-12T08:00", daily)))
        assertEquals(Expansion.MAX_SLOTS, Expansion.slots(event("2026-10-12T08:00", Recurrence())).size)
    }

    @Test
    fun singleEvent() {
        assertEquals(listOf(Slot(dt("2026-11-12T17:00"), dt("2026-11-12T19:00"), false)), Expansion.slots(event("2026-11-12T17:00", null)))
    }
}
