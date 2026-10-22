@file:UseSerializers(LocalDateTimeSerializer::class, LocalDateSerializer::class, InstantSerializer::class)

package de.luwify.stundenplanmd.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.IsoFields

/** All plan times are wall-clock times in this zone (the LSF export uses TZID=Europe/Berlin). */
val PlanZone: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Me or a friend whose plan was imported. A friend's [id] is the random id from their share (or a local one for a plan
 * imported from an .ics, [fromIcs]), [name] the name they shared (the name I gave an .ics friend); [localName] is my rename (kept when they share again); [favorite] is my heart on them.
 */
@Serializable
data class Person(val id: String, val name: String, val isMe: Boolean, val localName: String? = null, val favorite: Boolean = false) {
    val displayName: String get() = localName?.takeIf { it.isNotBlank() } ?: name

    /** My rename differs from the name they shared (or the .ics friend's first name): sharing them on needs a choice. */
    val renamed: Boolean get() = !isMe && localName?.isNotBlank() == true && localName.trim() != name.trim()

    /** A friend whose plan I imported from an .ics myself: a local id ([ICS_ID_PREFIX]), no share identity. */
    val fromIcs: Boolean get() = !isMe && id.startsWith(ICS_ID_PREFIX)

    companion object {
        /** `ics-` + 16 hex digits: never a share id (16 hex digits) or "me". */
        const val ICS_ID_PREFIX = "ics-"
    }
}

@Serializable
data class Plan(
    val personId: String,
    val importedAt: Instant,
    /** PRODID of the source file, e.g. "QIS-LSF HIS eG"; "share" for a friend's plan. */
    val source: String? = null,
    val events: List<Event>,
    /** Friend's plan: the day they shared it. */
    val exportedOn: LocalDate? = null,
    /** Friend's plan: the day they imported their .ics ("Stand"). Mine: see [importedAt]. */
    val planDate: LocalDate? = null,
    /** Friend's plan: their subjects, short names and colors, used as defaults (keyed by course key). */
    val sharedSettings: Map<String, CourseSetting> = emptyMap(),
    /** Friend's plan: their own cancellations, room/time changes and s.t. (no notes). */
    val sharedChanges: List<PersonalChange> = emptyList(),
    /**
     * Friend's plan: shared by an app before 1.2.2, whose biweekly series were converted to even/odd calendar weeks
     * ([de.luwify.stundenplanmd.core.share.LegacyShares]); some dates after a week 53 may differ. Shows a hint.
     */
    val legacyShare: Boolean = false,
    /** Friend's plan: dates they hid before sharing (appointment UID + date of the slot); never shown. */
    val sharedHidden: List<de.luwify.stundenplanmd.core.schedule.OccurrenceRef> = emptyList(),
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
    /** Starts s.t. (a manual course can say so; LSF has no such field). */
    val st: Boolean = false,
) {
    /** Added by hand ([ManualCourse]), not from LSF. */
    val isManual: Boolean get() = ManualCourse.isManual(uid)

    /** See [CourseKeys]. */
    val courseKey: String get() = CourseKeys.of(uid, title)
    val type: CourseType get() = CourseType.parse(category)

    /** Hint below the notes: only when a QR import really shortened or dropped them. */
    val noteHint: NoteHint
        get() = when {
            !notesShortened -> NoteHint.NONE
            notes.isBlank() -> NoteHint.NOT_SENT
            else -> NoteHint.SHORTENED
        }
}

enum class NoteHint { NONE, SHORTENED, NOT_SENT }

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
    /**
     * Weekly INTERVAL=2 only: the rhythm set by hand (a manual course, my override for a series); null = decided from
     * the data ([de.luwify.stundenplanmd.core.schedule.Expansion.rhythmOf]).
     */
    val rhythm: WeekRhythm? = null,
) {
    enum class Frequency { DAILY, WEEKLY }

    /** FREQ=WEEKLY;INTERVAL=2: LSF's "gKW"/"uKW" (or a plain 14-day rhythm, see [WeekRhythm]). */
    val biweekly: Boolean get() = frequency == Frequency.WEEKLY && interval == 2

    fun excludes(start: LocalDateTime) = start in exdates || start.toLocalDate() in exdays
}

/**
 * How a weekly INTERVAL=2 series is expanded. LSF exports "gerade/ungerade KW" (even/odd ISO calendar week) as
 * INTERVAL=2, which is not the same after a year with a week 53 (KW53 and KW1 are both odd): [EVEN_WEEKS] and
 * [ODD_WEEKS] follow the week number, [EVERY_14_DAYS] counts 14 days from DTSTART (RFC 5545).
 */
@Serializable
enum class WeekRhythm {
    EVEN_WEEKS,
    ODD_WEEKS,
    EVERY_14_DAYS,
    ;

    companion object {
        fun weekOf(date: LocalDate): Int = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

        /** The parity rhythm of the ISO week [date] is in. */
        fun parityOf(date: LocalDate): WeekRhythm = if (weekOf(date) % 2 == 0) EVEN_WEEKS else ODD_WEEKS
    }
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

    const val MANUAL_KEY_PREFIX = "manual:"

    /** LSF course id, a manual course's own key (never merged with anything by title), else the title. */
    fun of(uid: String, title: String): String = courseId(uid)?.let { "lsf:$it" }
        ?: if (ManualCourse.isManual(uid)) MANUAL_KEY_PREFIX + uid else titleKey(title)

    fun titleKey(title: String): String = "title:" + title.trim().replace(spaces, " ").lowercase()
}
