package de.luwify.stundenplanmd.core

import de.luwify.stundenplanmd.core.compat.CompatEdits
import de.luwify.stundenplanmd.core.compat.CompatProbe
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.FriendCourses
import de.luwify.stundenplanmd.core.store.HideTargets
import de.luwify.stundenplanmd.core.store.StoreEdits
import de.luwify.stundenplanmd.core.store.SubjectPalette
import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.schedule.PersonalChanges
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.SharedPlan
import de.luwify.stundenplanmd.core.store.MyShare
import de.luwify.stundenplanmd.core.store.StoreOps
import de.luwify.stundenplanmd.core.store.StoredData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * `./gradlew :core:deviceSeed --args="<dir>"`: fake data for update checks on the debug copy (`.preview` only):
 * `data.json` + `me.ics` as the app's PlanStore writes them (the fixture with my share identity, styles, short names
 * incl. a type part, own changes with notes, room, s.t. and a cancellation, a manual course, hidden subject and date,
 * three friends: a favorite, a local rename, local settings and a hidden course for one; then [richer]: a moved course,
 * a rhythm, hidden group/type, picked dates, a Saturday manual course, an .ics friend, "Zu meinem Plan", added courses), `data-v7.json` (the same
 * before storage step 7 -> 8, for a migration check), and `friend-new.stundenplan`, one more friend's share file.
 */
fun deviceSeed(out: File) {
    out.mkdirs()
    val ics = Fixture.text
    val events = LsfImport.parse(ics).events
    var d = CompatProbe.imported(ics).copy(share = MyShare("5eed00005eed0001", "Alex"))
    val subjects = Subjects.build(d.myEvents, d.courseSettings)
    d = d.copy(courseSettings = Subjects.setStyle(d.courseSettings, subjects[0], "Ana", 0x8E24AA))
    d = d.copy(courseSettings = Subjects.setStyle(d.courseSettings, subjects[1], "Num", null))
    val typed = subjects[1].courses.first().key
    d = d.copy(courseSettings = d.courseSettings + (typed to d.courseSettings.getValue(typed).copy(typeShortName = "VL")))
    val occ = d.mySchedule().occurrences
    var changes = PersonalChanges.setCancelled(emptyList(), occ[3].ref, occ[3].event.title, true)
    changes = changes + PersonalChange(occ[0].ref.uid, occ[0].ref.date, series = true, title = occ[0].event.title, note = "Serie: Skript mitbringen")
    changes = changes + PersonalChange(occ[5].ref.uid, occ[5].ref.date, title = occ[5].event.title, note = "Einzeltermin", room = "K99-001")
    changes = changes + PersonalChange(occ[9].ref.uid, occ[9].ref.date, title = occ[9].event.title, st = true)
    d = d.copy(
        personalChanges = changes,
        manualCourses = listOf(
            ManualCourse(
                id = "manual-5eed00005eed00aa", title = "Lerngruppe", room = "Bibliothek", note = "eigener Kurs",
                rhythm = ManualCourse.Rhythm.WEEKLY, startDate = LocalDate.of(2026, 10, 19), endDate = LocalDate.of(2027, 2, 5),
                start = LocalTime.of(18, 0), end = LocalTime.of(19, 30),
            ),
        ),
    )
    val last = subjects.last()
    val h = occ[7]
    d = d.copy(
        hidden = mapOf(
            StoredData.ME to listOf(
                Hidden(Hidden.Kind.SUBJECT, subject = last.key, courses = last.courses.map { it.key }.toSet(), title = last.name),
                Hidden(Hidden.Kind.DATE, courses = setOf(h.event.courseKey), uid = h.ref.uid, date = h.ref.date, title = h.event.title),
            ),
        ),
    )
    fun friend(id: String, name: String, keep: (Int) -> Boolean) = SharedPlan(
        personId = id, name = name, exportedOn = LocalDate.of(2026, 10, 30), planDate = LocalDate.of(2026, 10, 20),
        events = events.filterIndexed { i, _ -> keep(i) }, settings = emptyMap(), complete = true,
    )
    for (f in listOf(friend("5eed0000000000a1", "Tom", { it % 3 != 0 }), friend("5eed0000000000a2", "Lea", { it % 2 == 0 }), friend("5eed0000000000a3", "Kai", { it % 4 != 1 }))) {
        val parsed = (de.luwify.stundenplanmd.core.share.ShareCodec.parse(ShareCodec.file(f)) as de.luwify.stundenplanmd.core.share.ShareResult.Ok).plan
        d = (StoreOps.importFriend(d, parsed, CompatProbe.NOW) as StoreOps.FriendResult.Imported).data
    }
    d = d.copy(people = d.people.map {
        when (it.id) {
            "5eed0000000000a2" -> it.copy(favorite = true)
            "5eed0000000000a3" -> it.copy(localName = "Kai (Mathe)")
            else -> it
        }
    })
    val tom = "5eed0000000000a1"
    val tomSubjects = Subjects.build(d.eventsOf(tom), d.settingsOf(tom))
    d = d.copy(
        friendSettings = d.friendSettings + (tom to Subjects.setStyle(emptyMap(), tomSubjects[0], "T1", 0x00897B)),
        hidden = d.hidden + (tom to listOf(Hidden(Hidden.Kind.COURSE, courses = setOf(tomSubjects[1].courses.first().key), title = tomSubjects[1].name))),
    )
    d = richer(d, ics)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val text = json.encodeToString(StoredData.serializer(), d)
    File(out, "data.json").writeText(text)
    File(out, "me.ics").writeText(ics)
    // Storage version 7: no `hidden` yet (step 7 -> 8 adds it); the rest is the same.
    val v7 = JsonObject(json.parseToJsonElement(text).jsonObject.filterKeys { it != "hidden" } + ("version" to JsonPrimitive(7)))
    File(out, "data-v7.json").writeText(v7.toString())
    File(out, "friend-new.stundenplan").writeBytes(ShareCodec.file(friend("5eed0000000000a4", "Mia", { it % 5 != 2 })))
    println(
        "deviceSeed: $out (version ${d.version}, ${d.people.size} people, ${d.personalChanges.size} own changes, " +
            "${d.hidden.values.sumOf { it.size }} hidden, ${d.manualCourses.size} manual courses, ${d.addedEvents.size} added events, ${d.rhythms.values.sumOf { it.size }} rhythms)",
    )
}

/**
 * The rest of every edit kind, through [StoreEdits] (the same calls as the app): style with type parts, a moved course,
 * a rhythm, hidden group and type, "Termine auswählen", a weekend manual course with its own subject, an .ics friend
 * (style, rename), "Zu meinem Plan" from them, and courses added from another .ics.
 */
private fun richer(start: StoredData, ics: String): StoredData {
    var d = start
    CompatEdits.styleSubject(d).let { s -> d = StoreEdits.setSubjectStyle(d, StoredData.ME, s, "Ana2", 0x00897B, mapOf(s.courses.first().key to "Ü")) }
    CompatEdits.moveCourse(d).let { c -> d = StoreEdits.moveCourse(d, StoredData.ME, c, Subjects.ownKey(c.key)) }
    d = StoreEdits.setRhythm(d, StoredData.ME, CompatEdits.rhythmUid(d), WeekRhythm.EVEN_WEEKS)
    CompatEdits.groupDate(d).let { t -> d = StoreEdits.hide(d, StoredData.ME, HideTargets.group(d.mySchedule(), t)) }
    CompatEdits.typeDate(d).let { t ->
        val course = StoreEdits.subjectsOf(d, StoredData.ME).flatMap { it.courses }.first { it.key == t.event.courseKey }
        d = StoreEdits.hide(d, StoredData.ME, HideTargets.course(course))
    }
    CompatEdits.pickDate(d).let { t ->
        val dates = StoreEdits.pickDates(d, t).map { it.first }
        d = StoreEdits.setDates(d, mapOf(dates[0] to StoreEdits.DateState.CANCELLED, dates[1] to StoreEdits.DateState.HIDDEN), t.event.title)
    }
    val m = CompatEdits.manual
    d = StoreEdits.saveManual(d, m, StoreEdits.manualSetting(m, StoreEdits.subjectsOf(d, StoredData.ME), null, "LG", 0xF4511E))
    LsfImport.parse(CompatEdits.friendIcs(ics)).let { r ->
        d = StoreEdits.importIcsFriend(d, r.events, r.source, "Kim", null, CompatEdits.ICS_FRIEND_HEX, CompatEdits.TODAY, CompatProbe.NOW).data
    }
    val kim = CompatEdits.icsFriendId()
    StoreEdits.subjectsOf(d, kim).first().let { s -> d = StoreEdits.setSubjectStyle(d, kim, s, "K", null) }
    d = StoreEdits.renameFriend(d, kim, "Kimmy")
    CompatEdits.friendDate(d).let { t ->
        val subjects = StoreEdits.subjectsOf(d, kim)
        val subject = subjects.first { s -> s.courses.any { it.key == t.event.courseKey } }
        val color = subject.color ?: SubjectPalette.defaultColor(subjects.indexOf(subject))
        d = StoreEdits.addFromFriend(d, FriendCourses.group(d.planOf(kim)!!.events, t), color)!!
    }
    // Two series of another export (a course of its own) added to my plan.
    val added = LsfImport.parse(CompatEdits.addedIcs(ics)).events
    val course = added.last().courseKey
    d = StoreEdits.addCourses(d, added.filter { it.courseKey == course }).data
    return d
}

fun main(args: Array<String>) = deviceSeed(File(args.firstOrNull() ?: error("usage: <out dir>")))
