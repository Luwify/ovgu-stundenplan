package de.luwify.stundenplanmd.core.share

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.schedule.OccurrenceRef
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.core.schedule.Status
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.CRC32
import kotlin.random.Random

/** Share format 4 (the QR since 1.5) and its animated frames; reading versions 1 to 3 is in ShareCodecTest too. */
class QrFormat4Test {
    private val longNote = "Zwischentest Wiederholung. " + "Bitte Studierendenausweis mitbringen. ".repeat(6)

    /** Every kind of content: long notes on single dates and series, own changes, hidden dates, type short names. */
    private fun plan(): SharedPlan {
        val events = Fixture.events.mapIndexed { i, e -> if (i % 4 == 0) e.copy(notes = "$longNote ($i)") else e }
        val first = events.first { it.recurrence != null }
        val day = first.start.toLocalDate()
        return SharedPlan(
            personId = "0123456789abcdef", name = "Max Müller", exportedOn = LocalDate.of(2026, 11, 2), planDate = LocalDate.of(2026, 11, 1),
            events = events,
            settings = events.take(4).associate { it.courseKey to CourseSetting(it.courseKey, it.title, shortName = "S", typeShortName = "VL", color = 0x123456) },
            complete = true,
            changes = listOf(
                PersonalChange(first.uid, day.plusWeeks(1), cancelled = true),
                PersonalChange(first.uid, day.plusWeeks(2), series = true, room = "K99-001", st = true, start = LocalTime.of(9, 0), end = LocalTime.of(11, 0)),
            ),
            hidden = listOf(OccurrenceRef(first.uid, day.plusWeeks(3))),
        )
    }

    private fun ok(r: ShareResult) = (r as? ShareResult.Ok ?: error("not ok: $r")).plan

    private fun views(events: List<Event>, changes: List<PersonalChange>) =
        Schedule(events, changes).occurrences.map {
            val status = (it.status as? Status.Changed)?.let { c -> "changed ${c.changes} from ${c.original.start}" } ?: it.status.toString()
            listOf(it.event.uid, it.start, it.end, status, it.event.notes, it.event.summary, it.personal?.current, it.personal?.ref).toString()
        }.sorted()

    @Test fun roundTripKeepsEverythingUnshortened() {
        val plan = plan()
        val text = ShareCodec.qrText(plan)
        assertTrue(text.startsWith("SPMD:") && text.substring(5).all { it in Base45.ALPHABET })
        val back = ok(ShareCodec.parse(text))
        assertTrue(back.complete)
        assertEquals(plan.events.map { it.notes }.sorted(), back.events.map { it.notes }.sorted())
        assertTrue(back.events.none { it.notesShortened })
        assertEquals(plan.events.map { it.summary }.sorted(), back.events.map { it.summary }.sorted())
        assertEquals(views(plan.events, plan.changes), views(back.events, back.changes))
        assertEquals(plan.changes, back.changes)
        assertEquals(plan.hidden, back.hidden)
        assertEquals(plan.settings.mapValues { it.value.typeShortName }, back.settings.mapValues { it.value.typeShortName }.filterKeys { it in plan.settings })
        // Same content as the file.
        assertEquals(ok(ShareCodec.parse(ShareCodec.file(plan))), back)
    }

    @Test fun dictionaryMakesItSmallerThanTheOldFullPayload() {
        val plan = plan()
        assertTrue(ShareCodec.qrPayload(plan).size < ShareCodec.payload(plan, complete = true).size)
    }

    /** The dictionary is part of format 4: changing it breaks every version-4 code. */
    @Test fun dictionaryIsFrozen() {
        assertEquals(1037, ShareDictionary.bytes.size)
        assertEquals(DICTIONARY_CRC, CRC32().apply { update(ShareDictionary.bytes) }.value)
    }

    @Test fun corruptionAndNewerVersions() {
        val payload = ShareCodec.qrPayload(plan())
        for (i in listOf(1, 4, 5, payload.size / 2, payload.size - 1)) {
            val bad = payload.copyOf().also { it[i] = (it[i].toInt() xor 0x04).toByte() }
            assertTrue("byte $i", ShareCodec.decode(bad) is ShareResult.Broken)
        }
        assertTrue(ShareCodec.decode(payload.copyOf(4)) is ShareResult.Broken)
        assertEquals(ShareResult.TooNew, ShareCodec.decode(byteArrayOf(6) + payload.copyOfRange(1, payload.size)))
    }

    /** Older apps read the first payload byte as the version and ask for an update when it is above 3. */
    @Test fun olderAppsSeeANewerVersion() {
        val plan = plan()
        assertEquals(4, Base45.decode(ShareCodec.qrText(plan).substring(5))!![0].toInt())
        val frame = ShareFrames.Encoder(ShareCodec.qrPayload(plan)).frame(7)
        assertEquals(ShareCodec.FRAME_TAG, Base45.decode(frame.substring(5))!![0].toInt())
        assertTrue(ShareCodec.FRAME_TAG > 3)
        // This app too: a frame handed to the normal import is "newer", never a broken plan.
        assertEquals(ShareResult.TooNew, ShareCodec.parse(frame))
    }

    @Test fun filesStayReadableForOlderApps() {
        val plan = plan()
        assertEquals(3, ShareCodec.file(plan)[4].toInt())
        assertEquals(1, ShareCodec.file(plan.copy(changes = emptyList(), hidden = emptyList(), settings = emptyMap()))[4].toInt())
    }

    @Test fun allOldFormatsStayReadable() {
        val base = plan()
        val v1 = base.copy(changes = emptyList(), hidden = emptyList(), settings = emptyMap())
        val v2 = v1.copy(changes = base.changes)
        val v3 = base
        for ((want, p) in listOf(1 to v1, 2 to v2, 3 to v3)) {
            val old = ShareCodec.legacyQrText(p)
            assertEquals(want, Base45.decode(old.substring(5))!![0].toInt())
            val back = ok(ShareCodec.parse(old))
            assertFalse(back.complete)
            assertEquals(p.changes, back.changes)
            assertEquals(views(p.events, p.changes).size, views(back.events, back.changes).size)
            // Old QR: long notes shortened and flagged, as before.
            assertTrue(back.events.any { it.notesShortened })
            assertEquals(want, ShareCodec.file(p)[4].toInt())
            assertEquals(p.events.map { it.notes }.sorted(), ok(ShareCodec.parse(ShareCodec.file(p))).events.map { it.notes }.sorted())
        }
        // Apps before 1.2.2 (no parity flag).
        assertTrue(ShareCodec.decode(ShareCodec.payload(v1, complete = false, legacy = true)) is ShareResult.Ok)
    }

    // Frames.

    private val payload = ShareCodec.qrPayload(plan())
    private val enc = ShareFrames.Encoder(payload)

    private fun collect(seqs: Sequence<Int>): Pair<ByteArray?, Int> {
        val d = ShareFrames.Decoder()
        var used = 0
        for (s in seqs) {
            d.add(enc.frame(s)); used++
            if (d.result != null) break
        }
        return d.result to used
    }

    @Test fun framesInOrderNeedExactlyK() {
        assertTrue("k=${enc.k}", enc.k >= 4)
        val (out, used) = collect(generateSequence(0) { it + 1 }.take(1000))
        assertArrayEquals(payload, out)
        assertEquals(enc.k, used)
        assertEquals(ShareCodec.parse(ShareCodec.qrText(plan())), ShareCodec.decode(out!!))
    }

    @Test fun framesShuffledWithDropped() {
        val rnd = Random(11)
        var total = 0
        repeat(200) {
            val seqs = (0 until 300).filter { rnd.nextInt(3) == 0 }.shuffled(rnd)
            val (out, used) = collect(seqs.asSequence())
            assertArrayEquals(payload, out)
            total += used
        }
        assertTrue("average ${total / 200.0} for k=${enc.k}", total / 200.0 < enc.k + 3)
    }

    @Test fun onlyMixedFrames() {
        val (out, used) = collect(generateSequence(enc.k) { it + 1 }.take(1000))
        assertArrayEquals(payload, out)
        assertTrue(used < enc.k + 12)
    }

    @Test fun duplicatesForeignAndOtherCodes() {
        val d = ShareFrames.Decoder()
        assertTrue(d.add(enc.frame(0)))
        assertFalse(d.add(enc.frame(0)))
        assertFalse(d.add(ShareCodec.qrText(plan())))
        assertFalse(d.add("https://example.org"))
        assertFalse(ShareFrames.isFrame(ShareCodec.legacyQrText(plan())))
        assertTrue(ShareFrames.isFrame(enc.frame(3)))
        val other = ShareFrames.Encoder(ByteArray(700) { it.toByte() })
        assertTrue(d.add(other.frame(9)))
        assertEquals(other.k, d.blocks)
        (0 until 30).forEach { d.add(other.frame(it)) }
        assertArrayEquals(ByteArray(700) { it.toByte() }, d.result)
    }

    @Test fun progress() {
        val d = ShareFrames.Decoder()
        assertEquals(0f, d.progress)
        d.add(enc.frame(2))
        assertEquals(1f / enc.k, d.progress, 1e-6f)
        assertNull(d.result)
    }

    @Test fun framesStayWithinVersion12() {
        // Version 12-L holds 535 alphanumeric characters; seq up to 2^21 (about four days at 6 fps).
        assertTrue(enc.frame((1 shl 21) - 1).length <= 535)
    }

    companion object {
        const val DICTIONARY_CRC = 327891344L
    }
}
