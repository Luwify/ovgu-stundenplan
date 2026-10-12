package de.luwify.stundenplanmd.core.schedule

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** Where someone is right now, for the friends list: "K14-102 bis 13:00", "frei, ab 13:00 K14-102", "frei". */
sealed interface NowStatus {
    /** In [occurrence] until [until]. */
    data class In(val occurrence: Occurrence, val until: LocalTime) : NowStatus

    /** Between events; [next] is the next one today. */
    data class FreeUntil(val next: Occurrence) : NowStatus

    /** No more events today (or none at all on a weekday of the semester). */
    data object FreeToday : NowStatus

    /** Weekend or outside the semester: nothing is shown. */
    data object None : NowStatus

    companion object {
        fun of(schedule: Schedule, now: LocalDateTime): NowStatus {
            val first = schedule.firstDate ?: return None
            val today = now.toLocalDate()
            if (today.isBefore(first) || today.isAfter(schedule.lastDate!!)) return None
            if (today.dayOfWeek >= DayOfWeek.SATURDAY) return None
            val day = schedule.on(today)
            // Overlapping events: the one that started last is where they most likely are.
            day.filter { !it.start.isAfter(now) && it.end.isAfter(now) }.maxByOrNull { it.start }?.let { return In(it, it.end.toLocalTime()) }
            return day.filter { it.start.isAfter(now) }.minByOrNull { it.start }?.let { FreeUntil(it) } ?: FreeToday
        }
    }
}
