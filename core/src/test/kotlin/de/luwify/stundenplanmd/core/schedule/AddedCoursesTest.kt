package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.ical.LsfText
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Subjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class AddedCoursesTest {
    private val all = Fixture.events
    private val n4vl = "lsf:900066"
    private val single = all.filter { it.courseKey == n4vl }
    private val plan = all.filterNot { it.courseKey == n4vl }

    /** Wellen und Felder (Übung 2), Wed 13:00, and a second group of the same course: other appointment id, Thu 15:00, other room. */
    private val group1 = Fixture.event("900099500703")
    private val group2 = group1.copy(
        uid = "900099500999",
        summary = group1.summary.replace("Übung 2", "Übung 3"),
        title = group1.title.replace("Übung 2", "Übung 3"),
        location = LsfText.location("K09B - K09B-203 (40 Pl.)"),
        start = group1.start.plusDays(1).plusHours(2),
        end = group1.end.plusDays(1).plusHours(2),
        recurrence = group1.recurrence!!.copy(byDay = setOf(DayOfWeek.THURSDAY), exdates = emptySet()),
    )
    private val withoutCourse = all.filterNot { it.courseKey == group1.courseKey }

    @Test
    fun twoGroupsOfOneCourse() {
        assertEquals(group1.courseKey, group2.courseKey)
        val first = AddedCourses.add(withoutCourse, emptyList(), listOf(group1))
        val second = AddedCourses.add(withoutCourse, first.events, listOf(group2))
        assertEquals(1, second.added)
        assertEquals(0, second.updated)
        assertTrue(second.duplicates.isEmpty())
        assertEquals(listOf(group1, group2), second.events)
        // One course, so one subject (color and short name are per course key).
        val subjects = Subjects.build(withoutCourse + second.events, emptyMap())
        assertEquals(1, subjects.count { s -> s.courses.any { it.key == group1.courseKey } })
        assertEquals(setOf(group1.uid, group2.uid), subjects.flatMap { it.courses }.single { it.key == group1.courseKey }.events.map { it.uid }.toSet())
    }

    @Test
    fun sameGroupImportedTwiceUpdatesIt() {
        val first = AddedCourses.add(plan, emptyList(), single)
        assertEquals(single.size, first.added)
        val moved = single.map { it.copy(location = null) }
        val again = AddedCourses.add(plan, first.events, moved)
        assertEquals(0, again.added)
        assertEquals(single.size, again.updated)
        assertEquals(moved, again.events)
    }

    @Test
    fun groupInTheMainPlanPlusAnotherGroupAdded() {
        // My plan has group 1; a file with both groups adds group 2 and names group 1 as already there.
        val r = AddedCourses.add(all, emptyList(), listOf(group1, group2))
        assertEquals(listOf(group2), r.events)
        assertEquals(listOf(group1), r.duplicates)
        assertEquals(1, r.added)
        assertEquals(listOf(group2), AddedCourses.visible(all, r.events))
        val s = Schedule(all + r.events)
        assertTrue(s.occurrences.any { it.event.uid == group1.uid } && s.occurrences.any { it.event.uid == group2.uid })
        // The same appointment again is never added twice.
        assertEquals(listOf(group2), AddedCourses.add(all, r.events, listOf(group1)).events)
    }

    @Test
    fun fullReimportContainingOneOfTwoAddedGroups() {
        val added = AddedCourses.add(withoutCourse, emptyList(), listOf(group1, group2)).events
        val (kept, dropped) = AddedCourses.dedupe(withoutCourse + group1, added)
        assertEquals(listOf(group2), kept)
        assertEquals(1, dropped)
        // A plan without either keeps both; a plan with another appointment of the course keeps both too.
        assertEquals(added to 0, AddedCourses.dedupe(withoutCourse, added))
        assertEquals(added to 0, AddedCourses.dedupe(withoutCourse + group1.copy(uid = "900099500111"), added))
    }

    @Test
    fun groupsAreKeptApartForChangesTogetherAndRemoval() {
        val mine = withoutCourse + group1 + group2
        val s = Schedule(mine)
        val o1 = s.occurrences.first { it.event.uid == group1.uid }
        val o2 = s.occurrences.first { it.event.uid == group2.uid }
        assertEquals(setOf(group1.uid), Groups.of(s, o1))
        assertEquals(setOf(group2.uid), Groups.of(s, o2))
        // An own change of group 2 (keyed by its full UID) leaves group 1 alone.
        val change = PersonalChange(group2.uid, o2.date, series = true, title = group2.title, cancelled = true)
        val changed = Schedule(mine, listOf(change))
        assertTrue(changed.occurrences.none { it.event.uid == group2.uid })
        assertEquals(s.occurrences.count { it.event.uid == group1.uid }, changed.occurrences.count { it.event.uid == group1.uid })
        // Together: a friend in group 1 only.
        val t = Together.of(s, listOf("f" to Schedule(all)))
        assertTrue(t.keys.any { it.uid == group1.uid })
        assertTrue(t.keys.none { it.uid == group2.uid })
        // Removing group 2 keeps group 1.
        assertEquals(listOf(group1), AddedCourses.remove(listOf(group1, group2), Groups.of(s, o2)))
    }

    @Test
    fun aCourseWithOneGroupKeepsItsMovedAndExtraDates() {
        val s = Schedule(all)
        val course = all.filter { it.courseKey == "lsf:900121" }.map { it.uid }.toSet()
        val o = s.occurrences.first { it.event.uid == "900121500407" }
        assertEquals(course, Groups.of(s, o))
        val moved = s.occurrences.firstOrNull { (it.status as? Status.Changed)?.original?.event?.uid == "900121500407" }
        if (moved != null) assertEquals("900121500407", Groups.baseUid(moved))
    }

    @Test
    fun safeguardForFilesWithFarFewerCourses() {
        assertTrue(AddedCourses.looksPartial(all, single))
        assertFalse(AddedCourses.looksPartial(all, plan))
        assertFalse(AddedCourses.looksPartial(emptyList(), single)) // no plan yet
        assertFalse(AddedCourses.looksPartial(single, single))
        val half = all.filter { it.courseKey in AddedCourses.keys(all).take((AddedCourses.courseCount(all) + 1) / 2) }
        assertFalse(AddedCourses.looksPartial(all, half))
        assertEquals(LocalDate.parse("2026-10-14"), group1.start.toLocalDate())
    }
}
