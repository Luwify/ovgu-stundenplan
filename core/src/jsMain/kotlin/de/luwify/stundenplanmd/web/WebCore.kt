@file:OptIn(ExperimentalJsExport::class)

package de.luwify.stundenplanmd.web

import de.luwify.stundenplanmd.core.ical.ImportResult
import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.Course
import de.luwify.stundenplanmd.core.model.CourseKeys
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.AddedCourses
import de.luwify.stundenplanmd.core.schedule.Expansion
import de.luwify.stundenplanmd.core.schedule.FreeGaps
import de.luwify.stundenplanmd.core.schedule.FriendCourses
import de.luwify.stundenplanmd.core.schedule.Groups
import de.luwify.stundenplanmd.core.schedule.ManualCourses
import de.luwify.stundenplanmd.core.schedule.OccurrenceValues
import de.luwify.stundenplanmd.core.schedule.Personal
import de.luwify.stundenplanmd.core.schedule.PersonalChanges
import de.luwify.stundenplanmd.core.store.HideTargets
import de.luwify.stundenplanmd.core.store.IcsEntry
import de.luwify.stundenplanmd.core.store.IcsRoute
import de.luwify.stundenplanmd.core.store.ImportRouting
import de.luwify.stundenplanmd.core.store.StoreEdits
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
import java.time.LocalTime

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

    private class View(val id: String, val schedule: Schedule, val subjects: List<Subject>, val subjectOf: Map<String, Subject>, val together: Map<OccurrenceKey, List<String>>, val others: List<Pair<String, Schedule>>)

    private fun views(): Map<String, View> = cache ?: run {
        val schedules = data.plans.associate { it.personId to data.schedule(it) } + (StoredData.ME to data.mySchedule())
        val ids = listOf(StoredData.ME) + data.friends.map { it.id }
        ids.associateWith { id ->
            val events = data.eventsOf(id)
            val subjects = Subjects.build(events, data.settingsOf(id, events))
            val others = data.people.filter { it.id != id }.mapNotNull { p -> schedules[p.id]?.let { p.id to it } }
            val schedule = schedules[id] ?: Schedule(events)
            View(id, schedule, subjects, subjects.flatMap { s -> s.courses.map { it.key to s } }.toMap(), Together.of(schedule, others), others)
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
                    "id" to p.id, "name" to p.displayName, "sharedName" to p.name, "localName" to p.localName, "isMe" to p.isMe, "favorite" to p.favorite, "fromIcs" to p.fromIcs,
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

    private fun day(v: View, d: DayPlan): JsonObject {
        val together = if (d.gaps.isEmpty()) emptyMap() else FreeGaps.together(d.gaps, v.others.map { (id, s) -> id to s.day(d.date).gaps })
        return obj(
            "date" to d.date.toString(),
            "free" to d.free,
            "items" to d.placed.map { p -> item(v, p.item, p.column, p.columns) },
            "gaps" to d.gaps.map { g ->
                obj(
                    "start" to hm(g.start), "end" to hm(g.end), "minutes" to g.minutes,
                    "startMin" to g.start.hour * 60 + g.start.minute, "endMin" to g.end.hour * 60 + g.end.minute,
                    "together" to together[g].orEmpty().map { personRef(it) },
                )
            },
        )
    }

    private fun personRef(id: String): JsonObject =
        if (id == StoredData.ME) obj("id" to id, "me" to true) else obj("id" to id, "name" to nameOf(id), "favorite" to (data.person(id)?.favorite == true))

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
            "together" to together.map { personRef(it) },
            "key" to keyOf(t),
            "ref" to obj("uid" to t.ref.uid, "date" to t.ref.date.toString()),
            "courseKey" to e.courseKey, "subjectKey" to subject?.key,
            "shortName" to subject?.shortNameOf(e.courseKey),
            "building" to e.location?.building, "alias" to e.location?.alias, "capacity" to e.location?.capacity,
            "original" to (occ?.status as? Status.Changed)?.original?.let { o ->
                obj("date" to o.date.toString(), "start" to hm(o.start), "end" to hm(o.end), "room" to o.event.location?.room)
            },
            "personal" to t.personal?.let { personalJson(t, it) },
            "ownChange" to (t.personal?.changesPlan == true),
            "rhythm" to rhythmJson(v, e),
        )
    }


    // ---- items: keys, own changes, rhythm

    /** A date's key in the web app: UID, start and kind (a moved date and its slot never share one). */
    private fun keyOf(t: Timed) = "${t.event.uid}|${t.start}|" + if (t is Cancellation) "c" else "o"

    /** [key]'s date in [personId]'s plan as shown, or (for "Termine auswählen") among my dates incl. hidden ones. */
    private fun find(personId: String, key: String): Timed? {
        val s = views()[personId]?.schedule ?: return null
        return (s.occurrences.asSequence() + s.cancellations.asSequence()).firstOrNull { keyOf(it) == key }
    }

    private fun personalJson(t: Timed, p: Personal): JsonObject {
        val series = p.series
        val showSeries = series != null && p.single?.let { it.room == null && it.start == null && !it.cancelled } != false &&
            (series.room != null || series.start != null || series.cancelled)
        return obj(
            "cancelled" to p.cancelled, "changesPlan" to p.changesPlan, "overruled" to p.overruled,
            "roomChanged" to p.roomChanged, "timeChanged" to p.timeChanged,
            "room" to p.current.room, "originalRoom" to p.original.room,
            "start" to p.current.start.toString(), "end" to p.current.end.toString(),
            "originalStart" to p.original.start.toString(), "originalEnd" to p.original.end.toString(),
            "seriesFrom" to (if (showSeries) series!!.date.toString() else null),
            "note" to p.note, "noteFromSeries" to p.noteFromSeries, "st" to p.st,
        )
    }

    private fun rhythmJson(v: View, e: Event): JsonElement {
        val r = e.recurrence ?: return JsonNull
        val until = r.until?.let { Expansion.slots(e).lastOrNull()?.start?.toLocalDate() }
        val kind = when (Expansion.rhythmOf(e)) {
            WeekRhythm.EVEN_WEEKS -> "even"
            WeekRhythm.ODD_WEEKS -> "odd"
            WeekRhythm.EVERY_14_DAYS -> "14"
            null -> if (r.interval == 1) "weekly" else "n"
        }
        return obj(
            "kind" to kind, "interval" to r.interval, "first" to e.start.toLocalDate().toString(), "until" to until?.toString(),
            "byMe" to (e.uid in data.rhythmsOf(v.id)),
        )
    }

    /** The event of the series my changes of [t] belong to (for a moved date: the series it was moved out of). */
    private fun seriesEvent(t: Timed): Event = ((t as? Occurrence)?.status as? Status.Changed)?.original?.event ?: t.event

    private fun personalOrBlank(t: Timed): Personal = t.personal ?: Personal(
        t.ref, null, null, Personal.values(t.event, t.start, t.end),
        lsfChanged = (t is Cancellation && !t.byMe) || (t is Occurrence && t.status is Status.Changed),
    )

    /**
     * What the detail sheet offers for [key]: {hide: [{kind, type?}], friendState, added, manualId, lsfCancelled,
     * cancelledForMe, editor: {...}} or null.
     */
    fun detail(personId: String, key: String): String {
        val v = views()[personId] ?: return "null"
        val t = find(personId, key) ?: return "null"
        val subject = v.subjectOf[t.event.courseKey]
        val course = subject?.courses?.firstOrNull { it.key == t.event.courseKey }
        val hide = buildList {
            add(obj("kind" to "date"))
            if (subject != null) {
                add(obj("kind" to "subject"))
                if (course != null && subject.courses.size > 1) add(obj("kind" to "type", "type" to HideTargets.typeName(course)))
                if (Groups.count(v.schedule, t) > 1) add(obj("kind" to "group"))
            }
        }
        val friendState = if (personId == StoredData.ME) null else StoreEdits.friendCourseState(data, FriendCourses.group(data.planOf(personId)?.events.orEmpty(), t)).name
        val added = personId == StoredData.ME && (t.event.uid in data.addedUids || Groups.baseUid(t) in data.addedUids)
        val p = personalOrBlank(t)
        val cur = p.current
        val seriesStart = t.date.atTime(p.original.start)
        return obj(
            "hide" to hide, "friendState" to friendState, "added" to added,
            "manualId" to (if (t.event.isManual) t.event.uid else null),
            "lsfCancelled" to (t is Cancellation && !t.byMe), "cancelledForMe" to (t is Cancellation && t.byMe),
            "editor" to obj(
                "canSeries" to (seriesEvent(t).recurrence != null), "refDate" to p.ref.date.toString(),
                "seriesDate" to seriesStart.toLocalDate().toString(), "seriesTime" to hm(seriesStart),
                "note" to cur.note, "st" to cur.st, "room" to (if (cur.room == p.original.room) "" else cur.room.orEmpty()),
                "originalRoom" to p.original.room, "start" to cur.start.toString(), "end" to cur.end.toString(),
            ),
        ).toString()
    }

    // ---- hiding

    private fun hiddenJson(h: Hidden): JsonObject = obj(
        "kind" to h.kind.name, "title" to h.title, "type" to h.type, "day" to h.day,
        "start" to h.start?.let { hm(LocalDate.of(2000, 1, 1).atTime(it)) }, "end" to h.end?.let { hm(LocalDate.of(2000, 1, 1).atTime(it)) },
        "date" to h.date?.toString(), "room" to h.room,
    )

    private fun hideAndDescribe(personId: String, item: Hidden): String {
        set(StoreEdits.hide(data, personId, item))
        return hiddenJson(item).toString()
    }

    /** Hides [key]'s date / subject / type / group ([kind]) in [personId]'s plan; the hidden item (for the snackbar) or null. */
    fun hide(personId: String, key: String, kind: String): String? {
        val v = views()[personId] ?: return null
        val t = find(personId, key) ?: return null
        val subject = v.subjectOf[t.event.courseKey]
        val item = when (kind) {
            "date" -> HideTargets.date(t)
            "subject" -> subject?.let(HideTargets::subject)
            "type" -> subject?.courses?.firstOrNull { it.key == t.event.courseKey }?.let(HideTargets::course)
            "group" -> HideTargets.group(v.schedule, t)
            else -> null
        } ?: return null
        return hideAndDescribe(personId, item)
    }

    /** "Fach ausblenden" in the subject editor. */
    fun hideSubject(personId: String, subjectKey: String): String? {
        val subject = views()[personId]?.subjects?.firstOrNull { it.key == subjectKey } ?: return null
        return hideAndDescribe(personId, HideTargets.subject(subject))
    }

    /** "Nur <Typ> ausblenden" in the subject editor. */
    fun hideCourse(personId: String, courseKey: String): String? {
        val course = courseOf(personId, courseKey) ?: return null
        return hideAndDescribe(personId, HideTargets.course(course))
    }

    /** Everything hidden: [{personId, name, isMe, items: [...]}], me first, people with nothing hidden left out. */
    fun hidden(): String = JsonArray(
        data.people.mapNotNull { p ->
            data.hiddenOf(p.id).takeIf { it.isNotEmpty() }?.let { list ->
                obj("personId" to p.id, "name" to p.displayName, "isMe" to p.isMe, "items" to list.map(::hiddenJson))
            }
        },
    ).toString()

    fun unhide(personId: String, index: Int) {
        val item = data.hiddenOf(personId).getOrNull(index) ?: return
        set(StoreEdits.unhide(data, personId, item))
    }

    // ---- own changes (my plan)

    /** "Fällt für mich aus" on or off; {title, date} for the snackbar, or null. */
    fun setCancelledForMe(key: String, cancelled: Boolean): String? {
        val t = find(StoredData.ME, key) ?: return null
        set(data.copy(personalChanges = PersonalChanges.setCancelled(data.personalChanges, t.ref, t.event.title, cancelled)))
        return obj("title" to t.event.title, "date" to t.ref.date.toString()).toString()
    }

    /** Saves note, s.t., room ("" = LSF's) and time of [key] for that date or, [series], every later date of its series. */
    fun savePersonal(key: String, series: Boolean, note: String, st: Boolean, room: String, start: String, end: String): Boolean {
        val t = find(StoredData.ME, key) ?: return false
        val p = personalOrBlank(t)
        val s = runCatching { LocalTime.parse(start) }.getOrNull() ?: return false
        val e = runCatching { LocalTime.parse(end) }.getOrNull() ?: return false
        if (!e.isAfter(s)) return false
        val desired = OccurrenceValues(note.trim(), st, room.trim().ifEmpty { null } ?: p.original.room, s, e)
        val next = if (series) PersonalChanges.setSeries(data.personalChanges, p, t.event.title, desired) else PersonalChanges.setSingle(data.personalChanges, p, t.event.title, desired)
        set(data.copy(personalChanges = next))
        return true
    }

    /** "Termine auswählen": [{key, date, start, end, room, state: ON|CANCELLED|HIDDEN}] of [key]'s group in my plan. */
    fun pickDates(key: String): String {
        val t = find(StoredData.ME, key) ?: return "[]"
        return JsonArray(
            StoreEdits.pickDates(data, t).map { (d, state) ->
                obj("key" to keyOf(d), "date" to d.date.toString(), "start" to hm(d.start), "end" to hm(d.end), "room" to d.room, "state" to state.name)
            },
        ).toString()
    }

    /** Saves "Termine auswählen": [statesJson] = {dateKey: ON|CANCELLED|HIDDEN} for the changed dates of [key]'s group. */
    fun setDates(key: String, statesJson: String) {
        val t = find(StoredData.ME, key) ?: return
        val wanted = json.parseToJsonElement(statesJson).jsonObject.mapValues { StoreEdits.DateState.valueOf((it.value as JsonPrimitive).content) }
        val states = StoreEdits.pickDates(data, t).mapNotNull { (d, _) -> wanted[keyOf(d)]?.let { d to it } }.toMap()
        if (states.isNotEmpty()) set(StoreEdits.setDates(data, states, t.event.title))
    }

    /** Own changes no date matches any more: [{index, title, date, series, until, cancelled, room, start, end, note, st}]. */
    fun orphans(): String {
        val list = PersonalChanges.orphans(data.myEvents, data.personalChanges)
        return JsonArray(
            list.map { c ->
                obj(
                    "index" to data.personalChanges.indexOf(c), "title" to c.title, "date" to c.date.toString(), "series" to c.series,
                    "until" to c.until?.toString(), "cancelled" to c.cancelled, "room" to c.room,
                    "start" to c.start?.toString(), "end" to c.end?.toString(), "note" to c.note, "st" to c.st,
                )
            },
        ).toString()
    }

    fun deleteChange(index: Int) {
        val c = data.personalChanges.getOrNull(index) ?: return
        set(data.copy(personalChanges = PersonalChanges.remove(data.personalChanges, c)))
    }

    fun setShareChanges(on: Boolean) = set(StoreEdits.setShareChanges(data, on))

    // ---- subjects

    private fun courseOf(personId: String, courseKey: String): Course? =
        views()[personId]?.subjects?.firstNotNullOfOrNull { s -> s.courses.firstOrNull { it.key == courseKey } }

    /**
     * The subjects of [personId]'s plan for the subject editor and the settings list: [{key, name, displayName,
     * shortName, color, auto, typeShortNames, courses: [{key, title, typeShort, typeName, courseId, manualId, added,
     * series: [{uid, first, start, end, chosen, auto}]}]}].
     */
    fun subjects(personId: String): String {
        val v = views()[personId] ?: return "[]"
        val rhythms = data.rhythmsOf(personId)
        val added = if (personId == StoredData.ME) data.addedUids else emptySet()
        return JsonArray(
            v.subjects.mapIndexed { i, s ->
                obj(
                    "key" to s.key, "name" to s.name, "displayName" to s.displayName, "shortName" to s.shortName,
                    "color" to (s.color ?: SubjectPalette.defaultColor(i)), "auto" to (s.color == null),
                    "hidden" to data.hiddenOf(personId).any { it.kind == Hidden.Kind.SUBJECT && it.subject == s.key },
                    "typeShortNames" to JsonObject(s.typeShortNames.mapValues { JsonPrimitive(it.value) }),
                    "courses" to s.courses.map { c ->
                        obj(
                            "key" to c.key, "title" to c.title, "typeShort" to c.type.short, "typeName" to HideTargets.typeName(c),
                            "courseId" to CourseKeys.courseId(c.events.first().uid),
                            "manualId" to c.events.firstOrNull { it.isManual }?.uid,
                            "added" to c.events.any { it.uid in added },
                            "series" to c.events.filter { it.recurrence?.biweekly == true && !it.isManual }.sortedBy { it.start }.map { e ->
                                val auto = Expansion.rhythmOf(e.copy(recurrence = e.recurrence?.copy(rhythm = null)))
                                obj(
                                    "uid" to e.uid, "first" to e.start.toLocalDate().toString(), "start" to hm(e.start), "end" to hm(e.end),
                                    "chosen" to rhythms[e.uid]?.name, "auto" to auto?.name,
                                )
                            },
                        )
                    },
                )
            },
        ).toString()
    }

    /** Short name prefix, color (-1 = automatic) and type parts ([typedJson]: {courseKey: part or null}) of a subject. */
    fun setSubjectStyle(personId: String, subjectKey: String, shortName: String?, color: Int, typedJson: String) {
        val subject = views()[personId]?.subjects?.firstOrNull { it.key == subjectKey } ?: return
        val typed = json.parseToJsonElement(typedJson).jsonObject.mapValues { (it.value as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.trim()?.ifEmpty { null } }
        set(StoreEdits.setSubjectStyle(data, personId, subject, shortName?.trim()?.ifEmpty { null }, color.takeIf { it >= 0 }, typed))
    }

    /** Moves a course to the subject [target] (a subject key, or "own:<course key>" for one of its own). */
    fun moveCourse(personId: String, courseKey: String, target: String) {
        val course = courseOf(personId, courseKey) ?: return
        set(StoreEdits.moveCourse(data, personId, course, target))
    }

    /** My rhythm for a biweekly series ([rhythm] null or "" = automatic). */
    fun setRhythm(personId: String, uid: String, rhythm: String?) {
        set(StoreEdits.setRhythm(data, personId, uid, rhythm?.ifEmpty { null }?.let(WeekRhythm::valueOf)))
    }

    // ---- manual courses (my plan)

    /** The stored manual course (JSON as in data.json) or null. */
    fun manualCourse(id: String): String? = data.manualCourses.firstOrNull { it.id == id }?.let { json.encodeToString(ManualCourse.serializer(), it) }

    /** A new manual course id from 16 random hex digits. */
    fun newManualId(hex16: String): String = ManualCourse.newId(hex16)

    /** Default last date of a new series: the end of the imported semester. */
    fun manualDefaultEnd(start: String): String = ManualCourses.defaultEnd(data.myPlan?.events.orEmpty(), LocalDate.parse(start)).toString()

    /**
     * Adds or replaces a manual course ([courseJson] as stored) in the subject [subjectKey] of my plan, or (null) in a
     * new one with [shortName] and [color] (-1 = automatic).
     */
    fun saveManual(courseJson: String, subjectKey: String?, shortName: String?, color: Int): Boolean {
        val course = runCatching { json.decodeFromString(ManualCourse.serializer(), courseJson) }.getOrNull() ?: return false
        if (!ManualCourse.isManual(course.id) || course.title.isBlank() || !course.end.isAfter(course.start)) return false
        val subjects = views()[StoredData.ME]?.subjects.orEmpty()
        val setting = StoreEdits.manualSetting(course, subjects, subjectKey, shortName?.trim()?.ifEmpty { null }, color.takeIf { it >= 0 })
        set(StoreEdits.saveManual(data, course, setting))
        return true
    }

    /** Deletes a manual course; its title for the snackbar, or null. */
    fun deleteManual(id: String): String? {
        val title = data.manualCourses.firstOrNull { it.id == id }?.title ?: return null
        set(StoreEdits.deleteManual(data, id) ?: return null)
        return title
    }

    // ---- added courses and friends' courses

    /** "Entfernen" of an added group in my plan (the group of [key]); its title, or null. */
    fun removeAdded(key: String): String? {
        val t = find(StoredData.ME, key) ?: return null
        set(StoreEdits.removeAdded(data, Groups.of(views()[StoredData.ME]!!.schedule, t)) ?: return null)
        return t.event.title
    }

    /** "Aus meinem Plan entfernen" in the subject editor: every added appointment of a course. */
    fun removeAddedCourse(courseKey: String): String? {
        val course = courseOf(StoredData.ME, courseKey) ?: return null
        set(StoreEdits.removeAdded(data, course.events.mapTo(HashSet()) { it.uid }) ?: return null)
        return course.title
    }

    /** "Zu meinem Plan": the group of a friend's date with its shown color; its title, or null. */
    fun addFromFriend(personId: String, key: String): String? {
        val v = views()[personId] ?: return null
        val t = find(personId, key) ?: return null
        val group = FriendCourses.group(data.planOf(personId)?.events.orEmpty(), t)
        set(StoreEdits.addFromFriend(data, group, colorOf(v, t)) ?: return null)
        return t.event.title
    }

    /** "Entfernen" in a friend's sheet: their group of [key] out of my added courses again. */
    fun removeFriendGroup(personId: String, key: String): String? {
        val t = find(personId, key) ?: return null
        val group = FriendCourses.group(data.planOf(personId)?.events.orEmpty(), t).mapTo(HashSet()) { it.uid }
        val mine = views()[StoredData.ME]?.schedule?.let { Groups.of(it, t) }.orEmpty()
        set(StoreEdits.removeAdded(data, group + mine) ?: return null)
        return t.event.title
    }

    // ---- friends

    fun renameFriend(id: String, name: String?) = set(StoreEdits.renameFriend(data, id, name))

    /** New drawer order ([idsJson]: friend ids). */
    fun reorderFriends(idsJson: String) {
        val ids = json.parseToJsonElement(idsJson).let { it as JsonArray }.map { (it as JsonPrimitive).content }
        set(StoreEdits.reorderFriends(data, ids))
    }

    // ---- .ics routing

    private var pendingIcs: Pair<String, ImportResult>? = null

    /**
     * Reads an .ics for import from the settings ([fromSettings]) or elsewhere and keeps it for the choice:
     * {route: replace|askReplaceOrAdd|askFriendOrAdd, canBeMine, currentCourses, incomingCourses, icsFriends: [{id, name}]}
     * or {error: notCalendar|noEvents}.
     */
    fun readIcs(text: String, fromSettings: Boolean): String {
        if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) return obj("error" to "notCalendar").toString()
        val result = LsfImport.parse(text)
        if (result.events.isEmpty()) return obj("error" to "noEvents").toString()
        pendingIcs = text to result
        val route = ImportRouting.route(if (fromSettings) IcsEntry.SETTINGS else IcsEntry.ELSEWHERE, data, result.events)
        return obj(
            "route" to when (route) {
                IcsRoute.ReplaceMine -> "replace"
                IcsRoute.AskReplaceOrAdd -> "askReplaceOrAdd"
                is IcsRoute.AskFriendOrAdd -> "askFriendOrAdd"
            },
            "canBeMine" to ((route as? IcsRoute.AskFriendOrAdd)?.canBeMine == true),
            "currentCourses" to AddedCourses.courseCount(data.myPlan?.events.orEmpty()),
            "incomingCourses" to AddedCourses.courseCount(result.events),
            "icsFriends" to ImportRouting.icsFriends(data).map { obj("id" to it.id, "name" to it.displayName) },
        ).toString()
    }

    /** The pending .ics replaces my plan: as [importIcs]; its text is in "text" for the raw copy. */
    fun icsReplaceMine(nowMs: Double): String {
        val (text, result) = pendingIcs ?: return obj("error" to "noEvents").toString()
        pendingIcs = null
        val r = StoreOps.importIcs(data, result, instant(nowMs))
        set(r.data)
        return obj("ok" to true, "events" to result.events.size, "dropped" to r.dropped, "text" to text).toString()
    }

    /** "Kurse zu meinem Plan hinzufügen": {added, updated, duplicates: [{title, day, start, end, room}]}. */
    fun icsAddCourses(): String {
        val (_, result) = pendingIcs ?: return obj("added" to 0, "updated" to 0, "duplicates" to emptyList<Any>()).toString()
        pendingIcs = null
        val r = StoreEdits.addCourses(data, result.events)
        set(r.data)
        return obj(
            "added" to r.added, "updated" to r.updated,
            "duplicates" to r.duplicates.map { e -> obj("title" to e.title, "day" to e.start.toLocalDate().toString(), "start" to hm(e.start), "end" to hm(e.end), "room" to e.location?.room) },
        ).toString()
    }

    /** "Als Freund hinzufügen": new ([existingId] null, named [name]) or an update of that .ics friend; {id, name, replaced}. */
    fun icsAddFriend(name: String, existingId: String?, hex16: String, nowMs: Double): String {
        val (_, result) = pendingIcs ?: return "null"
        require(Regex("^[0-9a-f]{16}$").matches(hex16)) { "id" }
        pendingIcs = null
        val r = StoreEdits.importIcsFriend(data, result.events, result.source, name, existingId, hex16, localNow(nowMs).toLocalDate(), instant(nowMs))
        set(r.data)
        return obj("id" to r.person.id, "name" to r.person.displayName, "replaced" to r.replaced).toString()
    }

    fun cancelIcs() {
        pendingIcs = null
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
        StoreEdits.deleteFriend(data, id)?.let(::set)
    }

    fun setFavorite(id: String, favorite: Boolean) = set(StoreEdits.setFavorite(data, id, favorite))

    // ---- sharing

    /** {id, name}: my share identity (id null until the first share). */
    fun shareIdentity(): String = obj("id" to data.share.id, "name" to data.share.name, "withChanges" to data.share.withChanges).toString()

    /** Sets my share name; [newId] (16 hex digits from crypto.getRandomValues) is used only if I have no id yet. */
    fun setShareName(name: String, newId: String) {
        name.trim().ifEmpty { null } ?: return
        require(Regex("^[0-9a-f]{16}$").matches(newId)) { "id" }
        set(StoreEdits.shareIdentity(data, name, newId))
    }

    private fun shared(personId: String, nowMs: Double, asName: String? = null): SharedPlan? {
        val today = localNow(nowMs).toLocalDate()
        return if (personId == StoredData.ME) {
            StoreOps.mySharedPlan(data, data.share, today, instant(nowMs))
        } else {
            val person = data.person(personId) ?: return null
            val plan = data.planOf(personId) ?: return null
            Forwarding.of(person, plan, today, PlanZone, asName ?: person.name)
        }
    }

    /** The QR payload (format 4) of [personId]'s plan as I'd share it; null without a name or events. */
    fun qrPayload(personId: String, nowMs: Double): ByteArray? = shared(personId, nowMs)?.let { ShareCodec.qrPayload(it) }

    /** A friend's plan forwarded under [name] (their shared name or my local one). */
    fun qrPayloadAs(personId: String, name: String, nowMs: Double): ByteArray? = shared(personId, nowMs, name)?.let { ShareCodec.qrPayload(it) }

    fun shareFileAs(personId: String, name: String, nowMs: Double): ByteArray? = shared(personId, nowMs, name)?.let { ShareCodec.file(it) }

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
