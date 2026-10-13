@file:UseSerializers(LocalDateSerializer::class, LocalTimeSerializer::class)

package de.luwify.stundenplanmd.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalTime

/**
 * Something I changed about my plan: a note, s.t., a cancellation for me, another room or time. Keyed by the
 * appointment [uid] and the original date of the occurrence (for an occurrence LSF moved: the date it was moved away
 * from), so it survives re-imports. A [series] change applies to every occurrence of [uid] from [date] on (up to
 * [until]); a single change wins over a series one field by field. Null fields leave the value as it is.
 */
@Serializable
data class PersonalChange(
    val uid: String,
    /** Original date of the occurrence; for a series the first date it applies to. */
    val date: LocalDate,
    val series: Boolean = false,
    /** Series only: last date it applies to (inclusive); null = open-ended. */
    val until: LocalDate? = null,
    /** Course title when made, shown in the cleanup list once the occurrence is gone. */
    val title: String = "",
    /** Null = as before; "" = no note (hides a series note on one date). Never shared. */
    val note: String? = null,
    /** true = s.t., false = c.t. (hides a series s.t. on one date), null = as before. */
    val st: Boolean? = null,
    /** Cancelled for me ("fällt aus"). */
    val cancelled: Boolean = false,
    val room: String? = null,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
) {
    fun covers(uid: String, date: LocalDate): Boolean = uid == this.uid && if (series) {
        !date.isBefore(this.date) && (until == null || !date.isAfter(until))
    } else {
        date == this.date
    }

    val isEmpty: Boolean get() = note == null && st == null && !cancelled && room == null && start == null && end == null

    /** What a friend gets: everything but the note. */
    fun forSharing(): PersonalChange? = copy(note = null).takeUnless { it.isEmpty }
}
