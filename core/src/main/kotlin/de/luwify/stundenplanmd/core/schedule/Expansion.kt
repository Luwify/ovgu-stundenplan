package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Recurrence
import de.luwify.stundenplanmd.core.model.WeekRhythm
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
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
        for (start in rhythm(event.start, rule, rhythmOf(event))) {
            if (rule.until != null && start.atZone(PlanZone).toInstant().isAfter(rule.until)) break
            if (rule.count != null && out.size >= rule.count) break
            if (out.size >= MAX_SLOTS) break
            out += Slot(start, start.plus(duration), rule.excludes(start))
        }
        return out
    }

    /**
     * The rhythm of a weekly INTERVAL=2 series (null for any other rule): the one set by hand, else LSF's calendar-week
     * parity of DTSTART ("gKW"/"uKW"), unless UNTIL is a date of the 14-day rhythm and not of the parity one (the two
     * differ only after a week 53). Decided per series, from UNTIL alone.
     */
    fun rhythmOf(event: Event): WeekRhythm? {
        val rule = event.recurrence?.takeIf { it.biweekly } ?: return null
        rule.rhythm?.let { return it }
        val parity = WeekRhythm.parityOf(event.start.toLocalDate())
        val until = rule.until ?: return parity
        return if (!endsAt(event.start, rule, parity, until) && endsAt(event.start, rule, WeekRhythm.EVERY_14_DAYS, until)) {
            WeekRhythm.EVERY_14_DAYS
        } else {
            parity
        }
    }

    /** [events] with my rhythm for some series (appointment UID -> rhythm; only weekly INTERVAL=2 series take one). */
    fun withRhythms(events: List<Event>, rhythms: Map<String, WeekRhythm>): List<Event> = if (rhythms.isEmpty()) events else events.map { e ->
        val r = e.recurrence
        val chosen = rhythms[e.uid]
        if (r == null || chosen == null || !r.biweekly) e else e.copy(recurrence = r.copy(rhythm = chosen))
    }

    /** The last date of [rhythm] up to [until] lies on UNTIL's day (less than a day before it). */
    private fun endsAt(dtStart: LocalDateTime, rule: Recurrence, rhythm: WeekRhythm, until: Instant): Boolean {
        val limit = until.atZone(PlanZone).toLocalDateTime()
        val last = rhythm(dtStart, rule, rhythm).take(MAX_SLOTS).takeWhile { !it.isAfter(limit) }.lastOrNull() ?: return false
        return Duration.between(last, limit) < Duration.ofDays(1)
    }

    /**
     * The same dates (and cancelled dates) as [event], from a rule [rhythmOf] reads the same way without a rhythm set
     * by hand: starts on the first date and ends (UNTIL) on the last one. For shares, which carry no rhythm.
     * Unchanged when there is nothing to do or the result would differ.
     */
    fun portable(event: Event): Event {
        val rule = event.recurrence?.takeIf { it.biweekly && it.rhythm != null } ?: return event
        val slots = slots(event)
        if (slots.isEmpty() || slots.size >= MAX_SLOTS) return event
        val first = slots.first().start
        val out = event.copy(
            start = first,
            end = first.plus(Duration.between(event.start, event.end)),
            recurrence = rule.copy(
                rhythm = null,
                count = null,
                until = slots.last().start.toLocalDate().atTime(23, 59).atZone(PlanZone).toInstant(),
            ),
        )
        return if (slots(out) == slots) out else event
    }

    /** Unbounded, ascending candidate starts from DTSTART on. */
    private fun rhythm(dtStart: LocalDateTime, rule: Recurrence, biweekly: WeekRhythm?): Sequence<LocalDateTime> = when (rule.frequency) {
        Recurrence.Frequency.DAILY -> generateSequence(dtStart) { it.plusDays(rule.interval.toLong()) }
        Recurrence.Frequency.WEEKLY -> {
            // Weeks start on Monday (WKST default); INTERVAL counts weeks from the week of DTSTART, or, for even/odd
            // calendar weeks, every week with that parity.
            val days = rule.byDay.ifEmpty { setOf(dtStart.dayOfWeek) }.sorted()
            val firstMonday = dtStart.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val mondays = if (biweekly == WeekRhythm.EVEN_WEEKS || biweekly == WeekRhythm.ODD_WEEKS) {
                generateSequence(firstMonday) { it.plusWeeks(1) }.filter { WeekRhythm.parityOf(it) == biweekly }
            } else {
                generateSequence(firstMonday) { it.plusWeeks(rule.interval.toLong()) }
            }
            mondays
                .flatMap { monday -> days.asSequence().map { monday.plusDays(it.value - 1L).atTime(dtStart.toLocalTime()) } }
                .filter { !it.isBefore(dtStart) }
        }
    }
}
