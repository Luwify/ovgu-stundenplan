package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.ical.LsfText
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

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

    private companion object {
        val WEEKLY = Recurrence(until = Instant.parse("2026-11-30T22:59:00Z"))
    }
}
