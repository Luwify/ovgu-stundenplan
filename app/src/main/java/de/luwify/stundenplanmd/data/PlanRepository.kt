package de.luwify.stundenplanmd.data

import androidx.core.util.AtomicFile
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
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Everything the app stores. People and plans are lists from the start (me + imported friends later);
 * course settings are mine (how I see courses), keyed by course key.
 */
@Serializable
data class StoredData(
    val version: Int = 1,
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

/** One JSON file in app storage, written atomically; loaded once at start (a few kB, instant). */
class PlanRepository(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = AtomicFile(File(dir, "data.json"))
    private val mutex = Mutex()
    private val _data = MutableStateFlow<StoredData?>(null)

    /** Null while loading. */
    val data: StateFlow<StoredData?> = _data.asStateFlow()

    suspend fun load() = mutex.withLock {
        if (_data.value != null) return@withLock
        _data.value = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(StoredData.serializer(), file.readFully().decodeToString()) }.getOrNull()
        } ?: StoredData()
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
            withContext(Dispatchers.IO) { File(dir, "me.ics").writeText(text) }
            ImportOutcome.Imported(result.events.size, current)
        }
    }

    suspend fun restore(previous: StoredData) = mutex.withLock { write(previous) }

    suspend fun updateSettings(transform: (Map<String, CourseSetting>) -> Map<String, CourseSetting>) = mutex.withLock {
        val current = _data.value ?: return@withLock
        write(current.copy(courseSettings = transform(current.courseSettings)))
    }

    private suspend fun write(next: StoredData) {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val f = file
            val out = f.startWrite()
            try {
                out.write(json.encodeToString(StoredData.serializer(), next).encodeToByteArray())
                f.finishWrite(out)
            } catch (e: Exception) {
                f.failWrite(out)
                throw e
            }
        }
        _data.value = next
    }
}
