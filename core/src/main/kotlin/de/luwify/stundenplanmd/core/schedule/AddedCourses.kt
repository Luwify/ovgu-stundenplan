package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.Event

/**
 * LSF courses added to my plan from a separate .ics (e.g. one course or one exercise group exported on its own), stored
 * next to the imported plan with their LSF UIDs, so together matching and my own changes work as for any course.
 * Duplicates are found by the full UID (course id + appointment id): another group of a course I have (same course
 * id, other appointment id) is added and joins that course's subject. The imported plan always wins: an appointment
 * it contains is never added, and a full re-import drops added duplicates ([dedupe]).
 */
object AddedCourses {
    /**
     * [events] = the new list of added events; counts are appointments (UIDs). [duplicates]: incoming appointments the
     * imported plan already has (shown in the notice).
     */
    data class Added(val events: List<Event>, val added: Int, val updated: Int, val duplicates: List<Event>) {
        val alreadyInPlan: Int get() = duplicates.size
    }

    fun keys(events: List<Event>): Set<String> = events.mapTo(LinkedHashSet()) { it.courseKey }

    fun courseCount(events: List<Event>): Int = keys(events).size

    private fun uids(events: List<Event>): Set<String> = events.mapTo(HashSet()) { it.uid }

    /**
     * Adds the appointments of [incoming] to [added]: one already added (same UID) is replaced by its new version (a
     * re-import updates it), one the imported plan [lsf] has is left out, any other is added.
     */
    fun add(lsf: List<Event>, added: List<Event>, incoming: List<Event>): Added {
        val inPlan = uids(lsf)
        val before = uids(added)
        val (duplicates, take) = incoming.partition { it.uid in inPlan }
        val takeUids = uids(take)
        val events = added.filterNot { it.uid in takeUids } + take
        return Added(events, added = takeUids.count { it !in before }, updated = takeUids.count { it in before }, duplicates = duplicates)
    }

    /** After a full import of [lsf]: the added events whose UID is not in it, and how many were dropped. */
    fun dedupe(lsf: List<Event>, added: List<Event>): Pair<List<Event>, Int> {
        val inPlan = uids(lsf)
        val (dup, keep) = added.partition { it.uid in inPlan }
        return keep to dup.size
    }

    /** What is shown: never an appointment the imported plan has (stored duplicates are dropped at the next import). */
    fun visible(lsf: List<Event>, added: List<Event>): List<Event> {
        if (added.isEmpty()) return added
        val inPlan = uids(lsf)
        return added.filterNot { it.uid in inPlan }
    }

    /** Removes the appointments [uids] (one group, see [Groups]); the course's other groups stay. */
    fun remove(added: List<Event>, uids: Set<String>): List<Event> = added.filterNot { it.uid in uids }

    /**
     * A file meant to replace my plan that has far fewer courses than it (e.g. a single course exported from LSF): the
     * app asks whether to replace the plan or add the courses. Fewer than half, with at least 3 courses now.
     */
    fun looksPartial(current: List<Event>, incoming: List<Event>): Boolean {
        val now = courseCount(current)
        return now >= 3 && courseCount(incoming) * 2 < now
    }
}

/**
 * Groups of a course (e.g. exercise groups: one LSF course id, one regular series per group). A group is a series
 * (appointment UID) with the dates LSF moved out of it ([Status.Changed] keeps the original). Single dates that
 * replace nothing belong to the course's only group, or stand alone when it has several.
 */
object Groups {
    /** The appointment a date belongs to: for a moved date the series it was moved out of. */
    fun baseUid(t: Timed): String = ((t as? Occurrence)?.status as? Status.Changed)?.original?.event?.uid ?: t.event.uid

    /** How many groups (series, see above) [item]'s course has in [schedule]: hiding "only this group" needs two. */
    fun count(schedule: Schedule, item: Timed): Int {
        val course = item.event.courseKey
        val all: List<Timed> = (schedule.occurrences + schedule.cancellations).filter { it.event.courseKey == course }
        return all.filter { it.event.recurrence != null || (it as? Occurrence)?.status is Status.Changed }.mapTo(HashSet(), ::baseUid).size
    }

    /** The appointment UIDs of [item]'s group in [schedule]. */
    fun of(schedule: Schedule, item: Timed): Set<String> {
        val course = item.event.courseKey
        val all: List<Timed> = (schedule.occurrences + schedule.cancellations).filter { it.event.courseKey == course }
        val series = all.filter { it.event.recurrence != null || (it as? Occurrence)?.status is Status.Changed }.map(::baseUid).toSet()
        if (series.size <= 1) return all.mapTo(LinkedHashSet()) { it.event.uid } + item.event.uid
        val base = baseUid(item)
        return all.filter { baseUid(it) == base }.mapTo(LinkedHashSet()) { it.event.uid } + item.event.uid
    }
}
