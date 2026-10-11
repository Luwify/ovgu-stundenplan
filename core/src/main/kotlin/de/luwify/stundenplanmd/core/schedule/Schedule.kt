package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.Event
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** What differs between a changed appointment and the regular one it replaces. */
enum class Change { DAY, TIME, ROOM, LECTURER }

sealed interface Status {
    data object Regular : Status

    /** Replaces [original], a date of the course's regular series that EXDATE removed in the same week. */
    data class Changed(val original: Cancellation, val changes: Set<Change>) : Status

    /** An appointment of the course that replaces nothing, e.g. "Zwischentest Wiederholung". */
    data object Extra : Status
}

data class Occurrence(val event: Event, val start: LocalDateTime, val end: LocalDateTime, val status: Status) {
    val date: LocalDate get() = start.toLocalDate()
}

/** A date of a regular series removed by EXDATE (holiday, moved appointment, ...). */
data class Cancellation(val event: Event, val start: LocalDateTime, val end: LocalDateTime)

/**
 * All occurrences of a plan with change detection.
 *
 * Per course (UID prefix): the long series are the regular slots. A single event, or a short series
 * (at most [SHORT_SERIES] dates and shorter than the course's longest series), is irregular. An
 * irregular occurrence in a week where a regular slot of the same course was cancelled is a CHANGE of that
 * slot (closest day, then closest time); otherwise it is an EXTRA. Courses without any series (block
 * courses of single dates) are regular throughout.
 */
class Schedule(val events: List<Event>) {
    val occurrences: List<Occurrence>

    /** Cancelled regular dates that no change replaces. */
    val cancellations: List<Cancellation>

    init {
        val occ = mutableListOf<Occurrence>()
        val unmatched = mutableListOf<Cancellation>()
        for ((_, course) in events.groupBy { it.courseKey }) {
            val slots = course.associateWith { Expansion.slots(it) }
            val longest = course.filter { it.recurrence != null }.maxOfOrNull { slots.getValue(it).size }
            fun regular(e: Event) = longest == null ||
                e.recurrence != null && !(slots.getValue(e).size <= SHORT_SERIES && slots.getValue(e).size < longest)

            val pool = mutableListOf<Cancellation>()
            val irregular = mutableListOf<Pair<Event, Slot>>()
            for (e in course) {
                for (slot in slots.getValue(e)) {
                    when {
                        regular(e) && slot.cancelled -> pool += Cancellation(e, slot.start, slot.end)
                        regular(e) -> occ += Occurrence(e, slot.start, slot.end, Status.Regular)
                        !slot.cancelled -> irregular += e to slot
                    }
                }
            }
            for ((e, slot) in irregular.sortedBy { it.second.start }) {
                val week = monday(slot.start)
                val original = pool.filter { monday(it.start) == week }.minWithOrNull(
                    compareBy<Cancellation> { Math.abs(it.start.toLocalDate().toEpochDay() - slot.start.toLocalDate().toEpochDay()) }
                        .thenBy { Math.abs(Duration.between(it.start.toLocalTime(), slot.start.toLocalTime()).toMinutes()) },
                )
                val status = if (original == null) {
                    Status.Extra
                } else {
                    pool -= original
                    Status.Changed(original, changes(original, e, slot))
                }
                occ += Occurrence(e, slot.start, slot.end, status)
            }
            unmatched += pool
        }
        occurrences = occ.sortedWith(compareBy<Occurrence>({ it.start }, { it.end }, { it.event.title }))
        cancellations = unmatched.sortedBy { it.start }
    }

    private val byDate: Map<LocalDate, List<Occurrence>> = occurrences.groupBy { it.date }

    fun on(date: LocalDate): List<Occurrence> = byDate[date].orEmpty()

    /** Occurrences with [from] <= date < [until]. */
    fun between(from: LocalDate, until: LocalDate): List<Occurrence> =
        occurrences.filter { !it.date.isBefore(from) && it.date.isBefore(until) }

    val firstDate: LocalDate? get() = occurrences.firstOrNull()?.date
    val lastDate: LocalDate? get() = occurrences.maxOfOrNull { it.date }

    companion object {
        const val SHORT_SERIES = 4

        fun monday(t: LocalDateTime): LocalDate = t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        private fun changes(original: Cancellation, e: Event, slot: Slot): Set<Change> = buildSet {
            if (original.start.toLocalDate() != slot.start.toLocalDate()) add(Change.DAY)
            if (original.start.toLocalTime() != slot.start.toLocalTime() || original.end.toLocalTime() != slot.end.toLocalTime()) {
                add(Change.TIME)
            }
            if (roomOf(original.event) != roomOf(e)) add(Change.ROOM)
            if (original.event.lecturers.toSet() != e.lecturers.toSet()) add(Change.LECTURER)
        }

        private fun roomOf(e: Event) = e.location?.let { (it.building.orEmpty() + "|" + it.room).lowercase() }
    }
}
