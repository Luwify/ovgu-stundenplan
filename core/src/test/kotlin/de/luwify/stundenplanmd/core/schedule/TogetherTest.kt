package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.ical.LsfText
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.ManualType
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TogetherTest {
    private fun event(uid: String, title: String, room: String?, start: String, rule: Recurrence? = WEEKLY, category: String = "Übung (Ü)") = Event(
        uid = uid, summary = title, title = title, category = category,
        location = room?.let { LsfText.location("${it.substringBefore('-')} - $it (60 Pl.)") },
        start = LocalDateTime.parse(start), end = LocalDateTime.parse(start).plusHours(2), recurrence = rule,
    )

    private fun together(a: List<Event>, b: List<Event>) = Together.of(Schedule(a), listOf("b" to Schedule(b)))

    @Test
    fun sameAppointmentIsTogetherEveryWeek() {
        val e = event("500001000001", "Numerik N4 (VL)", "K30-H1", "2026-10-14T11:00", category = "Vorlesung (V)")
        val t = together(listOf(e), listOf(e))
        assertEquals(Schedule(listOf(e)).occurrences.size, t.size)
        assertTrue(t.values.all { it == listOf("b") })
    }

    @Test
    fun parallelGroupsOfOneCourseAtTheSameTimeInOtherRoomsAreNotTogether() {
        val mine = event("500002000011", "Wellen und Felder (Übung 1)", "K09B-110", "2026-10-14T13:00")
        val theirs = event("500002000012", "Wellen und Felder (Übung 2)", "K09B-203", "2026-10-14T13:00")
        assertEquals(emptyMap<OccurrenceKey, List<String>>(), together(listOf(mine), listOf(theirs)))
        // Same group in the same room under another appointment id (e.g. a second series): together.
        val sameRoom = theirs.copy(location = mine.location)
        assertEquals(Schedule(listOf(mine)).occurrences.size, together(listOf(mine), listOf(sameRoom)).size)
        // A room missing on one side can't tell groups apart: together.
        assertEquals(Schedule(listOf(mine)).occurrences.size, together(listOf(mine), listOf(theirs.copy(location = null))).size)
    }

    @Test
    fun crossListedCoursesMatchByExactTitleCategoryTimeAndRoom() {
        val mine = event("500003000001", "Angewandte Statik 2", "K30-H1", "2026-10-15T07:00", category = "Vorlesung (V)")
        val theirs = mine.copy(uid = "600003000001")
        assertEquals(Schedule(listOf(mine)).occurrences.size, together(listOf(mine), listOf(theirs)).size)
        // Not when anything differs: title, category, room or time.
        assertTrue(together(listOf(mine), listOf(theirs.copy(title = "Angewandte Statik II"))).isEmpty())
        assertTrue(together(listOf(mine), listOf(theirs.copy(category = "Übung (Ü)"))).isEmpty())
        assertTrue(together(listOf(mine), listOf(theirs.copy(location = LsfText.location("K25 - K25-H3 (60 Pl.)")))).isEmpty())
        assertTrue(together(listOf(mine), listOf(theirs.copy(location = null))).isEmpty())
        assertTrue(together(listOf(mine), listOf(theirs.copy(start = mine.start.plusHours(1), end = mine.end.plusHours(1)))).isEmpty())
        // Two different courses without a room at the same time: not together.
        assertTrue(together(listOf(mine.copy(location = null)), listOf(theirs.copy(location = null))).isEmpty())
    }

    @Test
    fun staleFriendPlanWithoutTheRoomChangeStillMatches() {
        // My newer export: the series lost 21.10. (EXDATE) and a single event moved it to another room.
        val series = event("500004000001", "Wellen und Felder", "K14-102", "2026-10-14T13:00", category = "Vorlesung (V)")
        val moved = LocalDateTime.parse("2026-10-21T13:00")
        val mine = listOf(
            series.copy(recurrence = WEEKLY.copy(exdates = setOf(moved))),
            event("500004000099", "Wellen und Felder", "K14-221", "2026-10-21T13:00", rule = null, category = "Vorlesung (V)"),
        )
        // The friend's older export: the plain series, still in the old room on 21.10.
        val t = together(mine, listOf(series))
        val onMoved = Schedule(mine).occurrences.single { it.start == moved }
        assertTrue(onMoved.status is Status.Changed)
        assertEquals(listOf("b"), t[OccurrenceKey.of(onMoved)])
        assertEquals(Schedule(mine).occurrences.size, t.size)
        // And the other way round (their plan is newer).
        val back = Together.of(Schedule(listOf(series)), listOf("me" to Schedule(mine)))
        assertEquals(listOf("me"), back[OccurrenceKey(series.uid, moved)])
        // A stale plan where the time moved is not together at the new time (they don't know it yet), nor at the old one.
        val timeMoved = listOf(mine[0], mine[1].copy(start = moved.plusHours(4), end = moved.plusHours(6), location = series.location))
        val t2 = together(timeMoved, listOf(series))
        assertFalse(t2.keys.any { it.start.toLocalDate() == moved.toLocalDate() })
    }

    @Test
    fun severalFriendsAreListedInOrder() {
        val e = Fixture.events.first { it.recurrence != null }
        val t = Together.of(Schedule(listOf(e)), listOf("x" to Schedule(listOf(e)), "y" to Schedule(emptyList()), "z" to Schedule(listOf(e))))
        assertTrue(t.values.all { it == listOf("x", "z") })
    }

    // --- Own changes never break a match (base identity: appointment UID + original date) ---

    private val course = event("500005000001", "Numerik N4 (VL)", "K30-H1", "2026-10-14T11:00", category = "Vorlesung (V)")
    private val day = LocalDate.parse("2026-10-21")

    private fun change(from: String = "2026-10-21", series: Boolean = false, uid: String = course.uid) =
        PersonalChange(uid = uid, date = LocalDate.parse(from), series = series, title = course.title)

    private fun plan(vararg changes: PersonalChange, events: List<Event> = listOf(course)) = Schedule(events, changes.toList())

    /** The weeks of [mine] attended together with a friend's [theirs]. */
    private fun dates(mine: Schedule, theirs: Schedule) = Together.of(mine, listOf("b" to theirs)).keys.map { it.start.toLocalDate() }.toSet()

    private val allDates = Schedule(listOf(course)).occurrences.map { it.date }.toSet()

    @Test
    fun myTimeChangeKeepsTheMatch() {
        val mine = plan(change(series = true).copy(start = LocalTime.of(11, 30), end = LocalTime.of(13, 30)))
        assertEquals(LocalTime.of(11, 30), mine.on(day).single().start.toLocalTime())
        assertEquals(allDates, dates(mine, plan()))
        // Both directions, and a single change.
        assertEquals(allDates, dates(plan(), mine))
        val single = plan(change().copy(start = LocalTime.of(15, 0), end = LocalTime.of(17, 0)))
        assertEquals(allDates, dates(single, plan()))
        assertEquals(listOf("b"), Together.of(single, listOf("b" to plan()))[OccurrenceKey(course.uid, day.atTime(15, 0))])
    }

    @Test
    fun myRoomChangeKeepsTheMatch() {
        val mine = plan(change(series = true).copy(room = "K22-A"))
        assertEquals("K22-A", mine.on(day).single().room)
        assertEquals(allDates, dates(mine, plan()))
        // Also against a friend with another appointment id of the same course (the room check uses LSF's room).
        val theirs = plan(events = listOf(course.copy(uid = "500005000002")))
        assertEquals(allDates, dates(mine, theirs))
    }

    @Test
    fun theFriendsSharedTimeChangeKeepsTheMatch() {
        val theirs = plan(change().copy(start = LocalTime.of(9, 0), end = LocalTime.of(11, 0)))
        assertEquals(LocalTime.of(9, 0), theirs.on(day).single().start.toLocalTime())
        assertEquals(allDates, dates(plan(), theirs))
        assertEquals(allDates, dates(theirs, plan()))
        // Both moved the same occurrence to different times: still the same appointment.
        val mine = plan(change().copy(start = LocalTime.of(14, 0), end = LocalTime.of(16, 0)))
        assertEquals(allDates, dates(mine, theirs))
    }

    @Test
    fun myCancellationBreaksTheMatchOnThatDateOnly() {
        val mine = plan(change().copy(cancelled = true))
        assertEquals(allDates - day, dates(mine, plan()))
        assertEquals(allDates - day, dates(plan(), mine))
    }

    @Test
    fun theFriendsCancellationBreaksTheMatch() {
        val theirs = plan(change().copy(cancelled = true))
        assertEquals(allDates - day, dates(plan(), theirs))
        // An LSF cancellation (EXDATE) on one side too.
        val lsf = listOf(course.copy(recurrence = WEEKLY.copy(exdates = setOf(day.atTime(11, 0)))))
        assertEquals(allDates - day, dates(plan(), Schedule(lsf)))
    }

    @Test
    fun manualCourseWithATimeChangeKeepsTheMatch() {
        val c = ManualCourse(
            id = ManualCourse.newId("0123456789abcdef"), title = "Lerngruppe", type = ManualType.OTHER, room = "Bibliothek",
            lecturer = "", rhythm = ManualCourse.Rhythm.WEEKLY, startDate = LocalDate.parse("2026-10-14"), endDate = LocalDate.parse("2026-11-11"),
            start = LocalTime.of(17, 0), end = LocalTime.of(18, 30), skipFreeWeeks = false,
        )
        val e = ManualCourses.event(c)
        val moved = PersonalChange(uid = e.uid, date = LocalDate.parse("2026-10-14"), series = true, title = e.title, start = LocalTime.of(18, 0), end = LocalTime.of(19, 30))
        val mine = Schedule(listOf(e), listOf(moved))
        val theirs = Schedule(listOf(e))
        val all = theirs.occurrences.map { it.date }.toSet()
        assertEquals(all, dates(mine, theirs))
        assertEquals(all, dates(theirs, mine))
        // Different title: no match even at the same base time.
        assertTrue(dates(mine, Schedule(listOf(e.copy(title = "Andere Gruppe")))).isEmpty())
    }

    private companion object {
        val WEEKLY = Recurrence(until = Instant.parse("2026-11-30T22:59:00Z"))
    }
}
