package de.luwify.stundenplanmd.core.store

import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Plan
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.AddedCourses
import de.luwify.stundenplanmd.core.schedule.Expansion
import de.luwify.stundenplanmd.core.schedule.Hiding
import de.luwify.stundenplanmd.core.schedule.ManualCourses
import de.luwify.stundenplanmd.core.schedule.Schedule
import kotlinx.serialization.Serializable

// The stored plan data (Android: files/plans/data.json, web: IndexedDB) and its migrations live in :core, so the
// Android app and the web app read and write the same format (backups move between them).

/** Default subject colors, assigned to subjects in order (0xRRGGBB); the app's SubjectColors tints them per theme. */
object SubjectPalette {
    val palette = listOf(
        0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA, 0x00897B, 0xE53935,
        0x3949AB, 0xC0CA33, 0xD81B60, 0x6D4C41, 0x00ACC1, 0xFDD835,
    )

    fun defaultColor(index: Int): Int = palette[index.mod(palette.size)]
}

/**
 * How I appear in shares: [id] is made at the first share, [name] asked then. [withChanges]: my own cancellations,
 * room/time changes and s.t. go into shares ("Eigene Änderungen teilen"; notes never do).
 */
@Serializable
data class MyShare(val id: String? = null, val name: String? = null, val withChanges: Boolean = true)

/**
 * Everything the app stores: me and friends (people + plans; the order of [people] is the drawer order), my course settings (keyed by course key) and, per
 * friend id, my local settings for their courses.
 */
@Serializable
data class StoredData(
    val version: Int = Migrations.CURRENT,
    val people: List<Person> = listOf(Person(ME, "Ich", isMe = true)),
    val plans: List<Plan> = emptyList(),
    val courseSettings: Map<String, CourseSetting> = emptyMap(),
    val friendSettings: Map<String, Map<String, CourseSetting>> = emptyMap(),
    val share: MyShare = MyShare(),
    /** My own changes to my plan (notes, s.t., cancellations, rooms, times); kept across re-imports. */
    val personalChanges: List<PersonalChange> = emptyList(),
    /** Courses I added by hand; the LSF import never touches them. */
    val manualCourses: List<ManualCourse> = emptyList(),
    /**
     * Rhythms set by hand for weekly INTERVAL=2 series (even/odd calendar weeks or 14 days), per person ([ME] or a
     * friend's id): appointment UID -> rhythm. Kept across re-imports; mine go into shares with my own changes.
     */
    val rhythms: Map<String, Map<String, WeekRhythm>> = emptyMap(),
    /** LSF courses added to my plan from another .ics ([AddedCourses]); kept across re-imports unless the plan has them. */
    val addedEvents: List<Event> = emptyList(),
    /** What I hid, per person ([ME] or a friend's id): subjects, courses, groups, dates. Kept across re-imports. */
    val hidden: Map<String, List<Hidden>> = emptyMap(),
) {
    /** My LSF events: the imported plan plus the added courses it does not have. */
    val myLsfEvents: List<Event> get() = myPlan?.events.orEmpty().let { it + AddedCourses.visible(it, addedEvents) }

    /** Appointment UIDs of the added courses shown. */
    val addedUids: Set<String> get() = AddedCourses.visible(myPlan?.events.orEmpty(), addedEvents).mapTo(HashSet()) { it.uid }

    /** My events: the LSF ones (with my rhythms) plus the manual courses. */
    val myEvents: List<Event> get() = ManualCourses.merge(Expansion.withRhythms(myLsfEvents, rhythmsOf(ME)), manualCourses)

    fun rhythmsOf(personId: String): Map<String, WeekRhythm> = rhythms[personId].orEmpty()

    /** The events shown for [personId]: mine ([myEvents]) or the friend's plan with my rhythms for it. */
    fun eventsOf(personId: String): List<Event> =
        if (personId == ME) myEvents else Expansion.withRhythms(planOf(personId)?.events.orEmpty(), rhythmsOf(personId))

    val myPlan: Plan? get() = planOf(ME)
    val friends: List<Person> get() = people.filter { !it.isMe }

    fun planOf(personId: String): Plan? = plans.firstOrNull { it.personId == personId }
    fun person(id: String): Person? = people.firstOrNull { it.id == id }

    fun hiddenOf(personId: String): List<Hidden> = hidden[personId].orEmpty()

    /**
     * The course settings shown for [personId]: mine, or for a friend my local overrides, else mine (resolved) for courses
     * I take too, else theirs ([Subjects.forFriend]).
     */
    fun settingsOf(personId: String, events: List<Event> = eventsOf(personId)): Map<String, CourseSetting> =
        if (personId == ME) courseSettings else Subjects.forFriend(
            events,
            local = friendSettings[personId].orEmpty(),
            mine = Subjects.resolved(myEvents, courseSettings, SubjectPalette::defaultColor),
            shared = planOf(personId)?.sharedSettings.orEmpty(),
        )

    /** What is left out of [personId]'s plan: what I hid there and, for a friend, the dates they hid before sharing. */
    fun hidingOf(personId: String, events: List<Event> = eventsOf(personId)): Hiding? {
        val items = hiddenOf(personId)
        val shared = if (personId == ME) emptyList() else planOf(personId)?.sharedHidden.orEmpty()
        if (items.isEmpty() && shared.isEmpty()) return null
        return Hiding.of(items, events, settingsOf(personId, events), shared)
    }

    /** [plan] expanded with the own changes (mine for my plan, the friend's shared ones for theirs), without what is hidden. */
    fun schedule(plan: Plan): Schedule = if (plan.personId == ME) mySchedule() else eventsOf(plan.personId).let { events ->
        Schedule(events, plan.sharedChanges, hidingOf(plan.personId, events))
    }

    fun mySchedule(): Schedule = myEvents.let { Schedule(it, personalChanges, hidingOf(ME, it)) }

    companion object {
        const val ME = "me"
    }
}
