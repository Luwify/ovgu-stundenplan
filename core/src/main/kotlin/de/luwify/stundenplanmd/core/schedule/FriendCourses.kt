package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.ManualType
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.model.WeekRhythm

/**
 * "Zu meinem Plan": one group of a course in a friend's plan copied into mine. An LSF group goes into the added courses
 * ([AddedCourses]) with its UIDs, so a later import of my own .ics that has it replaces the copy; a manual course of the
 * friend becomes a manual course of mine with the same id. Only the friend's events are copied: their own changes, the
 * dates they or I hid and my rhythms for their plan stay behind.
 */
object FriendCourses {
    enum class State {
        /** Not in my plan: can be added. */
        ADDABLE,

        /** In my imported plan, or a manual course I have: nothing to do. */
        IN_PLAN,

        /** Among my added courses: can be removed again. */
        ADDED,
    }

    /**
     * The friend's events of [item]'s group ([Groups.of]), as stored in their plan ([friendEvents]): without their
     * changes or hidden dates, so every date of the series comes along. A manual course is its one event.
     */
    fun group(friendEvents: List<Event>, item: Timed): List<Event> {
        if (item.event.isManual) return friendEvents.filter { it.uid == item.event.uid }.ifEmpty { listOf(item.event) }
        val uids = Groups.of(Schedule(friendEvents), item)
        return friendEvents.filter { it.uid in uids }
    }

    /**
     * Whether [group] is in my plan: [lsf] = my imported events, [added] = my added ones, [manual] = my manual courses.
     * An LSF group counts by its series (a date LSF moved may be missing from an older plan); single dates only when
     * the group has no series.
     */
    fun state(group: List<Event>, lsf: List<Event>, added: List<Event>, manual: List<ManualCourse>): State {
        if (group.isEmpty()) return State.IN_PLAN
        if (group.first().isManual) return if (manual.any { it.id == group.first().uid }) State.IN_PLAN else State.ADDABLE
        val keys = group.filter { it.recurrence != null }.ifEmpty { group }.mapTo(HashSet()) { it.uid }
        val inPlan = lsf.mapTo(HashSet()) { it.uid }
        val inAdded = AddedCourses.visible(lsf, added).mapTo(HashSet()) { it.uid }
        return when {
            keys.any { it in inAdded } -> State.ADDED
            keys.all { it in inPlan } -> State.IN_PLAN
            else -> State.ADDABLE
        }
    }

    /**
     * The friend's manual course [event] as one of mine, with the same id (a second copy is found by it). Type, room,
     * lecturer, s.t., rhythm and dates come along, the note doesn't (it is the friend's own text); lecture-free weeks are
     * left out by my plan's.
     */
    fun manualCourse(event: Event): ManualCourse {
        val r = event.recurrence
        val rhythm = when {
            r == null -> ManualCourse.Rhythm.ONCE
            !r.biweekly -> ManualCourse.Rhythm.WEEKLY
            else -> when (Expansion.rhythmOf(event)) {
                WeekRhythm.EVEN_WEEKS -> ManualCourse.Rhythm.EVEN_WEEKS
                WeekRhythm.ODD_WEEKS -> ManualCourse.Rhythm.ODD_WEEKS
                WeekRhythm.EVERY_14_DAYS, null -> ManualCourse.Rhythm.BIWEEKLY
            }
        }
        val last = if (r == null) null else (Expansion.slots(event.copy(recurrence = r.copy(exdates = emptySet(), exdays = emptySet()))).lastOrNull()?.start?.toLocalDate()
            ?: event.start.toLocalDate())
        return ManualCourse(
            id = event.uid,
            title = event.title,
            type = ManualType.entries.firstOrNull { it.category == event.category } ?: ManualType.OTHER,
            room = event.location?.raw?.takeIf { it.isNotBlank() },
            lecturer = event.lecturers.joinToString(", ").ifBlank { null },
            st = event.st,
            rhythm = rhythm,
            startDate = event.start.toLocalDate(),
            endDate = last,
            start = event.start.toLocalTime(),
            end = event.end.toLocalTime(),
        )
    }

    /**
     * My course settings after adding [incoming] to [mine] (my events before): a course of a subject I have keeps my
     * style (same course key, or its title stem joins one of my subjects); a new subject gets the friend's [color]
     * (0xRRGGBB, as shown in their plan).
     */
    fun settings(settings: Map<String, CourseSetting>, mine: List<Event>, incoming: List<Event>, color: Int?): Map<String, CourseSetting> {
        if (color == null) return settings
        val myKeys = mine.mapTo(HashSet()) { it.courseKey }
        val subjects = Subjects.build(mine + incoming, settings)
        val out = settings.toMutableMap()
        for (course in Subjects.courses(incoming)) {
            if (course.key in myKeys) continue
            val subject = subjects.first { s -> s.courses.any { it.key == course.key } }
            if (subject.courses.any { it.key in myKeys }) continue
            out[course.key] = (settings[course.key] ?: CourseSetting(course.key, course.title)).copy(color = color)
        }
        return out
    }
}
