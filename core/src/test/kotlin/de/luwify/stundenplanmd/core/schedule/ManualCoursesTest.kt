package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.model.CourseKeys
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.ManualType
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Subjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class ManualCoursesTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val fixture = Schedule(Fixture.events)

    private fun course(
        rhythm: ManualCourse.Rhythm = ManualCourse.Rhythm.WEEKLY,
        start: String = "2026-10-12",
        end: String? = "2027-01-29",
        skip: Boolean = true,
    ) = ManualCourse(
        id = ManualCourse.newId("0123456789abcdef"), title = "Lerngruppe Numerik", type = ManualType.OTHER, room = "Bibliothek",
        lecturer = "Tutor", rhythm = rhythm, startDate = d(start), endDate = end?.let(::d),
        start = LocalTime.of(17, 0), end = LocalTime.of(18, 30), skipFreeWeeks = skip,
    )

    private fun mine(c: ManualCourse) = Schedule(ManualCourses.merge(Fixture.events, listOf(c))).occurrences.filter { it.event.isManual }

    /** Weeks the LSF plan alone marks lecture-free (the Christmas break in the fixture). */
    private val freeWeeks = generateSequence(Schedule.monday(fixture.firstDate!!.atStartOfDay())) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(fixture.lastDate) }.filter { fixture.week(it).free }.toList()

    @Test
    fun idsNeverLookLikeLsfAndKeepTheirOwnCourse() {
        val c = course()
        assertEquals("manual-0123456789abcdef", c.id)
        assertNull(CourseKeys.courseId(c.id))
        val e = ManualCourses.event(c)
        assertTrue(e.isManual)
        assertEquals("manual:manual-0123456789abcdef", e.courseKey)
        // The same title as an LSF course is still its own course (settings are never shared by accident).
        val lsfTwin = Fixture.events.first().copy(title = c.title)
        assertEquals(2, Subjects.courses(listOf(e, lsfTwin)).size)
        assertTrue(Fixture.events.none { it.isManual })
    }

    @Test
    fun onceWeeklyAndEveryTwoWeeks() {
        assertEquals(listOf(d("2026-10-14")), mine(course(ManualCourse.Rhythm.ONCE, start = "2026-10-14")).map { it.date })
        val weekly = mine(course(skip = false, end = "2026-11-09"))
        assertEquals((0L..4L).map { d("2026-10-12").plusWeeks(it) }, weekly.map { it.date })
        assertTrue(weekly.all { it.date.dayOfWeek == DayOfWeek.MONDAY && it.start.toLocalTime() == LocalTime.of(17, 0) })
        val biweekly = mine(course(ManualCourse.Rhythm.BIWEEKLY, start = "2026-10-15", end = "2026-11-26", skip = false))
        assertEquals(listOf("2026-10-15", "2026-10-29", "2026-11-12", "2026-11-26").map(::d), biweekly.map { it.date })
    }

    @Test
    fun freeWeeksAreSkippedAndStillDetected() {
        assertTrue("fixture has a lecture-free week", freeWeeks.isNotEmpty())
        val merged = Schedule(ManualCourses.merge(Fixture.events, listOf(course())))
        for (monday in freeWeeks) {
            assertTrue(merged.week(monday).days.none { day -> day.occurrences.any { it.event.isManual } })
            assertTrue("still lecture-free: $monday", merged.week(monday).free)
        }
        // Without the option the course takes place, and the week is no longer free.
        val kept = Schedule(ManualCourses.merge(Fixture.events, listOf(course(skip = false))))
        assertFalse(kept.week(freeWeeks.first()).free)
        assertTrue(kept.week(freeWeeks.first()).days.any { day -> day.occurrences.any { it.event.isManual } })
    }

    @Test
    fun reimportKeepsManualCoursesAndOwnChangesApply() {
        val c = course(end = "2026-11-09", skip = false)
        val reimported = Fixture.events.drop(5)
        assertEquals(5, Schedule(ManualCourses.merge(reimported, listOf(c))).occurrences.count { it.event.isManual })
        val changes = listOf(
            PersonalChange(c.id, d("2026-10-19"), cancelled = true),
            PersonalChange(c.id, d("2026-10-26"), series = true, note = "Raum 2", room = "Bibliothek 2"),
        )
        val s = Schedule(ManualCourses.merge(Fixture.events, listOf(c)), changes)
        val occ = s.occurrences.filter { it.event.isManual }
        assertEquals(4, occ.size)
        assertTrue(s.cancellations.single { it.event.isManual }.byMe)
        assertEquals("Bibliothek 2", occ.last().room)
        assertEquals(emptyList<PersonalChange>(), PersonalChanges.orphans(ManualCourses.merge(Fixture.events, listOf(c)), changes))
        // Deleting the course takes my changes to it along (undo restores both).
        assertEquals(emptyList<ManualCourse>() to emptyList<PersonalChange>(), ManualCourses.remove(listOf(c), changes, c.id))
    }

    @Test
    fun stComesFromTheCourseAndCanBeChangedPerDate() {
        val c = course(end = "2026-10-26", skip = false).copy(st = true)
        val s = Schedule(ManualCourses.merge(emptyList(), listOf(c)), listOf(PersonalChange(c.id, d("2026-10-19"), st = false)))
        assertEquals(listOf(true, false, true), s.occurrences.map { it.st })
        // A series edit that keeps s.t. stores nothing for it (it is the course's own value).
        val p = s.occurrences.first().personalOrBlank()
        assertEquals(emptyList<PersonalChange>(), PersonalChanges.setSeries(emptyList(), p, c.title, p.original))
    }

    @Test
    fun togetherOnlyThroughTheExactFallback() {
        val c = course(end = "2026-10-26", skip = false)
        val e = ManualCourses.event(c)
        // A friend with an LSF event of exactly that title, type, room and time: together. Another room: not.
        val twin = e.copy(uid = "900999000001", summary = e.title)
        assertEquals(3, Together.of(Schedule(listOf(e)), listOf("f" to Schedule(listOf(twin)))).size)
        val elsewhere = twin.copy(location = twin.location!!.copy(raw = "Mensa", room = "Mensa"))
        assertEquals(0, Together.of(Schedule(listOf(e)), listOf("f" to Schedule(listOf(elsewhere)))).size)
    }

    private fun Occurrence.personalOrBlank() = personal ?: Personal(ref, null, null, Personal.values(event, start, end), lsfChanged = false)
}
