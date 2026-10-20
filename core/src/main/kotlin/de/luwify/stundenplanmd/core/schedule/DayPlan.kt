package de.luwify.stundenplanmd.core.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

data class DayPlan(val date: LocalDate, val occurrences: List<Occurrence>, val cancellations: List<Cancellation>) {
    /**
     * Every event of the day was cancelled by LSF (holiday, break): shown as one "Vorlesungsfrei" line. Dates I cancelled
     * for myself don't make a day lecture-free.
     */
    val free: Boolean get() = occurrences.isEmpty() && cancellations.any { !it.byMe }

    /** Occurrences and cancellations by time; cancellations only when [withCancellations]. */
    fun items(withCancellations: Boolean = true): List<Timed> =
        (occurrences + if (withCancellations) cancellations else emptyList()).sortedWith(compareBy({ it.start }, { it.end }))

    /** Breaks of at least [FreeGaps.MIN_MINUTES] between two courses that take place (cancelled ones leave a gap). */
    val gaps: List<FreeGap> by lazy { FreeGaps.of(occurrences) }

    /** Every item placed in side-by-side lanes for the week grid (built once). */
    val placed: List<Placed<Timed>> by lazy { DayLayout.place(items()) }
}

/** The days shown for a week: Monday to Friday, and Saturday/Sunday only when something is on them ([Schedule.week]). */
data class WeekPlan(val monday: LocalDate, val days: List<DayPlan>) {
    /** The last day shown (Friday, or Saturday/Sunday). */
    val lastDay: LocalDate get() = days.last().date

    /** Nothing takes place on the days shown and something was cancelled: one "Vorlesungsfrei" banner for the week. */
    val free: Boolean get() = days.all { it.occurrences.isEmpty() } && days.any { d -> d.cancellations.any { !it.byMe } }
}

/** What the Today list shows. */
sealed interface TodayView {
    data object NoPlan : TodayView

    /** Before the first event; [day] is the first day of the semester. */
    data class BeforeStart(val day: DayPlan) : TodayView

    /** After the last event of the plan. */
    data class Ended(val last: LocalDate) : TodayView

    /**
     * The day to show: today while something is still to come, else the next day with events.
     * [skippedFree] are lecture-free days (all cancelled) passed over on the way, starting with today.
     * [cancellations] of the day are included when it is in the current or next week.
     */
    data class Day(val day: DayPlan, val isToday: Boolean, val skippedFree: List<LocalDate>, val withCancellations: Boolean) : TodayView {
        val items: List<Timed> get() = day.items(withCancellations)
    }
}

object Navigation {
    /** Monday of the week to open: this week, or the next one on Saturday and Sunday. */
    fun weekToOpen(today: LocalDate): LocalDate {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return if (today.dayOfWeek >= DayOfWeek.SATURDAY) monday.plusWeeks(1) else monday
    }

    /** Like [weekToOpen], but on the weekend this week stays open while something of it is still to come (today or Sunday). */
    fun weekToOpen(schedule: Schedule, today: LocalDate): LocalDate {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (today.dayOfWeek >= DayOfWeek.SATURDAY && schedule.week(monday).days.any { !it.date.isBefore(today) && it.occurrences.isNotEmpty() }) return monday
        return weekToOpen(today)
    }

    /** The days of the day pager for [weeks] (Mondays): the days each week shows ([Schedule.week]). */
    fun days(schedule: Schedule, weeks: List<LocalDate>): List<LocalDate> = weeks.flatMap { m -> schedule.week(m).days.map { it.date } }

    /** The page of [date] in [days]: that day, or the next one shown (a weekend day without events: the next Monday). */
    fun dayPage(days: List<LocalDate>, date: LocalDate): Int =
        days.indexOfFirst { !it.isBefore(date) }.let { if (it < 0) days.lastIndex else it }.coerceAtLeast(0)

    /**
     * Mondays of the weeks the strips and pagers cover: from one week before the first event to the week of the
     * last one, widened to include [open] (the plan may lie in the future or the past).
     */
    fun weeks(schedule: Schedule, open: LocalDate): List<LocalDate> {
        val first = schedule.firstDate ?: return listOf(open)
        val start = minOf(Schedule.monday(first.atStartOfDay()).minusWeeks(1), open)
        val end = maxOf(Schedule.monday(schedule.lastDate!!.atStartOfDay()), open)
        return generateSequence(start) { it.plusWeeks(1) }.takeWhile { !it.isAfter(end) }.toList()
    }

    /** The day the day view opens on: [today]'s day, the next one with events, the first (before) or last (after) day. */
    fun dayToOpen(schedule: Schedule, now: LocalDateTime): LocalDate = when (val v = today(schedule, now)) {
        TodayView.NoPlan -> now.toLocalDate()
        is TodayView.BeforeStart -> v.day.date
        is TodayView.Ended -> v.last
        is TodayView.Day -> v.day.date
    }

    fun today(schedule: Schedule, now: LocalDateTime): TodayView {
        val first = schedule.firstDate ?: return TodayView.NoPlan
        val last = schedule.lastDate!!
        val today = now.toLocalDate()
        if (today.isBefore(first)) return TodayView.BeforeStart(schedule.day(first))
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val skipped = mutableListOf<LocalDate>()
        var date = today
        while (!date.isAfter(last)) {
            val day = schedule.day(date)
            val stillToCome = date != today || day.occurrences.any { it.end.isAfter(now) }
            if (day.occurrences.isNotEmpty() && stillToCome) {
                val near = date.isBefore(thisMonday.plusWeeks(2))
                return TodayView.Day(day, isToday = date == today, skippedFree = skipped, withCancellations = near)
            }
            if (day.free) skipped += date
            date = date.plusDays(1)
        }
        return TodayView.Ended(last)
    }
}

/** An item placed in a day column: [column] of [columns] side-by-side lanes. */
data class Placed<T : Timed>(val item: T, val column: Int, val columns: Int)

object DayLayout {
    /**
     * Side-by-side layout for overlapping items. Items that overlap (directly or through others) form a
     * group; each gets the first lane free at its start, all items of a group share the group's lane count.
     * Touching items (end == start) don't overlap.
     */
    fun <T : Timed> place(items: List<T>): List<Placed<T>> {
        val sorted = items.sortedWith(compareBy<T>({ it.start }, { it.end }))
        val out = mutableListOf<Placed<T>>()
        var group = mutableListOf<Pair<T, Int>>()
        var laneEnds = mutableListOf<LocalDateTime>()
        var groupEnd: LocalDateTime? = null

        fun flush() {
            group.forEach { (item, lane) -> out += Placed(item, lane, laneEnds.size) }
            group = mutableListOf()
            laneEnds = mutableListOf()
        }
        for (item in sorted) {
            if (groupEnd != null && !item.start.isBefore(groupEnd)) flush()
            var lane = laneEnds.indexOfFirst { !it.isAfter(item.start) }
            if (lane < 0) {
                lane = laneEnds.size
                laneEnds += item.end
            } else {
                laneEnds[lane] = item.end
            }
            group += item to lane
            groupEnd = if (groupEnd == null || group.size == 1) item.end else maxOf(groupEnd, item.end)
        }
        flush()
        return out
    }
}
