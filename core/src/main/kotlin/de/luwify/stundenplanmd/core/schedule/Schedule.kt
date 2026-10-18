package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PersonalChange
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

/** Something with a time span on one day: an [Occurrence] or a [Cancellation]. */
sealed interface Timed {
    val event: Event
    val start: LocalDateTime
    val end: LocalDateTime
    val date: LocalDate get() = start.toLocalDate()

    /** My changes to it (note, s.t., room, time, cancellation); null when there are none. */
    val personal: Personal?

    /** The room shown: mine when I changed it (and LSF did not change this occurrence), else LSF's. */
    val room: String? get() = personal?.takeIf { it.applied && it.roomChanged }?.current?.room ?: event.location?.room

    /** s.t.: by my change, else as the event says (a manual course can be s.t.). */
    val st: Boolean get() = personal?.st ?: event.st

    /** The appointment and original date my changes are keyed by. */
    val ref: OccurrenceRef
}

data class Occurrence(
    override val event: Event,
    override val start: LocalDateTime,
    override val end: LocalDateTime,
    val status: Status,
    override val personal: Personal? = null,
) : Timed {
    override val ref: OccurrenceRef
        get() = (status as? Status.Changed)?.original?.let { OccurrenceRef(it.event.uid, it.date) } ?: OccurrenceRef(event.uid, date)
}

/**
 * A date that does not take place: a date of a regular series removed by EXDATE (holiday, moved appointment, ...),
 * or, [byMe], one I cancelled for myself.
 */
data class Cancellation(
    override val event: Event,
    override val start: LocalDateTime,
    override val end: LocalDateTime,
    override val personal: Personal? = null,
    val byMe: Boolean = false,
) : Timed {
    override val ref: OccurrenceRef get() = OccurrenceRef(event.uid, date)
}

/**
 * All occurrences of a plan with change detection.
 *
 * Per course (UID prefix): the long series are the regular slots. A single event, or a short series
 * (at most [SHORT_SERIES] dates and shorter than the course's longest series), is irregular. An
 * irregular occurrence in a week where a regular slot of the same course was cancelled is a CHANGE of that
 * slot (closest day, then closest time); otherwise it is an EXTRA. Courses without any series (block
 * courses of single dates) are regular throughout. What [hiding] hides is neither an occurrence nor a cancellation.
 */
class Schedule(val events: List<Event>, val changes: List<PersonalChange> = emptyList(), val hiding: Hiding? = null) {
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
        if (changes.isNotEmpty()) personalize(occ, unmatched)
        // Hidden after change detection: a hidden series still pairs with its moved dates.
        if (hiding != null && !hiding.isEmpty) {
            occ.removeAll(hiding::hides)
            unmatched.removeAll(hiding::hides)
        }
        occurrences = occ.sortedWith(compareBy<Occurrence>({ it.start }, { it.end }, { it.event.title }))
        cancellations = unmatched.sortedBy { it.start }
    }

    /**
     * Applies my [changes] on top of the LSF state: a cancellation for me moves the occurrence to the cancellations
     * ([Cancellation.byMe]), room and time replace LSF's. Where LSF itself moved or cancelled the date, nothing of mine
     * is applied over it (the official change stays visible); notes and s.t. always apply.
     */
    private fun personalize(occ: MutableList<Occurrence>, cancelled: MutableList<Cancellation>) {
        val byUid = changes.groupBy { it.uid }
        fun personal(t: Timed, lsfChanged: Boolean): Personal? {
            val ref = t.ref
            val list = byUid[ref.uid] ?: return null
            return Personal.of(list, ref, Personal.values(t.event, t.start, t.end), lsfChanged)
        }
        val out = occ.map { o ->
            val p = personal(o, lsfChanged = o.status is Status.Changed) ?: return@map o
            when {
                p.lsfChanged -> o.copy(personal = p)
                p.cancelled -> Cancellation(o.event, o.start, o.end, p, byMe = true)
                else -> o.copy(start = o.date.atTime(p.current.start), end = o.date.atTime(p.current.end), personal = p)
            }
        }
        occ.clear()
        out.forEach { if (it is Occurrence) occ += it else cancelled += it as Cancellation }
        cancelled.replaceAll { c -> if (c.byMe) c else personal(c, lsfChanged = true)?.let { c.copy(personal = it) } ?: c }
    }

    private val byDate: Map<LocalDate, List<Occurrence>> = occurrences.groupBy { it.date }
    private val cancelledByDate: Map<LocalDate, List<Cancellation>> = cancellations.groupBy { it.date }

    fun on(date: LocalDate): List<Occurrence> = byDate[date].orEmpty()

    // Days and weeks are built once (the schedule never changes): the views ask for them on every page they compose.
    private val days = java.util.concurrent.ConcurrentHashMap<LocalDate, DayPlan>()
    private val weeks = java.util.concurrent.ConcurrentHashMap<LocalDate, WeekPlan>()

    fun day(date: LocalDate): DayPlan = days.getOrPut(date) { DayPlan(date, on(date), cancelledByDate[date].orEmpty()) }

    /** Monday to Friday of the week starting at [monday]. */
    fun week(monday: LocalDate): WeekPlan = weeks.getOrPut(monday) { WeekPlan(monday, (0L until 5L).map { day(monday.plusDays(it)) }) }


    /** Whole hours covering every occurrence and cancellation (for the week grid); 8–18 for an empty plan. */
    val hours: IntRange by lazy {
        val all: List<Timed> = occurrences + cancellations
        if (all.isEmpty()) return@lazy 8..18
        val first = all.minOf { it.start.hour }
        val last = all.maxOf { if (it.end.toLocalTime().let { t -> t.minute == 0 && t.second == 0 }) it.end.hour else it.end.hour + 1 }
        first..maxOf(last, first + 1)
    }

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
