package de.luwify.stundenplanmd.core.store

import de.luwify.stundenplanmd.core.model.Course
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Plan
import de.luwify.stundenplanmd.core.model.Subject
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.AddedCourses
import de.luwify.stundenplanmd.core.schedule.Cancellation
import de.luwify.stundenplanmd.core.schedule.FriendCourses
import de.luwify.stundenplanmd.core.schedule.Groups
import de.luwify.stundenplanmd.core.schedule.ManualCourses
import de.luwify.stundenplanmd.core.schedule.OccurrenceRef
import de.luwify.stundenplanmd.core.schedule.PersonalChanges
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.core.schedule.Timed
import java.time.Instant
import java.time.LocalDate

/**
 * The edits of the stored state, pure (`StoredData -> StoredData`): used by the Android app's PlanRepository and
 * MainViewModel and by the web app (WebCore), so both change the data the same way. Functions that return null
 * changed nothing; callers keep the previous state for undo themselves.
 */
object StoreEdits {
    /**
     * [next] with the colors my subjects showed in [previous] kept ([Subjects.keepColors]): for changes that add or
     * remove my courses, so the automatic colors of the other subjects don't move.
     */
    fun keepingColors(next: StoredData, previous: StoredData): StoredData = next.copy(
        courseSettings = Subjects.keepColors(previous.myEvents, previous.courseSettings, next.myEvents, next.courseSettings, SubjectPalette::defaultColor),
    )

    /** Counts are appointments (UIDs); [duplicates]: the ones my plan already has. */
    class AddResult(val data: StoredData, val added: Int, val updated: Int, val duplicates: List<Event>)

    /** Adds the courses of an .ics to my plan, next to the imported one ([AddedCourses.add]). */
    fun addCourses(current: StoredData, events: List<Event>): AddResult {
        val r = AddedCourses.add(current.myPlan?.events.orEmpty(), current.addedEvents, events)
        val next = if (r.events == current.addedEvents) {
            current
        } else {
            keepingColors(
                current.copy(addedEvents = r.events, courseSettings = Subjects.carryOver(current.courseSettings, current.myPlan?.events.orEmpty() + r.events)),
                current,
            )
        }
        return AddResult(next, r.added, r.updated, r.duplicates)
    }

    /** "Zu meinem Plan" state of a friend's [group] ([FriendCourses.group]). */
    fun friendCourseState(current: StoredData, group: List<Event>): FriendCourses.State =
        FriendCourses.state(group, current.myPlan?.events.orEmpty(), current.addedEvents, current.manualCourses)

    /**
     * "Zu meinem Plan": a friend's [group] copied into my plan: an LSF group into the added courses, a manual course as
     * one of mine. A new subject gets the friend's [color]. Null when nothing changed.
     */
    fun addFromFriend(current: StoredData, group: List<Event>, color: Int?): StoredData? {
        val lsf = current.myPlan?.events.orEmpty()
        if (group.isEmpty() || friendCourseState(current, group) != FriendCourses.State.ADDABLE) return null
        val next = if (group.first().isManual) {
            val course = FriendCourses.manualCourse(group.first())
            current.copy(
                manualCourses = current.manualCourses + course,
                courseSettings = FriendCourses.settings(current.courseSettings, current.myEvents, listOf(ManualCourses.event(course)), color),
            )
        } else {
            val added = AddedCourses.add(lsf, current.addedEvents, group).events
            val settings = Subjects.carryOver(current.courseSettings, lsf + added)
            current.copy(addedEvents = added, courseSettings = FriendCourses.settings(settings, current.myEvents, group, color))
        }
        return keepingColors(next, current)
    }

    /** Removes added appointments ([group]: UIDs) with my changes and rhythms for them; null when none was added. */
    fun removeAdded(current: StoredData, group: Set<String>): StoredData? {
        val uids = current.addedEvents.map { it.uid }.filter { it in group }.toSet()
        if (uids.isEmpty()) return null
        val mine = current.rhythmsOf(StoredData.ME) - uids
        return keepingColors(
            current.copy(
                addedEvents = AddedCourses.remove(current.addedEvents, uids),
                personalChanges = current.personalChanges.filterNot { it.uid in uids },
                rhythms = if (mine.isEmpty()) current.rhythms - StoredData.ME else current.rhythms + (StoredData.ME to mine),
            ),
            current,
        )
    }

    class IcsFriendResult(val data: StoredData, val person: Person, val replaced: Boolean)

    /**
     * A friend's plan from an .ics: new ([existingId] null, named [name], id `ics-` + [newId]) or an update of an .ics
     * friend I have (their name, favorite, place in the drawer and my settings for them stay). "Stand" = [today].
     */
    fun importIcsFriend(
        current: StoredData, events: List<Event>, source: String?, name: String, existingId: String?, newId: String, today: LocalDate, now: Instant,
    ): IcsFriendResult {
        val old = existingId?.let(current::person)?.takeIf { it.fromIcs }
        val person = old ?: Person(Person.ICS_ID_PREFIX + newId, name.trim(), isMe = false)
        val plan = Plan(personId = person.id, importedAt = now, source = source, events = events, planDate = today)
        val local = current.friendSettings[person.id]
        val next = current.copy(
            people = if (old == null) current.people + person else current.people,
            plans = current.plans.filterNot { it.personId == person.id } + plan,
            friendSettings = if (local == null) current.friendSettings else current.friendSettings + (person.id to Subjects.carryOver(local, events)),
        )
        return IcsFriendResult(next, person, replaced = old != null)
    }

    /** Null or blank: back to the name they shared. */
    fun renameFriend(current: StoredData, id: String, name: String?): StoredData =
        current.copy(people = current.people.map { if (it.id == id && !it.isMe) it.copy(localName = name?.trim()?.ifEmpty { null }) else it })

    /** New drawer order of the friends ([ids]); unknown ids are ignored, missing friends keep their place at the end. */
    fun reorderFriends(current: StoredData, ids: List<String>): StoredData {
        val rank = ids.withIndex().associate { it.value to it.index }
        val (me, friends) = current.people.partition { it.isMe }
        val next = me + friends.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        return if (next == current.people) current else current.copy(people = next)
    }

    fun setFavorite(current: StoredData, id: String, favorite: Boolean): StoredData =
        current.copy(people = current.people.map { if (it.id == id && !it.isMe) it.copy(favorite = favorite) else it })

    /** Removes the friend, their plan and my settings for them; null for me. */
    fun deleteFriend(current: StoredData, id: String): StoredData? {
        if (id == StoredData.ME || current.person(id) == null) return null
        return current.copy(
            people = current.people.filterNot { it.id == id },
            plans = current.plans.filterNot { it.personId == id },
            friendSettings = current.friendSettings - id,
            rhythms = current.rhythms - id,
            hidden = current.hidden - id,
        )
    }

    /** My rhythm for the series [uid] in [personId]'s plan; null = as the data says. */
    fun setRhythm(current: StoredData, personId: String, uid: String, rhythm: WeekRhythm?): StoredData {
        val mine = current.rhythmsOf(personId).let { if (rhythm == null) it - uid else it + (uid to rhythm) }
        val next = if (mine.isEmpty()) current.rhythms - personId else current.rhythms + (personId to mine)
        return if (next == current.rhythms) current else current.copy(rhythms = next)
    }

    /** The subjects of [personId]'s plan as shown (built from all events, so automatic colors don't shift). */
    fun subjectsOf(current: StoredData, personId: String): List<Subject> {
        val events = current.eventsOf(personId)
        return Subjects.build(events, current.settingsOf(personId, events))
    }

    /**
     * Short name prefix and color of [subject], and the type parts of the short name in [typed] (course key -> part). In
     * my plan: my settings. In a friend's: my local overrides for them; nothing set = their defaults.
     */
    fun setSubjectStyle(current: StoredData, personId: String, subject: Subject, shortName: String?, color: Int?, typed: Map<String, String?> = emptyMap()): StoredData {
        fun apply(settings: Map<String, CourseSetting>) = typed.entries.fold(Subjects.setStyle(settings, subject, shortName, color)) { acc, (key, part) ->
            subject.courses.firstOrNull { it.key == key }?.let { Subjects.setTypeShortName(acc, it, part) } ?: acc
        }
        if (personId == StoredData.ME) return current.copy(courseSettings = apply(current.courseSettings))
        val shown = current.settingsOf(personId)
        val edited = apply(shown)
        val local = current.friendSettings[personId].orEmpty()
        val keys = subject.courses.map { it.key }.toSet()
        val next = if (shortName.isNullOrBlank() && color == null && keys.all { edited[it]?.typeShortName == null }) {
            local.filterKeys { it !in keys || local[it]?.subject != null }
                .mapValues { (k, v) -> if (k in keys) v.copy(shortName = null, color = null, typeShortName = null) else v }
        } else {
            Subjects.overrides(local, shown, edited)
        }
        return current.copy(friendSettings = current.friendSettings + (personId to next))
    }

    /** Moves [course] to the subject [target] (or its own) in [personId]'s plan. */
    fun moveCourse(current: StoredData, personId: String, course: Course, target: String): StoredData {
        val subjects = subjectsOf(current, personId)
        if (personId == StoredData.ME) return current.copy(courseSettings = Subjects.move(current.courseSettings, course, target, subjects))
        val shown = current.settingsOf(personId)
        val local = current.friendSettings[personId].orEmpty()
        return current.copy(friendSettings = current.friendSettings + (personId to Subjects.overrides(local, shown, Subjects.move(shown, course, target, subjects))))
    }

    /** Hides [item] in [personId]'s plan (narrower items it covers are dropped). */
    fun hide(current: StoredData, personId: String, item: Hidden): StoredData =
        current.copy(hidden = current.hidden + (personId to Hidden.add(current.hiddenOf(personId), item)))

    /** Shows [item] in [personId]'s plan again ("Wiederherstellen"). */
    fun unhide(current: StoredData, personId: String, item: Hidden): StoredData {
        val list = Hidden.remove(current.hiddenOf(personId), item)
        return current.copy(hidden = if (list.isEmpty()) current.hidden - personId else current.hidden + (personId to list))
    }

    /** What "Termine auswählen" makes of a date: it takes place, is shown as cancelled for me, or is hidden. */
    enum class DateState { ON, CANCELLED, HIDDEN }

    /**
     * "Termine auswählen": every date of [item]'s group in my plan (LSF cancellations left out), sorted, with its state
     * now. Dates cancelled for me or hidden are listed too, so they can always be brought back or switched.
     */
    fun pickDates(current: StoredData, item: Timed): List<Pair<Timed, DateState>> =
        pickDates(current.myEvents, current.personalChanges, current.hiddenOf(StoredData.ME), item)

    /** [pickDates] from my events, changes and hidden items. */
    fun pickDates(events: List<Event>, changes: List<PersonalChange>, hidden: List<Hidden>, item: Timed): List<Pair<Timed, DateState>> {
        val hiddenRefs = hidden
            .filter { it.kind == Hidden.Kind.DATE && it.uid != null && it.date != null }
            .mapTo(HashSet()) { OccurrenceRef(it.uid!!, it.date!!) }
        val full = Schedule(events, changes)
        val group = Groups.of(full, item)
        return (full.occurrences + full.cancellations.filter { it.byMe })
            .filter { it.event.uid in group }
            .sortedBy { it.start }
            .map { t -> t to if (t.ref in hiddenRefs) DateState.HIDDEN else if (t is Cancellation) DateState.CANCELLED else DateState.ON }
    }

    /** "Termine auswählen" saved: the new state of each changed date. A date is cancelled for me or hidden, never both. */
    fun setDates(current: StoredData, states: Map<Timed, DateState>, title: String): StoredData {
        val changes = states.entries.fold(current.personalChanges) { acc, (t, s) ->
            PersonalChanges.setCancelled(acc, t.ref, title, s == DateState.CANCELLED)
        }
        val list = states.entries.fold(current.hiddenOf(StoredData.ME)) { acc, (t, s) ->
            val item = HideTargets.date(t)
            if (s == DateState.HIDDEN) Hidden.add(acc, item) else Hidden.remove(acc, item)
        }
        return current.copy(
            personalChanges = changes,
            hidden = if (list.isEmpty()) current.hidden - StoredData.ME else current.hidden + (StoredData.ME to list),
        )
    }

    /**
     * The course setting of a manual course: the chosen subject's style ([subjectKey] in [subjects]), or a new subject of
     * its own with [shortName] and [color].
     */
    fun manualSetting(course: ManualCourse, subjects: List<Subject>, subjectKey: String?, shortName: String?, color: Int?): CourseSetting {
        val key = course.courseKey
        val subject = subjectKey?.let { k -> subjects.firstOrNull { it.key == k } }
        return if (subject != null) {
            CourseSetting(key, course.title, subject = subject.key, shortName = subject.shortName, color = subject.color)
        } else {
            CourseSetting(key, course.title, subject = Subjects.ownKey(key), shortName = shortName, color = color)
        }
    }

    /** Adds or replaces (same id) a manual course and sets its course settings ([setting] null = none). */
    fun saveManual(current: StoredData, course: ManualCourse, setting: CourseSetting?): StoredData {
        val courses = if (current.manualCourses.any { it.id == course.id }) {
            current.manualCourses.map { if (it.id == course.id) course else it }
        } else {
            current.manualCourses + course
        }
        val settings = if (setting == null) current.courseSettings - course.courseKey else current.courseSettings + (course.courseKey to setting)
        return keepingColors(current.copy(manualCourses = courses, courseSettings = settings), current)
    }

    /** Removes a manual course with its settings and my changes to it; null when there is none with [id]. */
    fun deleteManual(current: StoredData, id: String): StoredData? {
        val course = current.manualCourses.firstOrNull { it.id == id } ?: return null
        val (courses, changes) = ManualCourses.remove(current.manualCourses, current.personalChanges, id)
        return keepingColors(current.copy(manualCourses = courses, personalChanges = changes, courseSettings = current.courseSettings - course.courseKey), current)
    }

    fun setShareChanges(current: StoredData, on: Boolean): StoredData =
        if (current.share.withChanges == on) current else current.copy(share = current.share.copy(withChanges = on))

    /** My share identity: the id ([newId]) is used only when I have none; [name] null or blank keeps the stored one. */
    fun shareIdentity(current: StoredData, name: String?, newId: String): StoredData {
        val next = current.share.copy(id = current.share.id ?: newId, name = name?.trim()?.ifEmpty { null } ?: current.share.name)
        return if (next == current.share) current else current.copy(share = next)
    }
}

/** What "Ausblenden" hides ([Hidden] items for the list), built from a date of a plan. */
object HideTargets {
    fun subject(subject: Subject) = Hidden(
        Hidden.Kind.SUBJECT, subject = subject.key, courses = subject.courses.mapTo(LinkedHashSet()) { it.key }, title = subject.name,
    )

    fun course(course: Course) = Hidden(Hidden.Kind.COURSE, courses = setOf(course.key), title = course.title, type = typeName(course))

    /** The group of [item]: its series (or the series it was moved out of), described by that series' day, time, room. */
    fun group(schedule: Schedule, item: Timed): Hidden {
        val base = Groups.baseUid(item)
        val series = schedule.events.firstOrNull { it.uid == base } ?: item.event
        return Hidden(
            Hidden.Kind.GROUP, courses = setOf(item.event.courseKey), uid = base, title = series.title, type = series.type.name,
            day = series.start.dayOfWeek.value, start = series.start.toLocalTime(), end = series.end.toLocalTime(), room = series.location?.room,
        )
    }

    /** One date ([Timed.ref]: an occurrence LSF moved is kept by the date it was moved from). */
    fun date(item: Timed) = Hidden(
        Hidden.Kind.DATE, courses = setOf(item.event.courseKey), uid = item.ref.uid, date = item.ref.date, title = item.event.title,
        type = item.event.type.name, start = item.start.toLocalTime(), end = item.end.toLocalTime(), room = item.room,
    )

    fun typeName(course: Course): String = course.type.name.ifEmpty { course.type.short }
}

/** Where an .ics comes from: the import in the settings (or the empty plan), or anywhere else (drawer "+", open with, share). */
enum class IcsEntry { SETTINGS, ELSEWHERE }

/** What happens with an .ics. Shares (`SPMD`) never get here: they are always a friend's plan. */
sealed interface IcsRoute {
    /** Replace my plan at once (settings, a full plan). */
    data object ReplaceMine : IcsRoute

    /** Settings, but far fewer courses than my plan has: "Plan ersetzen" or "Kurse zu meinem Plan hinzufügen". */
    data object AskReplaceOrAdd : IcsRoute

    /**
     * Not from the settings: "Als Freund hinzufügen" (new or update an .ics friend) or "Kurse zu meinem Plan
     * hinzufügen"; never replaces my plan. [canBeMine]: I have no plan yet, so it can also become mine.
     */
    data class AskFriendOrAdd(val canBeMine: Boolean) : IcsRoute
}

object ImportRouting {
    fun route(entry: IcsEntry, data: StoredData, incoming: List<Event>): IcsRoute = when (entry) {
        IcsEntry.SETTINGS -> if (AddedCourses.looksPartial(data.myPlan?.events.orEmpty(), incoming)) IcsRoute.AskReplaceOrAdd else IcsRoute.ReplaceMine
        IcsEntry.ELSEWHERE -> IcsRoute.AskFriendOrAdd(canBeMine = data.myPlan == null)
    }

    /** The friends an .ics can update (the ones imported from an .ics, in drawer order). */
    fun icsFriends(data: StoredData): List<Person> = data.friends.filter { it.fromIcs }
}
