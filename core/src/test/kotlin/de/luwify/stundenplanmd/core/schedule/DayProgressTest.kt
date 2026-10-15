package de.luwify.stundenplanmd.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DayProgressTest {
    private val monday = LocalDate.parse("2026-10-12")

    private fun at(t: String) = DayProgress.of(monday, LocalDateTime.parse(t), 7, 19)

    @Test
    fun fillsThroughTheDayRangeOfThisWeekOnly() {
        assertEquals(0f, at("2026-10-14T06:30"), 0f)
        assertEquals(0.5f, at("2026-10-14T13:00"), 0.001f)
        assertEquals(1f, at("2026-10-16T19:30"), 0f)
        assertEquals(1f, at("2026-10-17T10:00"), 0f) // Saturday of this week
        assertEquals(0f, at("2026-10-20T13:00"), 0f) // next week: this page stays empty
        assertEquals(0f, DayProgress.of(monday.plusWeeks(1), LocalDateTime.parse("2026-10-14T13:00"), 7, 19), 0f)
    }

    @Test
    fun startedAtTheFirstHourOfThisWeekOnly() {
        assertEquals(false, DayProgress.started(monday, LocalDateTime.parse("2026-10-14T06:59"), 7))
        assertEquals(true, DayProgress.started(monday, LocalDateTime.parse("2026-10-14T07:00"), 7))
        assertEquals(true, DayProgress.started(monday, LocalDateTime.parse("2026-10-14T23:00"), 7))
        assertEquals(true, DayProgress.started(monday, LocalDateTime.parse("2026-10-17T05:00"), 7)) // Saturday
        assertEquals(false, DayProgress.started(monday.plusWeeks(1), LocalDateTime.parse("2026-10-14T13:00"), 7))
    }
}
