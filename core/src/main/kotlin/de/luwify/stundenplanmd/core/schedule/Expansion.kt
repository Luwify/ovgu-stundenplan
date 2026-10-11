package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Recurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** One date of an event's rhythm; [cancelled] when an EXDATE removes it. */
data class Slot(val start: LocalDateTime, val end: LocalDateTime, val cancelled: Boolean)

/** Expands RRULE/EXDATE/UNTIL/COUNT into dates, in plan wall-clock time (DST keeps the clock time). */
object Expansion {
    /** Safety cap for rules without UNTIL/COUNT. */
    const val MAX_SLOTS = 1000

    fun occurrences(event: Event): List<Slot> = slots(event).filterNot { it.cancelled }

    /**
     * Every rhythm date, including the ones removed by EXDATE (needed to recognize changes). EXDATEs that
     * are not on the rhythm (LSF has some) produce nothing. COUNT counts cancelled dates too (RFC 5545).
     */
    fun slots(event: Event): List<Slot> {
        val rule = event.recurrence ?: return listOf(Slot(event.start, event.end, cancelled = false))
        val duration = Duration.between(event.start, event.end)
        val out = mutableListOf<Slot>()
        for (start in rhythm(event.start, rule)) {
            if (rule.until != null && start.atZone(PlanZone).toInstant().isAfter(rule.until)) break
            if (rule.count != null && out.size >= rule.count) break
            if (out.size >= MAX_SLOTS) break
            out += Slot(start, start.plus(duration), rule.excludes(start))
        }
        return out
    }

    /** Unbounded, ascending candidate starts from DTSTART on. */
    private fun rhythm(dtStart: LocalDateTime, rule: Recurrence): Sequence<LocalDateTime> = when (rule.frequency) {
        Recurrence.Frequency.DAILY -> generateSequence(dtStart) { it.plusDays(rule.interval.toLong()) }
        Recurrence.Frequency.WEEKLY -> {
            // Weeks start on Monday (WKST default); INTERVAL counts weeks from the week of DTSTART.
            val days = rule.byDay.ifEmpty { setOf(dtStart.dayOfWeek) }.sorted()
            val firstMonday = dtStart.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            generateSequence(0L) { it + rule.interval }
                .flatMap { week ->
                    val monday = firstMonday.plusWeeks(week)
                    days.asSequence().map { monday.plusDays(it.value - 1L).atTime(dtStart.toLocalTime()) }
                }
                .filter { !it.isBefore(dtStart) }
        }
    }
}
