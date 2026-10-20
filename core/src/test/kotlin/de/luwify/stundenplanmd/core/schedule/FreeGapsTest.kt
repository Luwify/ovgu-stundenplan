package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.ical.LsfText
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.PersonalChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class FreeGapsTest {
    private val day = LocalDate.parse("2026-10-21")
    private fun at(time: String) = LocalDateTime.parse("2026-10-21T$time")

    private fun event(uid: String, from: String, to: String) = Event(
        uid = uid, summary = "Kurs $uid (VL)", title = "Kurs $uid (VL)", category = "Vorlesung (V)",
        location = LsfText.location("K30 - K30-H1 (200 Pl.)"), start = at(from), end = at(to),
    )

    private fun gaps(events: List<Event>, changes: List<PersonalChange> = emptyList(), hidden: List<Hidden> = emptyList()): List<String> {
        val hiding = Hiding.of(hidden, events, emptyMap())
        return Schedule(events, changes, hiding).day(day).gaps.map { "${it.start.toLocalTime()}-${it.end.toLocalTime()}" }
    }

    private val morning = event("500001000001", "09:00", "11:00")
    private val noon = event("500002000001", "13:00", "15:00")
    private val evening = event("500003000001", "17:00", "19:00")

    @Test
    fun aBreakBetweenTwoCoursesIsAGap() {
        assertEquals(listOf("11:00-13:00"), gaps(listOf(morning, noon)))
        assertEquals(listOf("11:00-13:00", "15:00-17:00"), gaps(listOf(morning, noon, evening)))
    }

    @Test
    fun noGapBeforeTheFirstOrAfterTheLastCourse() {
        assertEquals(emptyList<String>(), gaps(listOf(morning)))
        assertEquals(emptyList<String>(), gaps(emptyList()))
        // The day starts at 9 and ends at 19: only the break in between counts.
        assertEquals(listOf("11:00-17:00"), gaps(listOf(morning, evening)))
    }

    @Test
    fun backToBackCoursesHaveNoGap() {
        assertEquals(emptyList<String>(), gaps(listOf(morning, event("500002000001", "11:00", "13:00"))))
    }

    @Test
    fun theThresholdIs45Minutes() {
        assertEquals(45, FreeGaps.MIN_MINUTES)
        assertEquals(listOf("11:00-11:45"), gaps(listOf(morning, event("500002000001", "11:45", "13:00"))))
        assertEquals(emptyList<String>(), gaps(listOf(morning, event("500002000001", "11:44", "13:00"))))
        assertEquals(emptyList<String>(), gaps(listOf(morning, event("500002000001", "11:15", "13:00"))))
    }

    @Test
    fun overlappingCoursesAreOneStretch() {
        // The long course still runs while the short one ends: the break starts after the long one.
        val long = event("500004000001", "09:00", "14:00")
        val short = event("500005000001", "09:30", "10:30")
        assertEquals(listOf("14:00-17:00"), gaps(listOf(long, short, evening)))
    }

    @Test
    fun aCourseCancelledForMeLeavesAGap() {
        val middle = event("500006000001", "11:00", "13:00")
        val all = listOf(morning, middle, noon)
        assertEquals(emptyList<String>(), gaps(all))
        val cancelled = PersonalChange(middle.uid, day, cancelled = true, title = middle.title)
        assertEquals(listOf("11:00-13:00"), gaps(all, listOf(cancelled)))
    }

    @Test
    fun aHiddenDateLeavesAGap() {
        val middle = event("500006000001", "11:00", "13:00")
        val all = listOf(morning, middle, noon)
        val hidden = Hidden(Hidden.Kind.DATE, courses = setOf("lsf:500006"), uid = middle.uid, date = day)
        assertEquals(listOf("11:00-13:00"), gaps(all, hidden = listOf(hidden)))
    }

    @Test
    fun aMovedCourseMovesTheGap() {
        val moved = PersonalChange(noon.uid, day, start = java.time.LocalTime.parse("12:00"), end = java.time.LocalTime.parse("14:00"), title = noon.title)
        assertEquals(listOf("11:00-12:00", "14:00-17:00"), gaps(listOf(morning, noon, evening), listOf(moved)))
    }

    @Test
    fun lsfCancellationsAreNotCoursesEither() {
        // Gaps follow what takes place; the day plan keeps them apart from the cancellations it lists.
        val d = Schedule(de.luwify.stundenplanmd.core.Fixture.events).day(LocalDate.parse("2026-10-15"))
        assertEquals(1, d.cancellations.size)
        assertEquals(listOf("09:00-11:00"), d.gaps.map { "${it.start.toLocalTime()}-${it.end.toLocalTime()}" })
        assertTrue(d.gaps.single().minutes == 120)
        assertFalse(d.gaps.any { it.start.toLocalTime().hour < 9 })
    }
}

class HourScaleTest {
    @Test
    fun fillsTheAvailableHeight() {
        assertEquals(20f, HourScale.height(available = 240f, hours = 12, min = 14f), 0.001f)
        assertTrue(HourScale.fits(240f, 12, 14f))
    }

    @Test
    fun theMarginStaysFree() {
        assertEquals(19f, HourScale.height(available = 240f, hours = 12, min = 14f, margin = 12f), 0.001f)
    }

    @Test
    fun neverBelowTheMinimumThenItScrolls() {
        assertEquals(14f, HourScale.height(available = 120f, hours = 12, min = 14f), 0.001f)
        assertFalse(HourScale.fits(120f, 12, 14f))
        // Exactly the minimum still fits.
        assertTrue(HourScale.fits(168f, 12, 14f))
    }

    @Test
    fun theWholePlanSetsTheScaleNotTheShownWeek() {
        val schedule = Schedule(de.luwify.stundenplanmd.core.Fixture.events)
        val hours = schedule.hours
        // Same range whichever week is asked: the scale cannot jump between weeks.
        val perWeek = listOf("2026-10-12", "2026-10-19", "2027-01-11").map { schedule.week(LocalDate.parse(it)); schedule.hours }
        assertTrue(perWeek.all { it == hours })
        assertEquals(7..19, hours)
        assertEquals(20f, HourScale.height(240f, hours.last - hours.first, 1f), 0.001f)
    }
}
