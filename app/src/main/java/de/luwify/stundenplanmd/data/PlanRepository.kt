package de.luwify.stundenplanmd.data

import de.luwify.stundenplanmd.core.store.StoredData
import de.luwify.stundenplanmd.core.store.MyShare

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.schedule.Hiding
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.schedule.ManualCourses
import de.luwify.stundenplanmd.core.schedule.Expansion
import de.luwify.stundenplanmd.core.schedule.AddedCourses
import de.luwify.stundenplanmd.core.schedule.FriendCourses
import de.luwify.stundenplanmd.core.ical.ImportResult
import java.time.LocalDate
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Plan
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.core.share.SharedPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/**
 * This state with the colors my subjects showed in [previous] kept ([Subjects.keepColors]): for changes that add or
 * remove my courses, so the automatic colors of the other subjects don't move.
 */
private fun StoredData.keepingColors(previous: StoredData): StoredData = copy(
    courseSettings = Subjects.keepColors(
        previous.myEvents, previous.courseSettings, myEvents, courseSettings, de.luwify.stundenplanmd.core.store.SubjectPalette::defaultColor,
    ),
)

sealed interface FriendImport {
    /** [replaced]: a plan of this person was there before. [previous] allows undo. */
    data class Imported(val person: Person, val replaced: Boolean, val previous: StoredData) : FriendImport

    /** My own share. */
    data object Own : FriendImport
}

sealed interface ImportOutcome {
    /** [previous] allows undo; [droppedAdded]: added appointments the new plan has itself (same UID; removed). */
    data class Imported(val events: Int, val previous: StoredData, val droppedAdded: Int = 0) : ImportOutcome
    data object NotACalendar : ImportOutcome
    data object NoEvents : ImportOutcome
}

/** Counts are appointments (UIDs); [duplicates]: the ones my plan already has. [previous] allows undo. */
data class AddOutcome(val added: Int, val updated: Int, val duplicates: List<Event>, val previous: StoredData)

sealed interface IcsParse {
    data class Ok(val result: ImportResult) : IcsParse
    data object NotACalendar : IcsParse
    data object NoEvents : IcsParse
}

/** An .ics read for import (pure: nothing is stored). */
suspend fun parseIcs(text: String): IcsParse {
    if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) return IcsParse.NotACalendar
    val result = withContext(Dispatchers.Default) { LsfImport.parse(text) }
    return if (result.events.isEmpty()) IcsParse.NoEvents else IcsParse.Ok(result)
}

/** Plan and settings in app storage ([PlanStore]); loaded once at start (a few kB, instant). */
class PlanRepository(dir: File) {
    private val store = PlanStore(dir)
    private val mutex = Mutex()
    private val _data = MutableStateFlow<StoredData?>(null)

    /** Null while loading. */
    val data: StateFlow<StoredData?> = _data.asStateFlow()

    /** What loading found; [StoreRead.Unreadable] must be shown to the user (their file was set aside). */
    suspend fun load(): StoreRead? = mutex.withLock {
        if (_data.value != null) return@withLock null
        val read = withContext(Dispatchers.IO) { store.read() }
        _data.value = (read as? StoreRead.Ok)?.data ?: StoredData()
        read
    }

    /** Replaces my plan; course settings are kept and carried over to changed course ids by title. */
    suspend fun importIcs(text: String): ImportOutcome {
        val result = when (val p = parseIcs(text)) {
            is IcsParse.Ok -> p.result
            IcsParse.NotACalendar -> return ImportOutcome.NotACalendar
            IcsParse.NoEvents -> return ImportOutcome.NoEvents
        }
        return mutex.withLock {
            val current = _data.value ?: StoredData()
            val r = de.luwify.stundenplanmd.core.store.StoreOps.importIcs(current, result, Instant.now())
            val dropped = r.dropped
            write(r.data)
            withContext(Dispatchers.IO) { store.writeIcs(text) }
            ImportOutcome.Imported(result.events.size, current, dropped)
        }
    }

    /** Adds the courses of an .ics to my plan, next to the imported one ([AddedCourses.add]); [AddOutcome.previous] allows undo. */
    suspend fun addCourses(events: List<Event>): AddOutcome = mutex.withLock {
        val current = _data.value ?: StoredData()
        val r = AddedCourses.add(current.myPlan?.events.orEmpty(), current.addedEvents, events)
        if (r.events != current.addedEvents) {
            write(current.copy(addedEvents = r.events, courseSettings = Subjects.carryOver(current.courseSettings, current.myPlan?.events.orEmpty() + r.events)).keepingColors(current))
        }
        AddOutcome(r.added, r.updated, r.duplicates, current)
    }

    /**
     * "Zu meinem Plan": a friend's [group] ([FriendCourses.group]) copied into my plan: an LSF group into the added
     * courses, a manual course as one of mine. A new subject gets the friend's [color]. Null when nothing changed, else
     * the previous state for undo.
     */
    suspend fun addFromFriend(group: List<Event>, color: Int?): StoredData? = mutex.withLock {
        val current = _data.value ?: return@withLock null
        val lsf = current.myPlan?.events.orEmpty()
        if (group.isEmpty() || FriendCourses.state(group, lsf, current.addedEvents, current.manualCourses) != FriendCourses.State.ADDABLE) return@withLock null
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
        write(next.keepingColors(current))
        current
    }

    /**
     * Removes added appointments ([group]: one group of a course, [de.luwify.stundenplanmd.core.schedule.Groups]) with my
     * changes and rhythms for them; other groups of the course stay. Returns the previous state for undo.
     */
    suspend fun removeAdded(group: Set<String>): StoredData? = mutex.withLock {
        val current = _data.value ?: return@withLock null
        val uids = current.addedEvents.map { it.uid }.filter { it in group }.toSet()
        if (uids.isEmpty()) return@withLock null
        val mine = current.rhythmsOf(StoredData.ME) - uids
        write(
            current.copy(
                addedEvents = AddedCourses.remove(current.addedEvents, uids),
                personalChanges = current.personalChanges.filterNot { it.uid in uids },
                rhythms = if (mine.isEmpty()) current.rhythms - StoredData.ME else current.rhythms + (StoredData.ME to mine),
            ).keepingColors(current),
        )
        current
    }

    /**
     * A friend's plan from an .ics: new ([existingId] null, named [name]) or an update of an .ics friend I have (their
     * name, favorite, place in the drawer and my settings for them stay). "Stand" is the import day.
     */
    suspend fun importIcsFriend(events: List<Event>, source: String?, name: String, existingId: String?, today: LocalDate): FriendImport.Imported = mutex.withLock {
        val current = _data.value ?: StoredData()
        val old = existingId?.let(current::person)?.takeIf { it.fromIcs }
        val person = old ?: Person(Person.ICS_ID_PREFIX + PersonIds.new(), name.trim(), isMe = false)
        val plan = Plan(personId = person.id, importedAt = Instant.now(), source = source, events = events, planDate = today)
        val local = current.friendSettings[person.id]
        write(
            current.copy(
                people = if (old == null) current.people + person else current.people,
                plans = current.plans.filterNot { it.personId == person.id } + plan,
                friendSettings = if (local == null) current.friendSettings else current.friendSettings + (person.id to Subjects.carryOver(local, events)),
            ),
        )
        FriendImport.Imported(person, replaced = old != null, previous = current)
    }

    suspend fun restore(previous: StoredData) = mutex.withLock { write(previous) }

    suspend fun readIcs(): String? = withContext(Dispatchers.IO) { store.readIcs() }

    /** Replaces plan, settings and the raw import ([ics] null removes it): backup import and its undo. */
    suspend fun replaceAll(data: StoredData, ics: String?) = mutex.withLock {
        write(data)
        withContext(Dispatchers.IO) { store.writeIcs(ics) }
    }

    suspend fun updateSettings(transform: (Map<String, CourseSetting>) -> Map<String, CourseSetting>) = mutex.withLock {
        val current = _data.value ?: return@withLock
        write(current.copy(courseSettings = transform(current.courseSettings)))
    }

    /** Adds or replaces a friend's plan; my rename and local settings for them are kept. */
    suspend fun importFriend(shared: SharedPlan): FriendImport = mutex.withLock {
        val current = _data.value ?: StoredData()
        when (val r = de.luwify.stundenplanmd.core.store.StoreOps.importFriend(current, shared, Instant.now())) {
            de.luwify.stundenplanmd.core.store.StoreOps.FriendResult.Own -> FriendImport.Own
            is de.luwify.stundenplanmd.core.store.StoreOps.FriendResult.Imported -> {
                write(r.data)
                FriendImport.Imported(r.person, replaced = r.replaced, previous = current)
            }
        }
    }

    /** Null or blank: back to the name they shared. */
    suspend fun renameFriend(id: String, name: String?) = mutex.withLock {
        val current = _data.value ?: return@withLock
        write(current.copy(people = current.people.map { if (it.id == id && !it.isMe) it.copy(localName = name?.trim()?.ifEmpty { null }) else it }))
    }

    /** New drawer order of the friends ([ids]); unknown ids are ignored, missing friends keep their place at the end. */
    suspend fun reorderFriends(ids: List<String>) = mutex.withLock {
        val current = _data.value ?: return@withLock
        val rank = ids.withIndex().associate { it.value to it.index }
        val (me, friends) = current.people.partition { it.isMe }
        val next = me + friends.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        if (next != current.people) write(current.copy(people = next))
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = mutex.withLock {
        val current = _data.value ?: return@withLock
        write(current.copy(people = current.people.map { if (it.id == id && !it.isMe) it.copy(favorite = favorite) else it }))
    }

    /** Removes the friend, their plan and my settings for them; returns the previous state for undo. */
    suspend fun deleteFriend(id: String): StoredData? = mutex.withLock {
        val current = _data.value ?: return@withLock null
        if (id == StoredData.ME) return@withLock null
        write(
            current.copy(
                people = current.people.filterNot { it.id == id },
                plans = current.plans.filterNot { it.personId == id },
                friendSettings = current.friendSettings - id,
                rhythms = current.rhythms - id,
                hidden = current.hidden - id,
            ),
        )
        current
    }

    /** My rhythm for the series [uid] in [personId]'s plan; null = as the data says. */
    suspend fun setRhythm(personId: String, uid: String, rhythm: WeekRhythm?) = mutex.withLock {
        val current = _data.value ?: return@withLock
        val mine = current.rhythmsOf(personId).let { if (rhythm == null) it - uid else it + (uid to rhythm) }
        val next = if (mine.isEmpty()) current.rhythms - personId else current.rhythms + (personId to mine)
        if (next != current.rhythms) write(current.copy(rhythms = next))
    }

    suspend fun updateFriendSettings(id: String, transform: (Map<String, CourseSetting>) -> Map<String, CourseSetting>) = mutex.withLock {
        val current = _data.value ?: return@withLock
        write(current.copy(friendSettings = current.friendSettings + (id to transform(current.friendSettings[id].orEmpty()))))
    }

    suspend fun updatePersonalChanges(transform: (List<PersonalChange>) -> List<PersonalChange>) = mutex.withLock {
        val current = _data.value ?: return@withLock
        val next = transform(current.personalChanges)
        if (next != current.personalChanges) write(current.copy(personalChanges = next))
    }

    /** Adds or replaces (same id) a manual course and sets its course settings ([setting] null = none). */
    suspend fun saveManual(course: ManualCourse, setting: CourseSetting?) = mutex.withLock {
        val current = _data.value ?: StoredData()
        val courses = if (current.manualCourses.any { it.id == course.id }) {
            current.manualCourses.map { if (it.id == course.id) course else it }
        } else {
            current.manualCourses + course
        }
        val settings = if (setting == null) current.courseSettings - course.courseKey else current.courseSettings + (course.courseKey to setting)
        write(current.copy(manualCourses = courses, courseSettings = settings).keepingColors(current))
    }

    /** Removes a manual course with its settings and my changes to it; returns the previous state for undo. */
    suspend fun deleteManual(id: String): StoredData? = mutex.withLock {
        val current = _data.value ?: return@withLock null
        val course = current.manualCourses.firstOrNull { it.id == id } ?: return@withLock null
        val (courses, changes) = ManualCourses.remove(current.manualCourses, current.personalChanges, id)
        write(current.copy(manualCourses = courses, personalChanges = changes, courseSettings = current.courseSettings - course.courseKey).keepingColors(current))
        current
    }

    /** Hides [item] in [personId]'s plan (narrower items it covers are dropped); returns the previous state for undo. */
    suspend fun hide(personId: String, item: Hidden): StoredData? = mutex.withLock {
        val current = _data.value ?: return@withLock null
        write(current.copy(hidden = current.hidden + (personId to Hidden.add(current.hiddenOf(personId), item))))
        current
    }

    /**
     * "Termine auswählen" in my plan, in one write: [cancelled] = date -> cancelled for me or not, [hidden] = date ->
     * hidden or not.
     */
    suspend fun setDates(cancelled: Map<de.luwify.stundenplanmd.core.schedule.OccurrenceRef, Boolean>, hidden: Map<Hidden, Boolean>, title: String) = mutex.withLock {
        val current = _data.value ?: return@withLock
        val changes = cancelled.entries.fold(current.personalChanges) { acc, (ref, on) ->
            de.luwify.stundenplanmd.core.schedule.PersonalChanges.setCancelled(acc, ref, title, on)
        }
        val list = hidden.entries.fold(current.hiddenOf(StoredData.ME)) { acc, (item, on) -> if (on) Hidden.add(acc, item) else Hidden.remove(acc, item) }
        val next = current.copy(
            personalChanges = changes,
            hidden = if (list.isEmpty()) current.hidden - StoredData.ME else current.hidden + (StoredData.ME to list),
        )
        if (next != current) write(next)
    }

    /** Shows [item] in [personId]'s plan again ("Wiederherstellen"). */
    suspend fun unhide(personId: String, item: Hidden) = mutex.withLock {
        val current = _data.value ?: return@withLock
        val list = Hidden.remove(current.hiddenOf(personId), item)
        write(current.copy(hidden = if (list.isEmpty()) current.hidden - personId else current.hidden + (personId to list)))
    }

    suspend fun setShareChanges(on: Boolean) = mutex.withLock {
        val current = _data.value ?: return@withLock
        if (current.share.withChanges != on) write(current.copy(share = current.share.copy(withChanges = on)))
    }

    /** My share identity; the id is made on first use. [name] null keeps the stored one. */
    suspend fun shareIdentity(name: String? = null): MyShare = mutex.withLock {
        val current = _data.value ?: StoredData()
        val next = current.share.copy(id = current.share.id ?: PersonIds.new(), name = name?.trim()?.ifEmpty { null } ?: current.share.name)
        if (next != current.share) write(current.copy(share = next))
        next
    }

    private suspend fun write(next: StoredData) {
        withContext(Dispatchers.IO) { store.write(next) }
        _data.value = next
    }
}
