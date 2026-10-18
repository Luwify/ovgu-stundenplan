package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Subjects

/**
 * What a [Schedule] leaves out: the [items] I hid in this plan (resolved with [subjectOf], course key -> subject key as
 * shown) and, in a friend's plan, the dates they hid before sharing ([shared]: appointment UID + date of the slot).
 * Hidden dates are no occurrence and no cancellation: not shown, not matched for "together", not in the now-line.
 */
class Hiding(
    val items: List<Hidden>,
    private val subjectOf: Map<String, String> = emptyMap(),
    private val shared: Set<OccurrenceRef> = emptySet(),
) {
    val isEmpty: Boolean get() = items.isEmpty() && shared.isEmpty()

    private val subjects = items.filter { it.kind == Hidden.Kind.SUBJECT }
    private val courses = items.filter { it.kind == Hidden.Kind.COURSE }.flatMapTo(HashSet()) { it.courses }
    private val groups = items.filter { it.kind == Hidden.Kind.GROUP }.mapTo(HashSet()) { it.courses.firstOrNull() to it.uid }
    private val dates = items.filter { it.kind == Hidden.Kind.DATE && it.uid != null && it.date != null }.mapTo(HashSet()) { OccurrenceRef(it.uid!!, it.date!!) }

    fun hides(t: Timed): Boolean {
        val course = t.event.courseKey
        if (course in courses) return true
        if (subjects.any { course in it.courses || it.subject != null && subjectOf[course] == it.subject }) return true
        if (groups.isNotEmpty() && (course to Groups.baseUid(t)) in groups) return true
        if (t.ref in dates) return true
        return shared.isNotEmpty() && OccurrenceRef(t.event.uid, t.date) in shared
    }

    companion object {
        /** [items] for a plan of [events] shown with [settings] (the subject keys come from them). */
        fun of(items: List<Hidden>, events: List<Event>, settings: Map<String, CourseSetting>, shared: Collection<OccurrenceRef> = emptyList()): Hiding? {
            if (items.isEmpty() && shared.isEmpty()) return null
            val subjectOf = if (items.none { it.kind == Hidden.Kind.SUBJECT }) emptyMap() else {
                Subjects.courses(events).associate { it.key to Subjects.subjectKey(it, settings) }
            }
            return Hiding(items, subjectOf, shared.toSet())
        }

        /**
         * My plan as shared: events whose every date is hidden are left out, the hidden dates of the others are listed
         * (appointment UID + date of the slot, which the receiver expands the same way), my changes only for events
         * that go along.
         */
        fun forSharing(events: List<Event>, changes: List<PersonalChange>, hiding: Hiding?): Shared {
            if (hiding == null || hiding.isEmpty) return Shared(events, changes, emptyList())
            val all = Schedule(events, changes)
            val timed: List<Timed> = all.occurrences + all.cancellations
            val hidden = timed.filter(hiding::hides)
            if (hidden.isEmpty()) return Shared(events, changes, emptyList())
            val byUid = timed.groupBy { it.event.uid }
            val kept = events.filter { e -> byUid[e.uid].orEmpty().let { it.isEmpty() || !it.all(hiding::hides) } }
            val keptUids = kept.mapTo(HashSet()) { it.uid }
            val refs = hidden.filter { it.event.uid in keptUids }.map { OccurrenceRef(it.event.uid, it.date) }.distinct()
                .sortedWith(compareBy({ it.uid }, { it.date }))
            return Shared(kept, changes.filter { it.uid in keptUids }, refs)
        }
    }

    data class Shared(val events: List<Event>, val changes: List<PersonalChange>, val hidden: List<OccurrenceRef>)
}
