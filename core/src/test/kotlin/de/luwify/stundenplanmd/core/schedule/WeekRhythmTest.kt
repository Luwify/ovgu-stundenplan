package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.ical.LsfText
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.ManualType
import de.luwify.stundenplanmd.core.model.Recurrence
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.ShareResult
import de.luwify.stundenplanmd.core.share.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** LSF's gKW/uKW (INTERVAL=2) across New Year: 2026 has a week 53, so KW53 and KW1 are both odd. */
class WeekRhythmTest {
    private fun d(s: String) = LocalDate.parse(s)

    /** An LSF-like series: UNTIL as `T235900Z` on [until]'s date, EXDATEs at the start time. */
    private fun series(
        start: String,
        until: String?,
        uid: String = "500001000001",
        exdates: List<String> = emptyList(),
        rhythm: WeekRhythm? = null,
        count: Int? = null,
    ): Event {
        val s = LocalDateTime.parse(start)
        return Event(
            uid = uid, summary = "Mathematik M3 (VL)", title = "Mathematik M3 (VL)", category = "Vorlesung (V)",
            location = LsfText.location("K30 - K30-H1 (300 Pl.)"),
            start = s, end = s.plusHours(2),
            recurrence = Recurrence(
                interval = 2, byDay = setOf(s.dayOfWeek), until = until?.let { Instant.parse("${it}T23:59:00Z") }, count = count,
                exdates = exdates.map { d(it).atTime(s.toLocalTime()) }.toSet(), rhythm = rhythm,
            ),
        )
    }

    private fun dates(e: Event) = Expansion.occurrences(e).map { it.start.toLocalDate() }
    private fun weeks(e: Event) = dates(e).map(WeekRhythm::weekOf)

    @Test
    fun oddWeeksAcrossNewYearWithWeek53() {
        // uKW Tue from KW43, UNTIL 19.01.2027 = KW3 (not on the 14-day rhythm, which would give 12.01. and 26.01.).
        val e = series("2026-10-20T11:00", "2027-01-19")
        assertEquals(WeekRhythm.ODD_WEEKS, Expansion.rhythmOf(e))
        assertEquals(listOf(43, 45, 47, 49, 51, 53, 1, 3), weeks(e))
        assertEquals(d("2026-12-29"), dates(e)[5])
        assertEquals(listOf(d("2027-01-05"), d("2027-01-19")), dates(e).takeLast(2))
    }

    @Test
    fun evenWeeksAcrossNewYearWithWeek53() {
        // gKW Wed from KW42, UNTIL 27.01.2027 = KW4: no date in KW53 or KW1.
        val e = series("2026-10-14T13:00", "2027-01-27")
        assertEquals(WeekRhythm.EVEN_WEEKS, Expansion.rhythmOf(e))
        assertEquals(listOf(42, 44, 46, 48, 50, 52, 2, 4), weeks(e))
        assertEquals(listOf(d("2026-12-23"), d("2027-01-13"), d("2027-01-27")), dates(e).takeLast(3))
    }

    @Test
    fun yearWithoutWeek53BothRulesAgree() {
        // 2025 has 52 weeks: KW52 (even) is followed by KW1 (odd), the 14-day rhythm keeps the parity.
        val e = series("2025-10-21T11:00", "2026-01-27")
        assertEquals(WeekRhythm.ODD_WEEKS, Expansion.rhythmOf(e))
        val fourteen = Expansion.withRhythms(listOf(e), mapOf(e.uid to WeekRhythm.EVERY_14_DAYS)).single()
        assertEquals(dates(fourteen), dates(e))
        assertEquals(listOf(43, 45, 47, 49, 51, 1, 3, 5), weeks(e))
        assertEquals(d("2025-12-30"), dates(e)[5])
    }

    @Test
    fun untilDecidesPerSeries() {
        // The same start: UNTIL on the 14-day rhythm (KW4) keeps 14 days, on the parity one (KW3) takes parity.
        val fourteen = series("2026-10-20T11:00", "2027-01-26")
        assertEquals(WeekRhythm.EVERY_14_DAYS, Expansion.rhythmOf(fourteen))
        assertEquals(listOf(d("2027-01-12"), d("2027-01-26")), dates(fourteen).takeLast(2))
        assertEquals(WeekRhythm.ODD_WEEKS, Expansion.rhythmOf(series("2026-10-20T11:00", "2027-01-19")))
        // Neither (UNTIL on a Sunday), no UNTIL (COUNT): the calendar weeks.
        assertEquals(WeekRhythm.ODD_WEEKS, Expansion.rhythmOf(series("2026-10-20T11:00", "2027-01-24")))
        val counted = series("2026-10-20T11:00", null, count = 8)
        assertEquals(WeekRhythm.ODD_WEEKS, Expansion.rhythmOf(counted))
        assertEquals(d("2027-01-19"), dates(counted).last())
        // UNTIL exactly at the last start (RFC style, in UTC) is on that date too.
        val exact = series("2026-10-20T11:00", null).let { it.copy(recurrence = it.recurrence!!.copy(until = Instant.parse("2027-01-19T10:00:00Z"))) }
        assertEquals(WeekRhythm.ODD_WEEKS, Expansion.rhythmOf(exact))
        assertEquals(d("2027-01-19"), dates(exact).last())
        // Only weekly INTERVAL=2 has a rhythm.
        assertNull(Expansion.rhythmOf(series("2026-10-20T11:00", "2027-01-19").let { it.copy(recurrence = it.recurrence!!.copy(interval = 1)) }))
    }

    @Test
    fun exdateInWeek53() {
        // The Christmas EXDATE on 29.12. (KW53) cancels a uKW date; on a gKW series it is off the rhythm (no effect).
        val odd = series("2026-10-20T11:00", "2027-01-19", exdates = listOf("2026-12-29"))
        val s = Schedule(listOf(odd))
        assertEquals(listOf(LocalDateTime.parse("2026-12-29T11:00")), s.cancellations.map { it.start })
        assertTrue(s.occurrences.none { it.date == d("2026-12-29") })
        assertEquals(d("2027-01-05"), s.occurrences.first { it.date.year == 2027 }.date)
        val even = series("2026-10-14T13:00", "2027-01-27", exdates = listOf("2026-12-30"))
        assertTrue(Schedule(listOf(even)).cancellations.isEmpty())
        assertEquals(8, Schedule(listOf(even)).occurrences.size)
    }

    @Test
    fun rhythmSetByHand() {
        val e = series("2026-10-20T11:00", "2027-01-19")
        fun with(r: WeekRhythm) = Expansion.withRhythms(listOf(e), mapOf(e.uid to r)).single()
        // Even weeks for a series LSF starts in an odd week: from the week after DTSTART on.
        assertEquals(listOf(44, 46, 48, 50, 52, 2), weeks(with(WeekRhythm.EVEN_WEEKS)))
        assertEquals(weeks(e), weeks(with(WeekRhythm.ODD_WEEKS)))
        assertEquals(listOf(d("2026-12-29"), d("2027-01-12")), dates(with(WeekRhythm.EVERY_14_DAYS)).takeLast(2))
        // Other series and other rules are left alone.
        val weekly = e.copy(uid = "500002000001", recurrence = e.recurrence!!.copy(interval = 1))
        assertEquals(listOf(weekly), Expansion.withRhythms(listOf(weekly), mapOf(weekly.uid to WeekRhythm.EVEN_WEEKS)))
        assertEquals(listOf(e), Expansion.withRhythms(listOf(e), mapOf("500009000009" to WeekRhythm.EVEN_WEEKS)))
    }

    @Test
    fun manualCourseRhythms() {
        fun course(r: ManualCourse.Rhythm) = ManualCourses.event(
            ManualCourse(
                id = ManualCourse.newId("0123456789abcdef"), title = "Lerngruppe", type = ManualType.OTHER, rhythm = r,
                startDate = d("2026-10-20"), endDate = d("2027-01-26"), start = LocalTime.of(17, 0), end = LocalTime.of(18, 30),
                skipFreeWeeks = false,
            ),
        )
        assertEquals(listOf(43, 45, 47, 49, 51, 53, 1, 3), weeks(course(ManualCourse.Rhythm.ODD_WEEKS)))
        assertEquals(listOf(44, 46, 48, 50, 52, 2, 4), weeks(course(ManualCourse.Rhythm.EVEN_WEEKS)))
        assertEquals(d("2026-10-27"), dates(course(ManualCourse.Rhythm.EVEN_WEEKS)).first())
        // "Every 14 days" keeps its meaning from before even/odd weeks.
        assertEquals(listOf(43, 45, 47, 49, 51, 53, 2, 4), weeks(course(ManualCourse.Rhythm.BIWEEKLY)))
        assertTrue(course(ManualCourse.Rhythm.WEEKLY).recurrence!!.rhythm == null)
    }

    @Test
    fun togetherAcrossNewYear() {
        val mine = series("2026-10-20T11:00", "2027-01-19", exdates = listOf("2026-12-29"))
        val t = Together.of(Schedule(listOf(mine)), listOf("b" to Schedule(listOf(mine))))
        assertEquals(setOf(d("2027-01-05"), d("2027-01-19")), t.keys.map { it.start.toLocalDate() }.filter { it.year == 2027 }.toSet())
        assertEquals(7, t.size)
        // A friend's plan read with 14 days (e.g. their override): together until KW53, not after.
        val theirs = Expansion.withRhythms(listOf(mine), mapOf(mine.uid to WeekRhythm.EVERY_14_DAYS))
        val t2 = Together.of(Schedule(listOf(mine)), listOf("b" to Schedule(theirs)))
        assertEquals(5, t2.size)
        assertTrue(t2.keys.none { it.start.year == 2027 })
    }

    @Test
    fun portableKeepsTheDates() {
        val e = series("2026-10-20T11:00", "2027-01-26", exdates = listOf("2026-11-03"))
        for (r in WeekRhythm.entries) {
            val chosen = Expansion.withRhythms(listOf(e), mapOf(e.uid to r)).single()
            val p = Expansion.portable(chosen)
            assertNull(p.recurrence!!.rhythm)
            assertEquals(Expansion.slots(chosen), Expansion.slots(p))
        }
        // Nothing set by hand: unchanged.
        assertEquals(e, Expansion.portable(e))
    }

    @Test
    fun sharedPlansKeepTheRhythm() {
        val odd = series("2026-10-20T11:00", "2027-01-19", uid = "500001000001", exdates = listOf("2026-12-29"))
        val even = series("2026-10-14T13:00", "2027-01-27", uid = "500002000001", exdates = listOf("2026-12-23"))
        val fourteen = series("2026-10-20T13:00", "2027-01-26", uid = "500003000001", exdates = listOf("2026-11-03"))
        val byHand = Expansion.withRhythms(listOf(series("2026-10-23T09:00", "2027-01-22", uid = "500004000001")), mapOf("500004000001" to WeekRhythm.EVEN_WEEKS))
        val manual = ManualCourses.event(
            ManualCourse(
                id = ManualCourse.newId("00000000000000aa"), title = "Lerngruppe", rhythm = ManualCourse.Rhythm.EVEN_WEEKS,
                startDate = d("2026-10-21"), endDate = d("2027-01-27"), start = LocalTime.of(17, 0), end = LocalTime.of(18, 30),
            ),
        )
        val events = listOf(odd, even, fourteen, manual) + byHand
        val plan = SharedPlan("0123456789abcdef", "Max", d("2026-10-15"), d("2026-10-14"), events, emptyMap(), complete = true)
        fun slots(es: List<Event>) = es.sortedBy { it.uid }.map { e -> e.uid to Expansion.slots(e).map { it.start to it.cancelled } }
        for (read in listOf(ShareCodec.parse(ShareCodec.file(plan)), ShareCodec.parse(ShareCodec.qrText(plan)))) {
            val got = (read as ShareResult.Ok).plan.events
            assertEquals(slots(events), slots(got))
            assertEquals(WeekRhythm.EVERY_14_DAYS, Expansion.rhythmOf(got.single { it.uid == fourteen.uid }))
        }
        assertFalse(ShareCodec.version(plan.copy(events = listOf(odd, even, fourteen) + byHand)) > 1)
    }

    @Test
    fun weekOfIsIso() {
        assertEquals(53, WeekRhythm.weekOf(d("2026-12-28")))
        assertEquals(1, WeekRhythm.weekOf(d("2027-01-04")))
        assertEquals(1, WeekRhythm.weekOf(d("2025-12-29")))
        assertEquals(DayOfWeek.MONDAY, d("2026-12-28").dayOfWeek)
    }
}
