package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.Event

/**
 * LSF courses added to my plan from a separate .ics (e.g. one course exported on its own), stored next to the imported
 * plan with their LSF UIDs, so together matching and my own changes work as for any course. A course is its course
 * key (LSF course id = UID prefix; title for other UIDs). The imported plan always wins: a course it contains is never
 * added, and a full re-import drops added duplicates ([dedupe]).
 */
object AddedCourses {
    /** [events] = the new list of added events; counts are courses. */
    data class Added(val events: List<Event>, val added: Int, val updated: Int, val alreadyInPlan: Int)

    fun keys(events: List<Event>): Set<String> = events.mapTo(LinkedHashSet()) { it.courseKey }

    fun courseCount(events: List<Event>): Int = keys(events).size

    /**
     * Adds the courses of [incoming] to [added]: a course already added is replaced by its new events (a re-import of
     * that course updates it), one the imported plan [lsf] has is left out.
     */
    fun add(lsf: List<Event>, added: List<Event>, incoming: List<Event>): Added {
        val inPlan = keys(lsf)
        val before = keys(added)
        val courses = keys(incoming)
        val take = courses - inPlan
        val events = added.filterNot { it.courseKey in take } + incoming.filter { it.courseKey in take }
        return Added(events, added = take.count { it !in before }, updated = take.count { it in before }, alreadyInPlan = courses.size - take.size)
    }

    /** After a full import of [lsf]: the added events whose course is not in it, and how many courses were dropped. */
    fun dedupe(lsf: List<Event>, added: List<Event>): Pair<List<Event>, Int> {
        val inPlan = keys(lsf)
        val (dup, keep) = added.partition { it.courseKey in inPlan }
        return keep to keys(dup).size
    }

    /** What is shown: never a course the imported plan has (stored duplicates are dropped at the next import anyway). */
    fun visible(lsf: List<Event>, added: List<Event>): List<Event> {
        if (added.isEmpty()) return added
        val inPlan = keys(lsf)
        return added.filterNot { it.courseKey in inPlan }
    }

    fun remove(added: List<Event>, courseKey: String): List<Event> = added.filterNot { it.courseKey == courseKey }

    /**
     * A file meant to replace my plan that has far fewer courses than it (e.g. a single course exported from LSF): the
     * app asks whether to replace the plan or add the courses. Fewer than half, with at least 3 courses now.
     */
    fun looksPartial(current: List<Event>, incoming: List<Event>): Boolean {
        val now = courseCount(current)
        return now >= 3 && courseCount(incoming) * 2 < now
    }
}
