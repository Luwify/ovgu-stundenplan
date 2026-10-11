package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Location
import de.luwify.stundenplanmd.core.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleTest {
    private fun dt(s: String) = LocalDateTime.parse(s)
    private val fixture by lazy { Schedule(Fixture.events) }

    private fun describe(o: Occurrence): String {
        val s = o.status
        return when (s) {
            is Status.Changed -> "${o.event.uid} ${o.start} CHANGED ${s.changes.sorted()} from ${s.original.start} ${s.original.event.location?.room}"
            Status.Extra -> "${o.event.uid} ${o.start} EXTRA"
            Status.Regular -> "${o.event.uid} ${o.start}"
        }
    }

    @Test
    fun fixtureChangesAndExtras() {
        assertEquals(
            listOf(
                "900154500814 2026-11-12T17:00 CHANGED [TIME, ROOM] from 2026-11-12T11:00 K14-102",
                "900121500851 2026-11-13T11:00 CHANGED [ROOM] from 2026-11-13T11:00 K14-102",
                "900176500888 2026-11-13T13:00 CHANGED [ROOM] from 2026-11-13T13:00 K14-102",
                "900121500851 2026-11-20T11:00 CHANGED [ROOM] from 2026-11-20T11:00 K14-102",
                "900176500888 2026-11-20T13:00 CHANGED [ROOM] from 2026-11-20T13:00 K14-102",
                "900121500777 2027-01-13T13:00 EXTRA",
            ),
            fixture.occurrences.filter { it.status != Status.Regular }.map(::describe),
        )
    }

    @Test
    fun fixtureTotalsAndRange() {
        assertEquals(219, fixture.occurrences.size)
        assertEquals(LocalDate.parse("2026-10-12"), fixture.firstDate)
        assertEquals(LocalDate.parse("2027-01-29"), fixture.lastDate)
        assertEquals(63, fixture.between(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-11-09")).size)
        // Two events at the same time on Tuesday mornings.
        assertEquals(2, fixture.on(LocalDate.parse("2026-10-13")).count { it.start.hour == 7 })
    }

    @Test
    fun fixtureCancellationsWithoutReplacement() {
        val cancelled = fixture.cancellations
        assertTrue(cancelled.none { it.start.toLocalDate() == LocalDate.parse("2026-11-12") && it.start.hour == 11 })
        assertTrue(cancelled.any { it.event.uid == "900055500111" && it.start == dt("2026-10-27T09:00") })
        // Off-rhythm EXDATEs (GDL Übung 22.10.) are no cancellations.
        assertTrue(cancelled.none { it.start == dt("2026-10-22T09:00") })
    }

    private fun ev(uid: String, start: String, rule: Recurrence? = null, room: String = "K14-102", lecturer: String = "Pohl") = Event(
        uid = uid, summary = "S", title = "S", lecturers = listOf(lecturer), start = dt(start), end = dt(start).plusHours(2),
        location = Location(raw = room, building = room.substringBefore('-'), room = room), recurrence = rule,
    )

    private val weekly = Recurrence(until = Instant.parse("2026-12-31T00:00:00Z"), exdates = setOf(dt("2026-11-04T09:00")))

    @Test
    fun movedToAnotherDayInTheSameWeek() {
        val s = Schedule(listOf(ev("100000000001", "2026-10-14T09:00", weekly), ev("100000000002", "2026-11-06T09:00", lecturer = "Hahn")))
        val changed = s.occurrences.single { it.status is Status.Changed }
        assertEquals(setOf(Change.DAY, Change.LECTURER), (changed.status as Status.Changed).changes)
        assertEquals(emptyList<Cancellation>(), s.cancellations)
    }

    @Test
    fun otherWeekIsExtraOtherCourseIsNoReplacement() {
        val s = Schedule(
            listOf(
                ev("100000000001", "2026-10-14T09:00", weekly),
                ev("100000000002", "2026-11-12T09:00"), // next week
                ev("200000000003", "2026-11-04T11:00"), // other course, only single dates: regular
            ),
        )
        assertEquals(listOf("100000000002"), s.occurrences.filter { it.status == Status.Extra }.map { it.event.uid })
        assertEquals(1, s.cancellations.size)
    }

    @Test
    fun courseOfOnlySingleDatesIsRegular() {
        val s = Schedule(listOf(ev("100000000001", "2026-10-14T09:00"), ev("100000000002", "2026-10-21T09:00")))
        assertTrue(s.occurrences.all { it.status == Status.Regular })
    }

    @Test
    fun onlyLongSeriesIsRegular() {
        // The only series of a course is regular even if short.
        val short = Recurrence(count = 3)
        assertTrue(Schedule(listOf(ev("100000000001", "2026-10-14T09:00", short))).occurrences.all { it.status == Status.Regular })
    }

    @Test
    fun eachCancelledSlotIsReplacedOnce() {
        val s = Schedule(
            listOf(ev("100000000001", "2026-10-14T09:00", weekly), ev("100000000002", "2026-11-04T09:00", room = "G01-1"), ev("100000000003", "2026-11-05T09:00")),
        )
        assertEquals(listOf("CHANGED", "EXTRA"), s.occurrences.filter { it.status != Status.Regular }.map { if (it.status is Status.Changed) "CHANGED" else "EXTRA" })
    }
}
