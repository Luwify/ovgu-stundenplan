package de.luwify.stundenplanmd.core.store

import de.luwify.stundenplanmd.core.compat.CompatEdits
import de.luwify.stundenplanmd.core.compat.CompatProbe
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.WeekRhythm
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web app's edit sequence ([CompatEdits]) on the JVM: every step changes something, and the result survives a backup. */
class StoreEditsTest {
    private val ics = File("src/test/resources/lsf_scrubbed.ics").readText()

    @Test
    fun everyStepChangesTheStateAndTheResultSurvivesABackup() {
        val steps = CompatEdits.run(ics)
        var before = CompatProbe.rich(ics)
        for ((name, state) in steps) {
            assertNotEquals("step $name changed nothing", before, state)
            before = state
        }
        val e = steps.last().second
        assertEquals(WeekRhythm.EVEN_WEEKS, e.rhythmsOf(StoredData.ME).values.single())
        assertEquals(listOf(CompatEdits.MANUAL_ID), e.manualCourses.map { it.id })
        assertEquals(listOf(CompatEdits.icsFriendId(), CompatProbe.FRIEND_ID), e.friends.map { it.id })
        assertEquals("Kimmy", e.person(CompatEdits.icsFriendId())!!.displayName)
        assertTrue("from the .ics friend", e.addedEvents.any { it.uid.startsWith("70") })
        assertTrue("from the added .ics", e.addedEvents.any { it.uid.startsWith("60") })
        assertTrue(e.hiddenOf(StoredData.ME).any { it.kind == Hidden.Kind.DATE })
        assertEquals(false, e.share.withChanges)
        // The manual course adds a Saturday column.
        val week = e.mySchedule().week(LocalDate.of(2026, 10, 12))
        assertTrue(week.days.any { it.date.dayOfWeek == DayOfWeek.SATURDAY && it.placed.isNotEmpty() })
        val backup = BackupFormat.encode(e, ics, ThemeBackup("system", "system", 0x7839D0, false, ""), "system", UpdatesBackup(), DisplayBackup(), CompatProbe.NOW)
        assertEquals(e, (BackupFormat.decode(backup) as BackupFileResult.Ok).backup.data)
    }
}
