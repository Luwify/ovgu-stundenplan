package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Location
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Recurrence
import java.time.LocalDate

/** Manual courses as events, merged with the LSF plan. */
object ManualCourses {
    /**
     * My events: [lsf] plus one event per manual course. A series gets EXDATEs on its dates in weeks or on days the
     * LSF plan alone marks lecture-free ([WeekPlan.free], [DayPlan.free]), so the manual courses never change what
     * counts as lecture-free (those dates show as cancelled, like LSF's own).
     */
    fun merge(lsf: List<Event>, courses: List<ManualCourse>): List<Event> {
        if (courses.isEmpty()) return lsf
        val lsfSchedule by lazy { Schedule(lsf) }
        return lsf + courses.map { event(it) { date -> lsfSchedule.week(Schedule.monday(date.atStartOfDay())).free || lsfSchedule.day(date).free } }
    }

    /** [free]: whether a date is lecture-free (only asked for series with [ManualCourse.skipFreeWeeks]). */
    fun event(c: ManualCourse, free: (LocalDate) -> Boolean = { false }): Event {
        val start = c.startDate.atTime(c.start)
        val recurrence = if (c.rhythm == ManualCourse.Rhythm.ONCE) null else {
            val last = maxOf(c.endDate ?: c.startDate, c.startDate)
            val base = Recurrence(
                interval = if (c.rhythm == ManualCourse.Rhythm.BIWEEKLY) 2 else 1,
                until = last.atTime(23, 59).atZone(PlanZone).toInstant(),
            )
            val skipped = if (!c.skipFreeWeeks) emptySet() else {
                Expansion.slots(Event(c.id, c.title, c.title, start = start, end = c.startDate.atTime(c.end), recurrence = base))
                    .map { it.start }.filter { free(it.toLocalDate()) }.toSet()
            }
            base.copy(exdates = skipped)
        }
        val room = c.room?.trim()?.takeIf { it.isNotEmpty() }
        val lecturer = c.lecturer?.trim()?.takeIf { it.isNotEmpty() }
        return Event(
            uid = c.id,
            summary = if (lecturer == null) c.title else "${c.title} ($lecturer)",
            title = c.title,
            lecturers = listOfNotNull(lecturer),
            category = c.type.category,
            location = room?.let { Location(raw = it, building = null, room = it) },
            notes = c.note.trim(),
            start = start,
            end = c.startDate.atTime(c.end),
            recurrence = recurrence,
            st = c.st,
        )
    }

    /** The course an event of my plan came from, if it is a manual one. */
    fun of(event: Event, courses: List<ManualCourse>): ManualCourse? = courses.firstOrNull { it.id == event.uid }

    /** Removes [id] and my changes to its dates (undo restores both). */
    fun remove(courses: List<ManualCourse>, changes: List<PersonalChange>, id: String) =
        courses.filterNot { it.id == id } to changes.filterNot { it.uid == id }

    /** Default end of a new series: the last date of the imported plan, else 14 weeks after [start]. */
    fun defaultEnd(lsf: List<Event>, start: LocalDate): LocalDate =
        Schedule(lsf).lastDate?.takeIf { !it.isBefore(start) } ?: start.plusWeeks(14)
}
