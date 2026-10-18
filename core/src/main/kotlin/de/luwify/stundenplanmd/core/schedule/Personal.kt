@file:UseSerializers(LocalDateSerializer::class)

package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.LocalDateSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PersonalChange
import java.time.LocalDate
import java.time.LocalTime

/**
 * An occurrence as LSF knows it: the appointment UID and its original date (before a move). Also a date a friend hid
 * before sharing ([de.luwify.stundenplanmd.core.model.Plan.sharedHidden]: UID and date of the slot).
 */
@Serializable
data class OccurrenceRef(val uid: String, val date: LocalDate)

/** The values I can change for one occurrence. */
data class OccurrenceValues(val note: String, val st: Boolean, val room: String?, val start: LocalTime, val end: LocalTime) {
    fun with(c: PersonalChange?): OccurrenceValues = if (c == null) this else OccurrenceValues(
        note = c.note ?: note,
        st = c.st ?: st,
        room = c.room ?: room,
        start = c.start ?: start,
        end = c.end ?: end,
    )
}

/**
 * My changes resolved for one occurrence (or LSF cancellation): [series] and [single] cover it, [original] are the
 * values LSF gives (no note, c.t.). [lsfChanged]: LSF moved or cancelled this occurrence; then my room, time and
 * cancellation are [overruled]: shown next to the official state, never applied over it.
 */
data class Personal(
    val ref: OccurrenceRef,
    val series: PersonalChange?,
    val single: PersonalChange?,
    val original: OccurrenceValues,
    val lsfChanged: Boolean,
) {
    /** What the series makes of the original: the starting point of a single edit. */
    val inherited: OccurrenceValues = original.with(series)
    val current: OccurrenceValues = inherited.with(single)

    val cancelled: Boolean get() = single?.cancelled == true || series?.cancelled == true
    val note: String? get() = current.note.takeIf { it.isNotBlank() }
    val noteFromSeries: Boolean get() = single?.note == null && series?.note != null
    val st: Boolean get() = current.st
    val roomChanged: Boolean get() = current.room != original.room
    val timeChanged: Boolean get() = current.start != original.start || current.end != original.end

    /** Room, time or cancellation changed by me: marked as an own change. */
    val changesPlan: Boolean get() = cancelled || roomChanged || timeChanged

    /** LSF changed this occurrence too: my room/time/cancellation are not applied, both are shown. */
    val overruled: Boolean get() = lsfChanged && changesPlan

    val applied: Boolean get() = changesPlan && !lsfChanged

    companion object {
        /** The series and single change covering [ref]; null when nothing does. */
        fun of(changes: List<PersonalChange>, ref: OccurrenceRef, original: OccurrenceValues, lsfChanged: Boolean): Personal? {
            val series = changes.lastOrNull { it.series && it.covers(ref.uid, ref.date) }
            val single = changes.lastOrNull { !it.series && it.covers(ref.uid, ref.date) }
            if (series == null && single == null) return null
            return Personal(ref, series, single, original, lsfChanged)
        }

        fun values(event: Event, start: java.time.LocalDateTime, end: java.time.LocalDateTime) =
            OccurrenceValues("", event.st, event.location?.room, start.toLocalTime(), end.toLocalTime())
    }
}

/** Editing and checking [PersonalChange]s (pure functions; the app stores the result). */
object PersonalChanges {
    /** The change of [ref] alone with [desired] values; fields equal to the inherited ones (series/LSF) are not stored. */
    fun setSingle(changes: List<PersonalChange>, p: Personal, title: String, desired: OccurrenceValues): List<PersonalChange> {
        val old = p.single
        val base = old ?: PersonalChange(p.ref.uid, p.ref.date, title = title)
        val next = base.copy(
            note = desired.note.takeIf { it != p.inherited.note },
            st = desired.st.takeIf { it != p.inherited.st },
            room = desired.room?.takeIf { it != p.inherited.room },
            start = desired.start.takeIf { it != p.inherited.start || desired.end != p.inherited.end },
            end = desired.end.takeIf { it != p.inherited.end || desired.start != p.inherited.start },
        )
        return replace(changes, old, next)
    }

    /** Cancels [ref] for me (or not); other fields stay. */
    fun setCancelled(changes: List<PersonalChange>, ref: OccurrenceRef, title: String, cancelled: Boolean): List<PersonalChange> {
        val old = changes.lastOrNull { !it.series && it.covers(ref.uid, ref.date) }
        val next = (old ?: PersonalChange(ref.uid, ref.date, title = title)).copy(cancelled = cancelled)
        return replace(changes, old, next)
    }

    /**
     * [desired] for every occurrence of the series [Personal.ref] from its date on: an earlier series change ends the
     * day before, later ones are replaced. Values equal to LSF's ([Personal.original]) are not stored. A single change
     * of that first date keeps only its cancellation (otherwise it would hide the new values there).
     */
    fun setSeries(changes: List<PersonalChange>, p: Personal, title: String, desired: OccurrenceValues): List<PersonalChange> {
        val (uid, from) = p.ref
        val out = changes.mapNotNull { c ->
            when {
                !c.series && c.covers(uid, from) -> PersonalChange(c.uid, c.date, title = c.title, cancelled = c.cancelled).takeUnless { it.isEmpty }
                !c.series || c.uid != uid -> c
                !c.date.isBefore(from) -> null
                c.until == null || !c.until.isBefore(from) -> c.copy(until = from.minusDays(1))
                else -> c
            }
        }
        val o = p.original
        val timeChanged = desired.start != o.start || desired.end != o.end
        val next = PersonalChange(
            uid, from, series = true, title = title,
            note = desired.note.takeIf { it.isNotBlank() },
            st = desired.st.takeIf { it != o.st },
            room = desired.room?.takeIf { it != o.room },
            start = desired.start.takeIf { timeChanged },
            end = desired.end.takeIf { timeChanged },
        )
        return if (next.isEmpty) out else out + next
    }

    fun remove(changes: List<PersonalChange>, change: PersonalChange): List<PersonalChange> = changes - change

    /** Changes no occurrence of [events] matches any more (re-import removed them): listed for cleanup, never dropped. */
    fun orphans(events: List<Event>, changes: List<PersonalChange>): List<PersonalChange> {
        if (changes.isEmpty()) return emptyList()
        val dates: Map<String, List<LocalDate>> = events.groupBy { it.uid }
            .mapValues { (_, es) -> es.flatMap { e -> Expansion.slots(e).map { it.start.toLocalDate() } } }
        return changes.filter { c -> dates[c.uid].orEmpty().none { c.covers(c.uid, it) } }
    }

    private fun replace(changes: List<PersonalChange>, old: PersonalChange?, next: PersonalChange): List<PersonalChange> {
        val rest = if (old == null) changes else changes - old
        return if (next.isEmpty) rest else rest + next
    }
}
