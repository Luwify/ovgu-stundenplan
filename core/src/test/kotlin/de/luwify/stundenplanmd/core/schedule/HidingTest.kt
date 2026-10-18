package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.ShareResult
import de.luwify.stundenplanmd.core.share.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HidingTest {
    private val events = Fixture.events
    private val all = Schedule(events)
    private val n4 = Subjects.autoKey("Numerik N4 (VL)")
    private val n4Courses = Subjects.build(events, emptyMap()).single { it.key == n4 }.courses.mapTo(LinkedHashSet()) { it.key }

    private fun schedule(vararg items: Hidden, settings: Map<String, CourseSetting> = emptyMap()) =
        Schedule(events, hiding = Hiding.of(items.toList(), events, settings))

    private fun timed(s: Schedule): List<Timed> = s.occurrences + s.cancellations

    private fun uids(s: Schedule) = timed(s).mapTo(HashSet()) { it.event.uid }

    @Test
    fun hidingASubjectHidesAllItsTypesGroupsAndDates() {
        assertTrue(n4Courses.size >= 2) // VL and HSÜ at least
        val s = schedule(Hidden(Hidden.Kind.SUBJECT, subject = n4, courses = n4Courses))
        assertTrue(timed(s).none { it.event.courseKey in n4Courses })
        // Everything else is untouched.
        assertEquals(timed(all).filter { it.event.courseKey !in n4Courses }.size, timed(s).size)
    }

    @Test
    fun aSubjectIsFoundByItsKeyWhenACourseIdChangesOnReimport() {
        val hidden = Hidden(Hidden.Kind.SUBJECT, subject = n4, courses = n4Courses)
        // The next export has the VL under another course id: same title stem, so the same subject.
        val reimported = LsfImport.parse(Fixture.text.replace("900066", "900067")).events
        val s = Schedule(reimported, hiding = Hiding.of(listOf(hidden), reimported, emptyMap()))
        assertTrue(timed(s).none { it.event.title.startsWith("Numerik N4") })
        assertTrue(timed(Schedule(reimported)).any { it.event.courseKey == "lsf:900067" })
    }

    @Test
    fun aSubjectMovedByHandIsFoundByTheShownSettings() {
        // "Ausgabe GFT III Skript" moved into N4 by hand: hiding N4 hides it too.
        val settings = mapOf("lsf:900033" to CourseSetting("lsf:900033", "Ausgabe GFT III Skript", subject = n4))
        val s = Schedule(events, hiding = Hiding.of(listOf(Hidden(Hidden.Kind.SUBJECT, subject = n4, courses = n4Courses)), events, settings))
        assertTrue(timed(s).none { it.event.courseKey == "lsf:900033" })
    }

    @Test
    fun hidingATypeKeepsTheOtherTypesOfTheSubject() {
        val s = schedule(Hidden(Hidden.Kind.COURSE, courses = setOf("lsf:900066")))
        assertTrue(timed(s).none { it.event.courseKey == "lsf:900066" })
        assertTrue(timed(s).any { it.event.courseKey == "lsf:900022" })
    }

    @Test
    fun hidingAGroupKeepsTheCoursesOtherGroups() {
        // N4 VL has two biweekly series (Tue and Wed).
        val tue = all.occurrences.first { it.event.uid == "900066500518" }
        assertEquals(2, Groups.count(all, tue))
        val s = schedule(Hidden(Hidden.Kind.GROUP, courses = setOf("lsf:900066"), uid = "900066500518"))
        assertFalse("900066500518" in uids(s))
        assertTrue("900066500555" in uids(s))
        assertEquals(1, Groups.count(s, s.occurrences.first { it.event.uid == "900066500555" }))
    }

    @Test
    fun hidingTheGroupOfASeriesHidesTheDatesLsfMovedOutOfIt() {
        val moved = all.occurrences.first { it.status is Status.Changed }
        val base = Groups.baseUid(moved)
        assertTrue(base != moved.event.uid)
        val s = schedule(Hidden(Hidden.Kind.GROUP, courses = setOf(moved.event.courseKey), uid = base))
        assertTrue(s.occurrences.none { Groups.baseUid(it) == base })
        assertTrue(s.occurrences.none { it.event.uid == moved.event.uid && it.start == moved.start })
    }

    @Test
    fun hidingADateHidesOnlyThatDateAndAMovedOneByItsOriginalDate() {
        val first = all.occurrences.first { it.event.uid == "900066500555" }
        val s = schedule(Hidden(Hidden.Kind.DATE, courses = setOf("lsf:900066"), uid = first.ref.uid, date = first.ref.date))
        assertEquals(all.occurrences.size - 1, s.occurrences.size)
        assertTrue(s.occurrences.none { it.event.uid == first.event.uid && it.date == first.date })

        val moved = all.occurrences.first { it.status is Status.Changed }
        val m = schedule(Hidden(Hidden.Kind.DATE, courses = setOf(moved.event.courseKey), uid = moved.ref.uid, date = moved.ref.date))
        assertEquals(all.occurrences.size - 1, m.occurrences.size)
        assertTrue(m.occurrences.none { it.event.uid == moved.event.uid && it.start == moved.start })
        // A hidden date is no cancellation either: no "fällt aus" and no "Vorlesungsfrei" from it.
        assertEquals(all.cancellations, m.cancellations)
    }

    @Test
    fun hiddenOccurrencesAreNotTogether() {
        val friend = Schedule(events)
        val before = Together.of(all, listOf("f" to friend))
        assertTrue(before.keys.any { it.uid.startsWith("900066") })
        // Hidden in my plan: not together. Hidden in theirs (by me): not together either.
        val mine = schedule(Hidden(Hidden.Kind.COURSE, courses = setOf("lsf:900066")))
        assertTrue(Together.of(mine, listOf("f" to friend)).keys.none { it.uid.startsWith("900066") })
        assertTrue(Together.of(all, listOf("f" to mine)).keys.none { it.uid.startsWith("900066") })
    }

    @Test
    fun addingABroaderItemDropsTheNarrowerOnesItCovers() {
        val date = Hidden(Hidden.Kind.DATE, courses = setOf("lsf:900066"), uid = "900066500555", date = LocalDate.parse("2026-10-14"))
        val group = Hidden(Hidden.Kind.GROUP, courses = setOf("lsf:900066"), uid = "900066500555")
        val other = Hidden(Hidden.Kind.COURSE, courses = setOf("lsf:900099"))
        var list = Hidden.add(Hidden.add(emptyList(), date), other)
        list = Hidden.add(list, group)
        assertEquals(listOf(other, group), list)
        list = Hidden.add(list, Hidden(Hidden.Kind.SUBJECT, subject = n4, courses = n4Courses, title = "Numerik"))
        assertEquals(2, list.size)
        // The same target again replaces it; restoring removes it.
        list = Hidden.add(list, other.copy(title = "x"))
        assertEquals(2, list.size)
        assertEquals(1, Hidden.remove(list, other).size)
    }

    @Test
    fun hiddenThingsAreLeftOutOfMyShareAndHiddenDatesStayHiddenForTheFriend() {
        val date = all.occurrences.first { it.event.uid == "900099500703" }
        val hiding = Hiding.of(
            listOf(
                Hidden(Hidden.Kind.SUBJECT, subject = n4, courses = n4Courses),
                Hidden(Hidden.Kind.DATE, courses = setOf(date.event.courseKey), uid = date.ref.uid, date = date.ref.date),
            ),
            events, emptyMap(),
        )
        val mine = Schedule(events, hiding = hiding)
        val shared = Hiding.forSharing(events, emptyList(), hiding)
        assertTrue(shared.events.none { it.courseKey in n4Courses })
        assertEquals(listOf(OccurrenceRef(date.event.uid, date.date)), shared.hidden)

        val plan = SharedPlan("0123456789abcdef", "Max", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 11), shared.events, emptyMap(), true, hidden = shared.hidden)
        assertEquals(3, ShareCodec.version(plan))
        for (bytes in listOf(ShareCodec.file(plan), ShareCodec.qrText(plan).toByteArray())) {
            val read = (ShareCodec.parse(bytes) as ShareResult.Ok).plan
            assertEquals(shared.hidden, read.hidden)
            val theirs = Schedule(read.events, hiding = Hiding.of(emptyList(), read.events, emptyMap(), read.hidden))
            assertEquals(mine.occurrences.map { it.event.uid to it.start }, theirs.occurrences.map { it.event.uid to it.start })
            assertEquals(mine.cancellations.map { it.event.uid to it.start }, theirs.cancellations.map { it.event.uid to it.start })
        }
        // Nothing hidden: the plan is shared as before, in the version older apps read.
        val plain = Hiding.forSharing(events, emptyList(), null)
        assertEquals(events, plain.events)
        assertEquals(1, ShareCodec.version(plan.copy(events = plain.events, hidden = plain.hidden)))
    }
}
