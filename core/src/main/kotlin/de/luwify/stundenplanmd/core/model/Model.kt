@file:UseSerializers(LocalDateTimeSerializer::class, LocalDateSerializer::class, InstantSerializer::class)

package de.luwify.stundenplanmd.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** All plan times are wall-clock times in this zone (the LSF export uses TZID=Europe/Berlin). */
val PlanZone: ZoneId = ZoneId.of("Europe/Berlin")

/** Me or a friend whose plan was imported. v1 only has me. */
@Serializable
data class Person(val id: String, val name: String, val isMe: Boolean)

@Serializable
data class Plan(
    val personId: String,
    val importedAt: Instant,
    /** PRODID of the source file, e.g. "QIS-LSF HIS eG". */
    val source: String? = null,
    val events: List<Event>,
)

/** One VEVENT: a single appointment or a weekly series. */
@Serializable
data class Event(
    val uid: String,
    /** SUMMARY as exported, always kept: [title] and [lecturers] are only a heuristic split of it. */
    val summary: String,
    val title: String,
    val lecturers: List<String> = emptyList(),
    /** CATEGORIES as exported, e.g. "Vorlesung (V)". */
    val category: String = "",
    val location: Location? = null,
    /** DESCRIPTION as plain text. */
    val notes: String = "",
    val start: LocalDateTime,
    val end: LocalDateTime,
    val recurrence: Recurrence? = null,
    /** [notes] are incomplete: a friend's plan shared by QR carries only short notes. */
    val notesShortened: Boolean = false,
) {
    /** See [CourseKeys]. */
    val courseKey: String get() = CourseKeys.of(uid, title)
    val type: CourseType get() = CourseType.parse(category)
}

/** LOCATION "K18 - K18-012 (60 Pl.)" split into its parts; [raw] is always kept. */
@Serializable
data class Location(
    val raw: String,
    val building: String? = null,
    val room: String,
    /** Second room name in parentheses, e.g. "K07-215" in "K07 - K07-H2 (K07-215) (120 Pl.)". */
    val alias: String? = null,
    val capacity: Int? = null,
)

/** RRULE + EXDATE of a series. Only FREQ=WEEKLY and DAILY are expanded. */
@Serializable
data class Recurrence(
    val frequency: Frequency = Frequency.WEEKLY,
    val interval: Int = 1,
    /** Days of the week (WEEKLY); empty = the weekday of DTSTART. */
    val byDay: Set<DayOfWeek> = emptySet(),
    /** UNTIL, inclusive. */
    val until: Instant? = null,
    val count: Int? = null,
    /** EXDATE with time, in plan wall-clock time. */
    val exdates: Set<LocalDateTime> = emptySet(),
    /** EXDATE given as date only. */
    val exdays: Set<LocalDate> = emptySet(),
) {
    enum class Frequency { DAILY, WEEKLY }

    fun excludes(start: LocalDateTime) = start in exdates || start.toLocalDate() in exdays
}

/** CATEGORIES "Vorlesung (V)" -> name "Vorlesung", short "V". */
data class CourseType(val name: String, val short: String) {
    companion object {
        private val withShort = Regex("^(.*?)\\s*\\(([^()]+)\\)\\s*$")

        fun parse(category: String): CourseType {
            val text = category.trim()
            if (text.isEmpty()) return CourseType("", "")
            val m = withShort.find(text)
            return if (m != null && m.groupValues[1].isNotBlank()) {
                CourseType(m.groupValues[1], m.groupValues[2].trim())
            } else {
                CourseType(text, text.take(1).uppercase())
            }
        }
    }
}

/**
 * Identity of a course across re-imports.
 *
 * Hypothesis (holds for every event of the S3 export, 2026-10): the LSF UID has 12 digits, the first six
 * are the course ("Veranstaltung") id and the last six the appointment ("Termin") id. All appointments of a
 * course share the prefix, including moved ones. Different event types of one subject (VL, Übung, ...)
 * are different courses. UIDs of another shape fall back to the normalized title.
 */
object CourseKeys {
    private val lsfUid = Regex("^(\\d{6})(\\d{6})$")
    private val spaces = Regex("\\s+")

    fun courseId(uid: String): String? = lsfUid.find(uid.trim())?.groupValues?.get(1)

    fun of(uid: String, title: String): String = courseId(uid)?.let { "lsf:$it" } ?: titleKey(title)

    fun titleKey(title: String): String = "title:" + title.trim().replace(spaces, " ").lowercase()
}
