package de.luwify.stundenplanmd.data

import de.luwify.stundenplanmd.core.store.StoredData
import de.luwify.stundenplanmd.core.store.MyShare
import de.luwify.stundenplanmd.core.store.StoreEdits
import de.luwify.stundenplanmd.core.schedule.Timed

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
        val r = StoreEdits.addCourses(current, events)
        if (r.data != current) write(r.data)
        AddOutcome(r.added, r.updated, r.duplicates, current)
    }

    /**
     * "Zu meinem Plan": a friend's [group] ([FriendCourses.group]) copied into my plan: an LSF group into the added
     * courses, a manual course as one of mine. A new subject gets the friend's [color]. Null when nothing changed, else
     * the previous state for undo.
     */
    suspend fun addFromFriend(group: List<Event>, color: Int?): StoredData? = mutex.withLock {
        val current = _data.value ?: return@withLock null
        val next = StoreEdits.addFromFriend(current, group, color) ?: return@withLock null
        write(next)
        current
    }

    /**
     * Removes added appointments ([group]: one group of a course, [de.luwify.stundenplanmd.core.schedule.Groups]) with my
     * changes and rhythms for them; other groups of the course stay. Returns the previous state for undo.
     */
    suspend fun removeAdded(group: Set<String>): StoredData? = edit { StoreEdits.removeAdded(it, group) }

    /**
     * A friend's plan from an .ics: new ([existingId] null, named [name]) or an update of an .ics friend I have (their
     * name, favorite, place in the drawer and my settings for them stay). "Stand" is the import day.
     */
    suspend fun importIcsFriend(events: List<Event>, source: String?, name: String, existingId: String?, today: LocalDate): FriendImport.Imported = mutex.withLock {
        val current = _data.value ?: StoredData()
        val r = StoreEdits.importIcsFriend(current, events, source, name, existingId, PersonIds.new(), today, Instant.now())
        write(r.data)
        FriendImport.Imported(r.person, replaced = r.replaced, previous = current)
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
    suspend fun renameFriend(id: String, name: String?) { edit { StoreEdits.renameFriend(it, id, name) } }

    /** New drawer order of the friends ([ids]); unknown ids are ignored, missing friends keep their place at the end. */
    suspend fun reorderFriends(ids: List<String>) { edit { StoreEdits.reorderFriends(it, ids) } }

    suspend fun setFavorite(id: String, favorite: Boolean) { edit { StoreEdits.setFavorite(it, id, favorite) } }

    /** Removes the friend, their plan and my settings for them; returns the previous state for undo. */
    suspend fun deleteFriend(id: String): StoredData? = edit { StoreEdits.deleteFriend(it, id) }

    /** My rhythm for the series [uid] in [personId]'s plan; null = as the data says. */
    suspend fun setRhythm(personId: String, uid: String, rhythm: WeekRhythm?) { edit { StoreEdits.setRhythm(it, personId, uid, rhythm) } }

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
        write(StoreEdits.saveManual(current, course, setting))
    }

    /** Removes a manual course with its settings and my changes to it; returns the previous state for undo. */
    suspend fun deleteManual(id: String): StoredData? = edit { StoreEdits.deleteManual(it, id) }

    /** Hides [item] in [personId]'s plan (narrower items it covers are dropped); returns the previous state for undo. */
    suspend fun hide(personId: String, item: Hidden): StoredData? = edit { StoreEdits.hide(it, personId, item) }

    /**
     * "Termine auswählen" in my plan, in one write: [cancelled] = date -> cancelled for me or not, [hidden] = date ->
     * hidden or not.
     */
    suspend fun setDates(states: Map<Timed, StoreEdits.DateState>, title: String) { edit { StoreEdits.setDates(it, states, title) } }

    /** Shows [item] in [personId]'s plan again ("Wiederherstellen"). */
    suspend fun unhide(personId: String, item: Hidden) { edit { StoreEdits.unhide(it, personId, item) } }

    suspend fun setShareChanges(on: Boolean) { edit { StoreEdits.setShareChanges(it, on) } }

    /** My share identity; the id is made on first use. [name] null keeps the stored one. */
    suspend fun shareIdentity(name: String? = null): MyShare = mutex.withLock {
        val current = _data.value ?: StoredData()
        val next = StoreEdits.shareIdentity(current, name, PersonIds.new())
        if (next != current) write(next)
        next.share
    }

    /**
     * Applies [change] to the stored data ([StoreEdits]); a result equal to the current state writes nothing. Returns
     * the previous state (for undo), or null when [change] returned null.
     */
    suspend fun edit(change: (StoredData) -> StoredData?): StoredData? = mutex.withLock {
        val current = _data.value ?: return@withLock null
        val next = change(current) ?: return@withLock null
        if (next != current) write(next)
        current
    }

    private suspend fun write(next: StoredData) {
        withContext(Dispatchers.IO) { store.write(next) }
        _data.value = next
    }
}
