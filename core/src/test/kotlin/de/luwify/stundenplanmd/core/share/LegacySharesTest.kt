package de.luwify.stundenplanmd.core.share

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Recurrence
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.Expansion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Shares from apps before 1.2.2: biweekly series counted every 14 days, converted to LSF's even/odd calendar weeks. */
class LegacySharesTest {
    private fun at(date: String) = LocalDateTime.parse("${date}T09:15")

    /**
     * As LSF exports it: odd calendar weeks from Tue 15.12.2026 (KW 51) to 02.02.2027 (KW 5). 2026 has a KW 53, so
     * the dates are 15.12., 29.12., 05.01., 19.01., 02.02.; LSF cancels 29.12. and 05.01. (holidays).
     */
    private val lsf = Event(
        uid = "900077000001", summary = "Labor (Prof)", title = "Labor", lecturers = listOf("Prof"), category = "Übung (Ü)",
        start = at("2026-12-15"), end = LocalDateTime.parse("2026-12-15T10:45"),
        recurrence = Recurrence(
            interval = 2, byDay = setOf(java.time.DayOfWeek.TUESDAY), until = Instant.parse("2027-02-02T23:59:00Z"),
            exdates = setOf(at("2026-12-29"), at("2027-01-05")),
        ),
    )

    private val parityDates = listOf("2026-12-15", "2026-12-29", "2027-01-05", "2027-01-19", "2027-02-02").map(::at)

    /** What an older app stored: 14-day slots up to UNTIL (the last is 26.01.), the cancelled 05.01. was off that rhythm. */
    private val oldStored = lsf.copy(
        recurrence = Recurrence(
            interval = 2,
            until = LocalDate.parse("2027-01-26").atTime(23, 59).atZone(PlanZone).toInstant(),
            exdates = setOf(at("2026-12-29")),
        ),
    )

    private fun share(events: List<Event>) = SharedPlan("0123456789abcdef", "Max", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 30), events, emptyMap(), complete = true)

    @Test
    fun oldSeriesIsReexpandedInCalendarWeeksAndKeepsCancellationsByDate() {
        assertEquals(WeekRhythm.EVERY_14_DAYS, Expansion.rhythmOf(oldStored)) // what the stored data reads as without conversion
        val converted = LegacyShares.convert(listOf(oldStored))
        assertTrue(converted.incomplete)
        val slots = Expansion.slots(converted.events.single())
        assertEquals(parityDates, slots.map { it.start })
        // 29.12. stays cancelled; 05.01. was dropped by the old app and can't come back (hence the hint).
        assertEquals(listOf(at("2026-12-29")), slots.filter { it.cancelled }.map { it.start })
        assertEquals(WeekRhythm.ODD_WEEKS, Expansion.rhythmOf(converted.events.single()))
    }

    @Test
    fun seriesWithoutAWeek53AndOtherRulesStayAsTheyAre() {
        val autumn = oldStored.copy(
            start = at("2026-10-13"), end = LocalDateTime.parse("2026-10-13T10:45"),
            recurrence = oldStored.recurrence!!.copy(until = LocalDate.parse("2026-12-08").atTime(23, 59).atZone(PlanZone).toInstant(), exdates = emptySet()),
        )
        val weekly = autumn.copy(uid = "900077000002", recurrence = autumn.recurrence!!.copy(interval = 1))
        val single = lsf.copy(uid = "900077000003", recurrence = null)
        val events = listOf(autumn, weekly, single, lsf)
        val converted = LegacyShares.convert(events)
        assertFalse(converted.incomplete)
        assertEquals(events, converted.events)
    }

    @Test
    fun manualCoursesOfOldVersionsStayEvery14Days() {
        val manual = oldStored.copy(uid = "manual-0123456789abcdef")
        val converted = LegacyShares.convert(listOf(manual)).events.single()
        assertEquals(WeekRhythm.EVERY_14_DAYS, Expansion.rhythmOf(converted))
        assertEquals(Expansion.slots(manual.copy(recurrence = manual.recurrence!!.copy(rhythm = WeekRhythm.EVERY_14_DAYS))), Expansion.slots(converted))
    }

    @Test
    fun oldShareIsConvertedOnImportNewOneIsExact() {
        val old = ShareCodec.decode(ShareCodec.payload(share(listOf(lsf)), complete = true, legacy = true)) as ShareResult.Ok
        assertTrue(old.plan.legacy)
        val oldSlots = Expansion.slots(old.plan.events.single())
        assertEquals(parityDates, oldSlots.map { it.start })
        assertEquals(listOf(at("2026-12-29")), oldSlots.filter { it.cancelled }.map { it.start })

        val new = ShareCodec.decode(ShareCodec.payload(share(listOf(lsf)), complete = true)) as ShareResult.Ok
        assertFalse(new.plan.legacy)
        assertEquals(Expansion.slots(lsf), Expansion.slots(new.plan.events.single()))
    }

    @Test
    fun oldShareWithoutAWeek53NeedsNoHint() {
        val autumn = lsf.copy(
            start = at("2026-10-13"), end = LocalDateTime.parse("2026-10-13T10:45"),
            recurrence = lsf.recurrence!!.copy(until = Instant.parse("2026-12-08T23:59:00Z"), exdates = setOf(at("2026-11-10"))),
        )
        val old = ShareCodec.decode(ShareCodec.payload(share(listOf(autumn)), complete = false, legacy = true)) as ShareResult.Ok
        assertFalse(old.plan.legacy)
        assertEquals(Expansion.slots(autumn), Expansion.slots(old.plan.events.single()))
    }
}
