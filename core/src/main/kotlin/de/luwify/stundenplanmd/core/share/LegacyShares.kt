package de.luwify.stundenplanmd.core.share

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Recurrence
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.Expansion
import java.time.LocalTime

/**
 * Friend plans shared by apps before 1.2.2. Those counted every weekly INTERVAL=2 series 14 days from its first date and
 * sent it as slot count + cancelled-slot bitmask; EXDATEs off that rhythm were dropped. After a year with a week 53 the
 * 14-day dates are a week off LSF's even/odd calendar weeks, so the cancellations LSF set there are lost and the end is
 * unclear. [convert] re-expands such series in the calendar-week parity of their first date and keeps a cancelled date
 * only where it is one of the new dates.
 */
object LegacyShares {
    data class Converted(val events: List<Event>, val incomplete: Boolean)

    private val LAST_MINUTE = LocalTime.of(23, 59)

    /**
     * [events] as an older decoder built them from a share (a plain series: weekly INTERVAL=2, no BYDAY, no COUNT, UNTIL
     * at 23:59 of its last date). [incomplete]: at least one series changed, so cancellations after the week 53 may be
     * missing and its end is a guess. Manual courses (`manual-`) meant every 14 days in those versions and stay so.
     */
    fun convert(events: List<Event>): Converted {
        var incomplete = false
        val out = events.map { e ->
            val r = e.recurrence
            if (r == null || !isOldPlain(r)) return@map e
            if (e.isManual) return@map e.copy(recurrence = r.copy(rhythm = WeekRhythm.EVERY_14_DAYS))
            val old = Expansion.slots(e.copy(recurrence = r.copy(rhythm = WeekRhythm.EVERY_14_DAYS)))
            val parity = WeekRhythm.parityOf(e.start.toLocalDate())
            val asParity = Expansion.slots(e.copy(recurrence = r.copy(rhythm = parity)))
            if (old.isEmpty() || asParity.map { it.start } == old.map { it.start }) return@map e
            incomplete = true
            // LSF's UNTIL was almost always on the parity rhythm: end on its first date on or after the old last one.
            val lastOld = old.last().start
            val open = e.copy(recurrence = r.copy(until = null, rhythm = parity))
            val last = Expansion.slots(open).map { it.start }.firstOrNull { !it.isBefore(lastOld) } ?: lastOld
            val until = last.toLocalDate().atTime(LAST_MINUTE).atZone(PlanZone).toInstant()
            val dates = Expansion.slots(e.copy(recurrence = r.copy(until = until, rhythm = parity))).map { it.start }.toSet()
            val cancelled = old.filter { it.cancelled }.map { it.start }.filter { it in dates }.toSet()
            val converted = e.copy(recurrence = r.copy(until = until, exdates = cancelled))
            // Without a rhythm the dates must read as the parity one; set it if UNTIL alone doesn't say so.
            if (Expansion.rhythmOf(converted) == parity) converted else converted.copy(recurrence = converted.recurrence!!.copy(rhythm = parity))
        }
        return Converted(out, incomplete)
    }

    private fun isOldPlain(r: Recurrence): Boolean =
        r.biweekly && r.byDay.isEmpty() && r.count == null && r.rhythm == null && r.exdays.isEmpty() &&
            r.until?.atZone(PlanZone)?.toLocalTime() == LAST_MINUTE
}
