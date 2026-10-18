package de.luwify.stundenplanmd.core.share

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Location
import de.luwify.stundenplanmd.core.model.Recurrence
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.core.schedule.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.util.zip.Deflater

class ShareCodecTest {
    private val settings = mapOf(
        "lsf:111111" to CourseSetting("lsf:111111", "x", subject = null, shortName = "N4", color = 0x3366CC),
        "title:frei" to CourseSetting("title:frei", "Frei", subject = "own:title:frei", shortName = null, color = 0xFF0000),
    )

    private fun plan(events: List<Event> = Fixture.events) = SharedPlan(
        personId = "0123456789abcdef",
        name = "Max Müller",
        exportedOn = LocalDate.of(2026, 10, 12),
        planDate = LocalDate.of(2026, 10, 11),
        events = events,
        settings = settings + Fixture.events.take(3).associate { it.courseKey to CourseSetting(it.courseKey, it.title, shortName = "S", color = 0x123456) },
        complete = false,
    )

    /** What the views show: occurrences with status and changes, and cancellations. */
    private fun fingerprint(events: List<Event>): Pair<List<String>, List<String>> {
        val s = Schedule(events)
        return s.occurrences.map { o ->
            val status = when (val st = o.status) {
                is Status.Changed -> "changed ${st.changes} from ${st.original.start}"
                else -> st.toString()
            }
            listOf(o.event.uid, o.start, o.end, status, o.event.title, o.event.category, o.event.location, o.event.lecturers).toString()
        }.sorted() to s.cancellations.map { "${it.event.uid} ${it.start}" }.sorted()
    }

    private fun ok(result: ShareResult) = (result as? ShareResult.Ok ?: error("not ok: $result")).plan

    @Test
    fun base45MatchesRfc9285() {
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
        assertEquals("UJCLQE7W581", Base45.encode("base-45".toByteArray()))
        assertEquals("ietf!", Base45.decode("QED8WEX0")!!.decodeToString())
        val all = ByteArray(256) { it.toByte() }
        assertTrue(all.contentEquals(Base45.decode(Base45.encode(all))))
        assertEquals(null, Base45.decode("GGW")) // 65535 + 1
        assertEquals(null, Base45.decode("a"))
    }

    @Test
    fun qrRoundTripKeepsTheSchedule() {
        val text = ShareCodec.qrText(plan())
        assertTrue(text.startsWith("SPMD:"))
        assertTrue(text.all { it in Base45.ALPHABET })
        assertTrue("QR text ${text.length} chars", text.length < ShareCodec.QR_MAX_CHARS)
        val back = ok(ShareCodec.parse(text))
        assertEquals("0123456789abcdef", back.personId)
        assertEquals("Max Müller", back.name)
        assertEquals(LocalDate.of(2026, 10, 12), back.exportedOn)
        assertEquals(LocalDate.of(2026, 10, 11), back.planDate)
        assertFalse(back.complete)
        // A saved QR text (a file starting with "SPMD:", which begins like the binary magic) reads the same.
        assertEquals(back, ok(ShareCodec.parse(text.toByteArray())))
        assertEquals(back, ok(ShareCodec.parse("\uFEFF$text\r\n".toByteArray())))
        assertEquals(fingerprint(Fixture.events), fingerprint(back.events))
        assertEquals(Fixture.events.map { it.uid }.sorted(), back.events.map { it.uid }.sorted())
        val s = back.settings
        assertEquals(CourseSetting("title:frei", "", "own:title:frei", null, 0xFF0000), s["title:frei"])
        assertEquals(0x3366CC, s["lsf:111111"]?.color)
        val first = Fixture.events.first()
        assertEquals("S", s[first.courseKey]?.shortName)
        assertEquals(0x123456, s[first.courseKey]?.color)
        assertEquals(first.title, s[first.courseKey]?.title)
    }

    @Test
    fun qrShortensNotesAndFlagsThem() {
        val long = "Zwischentest Wiederholung. " + "Bitte Studierendenausweis mitbringen. ".repeat(5)
        val single = Event(uid = "222222000001", summary = "Test", title = "Test", notes = long, start = LocalDateTime.parse("2026-10-14T13:00"), end = LocalDateTime.parse("2026-10-14T15:00"))
        val short = single.copy(uid = "222222000002", notes = "Raumänderung", start = single.start.plusDays(1), end = single.end.plusDays(1))
        val series = Fixture.events.first { it.recurrence != null }.copy(notes = "Lange Notiz")
        val back = ok(ShareCodec.parse(ShareCodec.qrText(plan(listOf(single, short, series))))).events.associateBy { it.uid }

        val cut = back.getValue(single.uid)
        assertTrue(cut.notesShortened)
        assertEquals(ShareCodec.QR_NOTE_CHARS, cut.notes.length)
        assertTrue(cut.notes.endsWith("…") && long.startsWith(cut.notes.dropLast(1)))
        assertEquals("Raumänderung", back.getValue(short.uid).notes)
        assertFalse(back.getValue(short.uid).notesShortened)
        assertEquals("", back.getValue(series.uid).notes)
        assertTrue(back.getValue(series.uid).notesShortened)
    }

    @Test
    fun fileKeepsEverything() {
        val bytes = ShareCodec.file(plan())
        assertTrue(ShareCodec.looksLikeShare(bytes))
        val back = ok(ShareCodec.parse(bytes))
        assertTrue(back.complete)
        assertEquals(fingerprint(Fixture.events), fingerprint(back.events))
        val original = Fixture.events.associateBy { it.uid }
        for (e in back.events) {
            val o = original.getValue(e.uid)
            assertEquals(o.summary, e.summary)
            assertEquals(o.notes, e.notes)
            assertEquals(o.location, e.location)
            assertFalse(e.notesShortened)
        }
    }

    @Test
    fun oddRulesUidsAndTimesSurviveExactly() {
        val odd = Event(
            uid = "not-an-lsf-uid@example",
            summary = "Block (A, B)", title = "Block", lecturers = listOf("A", "B"), category = "Seminar (S)",
            location = Location("Hörsaal | Nord", null, "Hörsaal | Nord"),
            start = LocalDateTime.parse("2026-10-12T08:10"), end = LocalDateTime.parse("2026-10-12T09:35"),
            recurrence = Recurrence(
                frequency = Recurrence.Frequency.WEEKLY, interval = 1, byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                count = 7, exdates = setOf(LocalDateTime.parse("2026-10-15T08:10")), exdays = setOf(LocalDate.parse("2026-10-19")),
            ),
        )
        val daily = odd.copy(
            uid = "333333000009", summary = "Daily", title = "Daily", lecturers = emptyList(), location = null,
            recurrence = Recurrence(frequency = Recurrence.Frequency.DAILY, interval = 2, until = Instant.parse("2026-10-30T22:59:00Z")),
        )
        val back = ok(ShareCodec.parse(ShareCodec.file(plan(listOf(odd, daily))))).events.associateBy { it.uid }
        assertEquals(odd, back.getValue(odd.uid))
        assertEquals(daily, back.getValue(daily.uid))
    }

    @Test
    fun locationsRebuildExactlyOrFallBackToRaw() {
        for (raw in Fixture.events.mapNotNull { it.location?.raw } + listOf("K07 - K07-H2 (K07-215) (120 Pl.)", "K09B - K09B-110", "Mensa", "X - Y-1  (3 Pl.)")) {
            assertEquals(raw, ShareCodec.rawLocation(ShareCodec.shortLocation(raw)))
        }
        assertEquals("K18-012||60", ShareCodec.shortLocation("K18 - K18-012 (60 Pl.)"))
    }

    @Test
    fun ownChangesTravelWithoutNotesAndOnlyThenNeedVersion2() {
        // Without changes the payload stays version 1: older apps still import it.
        assertEquals(1, ShareCodec.payload(plan(), complete = true)[0].toInt())
        val series = Fixture.events.first { it.recurrence != null }
        val single = Fixture.events.first { it.recurrence == null }
        val changes = listOf(
            PersonalChange(series.uid, series.start.toLocalDate().plusWeeks(1), title = "x", note = "privat", cancelled = true),
            PersonalChange(
                series.uid, series.start.toLocalDate().plusWeeks(2), series = true, until = series.start.toLocalDate().plusWeeks(6),
                st = true, room = "K22-A", start = LocalTime.of(9, 15), end = LocalTime.of(10, 50),
            ),
            PersonalChange(single.uid, single.start.toLocalDate(), st = false),
            PersonalChange(single.uid, single.start.toLocalDate().minusDays(3), note = "nur Notiz"),
        )
        val withChanges = plan().copy(changes = changes)
        val expected = listOf(
            changes[0].copy(title = "", note = null),
            changes[1].copy(title = ""),
            changes[2],
        )
        for (payload in listOf(ShareCodec.payload(withChanges, complete = true), ShareCodec.payload(withChanges, complete = false))) {
            assertEquals(2, payload[0].toInt())
            assertEquals(expected, ok(ShareCodec.decode(payload)).changes)
        }
        // Only notes: nothing to send, still version 1.
        assertEquals(1, ShareCodec.payload(plan().copy(changes = listOf(changes[3])), complete = true)[0].toInt())
        // The same schedule on the receiving side.
        val back = ok(ShareCodec.parse(ShareCodec.file(withChanges)))
        assertEquals(Schedule(Fixture.events, expected).cancellations.size, Schedule(back.events, back.changes).cancellations.size)
    }

    @Test
    fun manualCoursesAndStTravelOnlyInVersion2() {
        val manual = de.luwify.stundenplanmd.core.schedule.ManualCourses.event(
            de.luwify.stundenplanmd.core.model.ManualCourse(
                id = de.luwify.stundenplanmd.core.model.ManualCourse.newId("00000000000000aa"), title = "Lerngruppe",
                room = "Bibliothek", rhythm = de.luwify.stundenplanmd.core.model.ManualCourse.Rhythm.WEEKLY,
                startDate = LocalDate.of(2026, 10, 12), endDate = LocalDate.of(2026, 12, 21),
                start = LocalTime.of(17, 0), end = LocalTime.of(18, 30), st = true,
            ),
        ).let { it.copy(recurrence = it.recurrence!!.copy(exdates = setOf(it.start.plusWeeks(2)))) }
        val withManual = plan(Fixture.events + manual)
        for (payload in listOf(ShareCodec.payload(withManual, complete = true), ShareCodec.payload(withManual, complete = false))) {
            assertEquals(2, payload[0].toInt())
            val back = ok(ShareCodec.decode(payload))
            assertEquals(manual.copy(summary = back.events.single { it.isManual }.summary), back.events.single { it.isManual })
            assertEquals(fingerprint(Fixture.events + manual), fingerprint(back.events))
            assertTrue(back.events.filter { !it.isManual }.none { it.st })
        }
        // An s.t. flag alone also needs version 2.
        assertEquals(2, ShareCodec.version(plan(Fixture.events.mapIndexed { i, e -> if (i == 0) e.copy(st = true) else e })))
        assertEquals(1, ShareCodec.version(plan()))
    }

    @Test
    fun rejectsOtherInput() {
        assertEquals(ShareResult.NotAShare, ShareCodec.parse("BEGIN:VCALENDAR\r\n".toByteArray()))
        assertEquals(ShareResult.NotAShare, ShareCodec.parse("hello"))
        assertFalse(ShareCodec.looksLikeShare("BEGIN:VCALENDAR".toByteArray()))
        assertTrue(ShareCodec.looksLikeShare("SPMD:ABC".toByteArray()))

        val payload = ShareCodec.payload(plan(), complete = false)
        assertEquals(ShareResult.TooNew, ShareCodec.decode(byteArrayOf((ShareCodec.VERSION + 1).toByte()) + payload.drop(1)))
        assertTrue(ShareCodec.decode(payload.copyOf(payload.size / 2)) is ShareResult.Broken)
        assertTrue(ShareCodec.parse("SPMD:not base45 !") is ShareResult.Broken)
        assertTrue(ShareCodec.decode(byteArrayOf(1, 7, 7, 7)) is ShareResult.Broken)

        // A deflate bomb is cut off instead of filling memory.
        val bomb = ByteArrayOutputStream().apply {
            write(1)
            val d = Deflater(9, true); d.setInput(ByteArray(10_000_000)); d.finish()
            val buf = ByteArray(4096); while (!d.finished()) write(buf, 0, d.deflate(buf)); d.end()
        }.toByteArray()
        assertTrue(ShareCodec.decode(bomb) is ShareResult.Broken)
    }

    @Test
    fun qrSaysWhenItLeavesSomethingOut() {
        val base = Event(
            uid = "222222000001", summary = "Test (Meier, Schulz)", title = "Test", lecturers = listOf("Meier", "Schulz"),
            start = LocalDateTime.parse("2026-10-14T13:00"), end = LocalDateTime.parse("2026-10-14T15:00"),
        )
        assertFalse(ShareCodec.qrOmitsDetails(plan(listOf(base, base.copy(uid = "222222000002", notes = "Raum ändert sich")))))
        assertFalse(ShareCodec.qrOmitsDetails(plan(listOf(base.copy(summary = "Test  (Meier,Schulz)")))))
        // A long note of a single event, any note of a series, a SUMMARY with more than title and lecturers.
        assertTrue(ShareCodec.qrOmitsDetails(plan(listOf(base.copy(notes = "x".repeat(ShareCodec.QR_NOTE_CHARS + 1))))))
        assertTrue(ShareCodec.qrOmitsDetails(plan(listOf(base.copy(notes = "kurz", recurrence = Recurrence(until = Instant.parse("2026-11-30T22:59:00Z")))))))
        assertTrue(ShareCodec.qrOmitsDetails(plan(listOf(base.copy(summary = "Test (Meier, Schulz) Gruppe B")))))
        // The real-shaped fixture has series notes: the hint is shown.
        assertTrue(ShareCodec.qrOmitsDetails(plan()))
    }

    @Test
    fun typePartsOfShortNamesNeedVersion3AndRoundTrip() {
        val typed = plan().let { p -> p.copy(settings = p.settings + ("lsf:111111" to p.settings.getValue("lsf:111111").copy(typeShortName = "VL"))) }
        assertEquals(1, ShareCodec.version(plan()))
        assertEquals(3, ShareCodec.version(typed))
        for (bytes in listOf(ShareCodec.file(typed), ShareCodec.qrText(typed).toByteArray())) {
            val read = ok(ShareCodec.parse(bytes))
            assertEquals("VL", read.settings.getValue("lsf:111111").typeShortName)
            assertEquals("N4", read.settings.getValue("lsf:111111").shortName)
            assertEquals(typed.settings.mapValues { it.value.typeShortName }, read.settings.mapValues { it.value.typeShortName }.filterKeys { it in typed.settings })
        }
        // Only a type part, nothing else set for that course: still sent.
        val only = plan().copy(settings = mapOf("title:frei" to CourseSetting("title:frei", "Frei", typeShortName = "Ü")))
        assertEquals("Ü", ok(ShareCodec.parse(ShareCodec.file(only))).settings.getValue("title:frei").typeShortName)
    }
}
