package de.luwify.stundenplanmd.data

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.Plan
import de.luwify.stundenplanmd.core.model.Subjects
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
 * Everything the app stores. People and plans are lists from the start (me + imported friends later);
 * course settings are mine (how I see courses), keyed by course key.
 */
@Serializable
data class StoredData(
    val version: Int = Migrations.CURRENT,
    val people: List<Person> = listOf(Person(ME, "Ich", isMe = true)),
    val plans: List<Plan> = emptyList(),
    val courseSettings: Map<String, CourseSetting> = emptyMap(),
) {
    val myPlan: Plan? get() = plans.firstOrNull { it.personId == ME }

    companion object {
        const val ME = "me"
    }
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

    private suspend fun write(next: StoredData) {
        withContext(Dispatchers.IO) { store.write(next) }
        _data.value = next
    }
}
