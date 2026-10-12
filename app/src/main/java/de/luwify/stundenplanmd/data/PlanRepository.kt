package de.luwify.stundenplanmd.data

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.Plan
import de.luwify.stundenplanmd.core.model.Subjects
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

/** How I appear in shares: [id] is made at the first share, [name] asked then. */
@Serializable
data class MyShare(val id: String? = null, val name: String? = null)

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
) {
    val myPlan: Plan? get() = planOf(ME)
    val friends: List<Person> get() = people.filter { !it.isMe }

    fun planOf(personId: String): Plan? = plans.firstOrNull { it.personId == personId }
    fun person(id: String): Person? = people.firstOrNull { it.id == id }

    companion object {
        const val ME = "me"
    }
}

sealed interface FriendImport {
    /** [replaced]: a plan of this person was there before. [previous] allows undo. */
    data class Imported(val person: Person, val replaced: Boolean, val previous: StoredData) : FriendImport

    /** My own share. */
    data object Own : FriendImport
}

sealed interface ImportOutcome {
    /** [previous] allows undo. */
    data class Imported(val events: Int, val previous: StoredData) : ImportOutcome
    data object NotACalendar : ImportOutcome
    data object NoEvents : ImportOutcome
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
        if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) return ImportOutcome.NotACalendar
        val result = withContext(Dispatchers.Default) { LsfImport.parse(text) }
        if (result.events.isEmpty()) return ImportOutcome.NoEvents
        return mutex.withLock {
            val current = _data.value ?: StoredData()
            val plan = Plan(StoredData.ME, Instant.now(), result.source, result.events)
            val next = current.copy(
                plans = current.plans.filterNot { it.personId == StoredData.ME } + plan,
                courseSettings = Subjects.carryOver(current.courseSettings, result.events),
            )
            write(next)
            withContext(Dispatchers.IO) { store.writeIcs(text) }
            ImportOutcome.Imported(result.events.size, current)
        }
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
        if (shared.personId == current.share.id || shared.personId == StoredData.ME) return@withLock FriendImport.Own
        val old = current.person(shared.personId)
        val person = (old ?: Person(shared.personId, shared.name, isMe = false)).copy(name = shared.name)
        val plan = Plan(
            personId = shared.personId,
            importedAt = Instant.now(),
            source = "share",
            events = shared.events,
            exportedOn = shared.exportedOn,
            planDate = shared.planDate,
            sharedSettings = shared.settings,
        )
        val local = current.friendSettings[shared.personId]
        write(
            current.copy(
                people = if (old == null) current.people + person else current.people.map { if (it.id == person.id) person else it },
                plans = current.plans.filterNot { it.personId == shared.personId } + plan,
                friendSettings = if (local == null) current.friendSettings else current.friendSettings + (shared.personId to Subjects.carryOver(local, shared.events)),
            ),
        )
        FriendImport.Imported(person, replaced = old != null, previous = current)
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
            ),
        )
        current
    }

    suspend fun updateFriendSettings(id: String, transform: (Map<String, CourseSetting>) -> Map<String, CourseSetting>) = mutex.withLock {
        val current = _data.value ?: return@withLock
        write(current.copy(friendSettings = current.friendSettings + (id to transform(current.friendSettings[id].orEmpty()))))
    }

    /** My share identity; the id is made on first use. [name] null keeps the stored one. */
    suspend fun shareIdentity(name: String? = null): MyShare = mutex.withLock {
        val current = _data.value ?: StoredData()
        val next = MyShare(current.share.id ?: PersonIds.new(), name?.trim()?.ifEmpty { null } ?: current.share.name)
        if (next != current.share) write(current.copy(share = next))
        next
    }

    private suspend fun write(next: StoredData) {
        withContext(Dispatchers.IO) { store.write(next) }
        _data.value = next
    }
}
