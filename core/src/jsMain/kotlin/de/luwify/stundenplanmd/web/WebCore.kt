@file:OptIn(ExperimentalJsExport::class)

package de.luwify.stundenplanmd.web

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Subject
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.schedule.Cancellation
import de.luwify.stundenplanmd.core.schedule.Change
import de.luwify.stundenplanmd.core.schedule.DayPlan
import de.luwify.stundenplanmd.core.schedule.Navigation
import de.luwify.stundenplanmd.core.schedule.NowStatus
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.OccurrenceKey
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.core.schedule.Together
import de.luwify.stundenplanmd.core.share.Base45
import de.luwify.stundenplanmd.core.share.Forwarding
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.ShareFrames
import de.luwify.stundenplanmd.core.share.ShareResult
import de.luwify.stundenplanmd.core.share.SharedPlan
import de.luwify.stundenplanmd.core.store.BackupFileResult
import de.luwify.stundenplanmd.core.store.BackupFormat
import de.luwify.stundenplanmd.core.store.DisplayBackup
import de.luwify.stundenplanmd.core.store.MigrationException
import de.luwify.stundenplanmd.core.store.Migrations
import de.luwify.stundenplanmd.core.store.StoreOps
import de.luwify.stundenplanmd.core.store.StoredData
import de.luwify.stundenplanmd.core.store.SubjectPalette
import de.luwify.stundenplanmd.core.store.ThemeBackup
import de.luwify.stundenplanmd.core.store.UpdatesBackup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

// The web app's entry into :core. Everything crosses the boundary as JSON strings (and ByteArray = Int8Array for
// files and QR payloads), so the TypeScript side needs no Kotlin types. State = StoredData, the same JSON the
// Android app stores, kept by the web app in IndexedDB.

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun instant(ms: Double): Instant = Instant.ofEpochMilli(ms.toLong())

private fun localNow(ms: Double): LocalDateTime = LocalDateTime.ofInstant(instant(ms), PlanZone)

private fun hm(t: LocalDateTime) = t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')

private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to el(v) })

private fun el(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Iterable<*> -> JsonArray(v.map { el(it) })
    else -> JsonPrimitive(v.toString())
}

@JsExport
class WebCore(stateJson: String?) {
    private var data: StoredData = load(stateJson)
    private var cache: Map<String, View>? = null

    /** A failed load (newer format or broken JSON) keeps the text in [rejected] and starts empty; the UI says so. */
    var rejected: String? = null; private set

    private fun load(text: String?): StoredData {
        if (text.isNullOrBlank()) return StoredData()
        return try {
            json.decodeFromJsonElement(StoredData.serializer(), Migrations.migrate(json.parseToJsonElement(text).jsonObject))
        } catch (e: MigrationException) {
            rejected = text; StoredData()
        } catch (e: Exception) {
            rejected = text; StoredData()
        }
    }

    private fun set(next: StoredData) { data = next; cache = null }

    fun stateJson(): String = json.encodeToString(StoredData.serializer(), data)

    // ---- people and plans

    private class View(val schedule: Schedule, val subjects: List<Subject>, val subjectOf: Map<String, Subject>, val together: Map<OccurrenceKey, List<String>>)

    private fun views(): Map<String, View> = cache ?: run {
        val schedules = data.plans.associate { it.personId to data.schedule(it) } + (StoredData.ME to data.mySchedule())
        val ids = listOf(StoredData.ME) + data.friends.map { it.id }
        ids.associateWith { id ->
            val events = data.eventsOf(id)
            val subjects = Subjects.build(events, data.settingsOf(id, events))
            val others = data.people.filter { it.id != id }.mapNotNull { p -> schedules[p.id]?.let { p.id to it } }
            val schedule = schedules[id] ?: Schedule(events)
            View(schedule, subjects, subjects.flatMap { s -> s.courses.map { it.key to s } }.toMap(), Together.of(schedule, others))
        }.also { cache = it }
    }

    private fun nameOf(id: String) = if (id == StoredData.ME) null else data.person(id)?.displayName

    /** [{id, name, isMe, favorite, fromIcs, planDate, exportedOn, hasPlan, now}], me first, then the drawer order. */
    fun people(nowMs: Double): String {
        val now = localNow(nowMs)
        val v = views()
        val me = data.people.firstOrNull { it.isMe }
        val list = listOfNotNull(me) + data.friends
        return JsonArray(
            list.map { p ->
                val plan = data.planOf(p.id)
                val planDate = if (p.isMe) plan?.importedAt?.atZone(PlanZone)?.toLocalDate() else plan?.planDate
                val schedule = v[p.id]?.schedule
                obj(
                    "id" to p.id, "name" to p.displayName, "isMe" to p.isMe, "favorite" to p.favorite, "fromIcs" to p.fromIcs,
                    "planDate" to planDate?.toString(), "exportedOn" to plan?.exportedOn?.toString(),
                    "hasPlan" to (if (p.isMe) data.myEvents.isNotEmpty() else plan != null),
                    "legacyShare" to (plan?.legacyShare == true),
                    "now" to schedule?.let { nowLine(NowStatus.of(it, now)) },
                )
            },
        ).toString()
    }

    private fun nowLine(s: NowStatus): JsonElement = when (s) {
        is NowStatus.In -> obj("kind" to "in", "room" to s.occurrence.room, "until" to s.until.toString().take(5))
        is NowStatus.FreeUntil -> obj("kind" to "freeUntil", "room" to s.next.room, "from" to hm(s.next.start))
        NowStatus.FreeToday -> obj("kind" to "freeToday")
        NowStatus.None -> JsonNull
    }

    /** {weeks: [monday ISO], open: monday, day: ISO, hours: [first, last]} for the strip/pager of [personId]. */
    fun navigation(personId: String, nowMs: Double): String {
        val s = views()[personId]?.schedule ?: return "null"
        val now = localNow(nowMs)
        val open = Navigation.weekToOpen(s, now.toLocalDate())
        return obj(
            "weeks" to Navigation.weeks(s, open).map { it.toString() },
            "open" to open.toString(),
            "day" to Navigation.dayToOpen(s, now).toString(),
            "hours" to listOf(s.hours.first, s.hours.last),
            "today" to now.toLocalDate().toString(),
        ).toString()
    }

    /** One week of [personId]: {monday, free, days: [{date, free, items, gaps}]}. */
    fun week(personId: String, monday: String): String {
        val v = views()[personId] ?: return "null"
        val w = v.schedule.week(LocalDate.parse(monday))
        return obj("monday" to monday, "free" to w.free, "days" to w.days.map { day(v, it) }).toString()
    }

    private fun day(v: View, d: DayPlan): JsonObject = obj(
        "date" to d.date.toString(),
        "free" to d.free,
        "items" to d.placed.map { p -> item(v, p.item, p.column, p.columns) },
        "gaps" to d.gaps.map { g -> obj("start" to hm(g.start), "end" to hm(g.end), "minutes" to g.minutes) },
    )

    private fun colorOf(v: View, t: Timed): Int {
        val subject = v.subjectOf[t.event.courseKey] ?: return SubjectPalette.defaultColor(0)
        return subject.color ?: SubjectPalette.defaultColor(v.subjects.indexOf(subject))
    }

    private fun item(v: View, t: Timed, column: Int, columns: Int): JsonObject {
        val e = t.event
        val subject = v.subjectOf[e.courseKey]
        val occ = t as? Occurrence
        val cancelled = t as? Cancellation
        val status = when {
            cancelled != null -> if (cancelled.byMe) "cancelledByMe" else "cancelled"
            occ?.status is Status.Changed -> "changed"
            occ?.status == Status.Extra -> "extra"
            else -> "regular"
        }
        val changes = (occ?.status as? Status.Changed)?.let { ch ->
            val o = ch.original
            ch.changes.sorted().map { c ->
                when (c) {
                    Change.DAY -> obj("kind" to "day", "now" to t.date.toString(), "before" to o.date.toString())
                    Change.TIME -> obj("kind" to "time", "now" to "${hm(t.start)}–${hm(t.end)}", "before" to "${hm(o.start)}–${hm(o.end)}")
                    Change.ROOM -> obj("kind" to "room", "now" to e.location?.room, "before" to o.event.location?.room)
                    Change.LECTURER -> obj("kind" to "lecturer", "now" to e.lecturers.joinToString(", "))
                }
            }
        }.orEmpty()
        val together = occ?.let { v.together[OccurrenceKey.of(it)] }.orEmpty()
        val type = e.type
        return obj(
            "uid" to e.uid,
            "date" to t.date.toString(),
            "start" to hm(t.start), "end" to hm(t.end),
            "startMin" to t.start.hour * 60 + t.start.minute, "endMin" to t.end.hour * 60 + t.end.minute,
            "column" to column, "columns" to columns,
            "name" to (subject?.displayNameOf(e.courseKey) ?: e.title),
            "title" to e.title, "summary" to e.summary, "subject" to subject?.name,
            "typeShort" to type.short, "typeName" to type.name, "category" to e.category,
            "room" to t.room, "location" to e.location?.raw,
            "lecturers" to e.lecturers, "notes" to e.notes, "noteHint" to e.noteHint.name,
            "personalNote" to t.personal?.note,
            "st" to t.st, "manual" to e.isManual,
            "color" to colorOf(v, t),
            "status" to status, "changes" to changes,
            "together" to together.map { if (it == StoredData.ME) obj("id" to it, "me" to true) else obj("id" to it, "name" to nameOf(it), "favorite" to (data.person(it)?.favorite == true)) },
        )
    }

    // ---- imports

    /** Replaces my plan: {ok, events} or {error: "notCalendar" | "noEvents"}. */
    fun importIcs(text: String, nowMs: Double): String {
        if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) return obj("error" to "notCalendar").toString()
        val result = LsfImport.parse(text)
        if (result.events.isEmpty()) return obj("error" to "noEvents").toString()
        val r = StoreOps.importIcs(data, result, instant(nowMs))
        set(r.data)
        return obj("ok" to true, "events" to result.events.size, "dropped" to r.dropped).toString()
    }

    /** A share file's bytes (or a saved QR text): see [importResult]. */
    fun importShareBytes(bytes: ByteArray, nowMs: Double): String = importResult(ShareCodec.parse(bytes), nowMs)

    /** A scanned QR text (`SPMD:` + Base45). */
    fun importShareText(text: String, nowMs: Double): String = importResult(ShareCodec.parse(text), nowMs)

    /** Peek without importing: {name, planDate, exportedOn, events, known} or {error}. */
    fun peekShareBytes(bytes: ByteArray): String = peek(ShareCodec.parse(bytes))
    fun peekShareText(text: String): String = peek(ShareCodec.parse(text))

    private fun peek(r: ShareResult): String = when (r) {
        is ShareResult.Ok -> r.plan.let { p ->
            obj(
                "name" to p.name, "planDate" to p.planDate.toString(), "exportedOn" to p.exportedOn.toString(), "events" to p.events.size,
                "known" to (data.person(p.personId) != null), "own" to (p.personId == data.share.id),
            ).toString()
        }
        else -> error(r)
    }

    private fun error(r: ShareResult) = when (r) {
        ShareResult.NotAShare -> obj("error" to "notAShare")
        ShareResult.TooNew -> obj("error" to "tooNew")
        is ShareResult.Broken -> obj("error" to "broken", "reason" to r.reason)
        is ShareResult.Ok -> obj()
    }.toString()

    private fun importResult(r: ShareResult, nowMs: Double): String {
        if (r !is ShareResult.Ok) return error(r)
        return when (val f = StoreOps.importFriend(data, r.plan, instant(nowMs))) {
            StoreOps.FriendResult.Own -> obj("error" to "own").toString()
            is StoreOps.FriendResult.Imported -> {
                set(f.data)
                obj("ok" to true, "id" to f.person.id, "name" to f.person.displayName, "replaced" to f.replaced).toString()
            }
        }
    }

    fun deleteFriend(id: String) {
        if (id == StoredData.ME) return
        set(
            data.copy(
                people = data.people.filterNot { it.id == id },
                plans = data.plans.filterNot { it.personId == id },
                friendSettings = data.friendSettings - id,
                rhythms = data.rhythms - id,
                hidden = data.hidden - id,
            ),
        )
    }

    fun setFavorite(id: String, favorite: Boolean) {
        set(data.copy(people = data.people.map { if (it.id == id) it.copy(favorite = favorite) else it }))
    }

    // ---- sharing

    /** {id, name}: my share identity (id null until the first share). */
    fun shareIdentity(): String = obj("id" to data.share.id, "name" to data.share.name).toString()

    /** Sets my share name; [newId] (16 hex digits from crypto.getRandomValues) is used only if I have no id yet. */
    fun setShareName(name: String, newId: String) {
        val clean = name.trim().ifEmpty { null } ?: return
        require(Regex("^[0-9a-f]{16}$").matches(newId)) { "id" }
        set(data.copy(share = data.share.copy(id = data.share.id ?: newId, name = clean)))
    }

    private fun shared(personId: String, nowMs: Double): SharedPlan? {
        val today = localNow(nowMs).toLocalDate()
        return if (personId == StoredData.ME) {
            StoreOps.mySharedPlan(data, data.share, today, instant(nowMs))
        } else {
            val person = data.person(personId) ?: return null
            val plan = data.planOf(personId) ?: return null
            Forwarding.of(person, plan, today, PlanZone, person.name)
        }
    }

    /** The QR payload (format 4) of [personId]'s plan as I'd share it; null without a name or events. */
    fun qrPayload(personId: String, nowMs: Double): ByteArray? = shared(personId, nowMs)?.let { ShareCodec.qrPayload(it) }

    /** The share file (lowest version 1–3); null without a name or events. */
    fun shareFile(personId: String, nowMs: Double): ByteArray? = shared(personId, nowMs)?.let { ShareCodec.file(it) }

    /** File name like the app's: "Stundenplan <name>.stundenplan". */
    fun shareFileName(personId: String): String {
        val name = (if (personId == StoredData.ME) data.share.name else data.person(personId)?.name).orEmpty()
        val safe = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "Stundenplan" }
        return "Stundenplan $safe.${ShareCodec.FILE_EXTENSION}"
    }

    // ---- backup

    /**
     * The backup file, same format as the Android app's. [extrasJson] = what the last restored backup held that the web
     * app doesn't use (theme, language, updates, display) so they survive web -> Android; null = the app's defaults.
     */
    fun backup(ics: String?, extrasJson: String?, nowMs: Double): String {
        val extras = extrasJson?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val theme = extras?.get("theme")?.let { runCatching { json.decodeFromJsonElement(ThemeBackup.serializer(), it) }.getOrNull() }
            ?: ThemeBackup("system", "system", 0x7839D0, false, "")
        val language = (extras?.get("language") as? JsonPrimitive)?.content ?: "system"
        val updates = extras?.get("updates")?.let { runCatching { json.decodeFromJsonElement(UpdatesBackup.serializer(), it) }.getOrNull() } ?: UpdatesBackup()
        val display = extras?.get("display")?.let { runCatching { json.decodeFromJsonElement(DisplayBackup.serializer(), it) }.getOrNull() } ?: DisplayBackup()
        return BackupFormat.encode(data, ics, theme, language, updates, display, instant(nowMs))
    }

    /**
     * Restores a backup: {ok, ics, extras, exportedAt, people} or {error: "notABackup" | "tooNew" | "broken"}. The
     * previous state stays available through [stateJson] before calling this (undo is the caller's).
     */
    fun restore(text: String): String = when (val r = BackupFormat.decode(text)) {
        is BackupFileResult.Ok -> {
            val b = r.backup
            set(b.data)
            val extras = buildJsonObject {
                b.theme?.let { put("theme", json.encodeToJsonElement(ThemeBackup.serializer(), it)) }
                b.language?.let { put("language", it) }
                b.updates?.let { put("updates", json.encodeToJsonElement(UpdatesBackup.serializer(), it)) }
                b.display?.let { put("display", json.encodeToJsonElement(DisplayBackup.serializer(), it)) }
            }
            obj("ok" to true, "ics" to b.ics, "extras" to extras.toString(), "exportedAt" to b.exportedAt, "people" to b.data.people.size).toString()
        }
        BackupFileResult.NotABackup -> obj("error" to "notABackup").toString()
        BackupFileResult.TooNew -> obj("error" to "tooNew").toString()
        is BackupFileResult.Broken -> obj("error" to "broken", "reason" to r.reason).toString()
    }

    /** {format, version}: what a check of a file needs without restoring it. */
    fun peekBackup(text: String): String = when (val r = BackupFormat.decode(text)) {
        is BackupFileResult.Ok -> obj("ok" to true, "people" to r.backup.data.people.size, "exportedAt" to r.backup.exportedAt, "hasPlan" to (r.backup.data.myPlan != null)).toString()
        BackupFileResult.NotABackup -> obj("error" to "notABackup").toString()
        BackupFileResult.TooNew -> obj("error" to "tooNew").toString()
        is BackupFileResult.Broken -> obj("error" to "broken", "reason" to r.reason).toString()
    }
}

/** Frames of an animated QR ([ShareFrames.Encoder]) over a format-4 payload. */
@JsExport
class FrameEncoder(payload: ByteArray) {
    private val encoder = ShareFrames.Encoder(payload)
    val blocks: Int get() = encoder.k
    fun frame(seq: Int): String = encoder.frame(seq)
}

/** Collects scanned frames; [offer] returns the progress 0..1, or -1 for text that is no frame. */
@JsExport
class FrameDecoder {
    private val decoder = ShareFrames.Decoder()
    fun offer(text: String): Double {
        if (!ShareFrames.isFrame(text)) return -1.0
        decoder.add(text)
        return decoder.progress.toDouble()
    }

    /** The finished share as QR text (`SPMD:` + Base45 of the payload), for the normal import. */
    fun result(): String? = decoder.result?.let { ShareCodec.QR_PREFIX + Base45.encode(it) }
}

/** The single-code QR text of a payload. */
@JsExport
fun qrText(payload: ByteArray): String = ShareCodec.QR_PREFIX + Base45.encode(payload)

@JsExport
fun isFrame(text: String): Boolean = ShareFrames.isFrame(text)

/** Default subject palette (0xRRGGBB), for the UI. */
@JsExport
fun subjectPalette(): IntArray = SubjectPalette.palette.toIntArray()
