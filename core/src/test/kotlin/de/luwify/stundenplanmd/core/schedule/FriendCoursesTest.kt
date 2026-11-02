package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.ical.LsfText
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.ManualType
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Subjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class FriendCoursesTest {
    private val all = Fixture.events

    /** Wellen und Felder (Übung 2), Wed 13:00, biweekly; a second group: other appointment id, Thu 15:00, other room. */
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
    private val course = group1.courseKey
    private val withoutCourse = all.filterNot { it.courseKey == course }

    /** The friend has both groups; I have neither. */
    private val friend = withoutCourse + group1 + group2

    private fun firstOf(schedule: Schedule, uid: String): Timed = schedule.occurrences.first { it.event.uid == uid }

    @Test
    fun copiesTheWholeSeriesOfTheGroupWithItsIdentity() {
        val group = FriendCourses.group(friend, firstOf(Schedule(friend), group2.uid))
        assertEquals(listOf(group2), group)
        val r = AddedCourses.add(withoutCourse, emptyList(), group)
        assertEquals(listOf(group2), r.events)
        // Same UID, recurrence and EXDATEs: every date of the series, matched as "Zusammen mit" by the full UID.
        assertEquals(Expansion.slots(group2), Expansion.slots(r.events.single()))
    }

    @Test
    fun friendsChangesAndHiddenDatesAreNotCopied() {
        val dates = Expansion.slots(group2).map { it.start.toLocalDate() }
        val changes = listOf(
            PersonalChange(group2.uid, dates[0], cancelled = true),
            PersonalChange(group2.uid, dates[1], note = "bring laptop", room = "K99-999"),
        )
        val hiddenDate = Schedule(friend).occurrences.first { it.event.uid == group2.uid && it.date == dates[2] }
        val hiding = Hiding.of(listOf(Hidden(Hidden.Kind.DATE, courses = setOf(course), uid = group2.uid, date = hiddenDate.date)), friend, emptyMap(), shared = listOf(OccurrenceRef(group2.uid, dates[3])))
        val shown = Schedule(friend, changes, hiding)
        // Tapped on a date the friend changed.
        val item = shown.occurrences.first { it.event.uid == group2.uid && it.date == dates[1] }
        assertEquals("K99-999", item.room)
        val group = FriendCourses.group(friend, item)
        assertEquals(listOf(group2), group)
        val mine = Schedule(withoutCourse + group)
        val myDates = mine.occurrences.filter { it.event.uid == group2.uid }
        // No cancellation, no changed room, nothing hidden: all of them, as LSF has them.
        assertEquals(dates, myDates.map { it.date })
        assertTrue(myDates.all { it.room == group2.location?.room })
        assertTrue(mine.cancellations.none { it.event.uid == group2.uid })
    }

    @Test
    fun movedDatesComeWithTheirGroup() {
        // N4 VL: two series (Tue, Wed) = two groups; a moved date of the Tue one goes with it.
        val n4 = all.filter { it.courseKey == "lsf:900066" }
        val tue = n4.single { it.uid == "900066500518" }
        val slot = Expansion.slots(tue)[2].start
        val moved = tue.copy(
            uid = "900066500777", recurrence = null,
            start = slot.plusDays(1), end = slot.plusDays(1).plusHours(2),
        )
        val friendN4 = all.filterNot { it.courseKey == "lsf:900066" } + n4.map {
            if (it.uid == tue.uid) it.copy(recurrence = it.recurrence!!.copy(exdates = it.recurrence!!.exdates + slot)) else it
        } + moved
        val group = FriendCourses.group(friendN4, firstOf(Schedule(friendN4), tue.uid))
        assertEquals(setOf(tue.uid, moved.uid), group.map { it.uid }.toSet())
    }

    @Test
    fun stateFindsWhatIsAlreadyMine() {
        val group = listOf(group1)
        // In my imported plan.
        assertEquals(FriendCourses.State.IN_PLAN, FriendCourses.state(group, all, emptyList(), emptyList()))
        // Not there.
        assertEquals(FriendCourses.State.ADDABLE, FriendCourses.state(group, withoutCourse, emptyList(), emptyList()))
        // Added (from a friend or an .ics): can be removed.
        assertEquals(FriendCourses.State.ADDED, FriendCourses.state(group, withoutCourse, listOf(group1), emptyList()))
        // Another group of a course I have: addable.
        assertEquals(FriendCourses.State.ADDABLE, FriendCourses.state(listOf(group2), all, emptyList(), emptyList()))
        // A moved date my older plan lacks doesn't make the group "new": the series decides.
        val extra = group1.copy(uid = "900099500888", recurrence = null)
        assertEquals(FriendCourses.State.IN_PLAN, FriendCourses.state(listOf(group1, extra), all, emptyList(), emptyList()))
    }

    @Test
    fun anotherGroupJoinsTheCourseIHave() {
        val mine = withoutCourse + group1
        val group = FriendCourses.group(friend, firstOf(Schedule(friend), group2.uid))
        val r = AddedCourses.add(mine, emptyList(), group)
        assertEquals(1, r.added)
        assertTrue(r.duplicates.isEmpty())
        val subjects = Subjects.build(mine + r.events, emptyMap())
        val c = subjects.flatMap { it.courses }.single { it.key == course }
        assertEquals(setOf(group1.uid, group2.uid), c.events.map { it.uid }.toSet())
    }

    @Test
    fun reImportDoesNotDuplicate() {
        val added = AddedCourses.add(withoutCourse, emptyList(), listOf(group2)).events
        // My new export has that group itself: the copy goes, the plan's one stays.
        val (kept, dropped) = AddedCourses.dedupe(withoutCourse + group2, added)
        assertTrue(kept.isEmpty())
        assertEquals(1, dropped)
        // Until then, an import that lacks it keeps it.
        assertEquals(added to 0, AddedCourses.dedupe(withoutCourse, added))
        // And a plan with it never shows it twice.
        assertTrue(AddedCourses.visible(withoutCourse + group2, added).isEmpty())
    }

    @Test
    fun colorMineForMySubjectsTheFriendsOtherwise() {
        val red = 0xC62828
        val mySettings = mapOf(course to CourseSetting(course, group1.title, color = 0x1565C0))
        // Another group of my course: same course key, my setting untouched.
        assertEquals(mySettings, FriendCourses.settings(mySettings, withoutCourse + group1, listOf(group2), red))
        // A course of a subject I have (N4 HSÜ joins my N4 by title stem): no color of its own.
        val hsu = all.filter { it.title.startsWith("Numerik N4") && it.courseKey != "lsf:900066" }.groupBy { it.courseKey }.values.first()
        val mineN4 = all.filterNot { e -> hsu.any { it.courseKey == e.courseKey } }
        assertNull(FriendCourses.settings(emptyMap(), mineN4, hsu, red)[hsu.first().courseKey])
        // Wellen und Felder VL is in my plan, so its Übung joins that subject.
        assertNull(FriendCourses.settings(emptyMap(), withoutCourse, listOf(group2), red)[course])
        // A new subject: the friend's color.
        val other = group2.copy(uid = "123456000001", summary = "Kryptografie", title = "Kryptografie")
        assertEquals(red, FriendCourses.settings(emptyMap(), withoutCourse, listOf(other), red)[other.courseKey]?.color)
    }

    @Test
    fun manualCourseIsCopiedAsManual() {
        val theirs = ManualCourse(
            id = "manual-0123456789abcdef", title = "Lerngruppe", type = ManualType.SEMINAR, room = "Bibliothek",
            lecturer = "Tutor", note = "my own note", st = true, rhythm = ManualCourse.Rhythm.ODD_WEEKS,
            startDate = LocalDate.of(2026, 10, 20), endDate = LocalDate.of(2027, 1, 19),
            start = LocalTime.of(17, 0), end = LocalTime.of(19, 0),
        )
        // As it arrives in a share: rhythm written out (Expansion.portable), free weeks as EXDATEs.
        val event = Expansion.portable(ManualCourses.event(theirs) { it.monthValue == 12 && it.dayOfMonth > 20 })
        val friendEvents = withoutCourse + event
        val group = FriendCourses.group(friendEvents, firstOf(Schedule(friendEvents), event.uid))
        assertEquals(listOf(event), group)
        assertEquals(FriendCourses.State.ADDABLE, FriendCourses.state(group, all, emptyList(), emptyList()))
        val mine = FriendCourses.manualCourse(group.single())
        assertEquals(theirs.copy(note = ""), mine)
        assertEquals(FriendCourses.State.IN_PLAN, FriendCourses.state(group, all, emptyList(), listOf(mine)))
        // My copy has the same dates (my free weeks left out) and matches as "Zusammen mit" (title, type, room, time).
        val copied = ManualCourses.event(mine)
        assertEquals(Expansion.slots(ManualCourses.event(theirs)), Expansion.slots(copied))
        assertEquals(event.category, copied.category)
        assertEquals(event.location, copied.location)
    }
}
