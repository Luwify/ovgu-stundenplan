package de.luwify.stundenplanmd.core.schedule

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Fill of the week view's day progress bar (hour column). */
object DayProgress {
    /**
     * How far today has got through the day range [firstHour]..[lastHour] (0..1), in the week of [monday] only: 0 in
     * other weeks and before the range, 1 after it (and on the weekend of this week).
     */
    fun of(monday: LocalDate, now: LocalDateTime, firstHour: Int, lastHour: Int): Float {
        val today = now.toLocalDate()
        if (Schedule.monday(today.atStartOfDay()) != monday || lastHour <= firstHour) return 0f
        if (today.dayOfWeek.value > 5) return 1f
        val minutes = Duration.between(today.atTime(firstHour, 0), now).toMinutes()
        return (minutes / ((lastHour - firstHour) * 60f)).coerceIn(0f, 1f)
    }
}
