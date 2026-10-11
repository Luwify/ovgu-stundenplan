package de.luwify.stundenplanmd.core.ical

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.model.CourseType
import de.luwify.stundenplanmd.core.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class LsfImportTest {
    private fun dt(s: String) = LocalDateTime.parse(s)

    @Test
    fun fixtureHasAllEventsAndNoWarnings() {
        assertEquals(24, Fixture.events.size)
        assertEquals("QIS-LSF HIS eG", Fixture.result.source)
        assertEquals(emptyList<String>(), Fixture.result.warnings)
    }

    @Test
    fun fixtureWeeklySeriesWithTrailingCommaExdates() {
        val e = Fixture.event("900011500666")
        assertEquals(dt("2026-10-12T13:00"), e.start)
        assertEquals(dt("2026-10-12T15:00"), e.end)
        assertEquals(
            Recurrence(
                interval = 1,
                byDay = setOf(DayOfWeek.MONDAY),
                until = Instant.parse("2027-01-25T23:59:00Z"),
                exdates = setOf(dt("2026-12-21T13:00"), dt("2026-12-28T13:00")),
            ),
            e.recurrence,
        )
        assertEquals("Angewandte Statik 2 - Übung - VT", e.title)
        assertEquals("Angewandte Statik 2 -  Übung - VT (Albers)", e.summary)
        assertEquals(CourseType("Übung", "Ü"), e.type)
        assertEquals("K18-012", e.location?.room)
        assertEquals("lsf:900011", e.courseKey)
    }

    @Test
    fun fixtureSingleEventWithEmptyExdate() {
        val e = Fixture.event("900121500777")
        assertNull(e.recurrence)
        assertEquals(dt("2027-01-13T13:00"), e.start)
        assertEquals("Zwischentest Wiederholung", e.notes)
        assertEquals(emptyList<String>(), e.lecturers)
        assertEquals("lsf:900121", e.courseKey)
    }

    @Test
    fun fixtureMultiLineHtmlDescription() {
        val notes = Fixture.event("900165500222").notes
        assertTrue(notes, notes.startsWith("Die Arbeitsschutzbelehrung und Einweisung findet am\nDonnerstag den 15.10.2026 um 13:00 in Raum K21-305\nstatt."))
        assertTrue(notes, "\n\nBei grippeähnlichen Symptomen" in notes)
        assertTrue(notes, "dozent@example.org ." in notes)
        assertTrue('<' !in notes && '\r' !in notes && ' ' !in notes)
        assertNull(Fixture.event("900165500222").location)
    }

    @Test
    fun fixtureTypes() {
        assertEquals(
            setOf("V", "Ü", "V/Ü", "P", "S", "SLV"),
            Fixture.events.map { it.type.short }.toSet(),
        )
        assertEquals(CourseType("Sonst. LV", "SLV"), Fixture.event("900033500037").type)
    }

    private fun one(vararg lines: String) = LsfImport.parse(
        (listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT") + lines + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n"),
    )

    @Test
    fun utcAndForeignZonesBecomeBerlinWallClock() {
        val e = one("UID:1", "DTSTART:20261012T110000Z", "DTEND;TZID=Europe/London:20261012T140000").events.single()
        assertEquals(dt("2026-10-12T13:00"), e.start)
        assertEquals(dt("2026-10-12T15:00"), e.end)
    }

    @Test
    fun durationDateOnlyUntilCountAndMultipleByDay() {
        val e = one(
            "UID:1", "DTSTART;TZID=Europe/Berlin:20261012T090000", "DURATION:PT1H30M",
            "RRULE:FREQ=WEEKLY;BYDAY=MO,2WE;UNTIL=20261130;COUNT=5", "EXDATE;VALUE=DATE:20261014,", "EXDATE:20261019T090000",
        ).events.single()
        assertEquals(dt("2026-10-12T10:30"), e.end)
        val r = e.recurrence!!
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), r.byDay)
        assertEquals(5, r.count)
        assertEquals(Instant.parse("2026-11-30T22:59:59Z"), r.until)
        assertEquals(setOf(LocalDate.parse("2026-10-14")), r.exdays)
        assertEquals(setOf(dt("2026-10-19T09:00")), r.exdates)
    }

    @Test
    fun badEventsAreSkippedWithWarnings() {
        val r = LsfImport.parse(
            listOf(
                "BEGIN:VCALENDAR",
                "BEGIN:VEVENT", "UID:a", "SUMMARY:kein Start", "END:VEVENT",
                "BEGIN:VEVENT", "UID:b", "DTSTART;VALUE=DATE:20261012", "END:VEVENT",
                "BEGIN:VEVENT", "UID:c", "DTSTART:2026", "END:VEVENT",
                "BEGIN:VEVENT", "UID:d", "DTSTART:20261012T090000", "RRULE:FREQ=MONTHLY", "END:VEVENT",
                "END:VCALENDAR",
            ).joinToString("\n"),
        )
        assertEquals(listOf("d"), r.events.map { it.uid })
        assertNull(r.events.single().recurrence)
        assertEquals(dt("2026-10-12T10:00"), r.events.single().end)
        assertEquals(4, r.warnings.size)
    }

    @Test
    fun notACalendar() {
        assertEquals(emptyList<Any>(), LsfImport.parse("hello").events)
        assertEquals(emptyList<Any>(), LsfImport.parse("").events)
    }
}
