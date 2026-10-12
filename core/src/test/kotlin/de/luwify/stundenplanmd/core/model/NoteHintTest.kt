package de.luwify.stundenplanmd.core.model

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.ShareResult
import de.luwify.stundenplanmd.core.share.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The "shortened in the QR code" hint appears only when a QR import really shortened or dropped a note. */
class NoteHintTest {
    private fun shared(events: List<Event>) = SharedPlan(
        "0123456789abcdef", "Max", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 11), events, emptyMap(), complete = true,
    )

    private fun decode(result: ShareResult) = (result as ShareResult.Ok).plan.events

    private val single = Event(
        uid = "222222000001", summary = "Test", title = "Test", notes = "Raumänderung",
        start = LocalDateTime.parse("2026-10-14T13:00"), end = LocalDateTime.parse("2026-10-14T15:00"),
    )

    @Test
    fun icsImportHasNoHints() {
        assertTrue(Fixture.events.any { it.notes.isNotBlank() })
        Fixture.events.forEach { assertEquals(it.uid, NoteHint.NONE, it.noteHint) }
    }

    @Test
    fun fileImportHasNoHints() {
        val back = decode(ShareCodec.parse(ShareCodec.file(shared(Fixture.events))))
        assertTrue(back.any { it.notes.isNotBlank() })
        back.forEach { assertEquals(it.uid, NoteHint.NONE, it.noteHint) }
    }

    @Test
    fun qrWithShortNotesOnlyHasNoHints() {
        val back = decode(ShareCodec.parse(ShareCodec.qrText(shared(listOf(single)))))
        assertEquals("Raumänderung", back.single().notes)
        assertEquals(NoteHint.NONE, back.single().noteHint)
    }

    @Test
    fun qrHintsOnlyTheShortenedNotes() {
        val long = single.copy(uid = "222222000002", notes = "Bitte Studierendenausweis mitbringen. ".repeat(5), start = single.start.plusDays(1), end = single.end.plusDays(1))
        val series = Fixture.events.first { it.recurrence != null }.copy(notes = "Notiz der Reihe")
        val plain = Fixture.events.first { it.recurrence != null && it.notes.isBlank() && it.uid != series.uid }
        val back = decode(ShareCodec.parse(ShareCodec.qrText(shared(listOf(single, long, series, plain))))).associateBy { it.uid }
        assertEquals(NoteHint.NONE, back.getValue(single.uid).noteHint)
        assertEquals(NoteHint.SHORTENED, back.getValue(long.uid).noteHint)
        assertEquals(NoteHint.NOT_SENT, back.getValue(series.uid).noteHint)
        assertEquals(NoteHint.NONE, back.getValue(plain.uid).noteHint)
    }
}
