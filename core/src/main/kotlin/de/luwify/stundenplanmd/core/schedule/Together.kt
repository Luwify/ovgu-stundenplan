package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.CourseKeys
import java.time.LocalDateTime

/** One occurrence across plans: the event's UID and its start. */
data class OccurrenceKey(val uid: String, val start: LocalDateTime) {
    companion object {
        fun of(o: Occurrence) = OccurrenceKey(o.event.uid, o.start)
    }
}

/**
 * Which occurrences two people attend together. Same date, start and end, and either
 * - the same LSF course id (UID prefix) and: the same appointment (full UID), or the UID of the other's original slot
 *   (one plan is older and does not know about a moved appointment yet), or the same room, or a room missing on one
 *   side. Different rooms otherwise mean parallel groups of one course: not together.
 * - or (cross-listed courses with other ids) exactly the same title, category and location.
 *
 * Checked against two real exports (2026-10-12): the same appointment has the identical full UID in both.
 */
object Together {
    fun matches(a: Occurrence, b: Occurrence): Boolean {
        if (a.start != b.start || a.end != b.end) return false
        val ea = a.event
        val eb = b.event
        val course = CourseKeys.courseId(ea.uid)
        if (course != null && course == CourseKeys.courseId(eb.uid)) {
            if (ea.uid == eb.uid) return true
            if (originalUid(a) == eb.uid || originalUid(b) == ea.uid) return true
            val ra = a.room?.lowercase()
            val rb = b.room?.lowercase()
            return ra == null || rb == null || ra == rb
        }
        return ea.title == eb.title && ea.category == eb.category && ea.location != null && ea.location.raw == eb.location?.raw
    }

    private fun originalUid(o: Occurrence) = (o.status as? Status.Changed)?.original?.event?.uid

    /** For every occurrence of [target] attended by someone in [others] (person id -> schedule): their ids, in order. */
    fun of(target: Schedule, others: List<Pair<String, Schedule>>): Map<OccurrenceKey, List<String>> {
        val indexed = others.map { (id, s) -> id to s.occurrences.groupBy { it.start to it.end } }
        val out = LinkedHashMap<OccurrenceKey, List<String>>()
        for (o in target.occurrences) {
            val ids = indexed.filter { (_, byTime) -> byTime[o.start to o.end].orEmpty().any { matches(o, it) } }.map { it.first }
            if (ids.isNotEmpty()) out[OccurrenceKey.of(o)] = ids
        }
        return out
    }
}
