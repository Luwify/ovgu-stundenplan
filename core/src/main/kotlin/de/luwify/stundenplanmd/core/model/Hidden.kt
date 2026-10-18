@file:UseSerializers(LocalDateSerializer::class, LocalTimeSerializer::class)

package de.luwify.stundenplanmd.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalTime

/**
 * Something I hid in a plan (mine or a friend's, local to my device): a whole subject, one course (a type, e.g. the
 * Übung with all its groups), one group of a course, or one date. Nothing is deleted: the plan keeps the events, the
 * views leave them out ([de.luwify.stundenplanmd.core.schedule.Hiding]). Keyed by the same identities as course
 * settings and own changes (course key, subject key, appointment UID + original date), so a re-import keeps it.
 */
@Serializable
data class Hidden(
    val kind: Kind,
    /** [Kind.SUBJECT]: the subject key when hidden. */
    val subject: String? = null,
    /** [Kind.SUBJECT]: its course keys when hidden; any other kind: the course. */
    val courses: Set<String> = emptySet(),
    /** [Kind.GROUP]: the group's series UID ([de.luwify.stundenplanmd.core.schedule.Groups.baseUid]); [Kind.DATE]: the appointment UID. */
    val uid: String? = null,
    /** [Kind.DATE]: the original date (an occurrence LSF moved: the date it was moved away from). */
    val date: LocalDate? = null,
    /** For the list: subject name or course title, the type ("Übung"), day of week (1 = Monday), time and room. */
    val title: String = "",
    val type: String = "",
    val day: Int? = null,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val room: String? = null,
) {
    enum class Kind { SUBJECT, COURSE, GROUP, DATE }

    /** Same thing hidden (the descriptive fields aside). */
    fun sameTarget(other: Hidden): Boolean = kind == other.kind && when (kind) {
        Kind.SUBJECT -> subject == other.subject
        Kind.COURSE -> courses == other.courses
        Kind.GROUP -> courses == other.courses && uid == other.uid
        Kind.DATE -> uid == other.uid && date == other.date
    }

    /** [other] is narrower and inside this one, so hiding this makes it redundant. */
    fun covers(other: Hidden): Boolean = when (kind) {
        Kind.SUBJECT -> other.kind != Kind.SUBJECT && other.courses.isNotEmpty() && courses.containsAll(other.courses)
        Kind.COURSE -> (other.kind == Kind.GROUP || other.kind == Kind.DATE) && other.courses.isNotEmpty() && courses.containsAll(other.courses)
        Kind.GROUP -> other.kind == Kind.DATE && other.courses == courses && other.uid == uid
        Kind.DATE -> false
    }

    companion object {
        /** [list] with [item] added: the same target replaced, narrower items it covers dropped. */
        fun add(list: List<Hidden>, item: Hidden): List<Hidden> =
            list.filterNot { it.sameTarget(item) || item.covers(it) } + item

        fun remove(list: List<Hidden>, item: Hidden): List<Hidden> = list.filterNot { it.sameTarget(item) }
    }
}
