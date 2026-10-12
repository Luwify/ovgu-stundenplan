package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Location
import de.luwify.stundenplanmd.core.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

class NowStatusTest {
    private fun series(uid: String, start: String, hours: Long, room: String) = Event(
        uid = uid, summary = uid, title = uid, location = Location(room, null, room),
        start = LocalDateTime.parse(start), end = LocalDateTime.parse(start).plusHours(hours),
        recurrence = Recurrence(until = Instant.parse("2026-11-30T22:59:00Z")),
    )

    // Monday 09–11 and 13–15, Tuesday 07–09 and an overlapping 08–10.
    private val schedule = Schedule(
        listOf(
            series("100001000001", "2026-10-12T09:00", 2, "K18-012"),
            series("100002000001", "2026-10-12T13:00", 2, "K14-102"),
            series("100003000001", "2026-10-13T07:00", 2, "K25-H3"),
            series("100004000001", "2026-10-13T08:00", 2, "K30-H1"),
        ),
    )

    private fun at(t: String) = NowStatus.of(schedule, LocalDateTime.parse(t))

    @Test
    fun duringBetweenAndAfterEvents() {
        val inFirst = at("2026-10-19T10:30") as NowStatus.In
        assertEquals("K18-012", inFirst.occurrence.event.location?.room)
        assertEquals(LocalTime.of(11, 0), inFirst.until)
        // Start is inclusive, end exclusive: at 11:00 they are free until 13:00.
        val between = at("2026-10-19T11:00") as NowStatus.FreeUntil
        assertEquals("K14-102", between.next.event.location?.room)
        assertEquals((at("2026-10-19T08:00") as NowStatus.FreeUntil).next.start.toLocalTime(), LocalTime.of(9, 0))
        assertEquals(NowStatus.FreeToday, at("2026-10-19T15:00"))
        // A weekday of the semester without events.
        assertEquals(NowStatus.FreeToday, at("2026-10-21T10:00"))
    }

    @Test
    fun overlapPicksTheLaterStart() {
        assertEquals("K30-H1", (at("2026-10-13T08:30") as NowStatus.In).occurrence.event.location?.room)
    }

    @Test
    fun nothingOnWeekendsOrOutsideTheSemester() {
        assertEquals(NowStatus.None, at("2026-10-17T10:00"))
        assertEquals(NowStatus.None, at("2026-10-11T10:00"))
        assertEquals(NowStatus.None, at("2026-12-15T10:00"))
        assertEquals(NowStatus.None, NowStatus.of(Schedule(emptyList()), LocalDateTime.parse("2026-10-12T10:00")))
    }
}
