package de.luwify.stundenplanmd.core.schedule

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.ical.LsfText
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PersonalTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun t(s: String) = LocalTime.parse(s)

    /** Weekly on Wednesdays 11–13 from 14.10. to 9.12.2026 (9 dates), room K30-H1. */
    private val series = Event(
        uid = "500001000001", summary = "Numerik N4 (VL)", title = "Numerik N4 (VL)", category = "Vorlesung (V)",
        location = LsfText.location("K30 - K30-H1 (200 Pl.)"),
        start = LocalDateTime.parse("2026-10-14T11:00"), end = LocalDateTime.parse("2026-10-14T13:00"),
        recurrence = Recurrence(interval = 1, until = Instant.parse("2026-12-09T23:59:00Z")),
    )

    private fun schedule(vararg changes: PersonalChange) = Schedule(listOf(series), changes.toList())
    private fun on(s: Schedule, date: String) = s.on(d(date)).single()
    private fun change(date: String, series: Boolean = false) = PersonalChange(series = series, uid = this.series.uid, date = d(date), title = "Numerik N4 (VL)")

    @Test
    fun noChangesLeaveTheScheduleAlone() {
        val s = schedule()
        assertEquals(9, s.occurrences.size)
        assertTrue(s.occurrences.all { it.personal == null })
    }

    @Test
    fun singleNoteStAndRoomApplyToOneDateOnly() {
        val s = schedule(change("2026-10-21").copy(note = "Taschenrechner", st = true, room = "K22-A"))
        val o = on(s, "2026-10-21")
        assertEquals("Taschenrechner", o.personal!!.note)
        assertTrue(o.personal!!.st)
        assertEquals("K22-A", o.room)
        assertTrue(o.personal!!.applied)
        assertNull(on(s, "2026-10-28").personal)
        assertEquals("K30-H1", on(s, "2026-10-28").room)
    }

    @Test
    fun seriesAppliesFromItsDateAndSingleWinsFieldByField() {
        val s = schedule(
            change("2026-10-28", series = true).copy(note = "Skript mitbringen", st = true),
            change("2026-11-04").copy(note = "", st = false),
        )
        assertNull(on(s, "2026-10-21").personal)
        assertEquals("Skript mitbringen", on(s, "2026-10-28").personal!!.note)
        assertTrue(on(s, "2026-10-28").personal!!.noteFromSeries)
        // "" and false hide the series values on that one date.
        assertNull(on(s, "2026-11-04").personal!!.note)
        assertFalse(on(s, "2026-11-04").personal!!.st)
        assertTrue(on(s, "2026-11-11").personal!!.st)
    }

    @Test
    fun timeChangeMovesTheOccurrence() {
        val s = schedule(change("2026-10-21").copy(start = t("12:00"), end = t("14:00")))
        val o = on(s, "2026-10-21")
        assertEquals(LocalDateTime.parse("2026-10-21T12:00"), o.start)
        assertEquals(LocalDateTime.parse("2026-10-21T14:00"), o.end)
        assertTrue(o.personal!!.timeChanged)
        assertEquals(t("11:00"), o.personal!!.original.start)
    }

    @Test
    fun cancelledByMeIsACancellationButNoLectureFreeDay() {
        val s = schedule(change("2026-10-21").copy(cancelled = true))
        assertTrue(s.on(d("2026-10-21")).isEmpty())
        val c = s.cancellations.single()
        assertTrue(c.byMe)
        assertFalse(s.day(d("2026-10-21")).free)
        assertFalse(s.week(d("2026-10-19")).free)
        assertEquals(8, s.occurrences.size)
    }

    @Test
    fun cancelledByMeCountsForMatchingAndNowStatus() {
        val friend = Schedule(listOf(series))
        val mine = schedule(change("2026-10-21").copy(cancelled = true))
        val together = Together.of(mine, listOf("f" to friend))
        assertEquals(8, together.size)
        assertTrue(together.keys.none { it.start.toLocalDate() == d("2026-10-21") })
        // From the friend's side I am not there either.
        assertEquals(8, Together.of(friend, listOf("me" to mine)).size)
        val status = NowStatus.of(mine, LocalDateTime.parse("2026-10-21T11:30"))
        assertEquals(NowStatus.FreeToday, status)
    }

    @Test
    fun lsfChangesAreNeverHiddenByMine() {
        val fixture = Schedule(Fixture.events)
        val changed = fixture.occurrences.first { it.status is Status.Changed && Change.TIME in (it.status as Status.Changed).changes }
        val ref = changed.ref
        val mine = PersonalChange(ref.uid, ref.date, room = "K99-1", cancelled = true, note = "Raum laut Mail")
        val s = Schedule(Fixture.events, listOf(mine))
        val o = s.occurrences.single { it.event.uid == changed.event.uid && it.start == changed.start }
        // Still the official change, at LSF's time and room; mine is attached, marked overruled.
        assertTrue(o.status is Status.Changed)
        assertEquals(changed.event.location?.room, o.room)
        assertTrue(o.personal!!.overruled)
        assertEquals("Raum laut Mail", o.personal!!.note)
        assertEquals("K99-1", o.personal!!.current.room)
    }

    @Test
    fun lsfCancellationKeepsMyNoteNextToIt() {
        val cancelled = Schedule(Fixture.events).cancellations.first()
        val s = Schedule(Fixture.events, listOf(PersonalChange(cancelled.event.uid, cancelled.date, note = "Nachholtermin?", start = t("08:00"), end = t("09:00"))))
        val c = s.cancellations.first { it.event.uid == cancelled.event.uid && it.date == cancelled.date }
        assertFalse(c.byMe)
        assertEquals(cancelled.start, c.start)
        assertEquals("Nachholtermin?", c.personal!!.note)
        assertTrue(c.personal!!.overruled)
    }

    @Test
    fun editSingleStoresOnlyWhatDiffersFromTheSeries() {
        var changes = listOf(change("2026-10-14", series = true).copy(note = "Skript", room = "K22-A"))
        val p = on(Schedule(listOf(series), changes), "2026-10-21").personal!!
        changes = PersonalChanges.setSingle(changes, p, "N4", p.current.copy(st = true))
        val single = changes.single { !it.series }
        assertEquals(PersonalChange(series.uid, d("2026-10-21"), title = "N4", st = true), single)
        // Back to the inherited values: the single change disappears.
        val p2 = on(Schedule(listOf(series), changes), "2026-10-21").personal!!
        changes = PersonalChanges.setSingle(changes, p2, "N4", p2.inherited)
        assertEquals(1, changes.size)
    }

    @Test
    fun editSeriesEndsTheEarlierSeriesAndReplacesLaterOnes() {
        var changes = listOf(
            change("2026-10-14", series = true).copy(room = "K22-A"),
            change("2026-11-18", series = true).copy(st = true),
        )
        val p = on(Schedule(listOf(series), changes), "2026-11-04").personal!!
        changes = PersonalChanges.setSeries(changes, p, "N4", p.original.copy(note = "ab jetzt online"))
        assertEquals(
            listOf(
                change("2026-10-14", series = true).copy(room = "K22-A", until = d("2026-11-03")),
                PersonalChange(series.uid, d("2026-11-04"), series = true, title = "N4", note = "ab jetzt online"),
            ),
            changes,
        )
        val s = Schedule(listOf(series), changes)
        assertEquals("K22-A", on(s, "2026-10-28").room)
        assertEquals("K30-H1", on(s, "2026-11-04").room)
        assertFalse(on(s, "2026-11-18").personal!!.st)
        assertEquals("ab jetzt online", on(s, "2026-12-09").personal!!.note)
    }

    @Test
    fun cancelAndUndo() {
        val ref = OccurrenceRef(series.uid, d("2026-10-21"))
        val cancelled = PersonalChanges.setCancelled(emptyList(), ref, "N4", true)
        assertEquals(8, Schedule(listOf(series), cancelled).occurrences.size)
        assertEquals(emptyList<PersonalChange>(), PersonalChanges.setCancelled(cancelled, ref, "N4", false))
    }

    @Test
    fun reimportKeepsChangesAndListsOrphans() {
        val changes = listOf(
            change("2026-10-21").copy(note = "a"),
            change("2026-10-22").copy(note = "no such date"),
            change("2026-12-16", series = true).copy(st = true),
            PersonalChange("500001000999", d("2026-10-21"), title = "Gone", cancelled = true),
        )
        // Re-import: the same series, now one week shorter and with EXDATE on 21.10.: changes keyed by UID + date stay.
        val reimported = series.copy(recurrence = series.recurrence!!.copy(
            until = Instant.parse("2026-12-02T23:59:00Z"), exdates = setOf(LocalDateTime.parse("2026-10-21T11:00")),
        ))
        assertEquals(changes.drop(1), PersonalChanges.orphans(listOf(reimported), changes))
        // The LSF-cancelled 21.10. still carries my note.
        val c = Schedule(listOf(reimported), changes).cancellations.single()
        assertEquals("a", c.personal!!.note)
    }

    @Test
    fun notesAreNeverShared() {
        assertNull(change("2026-10-21").copy(note = "privat").forSharing())
        assertEquals(change("2026-10-21").copy(st = true), change("2026-10-21").copy(note = "privat", st = true).forSharing())
    }
}
