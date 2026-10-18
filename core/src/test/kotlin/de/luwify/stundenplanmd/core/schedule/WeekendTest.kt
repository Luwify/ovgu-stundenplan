package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.Subjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Saturday/Sunday dates (a manual course, an .ics entry) are shown in the weeks that have them, and nowhere else. */
class WeekendTest {
    private fun d(s: String) = LocalDate.parse(s)

    /** A weekly Saturday course, the only thing on weekends. */
    private val saturday = ManualCourse(
        id = ManualCourse.newId("00000000000000aa"), title = "Lerngruppe Samstag", rhythm = ManualCourse.Rhythm.WEEKLY,
        startDate = d("2026-10-17"), endDate = d("2026-11-07"), start = LocalTime.of(10, 0), end = LocalTime.of(12, 0), skipFreeWeeks = false,
    )
    private val events = ManualCourses.merge(Fixture.events, listOf(saturday))
    private val schedule = Schedule(events)

    @Test
    fun weekGetsSaturdayOnlyWhereSomethingIsOnIt() {
        val week = schedule.week(d("2026-10-19"))
        assertEquals((19..24).map { d("2026-10-$it") }, week.days.map { it.date }) // Mon–Sat, no Sunday
        assertEquals(1, week.days.last().occurrences.size)
        assertEquals(d("2026-10-24"), week.lastDay)
        // A week without weekend dates stays Mon–Fri.
        assertEquals(5, schedule.week(d("2026-11-16")).days.size)
        // Without the course: Mon–Fri as before.
        assertEquals(5, Schedule(Fixture.events).week(d("2026-10-19")).days.size)
    }

    @Test
    fun weekendOnlyCourseIsReachableEverywhere() {
        // Subject overview: it is a subject of its own (opened, edited, hidden or deleted from there).
        val subject = Subjects.build(events, emptyMap()).single { it.name == "Lerngruppe Samstag" }
        assertEquals(listOf(saturday.courseKey), subject.courses.map { it.key })
        // Day pager: its Saturdays are pages.
        val weeks = Navigation.weeks(schedule, d("2026-10-12"))
        val days = Navigation.days(schedule, weeks)
        assertTrue(d("2026-10-24") in days)
        assertTrue(days.none { it.dayOfWeek == DayOfWeek.SUNDAY })
        assertEquals(d("2026-10-24"), days[Navigation.dayPage(days, d("2026-10-24"))])
        // A weekend day without a page opens on the next day shown.
        assertEquals(d("2026-10-26"), days[Navigation.dayPage(days, d("2026-10-25"))])
        // On that Saturday morning the week still open is this one, and the day view opens on it.
        assertEquals(d("2026-10-19"), Navigation.weekToOpen(schedule, d("2026-10-24")))
        assertEquals(d("2026-10-26"), Navigation.weekToOpen(schedule, d("2026-10-25")))
        assertEquals(d("2026-10-24"), Navigation.dayToOpen(schedule, LocalDateTime.parse("2026-10-24T08:00")))
        // Drawer now-line: shown on a weekend day with something on it.
        assertTrue(NowStatus.of(schedule, LocalDateTime.parse("2026-10-24T11:00")) is NowStatus.In)
        assertEquals(NowStatus.None, NowStatus.of(schedule, LocalDateTime.parse("2026-10-25T11:00")))
    }

    @Test
    fun hiddenWeekendDatesDontAddAColumn() {
        val hiding = Hiding.of(listOf(Hidden(Hidden.Kind.COURSE, courses = setOf(saturday.courseKey))), events, emptyMap())
        assertEquals(5, Schedule(events, hiding = hiding).week(d("2026-10-19")).days.size)
    }
}
