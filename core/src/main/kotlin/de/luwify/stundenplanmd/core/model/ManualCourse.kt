@file:UseSerializers(LocalDateSerializer::class, LocalTimeSerializer::class)

package de.luwify.stundenplanmd.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalTime

/**
 * A course I added by hand (my plan only), stored apart from the LSF import, which never touches it. [id] is local
 * ([ManualCourse.newId]: `manual-` + 16 hex digits) and is the UID of its event, so it can never collide with a
 * 12-digit LSF UID. Short name, color and subject are course settings like any other course's (key [courseKey]).
 */
@Serializable
data class ManualCourse(
    val id: String,
    val title: String,
    val type: ManualType = ManualType.LECTURE,
    val room: String? = null,
    val lecturer: String? = null,
    val note: String = "",
    val st: Boolean = false,
    val rhythm: Rhythm = Rhythm.ONCE,
    /** The only date ([Rhythm.ONCE]) or the first one; a series takes its weekday. */
    val startDate: LocalDate,
    /** Last date of a series (inclusive); ignored for [Rhythm.ONCE]. */
    val endDate: LocalDate? = null,
    val start: LocalTime,
    val end: LocalTime,
    /** Series: no dates in weeks (and on days) LSF marks lecture-free. */
    val skipFreeWeeks: Boolean = true,
) {
    /**
     * [BIWEEKLY] = every 14 days from the first date (its meaning since stored format 5); [EVEN_WEEKS]/[ODD_WEEKS] =
     * even/odd calendar weeks (format 6).
     */
    enum class Rhythm {
        ONCE, WEEKLY, EVEN_WEEKS, ODD_WEEKS, BIWEEKLY;

        /** The rhythm of the series event (null = weekly or once). */
        val weekRhythm: WeekRhythm?
            get() = when (this) {
                EVEN_WEEKS -> WeekRhythm.EVEN_WEEKS
                ODD_WEEKS -> WeekRhythm.ODD_WEEKS
                BIWEEKLY -> WeekRhythm.EVERY_14_DAYS
                ONCE, WEEKLY -> null
            }
    }

    val courseKey: String get() = CourseKeys.MANUAL_KEY_PREFIX + id

    companion object {
        const val ID_PREFIX = "manual-"

        fun isManual(uid: String) = uid.startsWith(ID_PREFIX)

        /** [hex16]: 16 random hex digits (the app takes them from SecureRandom). */
        fun newId(hex16: String): String {
            require(hex16.matches(Regex("[0-9a-f]{16}"))) { "id" }
            return ID_PREFIX + hex16
        }
    }
}

/** The types offered for a manual course, as CATEGORIES text like LSF's. */
@Serializable
enum class ManualType(val category: String) {
    LECTURE("Vorlesung (V)"),
    EXERCISE("Übung (Ü)"),
    LAB("Praktikum (P)"),
    SEMINAR("Seminar (S)"),
    OTHER("Sonstiges (So)"),
}
