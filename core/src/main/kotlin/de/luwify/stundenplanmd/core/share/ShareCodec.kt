package de.luwify.stundenplanmd.core.share

import de.luwify.stundenplanmd.core.ical.LsfText
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Recurrence
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.Expansion
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A plan as shared with a friend. [personId] is random (16 hex digits), made once by the sender; [planDate] is the
 * day the sender imported the .ics ("Stand"), [exportedOn] the day of sharing. [settings] are the sender's subject
 * groupings, short names and colors (keyed by course key), used as defaults on the receiving side.
 */
data class SharedPlan(
    val personId: String,
    val name: String,
    val exportedOn: LocalDate,
    val planDate: LocalDate,
    val events: List<Event>,
    val settings: Map<String, CourseSetting>,
    /** File profile: every note and the raw SUMMARY are included. */
    val complete: Boolean,
    /** The sender's own changes (cancellations, rooms, times, s.t.; never notes). */
    val changes: List<PersonalChange> = emptyList(),
)

sealed interface ShareResult {
    data class Ok(val plan: SharedPlan) : ShareResult
    data object NotAShare : ShareResult

    /** Made by a newer app version. */
    data object TooNew : ShareResult
    data class Broken(val reason: String) : ShareResult
}

/**
 * The share format. Payload = format version byte + raw deflate of a compact binary body: a string table, LSF course
 * ids (sorted, delta-coded), the sender's course settings, then the events column by column (all course refs, then all
 * appointment ids, titles, ... so deflate sees similar bytes together). Times are day offsets and quarter hours; a
 * plain LSF series is stored as slot count + interval + a bitmask of cancelled slots, which keeps the expanded
 * schedule identical (EXDATEs off the rhythm are dropped: they never had an effect).
 *
 * QR: `SPMD:` + Base45(payload), alphanumeric mode. File: `SPMD` + payload. The QR profile leaves out the raw SUMMARY
 * and notes of series, and caps notes of single events; such events are flagged [Event.notesShortened].
 */
object ShareCodec {
    /**
     * 2: own changes and the s.t. flags of events appended after the events; manual courses (`manual-` UIDs) only in 2.
     * A plan without any of them is still written as 1, which every app reads.
     */
    const val VERSION = 2
    const val QR_PREFIX = "SPMD:"
    val FILE_MAGIC = "SPMD".toByteArray()
    const val FILE_EXTENSION = "stundenplan"
    const val MIME = "application/vnd.luwify.stundenplan"

    /** Alphanumeric capacity of QR version 30 at ECC L; longer plans are shared as a file. */
    const val QR_MAX_CHARS = 2520
    const val QR_NOTE_CHARS = 80

    private const val MAX_INFLATED = 256 * 1024
    private const val MAX_EVENTS = 2000
    private const val MAX_STRINGS = 20_000
    private const val MAX_STRING_BYTES = 16 * 1024

    private const val FLAG_SUMMARY = 1
    private const val FLAG_ALL_NOTES = 2

    fun qrText(plan: SharedPlan): String = QR_PREFIX + Base45.encode(payload(plan, complete = false))

    /**
     * True when the QR code leaves out something the file has: a shortened or dropped note, or a raw SUMMARY that is
     * more than "title (lecturers)" (the QR rebuilds neither).
     */
    fun qrOmitsDetails(plan: SharedPlan): Boolean = plan.events.any { e ->
        Body.note(e, complete = false).second || !summaryCovered(e)
    }

    private fun summaryCovered(e: Event): Boolean {
        val summary = LsfText.normalizeSpaces(e.summary)
        val title = LsfText.normalizeSpaces(e.title)
        return summary == title || summary == "$title (${e.lecturers.joinToString(", ")})" ||
            summary == LsfText.normalizeSpaces("$title (${e.lecturers.joinToString(",")})")
    }

    fun file(plan: SharedPlan): ByteArray = FILE_MAGIC + payload(plan, complete = true)

    /** A file's bytes: binary (`SPMD` + payload) or a saved QR text (`SPMD:`, which also starts with the magic). */
    fun parse(bytes: ByteArray): ShareResult {
        val text = bytes.copyOfRange(0, minOf(bytes.size, 16)).decodeToString().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        return if (text.startsWith(QR_PREFIX)) {
            parse(bytes.decodeToString())
        } else if (bytes.size >= FILE_MAGIC.size && bytes.copyOfRange(0, FILE_MAGIC.size).contentEquals(FILE_MAGIC)) {
            decode(bytes.copyOfRange(FILE_MAGIC.size, bytes.size))
        } else {
            ShareResult.NotAShare
        }
    }

    fun parse(text: String): ShareResult {
        val t = text.trim().removePrefix("\uFEFF").trim()
        if (!t.startsWith(QR_PREFIX)) return ShareResult.NotAShare
        val bytes = Base45.decode(t.substring(QR_PREFIX.length)) ?: return ShareResult.Broken("not Base45")
        return decode(bytes)
    }

    /** True when [bytes] start like a share (file magic or QR text); used to dispatch incoming files. */
    fun looksLikeShare(bytes: ByteArray): Boolean {
        val head = bytes.copyOfRange(0, minOf(bytes.size, 16)).decodeToString().trimStart('﻿', ' ', '\r', '\n', '\t')
        return head.startsWith("SPMD")
    }

    fun payload(plan: SharedPlan, complete: Boolean): ByteArray {
        val body = Body.write(plan, complete)
        val d = Deflater(9, true)
        try {
            d.setInput(body); d.finish()
            val out = ByteArrayOutputStream()
            out.write(version(plan))
            val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    /** 1 when an older app can read everything, else 2 (older apps then say a newer version is needed). */
    fun version(plan: SharedPlan): Int =
        if (plan.changes.none { it.forSharing() != null } && plan.events.none { it.isManual || it.st }) 1 else 2

    fun decode(payload: ByteArray): ShareResult {
        if (payload.isEmpty()) return ShareResult.Broken("empty")
        val version = payload[0].toInt() and 0xff
        if (version > VERSION) return ShareResult.TooNew
        if (version < 1) return ShareResult.Broken("version $version")
        val body = inflate(payload, 1) ?: return ShareResult.Broken("bad or too large data")
        return try {
            ShareResult.Ok(Body.read(body, version))
        } catch (e: IndexOutOfBoundsException) {
            ShareResult.Broken("truncated")
        } catch (e: IllegalArgumentException) {
            ShareResult.Broken(e.message ?: "invalid")
        } catch (e: java.time.DateTimeException) {
            ShareResult.Broken("invalid date")
        } catch (e: ArithmeticException) {
            ShareResult.Broken("invalid number")
        }
    }

    private fun inflate(bytes: ByteArray, offset: Int): ByteArray? {
        val inf = Inflater(true)
        try {
            inf.setInput(bytes, offset, bytes.size - offset)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) return null
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            return null
        } finally {
            inf.end()
        }
    }

    /** LOCATION "K18 - K18-012 (60 Pl.)" as "K18-012|<alias>|60" when that rebuilds the raw text exactly, else "=raw". */
    internal fun shortLocation(raw: String): String {
        val l = LsfText.location(raw) ?: return "=$raw"
        val short = l.room + "|" + (l.alias ?: "") + "|" + (l.capacity ?: "")
        return if ('|' !in l.room && '|' !in (l.alias ?: "") && !l.room.startsWith("=") && rawLocation(short) == raw) short else "=$raw"
    }

    internal fun rawLocation(short: String): String {
        if (short.startsWith("=")) return short.substring(1)
        val (room, alias, cap) = short.split("|").also { require(it.size == 3) { "location" } }
        return "${room.substringBefore("-")} - $room" + (if (alias.isNotEmpty()) " ($alias)" else "") + (if (cap.isNotEmpty()) " ($cap Pl.)" else "")
    }

    private object Body {
        private val lsfUid = Regex("^(\\d{6})(\\d{6})$")

        fun write(plan: SharedPlan, complete: Boolean): ByteArray {
            // A rhythm set by hand is sent as the dates it gives (the format has no rhythm field).
            val events = plan.events.map(Expansion::portable).sortedWith(compareBy({ it.uid }, { it.start }))
            val strings = LinkedHashMap<String, Int>()
            fun s(text: String) = strings.getOrPut(text) { strings.size }
            fun opt(text: String?) = if (text == null) 0 else s(text) + 1

            val courseIds = events.mapNotNull { lsfUid.find(it.uid)?.groupValues?.get(1)?.toLong() }.distinct().sorted()
            val courseIndex = courseIds.withIndex().associate { (i, id) -> id to i }

            // Course settings: course ref (index + 1, or 0 + key string), subject, short name, color.
            val settings = Out()
            val keyed = plan.settings.values.filter { it.subject != null || it.shortName != null || it.color != null }.sortedBy { it.courseKey }
            settings.varint(keyed.size)
            for (c in keyed) {
                val ref = c.courseKey.removePrefix("lsf:").takeIf { c.courseKey.startsWith("lsf:") }?.toLongOrNull()?.let { courseIndex[it] }
                if (ref != null) settings.varint(ref + 1) else { settings.varint(0); settings.varint(s(c.courseKey)) }
                settings.varint(opt(c.subject)); settings.varint(opt(c.shortName))
                val color = c.color
                if (color == null) settings.byte(0) else { settings.byte(1); settings.byte(color shr 16); settings.byte(color shr 8); settings.byte(color) }
            }

            val base = events.minOfOrNull { it.start.toLocalDate() } ?: plan.exportedOn
            val cols = List(10) { Out() }
            val prevAppt = LongArray(courseIds.size)
            for (e in events) {
                val m = lsfUid.find(e.uid)
                if (m == null) {
                    cols[0].varint(0); cols[0].varint(s(e.uid))
                } else {
                    val ci = courseIndex.getValue(m.groupValues[1].toLong())
                    cols[0].varint(ci + 1)
                    val appt = m.groupValues[2].toLong()
                    cols[1].zigzag(appt - prevAppt[ci]); prevAppt[ci] = appt
                }
                cols[2].varint(s(e.title)); cols[2].varint(s(e.category))
                cols[3].varint(e.lecturers.size); e.lecturers.forEach { cols[3].varint(s(it)) }
                if (complete) cols[4].varint(s(e.summary))
                cols[5].varint(e.location?.raw?.let { s(shortLocation(it)) + 1 } ?: 0)
                val (note, shortened) = note(e, complete)
                cols[6].varint(opt(note).toLong() * 2 + if (shortened) 1 else 0)
                cols[7].varint(ChronoUnit.DAYS.between(base, e.start.toLocalDate()))
                cols[7].minutes(e.start.hour * 60L + e.start.minute)
                cols[7].minutes(Duration.between(e.start, e.end).toMinutes().coerceAtLeast(0))
                recurrence(cols[8], cols[9], e)
            }

            // Own changes (version 2): appointment UID, date, flags, until, room, time. Notes are never sent.
            val changes = Out()
            val shared = plan.changes.mapNotNull { it.forSharing() }
            for (c in shared) {
                changes.varint(s(c.uid))
                changes.zigzag(ChronoUnit.DAYS.between(base, c.date))
                val flags = (if (c.series) 1 else 0) or (if (c.until != null) 2 else 0) or (if (c.st != null) 4 else 0) or
                    (if (c.st == true) 8 else 0) or (if (c.cancelled) 16 else 0) or (if (c.room != null) 32 else 0) or
                    (if (c.start != null && c.end != null) 64 else 0)
                changes.byte(flags)
                c.until?.let { changes.varint(ChronoUnit.DAYS.between(c.date, it).coerceAtLeast(0)) }
                c.room?.let { changes.varint(s(it)) }
                if (c.start != null && c.end != null) {
                    changes.minutes(c.start.hour * 60L + c.start.minute); changes.minutes(c.end.hour * 60L + c.end.minute)
                }
            }

            val out = Out()
            out.byte((if (complete) FLAG_SUMMARY or FLAG_ALL_NOTES else 0))
            require(plan.personId.matches(Regex("[0-9a-f]{16}"))) { "person id" }
            val id = plan.personId.chunked(2).map { it.toInt(16) }
            id.forEach { out.byte(it) }
            out.varint(plan.exportedOn.toEpochDay()); out.varint(plan.planDate.toEpochDay())
            out.string(plan.name)
            out.varint(base.toEpochDay())
            out.varint(strings.size); strings.keys.forEach { out.string(it) }
            out.varint(courseIds.size); var prev = 0L; courseIds.forEach { out.varint(it - prev); prev = it }
            out.bytes(settings.toByteArray())
            out.varint(events.size)
            cols.forEach { out.bytes(it.toByteArray()) }
            if (version(plan) >= 2) {
                out.varint(shared.size); out.bytes(changes.toByteArray())
                val st = events.withIndex().filter { it.value.st }.map { it.index }
                out.varint(st.size); var last = 0; st.forEach { out.varint(it - last); last = it }
            }
            return out.toByteArray()
        }

        /** Note to send and whether it is incomplete. QR: series lose their notes, single events keep a short one. */
        fun note(e: Event, complete: Boolean): Pair<String?, Boolean> {
            val text = e.notes.takeIf { it.isNotBlank() }
            if (complete || text == null) return text to e.notesShortened
            if (e.recurrence != null) return null to true
            if (text.length <= QR_NOTE_CHARS) return text to e.notesShortened
            return text.take(QR_NOTE_CHARS - 1).trimEnd() + "…" to true
        }

        /** 0 = single date; 1 = plain weekly series (slot count, interval, cancelled bitmask); 2 = any other rule. */
        private fun recurrence(out: Out, masks: Out, e: Event) {
            val r = e.recurrence
            if (r == null) { out.varint(0); return }
            val slots = Expansion.slots(e)
            // INTERVAL=2: plain only in the calendar-week rhythm of its first date (what the reader rebuilds); a 14-day
            // series that differs from it goes as a full rule, whose UNTIL tells the reader the rhythm.
            val plain = r.frequency == Recurrence.Frequency.WEEKLY && r.byDay.all { it == e.start.dayOfWeek } &&
                r.count == null && r.until != null && slots.isNotEmpty() && slots.size < Expansion.MAX_SLOTS &&
                (!r.biweekly || Expansion.rhythmOf(e) == WeekRhythm.parityOf(e.start.toLocalDate()))
            if (plain) {
                out.varint(1); out.varint(slots.size); out.varint(r.interval)
                val mask = ByteArray((slots.size + 7) / 8)
                slots.forEachIndexed { i, slot -> if (slot.cancelled) mask[i / 8] = (mask[i / 8].toInt() or (1 shl (i % 8))).toByte() }
                masks.bytes(mask)
                return
            }
            out.varint(2)
            out.byte(r.frequency.ordinal); out.varint(r.interval)
            out.byte(r.byDay.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) })
            out.varint(r.until?.let { it.epochSecond - e.start.atZone(PlanZone).toEpochSecond() + 1 } ?: 0)
            out.varint(r.count?.let { it + 1L } ?: 0)
            val day = e.start.toLocalDate()
            out.varint(r.exdates.size)
            r.exdates.sorted().forEach { out.zigzag(ChronoUnit.DAYS.between(day, it.toLocalDate())); out.minutes(it.hour * 60L + it.minute) }
            out.varint(r.exdays.size)
            r.exdays.sorted().forEach { out.zigzag(ChronoUnit.DAYS.between(day, it)) }
        }

        fun read(bytes: ByteArray, version: Int): SharedPlan {
            val r = In(bytes)
            val flags = r.byte()
            val personId = (0 until 8).joinToString("") { "%02x".format(r.byte()) }
            val exportedOn = LocalDate.ofEpochDay(r.varint())
            val planDate = LocalDate.ofEpochDay(r.varint())
            val name = r.string()
            val base = LocalDate.ofEpochDay(r.varint())
            val strings = List(r.count(MAX_STRINGS)) { r.string() }
            fun s(i: Long) = strings.getOrNull(i.toInt()) ?: throw IllegalArgumentException("string $i")
            fun opt(i: Long) = if (i == 0L) null else s(i - 1)
            var acc = 0L
            val courseIds = List(r.count(MAX_EVENTS)) { acc += r.varint(); acc }
            fun course(ref: Long) = courseIds.getOrNull(ref.toInt() - 1) ?: throw IllegalArgumentException("course $ref")

            data class RawSetting(val key: String, val subject: String?, val shortName: String?, val color: Int?)
            val rawSettings = List(r.count(MAX_EVENTS)) {
                val ref = r.varint()
                val key = if (ref == 0L) s(r.varint()) else "lsf:%06d".format(course(ref))
                val subject = opt(r.varint())
                val shortName = opt(r.varint())
                val color = if (r.byte() == 1) (r.byte() shl 16) or (r.byte() shl 8) or r.byte() else null
                RawSetting(key, subject, shortName, color)
            }

            val n = r.count(MAX_EVENTS)
            val uids = arrayOfNulls<String>(n)
            val courseOf = IntArray(n)
            for (i in 0 until n) {
                val ref = r.varint()
                if (ref == 0L) uids[i] = s(r.varint()) else { course(ref); courseOf[i] = ref.toInt() }
            }
            val prevAppt = LongArray(courseIds.size + 1)
            for (i in 0 until n) if (uids[i] == null) {
                val c = courseOf[i]
                prevAppt[c] += r.zigzag()
                require(prevAppt[c] in 0..999_999) { "appointment id" }
                uids[i] = "%06d%06d".format(courseIds[c - 1], prevAppt[c])
            }
            val titles = List(n) { s(r.varint()) to s(r.varint()) }
            val lecturers = List(n) { List(r.count(MAX_STRINGS)) { s(r.varint()) } }
            val summaries = if (flags and FLAG_SUMMARY != 0) List(n) { s(r.varint()) } else null
            val locations = List(n) { opt(r.varint())?.let { LsfText.location(rawLocation(it)) } }
            val notes = List(n) { r.varint().let { v -> opt(v / 2) to (v % 2 == 1L) } }
            val times = List(n) {
                val start = base.plusDays(r.varint()).atStartOfDay().plusMinutes(r.minutes())
                start to start.plusMinutes(r.minutes())
            }
            val kinds = List(n) { readRule(r, times[it].first) }
            val events0 = List(n) { i ->
                val (title, category) = titles[i]
                val (start, end) = times[i]
                val rule = kinds[i]?.let { k -> if (k is PlainSeries) k.toRecurrence(start, r.bytes((k.slots + 7) / 8)) else k as Recurrence }
                Event(
                    uid = uids[i]!!,
                    // Not sent by QR: the raw SUMMARY is unknown, so none is claimed.
                    summary = summaries?.get(i) ?: title,
                    title = title,
                    lecturers = lecturers[i],
                    category = category,
                    location = locations[i],
                    notes = notes[i].first.orEmpty(),
                    notesShortened = notes[i].second,
                    start = start,
                    end = end,
                    recurrence = rule,
                )
            }
            val changes = if (version < 2) emptyList() else List(r.count(MAX_EVENTS)) {
                val uid = s(r.varint())
                val date = base.plusDays(r.zigzag())
                val flags = r.byte()
                val until = if (flags and 2 != 0) date.plusDays(r.varint()) else null
                val room = if (flags and 32 != 0) s(r.varint()) else null
                val time = if (flags and 64 != 0) {
                    fun clock() = r.minutes().also { require(it < 24 * 60) { "time" } }.let { java.time.LocalTime.of((it / 60).toInt(), (it % 60).toInt()) }
                    clock() to clock()
                } else null
                PersonalChange(
                    uid, date, series = flags and 1 != 0, until = until,
                    st = if (flags and 4 != 0) flags and 8 != 0 else null, cancelled = flags and 16 != 0, room = room,
                    start = time?.first, end = time?.second,
                )
            }
            val st = BooleanArray(n)
            if (version >= 2) { var at = 0L; repeat(r.count(MAX_EVENTS)) { at += r.varint(); require(at < n) { "st" }; st[at.toInt()] = true } }
            require(r.atEnd()) { "trailing data" }

            val events = events0.mapIndexed { i, e -> if (st[i]) e.copy(st = true) else e }
            val titleOf = Subjects.courses(events).associate { it.key to it.title }
            val settings = rawSettings.associate { it.key to CourseSetting(it.key, titleOf[it.key] ?: "", it.subject, it.shortName, it.color) }
            return SharedPlan(personId, name, exportedOn, planDate, events, settings, complete = flags and FLAG_ALL_NOTES != 0, changes = changes)
        }

        /** INTERVAL=2 in the calendar-week rhythm of [start] (older apps counted 14 days from it instead). */
        private class PlainSeries(val slots: Int, val interval: Int) {
            fun toRecurrence(start: LocalDateTime, mask: ByteArray): Recurrence {
                val rhythm = if (interval == 2) WeekRhythm.parityOf(start.toLocalDate()) else null
                val probe = Event("", "", "", start = start, end = start, recurrence = Recurrence(interval = interval, count = slots, rhythm = rhythm))
                val starts = Expansion.slots(probe).map { it.start }
                return Recurrence(
                    interval = interval,
                    until = starts.last().toLocalDate().atTime(23, 59).atZone(PlanZone).toInstant(),
                    exdates = starts.filterIndexed { i, _ -> (mask[i / 8].toInt() shr (i % 8)) and 1 == 1 }.toSet(),
                )
            }
        }

        /** Returns null (single date), a [PlainSeries] (mask read later, from the mask column) or a full [Recurrence]. */
        private fun readRule(r: In, start: LocalDateTime): Any? = when (r.varint()) {
            0L -> null
            1L -> {
                val slots = r.varint().toInt()
                val interval = r.varint().toInt()
                require(slots in 1 until Expansion.MAX_SLOTS && interval in 1..52) { "series" }
                PlainSeries(slots, interval)
            }
            2L -> {
                val frequency = Recurrence.Frequency.entries.getOrNull(r.byte()) ?: throw IllegalArgumentException("frequency")
                val interval = r.varint().toInt().also { require(it in 1..366) { "interval" } }
                val days = r.byte().let { mask -> DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet() }
                val until = r.varint().let { if (it == 0L) null else Instant.ofEpochSecond(start.atZone(PlanZone).toEpochSecond() + it - 1) }
                val count = r.varint().let { if (it == 0L) null else (it - 1).toInt() }
                val day = start.toLocalDate()
                val exdates = List(r.count(MAX_EVENTS)) { day.plusDays(r.zigzag()).atStartOfDay().plusMinutes(r.minutes()) }.toSet()
                val exdays = List(r.count(MAX_EVENTS)) { day.plusDays(r.zigzag()) }.toSet()
                Recurrence(frequency, interval, days, until, count, exdates, exdays)
            }
            else -> throw IllegalArgumentException("rule kind")
        }
    }

    private class Out {
        private val out = ByteArrayOutputStream()
        fun byte(v: Int) = out.write(v and 0xff)
        fun bytes(b: ByteArray) = out.write(b)
        fun varint(v: Int) = varint(v.toLong())
        fun varint(v: Long) {
            require(v >= 0) { "negative varint" }
            var x = v
            while (x >= 0x80) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        fun zigzag(v: Long) = varint((v shl 1) xor (v shr 63))

        /** Minutes as quarter hours (even) or, if not on a quarter, exact minutes (odd). */
        fun minutes(m: Long) = varint(if (m % 15 == 0L) m / 15 * 2 else m * 2 + 1)
        fun string(s: String) { val b = s.toByteArray(); varint(b.size); out.write(b) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class In(private val b: ByteArray) {
        private var pos = 0
        fun atEnd() = pos == b.size
        fun byte(): Int = b[pos++].toInt() and 0xff
        fun bytes(n: Int): ByteArray = b.copyOfRange(pos, pos + n).also { pos += n }
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val v = byte()
                result = result or ((v and 0x7f).toLong() shl shift)
                if (v < 0x80) return result
                shift += 7
                require(shift < 63) { "varint too long" }
            }
        }
        fun zigzag(): Long = varint().let { (it ushr 1) xor -(it and 1) }
        fun minutes(): Long = varint().let { if (it % 2 == 0L) it / 2 * 15 else it / 2 }.also { require(it < 7 * 24 * 60 * 400) { "minutes" } }
        fun count(max: Int): Int = varint().also { require(it <= max) { "count $it" } }.toInt()
        fun string(): String {
            val n = count(MAX_STRING_BYTES)
            return bytes(n).decodeToString()
        }
    }
}
