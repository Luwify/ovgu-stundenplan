package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddedCoursesTest {
    private val all = Fixture.events
    private val n4vl = "lsf:900066"
    private val single = all.filter { it.courseKey == n4vl }
    private val plan = all.filterNot { it.courseKey == n4vl }

    @Test
    fun addsUpdatesAndSkipsCoursesOfThePlan() {
        val first = AddedCourses.add(plan, emptyList(), single)
        assertEquals(1, first.added)
        assertEquals(0, first.updated)
        assertEquals(single, first.events)
        // The same course again (e.g. a newer export of it): replaced, not doubled.
        val moved = single.map { it.copy(location = null) }
        val again = AddedCourses.add(plan, first.events, moved)
        assertEquals(0, again.added)
        assertEquals(1, again.updated)
        assertEquals(moved, again.events)
        // A course the imported plan has is never added.
        val dup = AddedCourses.add(plan, again.events, plan.filter { it.courseKey == "lsf:900011" })
        assertEquals(1, dup.alreadyInPlan)
        assertEquals(moved, dup.events)
    }

    @Test
    fun fullImportWinsOverAddedDuplicates() {
        val added = AddedCourses.add(plan, emptyList(), single).events
        assertEquals(added, AddedCourses.visible(plan, added))
        val (kept, dropped) = AddedCourses.dedupe(all, added)
        assertTrue(kept.isEmpty())
        assertEquals(1, dropped)
        assertTrue(AddedCourses.visible(all, added).isEmpty())
        // A full import without that course keeps it.
        assertEquals(added to 0, AddedCourses.dedupe(plan, added))
        assertTrue(AddedCourses.remove(added, n4vl).isEmpty())
    }

    @Test
    fun addedCoursesKeepTheirUidsForTogether() {
        val mine = plan + AddedCourses.add(plan, emptyList(), single).events
        val t = Together.of(Schedule(mine), listOf("b" to Schedule(all)))
        assertTrue(t.keys.any { k -> single.any { it.uid == k.uid } })
    }

    @Test
    fun safeguardForFilesWithFarFewerCourses() {
        assertTrue(AddedCourses.looksPartial(all, single))
        assertFalse(AddedCourses.looksPartial(all, plan))
        assertFalse(AddedCourses.looksPartial(emptyList(), single)) // no plan yet
        assertFalse(AddedCourses.looksPartial(single, single))
        val half = all.filter { it.courseKey in AddedCourses.keys(all).take((AddedCourses.courseCount(all) + 1) / 2) }
        assertFalse(AddedCourses.looksPartial(all, half))
    }
}
