package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.model.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DayPlanTest {
    private val schedule by lazy { Schedule(Fixture.events) }
    private fun d(s: String) = LocalDate.parse(s)
    private fun today(now: String) = Navigation.today(schedule, LocalDateTime.parse(now))

    @Test
    fun breakWeeksAreFreeFromTheData() {
        assertTrue(schedule.week(d("2026-12-21")).free)
        assertTrue(schedule.week(d("2026-12-28")).free)
        assertFalse(schedule.week(d("2027-01-04")).free)
        assertFalse(schedule.week(d("2026-10-12")).free)
        // Before the semester nothing was cancelled: empty, not "free".
        assertFalse(schedule.week(d("2026-10-05")).free)
    }

    @Test
    fun partlyCancelledDayIsNotFree() {
        val jan14 = schedule.day(d("2027-01-14"))
        assertFalse(jan14.free)
        assertEquals(3, jan14.occurrences.size)
        assertEquals(1, jan14.cancellations.size)
        assertEquals(listOf(7, 9, 11, 13), jan14.items().map { it.start.hour })
        assertEquals(listOf(7, 11, 13), jan14.items(withCancellations = false).map { it.start.hour })
        assertTrue(schedule.day(d("2026-12-24")).free)
        assertFalse(schedule.day(d("2026-12-26")).free) // Saturday: nothing at all
        // KW1: the Wednesday Übung is in even weeks (KW2), the rest of the day is cancelled.
        assertTrue(schedule.day(d("2027-01-06")).free)
    }

    @Test
    fun hourRange() {
        assertEquals(7..19, schedule.hours)
        assertEquals(8..18, Schedule(emptyList()).hours)
    }

    @Test
    fun weekToOpen() {
        assertEquals(d("2026-10-12"), Navigation.weekToOpen(d("2026-10-11"))) // Sunday
        assertEquals(d("2026-10-12"), Navigation.weekToOpen(d("2026-10-10"))) // Saturday
        assertEquals(d("2026-10-12"), Navigation.weekToOpen(d("2026-10-16"))) // Friday
        assertEquals(d("2026-10-12"), Navigation.weekToOpen(d("2026-10-12")))
    }

    @Test
    fun todayBeforeDuringAndAfter() {
        assertEquals(TodayView.BeforeStart(schedule.day(d("2026-10-12"))), today("2026-10-11T10:00"))
        assertEquals(TodayView.Ended(d("2027-01-29")), today("2027-01-29T20:00"))
        assertEquals(TodayView.NoPlan, Navigation.today(Schedule(emptyList<Event>()), LocalDateTime.parse("2026-10-11T10:00")))

        val morning = today("2026-10-13T08:00") as TodayView.Day
        assertEquals(d("2026-10-13") to true, morning.day.date to morning.isToday)

        // Tuesday ends at 11:00: in the evening the list shows Wednesday.
        val evening = today("2026-10-13T18:00") as TodayView.Day
        assertEquals(d("2026-10-14") to false, evening.day.date to evening.isToday)

        // Saturday -> Monday.
        assertEquals(d("2026-10-19"), (today("2026-10-17T09:00") as TodayView.Day).day.date)
    }

    @Test
    fun todayListsCancellationsOnlyNearby() {
        val tue = today("2026-10-27T06:00") as TodayView.Day
        assertTrue(tue.withCancellations)
        assertTrue(tue.items.any { it is Cancellation && it.event.uid == "900055500111" })
    }

    @Test
    fun todaySkipsTheBreak() {
        val v = today("2026-12-19T12:00") as TodayView.Day
        assertEquals(d("2027-01-04"), v.day.date)
        assertEquals(10, v.skippedFree.size)
        assertEquals(d("2026-12-21"), v.skippedFree.first())
        assertFalse(v.withCancellations) // 04.01. is three weeks away
        assertTrue((today("2026-12-31T12:00") as TodayView.Day).withCancellations)
    }

    private fun item(start: String, end: String) = Occurrence(
        Event(uid = start, summary = "", title = "", start = LocalDateTime.parse("2026-10-13T$start"), end = LocalDateTime.parse("2026-10-13T$end")),
        LocalDateTime.parse("2026-10-13T$start"), LocalDateTime.parse("2026-10-13T$end"), Status.Regular,
    )

    private fun lanes(vararg spans: Pair<String, String>) =
        DayLayout.place(spans.map { item(it.first, it.second) }).map { "${it.item.event.uid}:${it.column}/${it.columns}" }

    @Test
    fun layoutSideBySide() {
        assertEquals(listOf("07:00:0/2", "07:00:1/2", "09:00:0/1"), lanes("07:00" to "09:00", "07:00" to "09:00", "09:00" to "11:00"))
        // Chain: A overlaps B, B overlaps C, A and C don't: two lanes, C reuses A's lane.
        assertEquals(listOf("08:00:0/2", "09:00:1/2", "10:00:0/2"), lanes("08:00" to "10:00", "09:00" to "11:00", "10:00" to "12:00"))
        assertEquals(listOf("11:00:0/1", "13:00:0/1"), lanes("11:00" to "13:00", "13:00" to "15:00"))
        assertEquals(listOf("08:00:0/3", "08:30:1/3", "09:00:2/3"), lanes("08:00" to "12:00", "08:30" to "10:00", "09:00" to "10:00"))
    }

    @Test
    fun weekRangeStartsOneWeekBeforeTheFirstEvent() {
        val first = Schedule.monday(schedule.firstDate!!.atStartOfDay())
        val weeks = Navigation.weeks(schedule, first)
        assertEquals(first.minusWeeks(1), weeks.first())
        assertEquals(Schedule.monday(schedule.lastDate!!.atStartOfDay()), weeks.last())
        // the week to open is always inside, even far away
        assertTrue(Navigation.weeks(schedule, d("2030-01-07")).contains(d("2030-01-07")))
    }

    @Test
    fun dayToOpenIsTheDayOfTheTodayView() {
        assertEquals(d("2026-10-12"), Navigation.dayToOpen(schedule, LocalDateTime.parse("2026-10-12T07:00")))
        assertEquals(schedule.firstDate, Navigation.dayToOpen(schedule, LocalDateTime.parse("2020-01-01T07:00")))
        assertEquals(schedule.lastDate, Navigation.dayToOpen(schedule, LocalDateTime.parse("2035-01-01T07:00")))
    }
}
