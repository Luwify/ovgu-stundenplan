package de.luwify.stundenplanmd.core.schedule

import java.time.Duration
import java.time.LocalDateTime

/** A stretch between two courses of a day with nothing on it ([start] = end of the one before, [end] = start of the next). */
data class FreeGap(val start: LocalDateTime, val end: LocalDateTime) {
    val minutes: Int get() = Duration.between(start, end).toMinutes().toInt()
}

object FreeGaps {
    /** Shorter breaks are the normal walk between two blocks and get no marker. */
    const val MIN_MINUTES = 45

    /**
     * The gaps of at least [minMinutes] between consecutive [occurrences] of one day, never before the first or after
     * the last. Overlapping courses are one stretch (the gap starts when the last of them ends). Cancelled and hidden
     * dates are not occurrences, so they leave a gap.
     */
    fun of(occurrences: List<Occurrence>, minMinutes: Int = MIN_MINUTES): List<FreeGap> {
        val sorted = occurrences.sortedWith(compareBy({ it.start }, { it.end }))
        val gaps = mutableListOf<FreeGap>()
        var busyUntil = sorted.firstOrNull()?.end ?: return gaps
        for (next in sorted.drop(1)) {
            val gap = FreeGap(busyUntil, next.start)
            if (gap.minutes >= minMinutes) gaps += gap
            if (next.end.isAfter(busyUntil)) busyUntil = next.end
        }
        return gaps
    }

    /**
     * Who is free together with me in each of my [gaps]: everyone in [others] (person id -> their gaps of the same day)
     * with a gap that overlaps mine by at least [minMinutes]. Gaps lie between two courses, so both have courses before
     * and after. Only gaps shared with someone are in the result.
     */
    fun together(gaps: List<FreeGap>, others: List<Pair<String, List<FreeGap>>>, minMinutes: Int = MIN_MINUTES): Map<FreeGap, List<String>> =
        gaps.associateWith { mine -> others.filter { (_, theirs) -> theirs.any { overlap(mine, it) >= minMinutes } }.map { it.first } }
            .filterValues { it.isNotEmpty() }

    /** Minutes two gaps share (0 if none). */
    fun overlap(a: FreeGap, b: FreeGap): Int {
        val start = maxOf(a.start, b.start)
        val end = minOf(a.end, b.end)
        return if (end.isAfter(start)) Duration.between(start, end).toMinutes().toInt() else 0
    }
}

/** The hour height of the week grid. */
object HourScale {
    /**
     * Height of one hour (any unit) so that [hours] hours fit into [available] minus a [margin] below, but not below
     * [min]: with [fits] false the grid is taller than [available] and scrolls.
     */
    fun height(available: Float, hours: Int, min: Float, margin: Float = 0f): Float =
        maxOf(min, (available - margin) / hours.coerceAtLeast(1))

    fun fits(available: Float, hours: Int, min: Float, margin: Float = 0f): Boolean =
        (available - margin) / hours.coerceAtLeast(1) >= min
}
