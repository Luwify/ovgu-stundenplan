package de.luwify.stundenplanmd.data

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.ManualType
import de.luwify.stundenplanmd.core.model.Plan
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.Expansion
import de.luwify.stundenplanmd.core.share.SharedPlan
import kotlinx.coroutines.runBlocking
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.data.settings.ColorRole
import de.luwify.stundenplanmd.data.settings.CustomBase
import de.luwify.stundenplanmd.data.settings.CustomThemeSpec
import de.luwify.stundenplanmd.data.settings.ThemeChoice
import de.luwify.stundenplanmd.data.settings.ThemeMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class PersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir() = tmp.newFolder()

    private fun event(uid: String, title: String) = Event(
        uid, "$title (Prof)", title, listOf("Prof"), "Vorlesung (V)", null, "",
        LocalDateTime.parse("2026-10-12T09:00"), LocalDateTime.parse("2026-10-12T11:00"),
    )

    private val events = listOf(
        event("900022000001", "Numerik N4 (HSÜ)"),
        event("900066000001", "Numerik N4 (VL)"),
        event("900033000001", "Ausgabe GFT III Skript"),
    )

    /** Short names, colors and a moved course: everything the user sets by hand. */
    private val settings = mapOf(
        "lsf:900022" to CourseSetting("lsf:900022", "Numerik N4 (HSÜ)", null, "N4x", 0xE91E63),
        "lsf:900066" to CourseSetting("lsf:900066", "Numerik N4 (VL)", null, "N4x", 0xE91E63),
        "lsf:900033" to CourseSetting("lsf:900033", "Ausgabe GFT III Skript", subject = Subjects.autoKey("Numerik N4 (VL)"), shortName = "N4x", color = 0xE91E63),
    )

    private val data = StoredData(
        plans = listOf(Plan(StoredData.ME, Instant.parse("2026-10-12T07:07:15Z"), "QIS-LSF HIS eG", events)),
        courseSettings = settings,
    )

    private fun assertUserDataSurvived(loaded: StoredData) {
        assertEquals(events, loaded.myPlan!!.events)
        assertEquals(settings, loaded.courseSettings)
        val subjects = Subjects.build(loaded.myPlan!!.events, loaded.courseSettings)
        val n4 = subjects.single { it.shortName == "N4x" }
        assertEquals(0xE91E63, n4.color)
        assertEquals(3, n4.courses.size) // the moved course is still in the subject
    }

    @Test
    fun currentFormatRoundTrips() {
        val d = dir()
        PlanStore(d).write(data)
        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals(null, read.migratedFrom)
        assertUserDataSurvived(read.data)
        assertEquals(Migrations.CURRENT, read.data.version)
    }

    @Test
    fun fileAsWrittenByTheFirstReleaseStillLoads() {
        val d = dir()
        File(d, "data.json").writeText(
            """{"version":1,"people":[{"id":"me","name":"Ich","isMe":true}],"plans":[],"courseSettings":
            {"lsf:900022":{"courseKey":"lsf:900022","title":"Numerik N4 (HSÜ)","shortName":"N4x","color":15277667}},"futureField":1}""",
        )
        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals("N4x", read.data.courseSettings.getValue("lsf:900022").shortName)
        assertEquals(15277667, read.data.courseSettings.getValue("lsf:900022").color)
    }

    /** Version 1 (first release, before friends) with a real-shaped plan: migrated to 2, nothing lost, old file kept. */
    @Test
    fun version1IsMigratedToFriends() {
        val d = dir()
        val v1 = PlanStore(d, current = 1, steps = emptyMap()).also { it.write(data) }.let { File(d, "data.json").readText() }
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it) as JsonObject }
            .let { o -> JsonObject(o - "friendSettings" - "share") }.toString()
        File(d, "data.json").writeText(v1)
        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals(1, read.migratedFrom)
        assertUserDataSurvived(read.data)
        assertEquals(emptyMap<String, Any>(), read.data.friendSettings)
        assertEquals(MyShare(), read.data.share)
        assertEquals(v1, File(d, "data.json.v1").readText())
        assertEquals(Migrations.CURRENT, (PlanStore(d).read() as StoreRead.Ok).data.version)
    }

    /** Version 2 showed friends sorted by name; version 3 stores that order, so the drawer looks the same. */
    @Test
    fun version2KeepsTheShownFriendOrderAndHasNoFavorites() {
        val d = dir()
        val people = listOf(
            Person("b", "zoe", isMe = false), Person(StoredData.ME, "Ich", isMe = true),
            Person("c", "Anna", isMe = false), Person("a", "Max", isMe = false, localName = "bert"),
        )
        PlanStore(d).write(data.copy(people = people))
        val o = kotlinx.serialization.json.Json.parseToJsonElement(File(d, "data.json").readText()) as JsonObject
        val v2 = JsonObject(o + ("version" to JsonPrimitive(2)) + ("people" to kotlinx.serialization.json.JsonArray(
            (o.getValue("people") as kotlinx.serialization.json.JsonArray).map { JsonObject((it as JsonObject) - "favorite") },
        ))).toString()
        File(d, "data.json").writeText(v2)
        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals(2, read.migratedFrom)
        assertUserDataSurvived(read.data)
        assertEquals(listOf(StoredData.ME, "c", "a", "b"), read.data.people.map { it.id })
        assertFalse(read.data.people.any { it.favorite })
        assertEquals(v2, File(d, "data.json.v2").readText())
    }

    /** Version 3 had no own changes: migrated to 4 with none, nothing else lost; changes then survive store and backup. */
    @Test
    fun version3GetsPersonalChangesThatSurviveStoreAndBackup() {
        val d = dir()
        PlanStore(d).write(data)
        val o = kotlinx.serialization.json.Json.parseToJsonElement(File(d, "data.json").readText()) as JsonObject
        val v3 = JsonObject(o - "personalChanges" + ("version" to JsonPrimitive(3)) + ("share" to JsonObject(mapOf("name" to JsonPrimitive("Lu"))))).toString()
        File(d, "data.json").writeText(v3)
        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals(3, read.migratedFrom)
        assertUserDataSurvived(read.data)
        assertEquals(emptyList<PersonalChange>(), read.data.personalChanges)
        assertEquals(MyShare(name = "Lu", withChanges = true), read.data.share)
        assertEquals(v3, File(d, "data.json.v3").readText())

        val changes = listOf(
            PersonalChange("900022000001", LocalDate.parse("2026-10-12"), title = "Numerik N4 (HSÜ)", note = "Raum K22", cancelled = true),
            PersonalChange(
                "900066000001", LocalDate.parse("2026-10-19"), series = true, until = LocalDate.parse("2026-12-01"), st = true,
                room = "K22-A", start = java.time.LocalTime.parse("09:15"), end = java.time.LocalTime.parse("10:45"),
            ),
        )
        val withChanges = read.data.copy(personalChanges = changes, share = read.data.share.copy(withChanges = false))
        PlanStore(d).write(withChanges)
        assertEquals(withChanges, (PlanStore(d).read() as StoreRead.Ok).data)
        val restored = BackupCodec.decode(BackupCodec.encode(withChanges, null, theme, "de", UpdatesBackup())) as BackupResult.Ok
        assertEquals(changes, restored.backup.data.personalChanges)
        assertFalse(restored.backup.data.share.withChanges)
    }

    /** Version 4 (own changes, on a real phone since 2026-10-13) gets manual courses in 5; its changes stay as they are. */
    @Test
    fun version4GetsManualCoursesThatSurviveStoreReimportAndBackup() = runBlocking {
        val d = dir()
        val change = PersonalChange("900022000001", LocalDate.parse("2026-10-12"), title = "Numerik N4 (HSÜ)", note = "x", st = true)
        PlanStore(d).write(data.copy(personalChanges = listOf(change)))
        val o = kotlinx.serialization.json.Json.parseToJsonElement(File(d, "data.json").readText()) as JsonObject
        val v4 = JsonObject(o - "manualCourses" + ("version" to JsonPrimitive(4))).toString()
        File(d, "data.json").writeText(v4)
        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals(4, read.migratedFrom)
        assertUserDataSurvived(read.data)
        assertEquals(listOf(change), read.data.personalChanges)
        assertEquals(emptyList<ManualCourse>(), read.data.manualCourses)
        assertEquals(v4, File(d, "data.json.v4").readText())

        val course = ManualCourse(
            id = ManualCourse.newId("0123456789abcdef"), title = "Lerngruppe", type = ManualType.SEMINAR, room = "Bibliothek",
            lecturer = "Tutor", note = "mit Laptop", st = true, rhythm = ManualCourse.Rhythm.BIWEEKLY,
            startDate = LocalDate.parse("2026-10-13"), endDate = LocalDate.parse("2027-01-26"),
            start = java.time.LocalTime.parse("17:00"), end = java.time.LocalTime.parse("18:30"), skipFreeWeeks = false,
        )
        val repo = PlanRepository(d)
        repo.load()
        repo.saveManual(course, CourseSetting(course.courseKey, course.title, shortName = "LG", color = 0x123456))
        // A re-import of the .ics leaves the manual course and its settings alone.
        repo.importIcs(File("../core/src/test/resources/lsf_scrubbed.ics").readText())
        val after = (PlanStore(d).read() as StoreRead.Ok).data
        assertEquals(listOf(course), after.manualCourses)
        assertEquals("LG", after.courseSettings.getValue(course.courseKey).shortName)
        assertTrue(after.myEvents.any { it.uid == course.id && it.st })
        val restored = BackupCodec.decode(BackupCodec.encode(after, null, theme, "de", UpdatesBackup())) as BackupResult.Ok
        assertEquals(listOf(course), restored.backup.data.manualCourses)
        // A backup made with version 4 data restores with no manual courses.
        val oldBackup = BackupCodec.encode(read.data, null, theme, "de", UpdatesBackup())
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it) as JsonObject }
            .let { b -> JsonObject(b + ("data" to JsonObject((b.getValue("data") as JsonObject) - "manualCourses" + ("version" to JsonPrimitive(4))))) }
        val fromOld = BackupCodec.decode(oldBackup.toString()) as BackupResult.Ok
        assertEquals(emptyList<ManualCourse>(), fromOld.backup.data.manualCourses)
        assertEquals(listOf(change), fromOld.backup.data.personalChanges)

        val previous = repo.deleteManual(course.id)!!
        assertTrue(repo.data.value!!.manualCourses.isEmpty())
        assertFalse(course.courseKey in repo.data.value!!.courseSettings)
        repo.restore(previous)
        assertEquals(listOf(course), repo.data.value!!.manualCourses)
    }

    @Test
    fun version5GetsRhythmsThatSurviveStoreReimportAndBackup() = runBlocking {
        val d = dir()
        val biweekly = ManualCourse(
            id = ManualCourse.newId("0123456789abcdef"), title = "Lerngruppe", rhythm = ManualCourse.Rhythm.BIWEEKLY,
            startDate = LocalDate.parse("2026-10-20"), endDate = LocalDate.parse("2027-01-26"),
            start = java.time.LocalTime.parse("17:00"), end = java.time.LocalTime.parse("18:30"), skipFreeWeeks = false,
        )
        PlanStore(d).write(withFriend.copy(manualCourses = listOf(biweekly)))
        val o = kotlinx.serialization.json.Json.parseToJsonElement(File(d, "data.json").readText()) as JsonObject
        val v5 = JsonObject(o - "rhythms" + ("version" to JsonPrimitive(5))).toString()
        File(d, "data.json").writeText(v5)
        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals(5, read.migratedFrom)
        assertUserDataSurvived(read.data)
        assertEquals(emptyMap<String, Map<String, WeekRhythm>>(), read.data.rhythms)
        assertEquals(v5, File(d, "data.json.v5").readText())
        // "Every 2 weeks" of version 5 stays every 14 days (12.01. and 26.01., not 05.01. and 19.01.).
        val manual = read.data.myEvents.single { it.isManual }
        assertEquals(LocalDate.parse("2027-01-26"), Expansion.occurrences(manual).last().start.toLocalDate())

        val repo = PlanRepository(d)
        repo.load()
        repo.importIcs(File("../core/src/test/resources/lsf_scrubbed.ics").readText())
        val uid = "900066500518" // N4 VL, odd weeks from the data
        repo.setRhythm(StoredData.ME, uid, WeekRhythm.EVERY_14_DAYS)
        repo.setRhythm(friendId, "900066000001", WeekRhythm.EVEN_WEEKS)
        repo.importIcs(File("../core/src/test/resources/lsf_scrubbed.ics").readText())
        val after = (PlanStore(d).read() as StoreRead.Ok).data
        assertEquals(mapOf(StoredData.ME to mapOf(uid to WeekRhythm.EVERY_14_DAYS), friendId to mapOf("900066000001" to WeekRhythm.EVEN_WEEKS)), after.rhythms)
        assertEquals(WeekRhythm.EVERY_14_DAYS, Expansion.rhythmOf(after.myEvents.single { it.uid == uid }))
        assertEquals(LocalDate.parse("2027-01-12"), after.mySchedule().occurrences.last { it.event.uid == uid }.date)
        val restored = BackupCodec.decode(BackupCodec.encode(after, null, theme, "de", UpdatesBackup())) as BackupResult.Ok
        assertEquals(after.rhythms, restored.backup.data.rhythms)
        // Back to automatic; a removed friend takes my rhythms for them along.
        repo.setRhythm(StoredData.ME, uid, null)
        assertEquals(WeekRhythm.ODD_WEEKS, Expansion.rhythmOf(repo.data.value!!.myEvents.single { it.uid == uid }))
        repo.deleteFriend(friendId)
        assertEquals(emptyMap<String, Map<String, WeekRhythm>>(), repo.data.value!!.rhythms)
    }

    /**
     * Version 8 adds what I hid (per person) and the type part of short names. A version 7 file keeps its short names as
     * the prefix shared by all types, so every name looks as before; hidden things survive a re-import, the backup, and
     * come back with "Wiederherstellen".
     */
    @Test
    fun version7GetsHidingAndKeepsShortNamesAsPrefix() = runBlocking {
        val d = dir()
        PlanStore(d).write(withFriend)
        val o = kotlinx.serialization.json.Json.parseToJsonElement(File(d, "data.json").readText()) as JsonObject
        val v7 = JsonObject(o - "hidden" + ("version" to JsonPrimitive(7))).toString()
        File(d, "data.json").writeText(v7)
        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals(7, read.migratedFrom)
        assertUserDataSurvived(read.data)
        assertEquals(emptyMap<String, List<Hidden>>(), read.data.hidden)
        assertEquals(v7, File(d, "data.json.v7").readText())
        // Short names: the old one is the prefix, no type has a part of its own; shown exactly as before.
        val n4 = Subjects.build(read.data.myPlan!!.events, read.data.courseSettings).single { it.shortName == "N4x" }
        assertEquals(emptyMap<String, String>(), n4.typeShortNames)
        n4.courses.forEach { assertEquals("N4x", n4.displayNameOf(it.key)) }

        val repo = PlanRepository(d)
        repo.load()
        val ics = File("../core/src/test/resources/lsf_scrubbed.ics").readText()
        repo.importIcs(ics)
        val subject = Subjects.build(repo.data.value!!.myEvents, repo.data.value!!.courseSettings).single { it.name == "Wellen und Felder" }
        val hideSubject = Hidden(Hidden.Kind.SUBJECT, subject = subject.key, courses = subject.courses.mapTo(LinkedHashSet()) { it.key }, title = subject.name)
        val first = repo.data.value!!.mySchedule().occurrences.first { it.event.courseKey == "lsf:900066" }
        val hideDate = Hidden(Hidden.Kind.DATE, courses = setOf("lsf:900066"), uid = first.ref.uid, date = first.ref.date, title = first.event.title)
        val hideFriend = Hidden(Hidden.Kind.COURSE, courses = setOf("lsf:900066"), title = "Numerik N4 (VL)")
        repo.hide(StoredData.ME, hideSubject)
        val beforeDate = repo.hide(StoredData.ME, hideDate)!!
        repo.hide(friendId, hideFriend)
        repo.updateSettings { Subjects.setTypeShortName(it, subject.courses.first(), "VL") }

        fun check(data: StoredData) {
            val mine = data.mySchedule()
            assertTrue(mine.occurrences.none { it.event.courseKey in hideSubject.courses })
            assertTrue(mine.cancellations.none { it.event.courseKey in hideSubject.courses })
            assertTrue(mine.occurrences.none { it.ref == first.ref })
            assertTrue(mine.occurrences.any { it.event.courseKey == "lsf:900066" })
            assertTrue(data.schedule(data.planOf(friendId)!!).occurrences.isEmpty())
            // The data is all still there.
            assertTrue(data.myEvents.any { it.courseKey in hideSubject.courses })
            assertEquals("VL", data.courseSettings.getValue(subject.courses.first().key).typeShortName)
        }
        // A re-import of the .ics brings nothing back.
        repo.importIcs(ics)
        val after = (PlanStore(d).read() as StoreRead.Ok).data
        assertEquals(listOf(hideSubject, hideDate), after.hiddenOf(StoredData.ME))
        check(after)
        // Backup round trip.
        val restored = (BackupCodec.decode(BackupCodec.encode(after, ics, theme, "de", UpdatesBackup())) as BackupResult.Ok).backup.data
        assertEquals(after.hidden, restored.hidden)
        check(restored)
        // "Wiederherstellen", and undo of a hide.
        repo.unhide(StoredData.ME, hideSubject)
        assertTrue(repo.data.value!!.mySchedule().occurrences.any { it.event.courseKey in hideSubject.courses })
        repo.restore(beforeDate)
        assertEquals(listOf(hideSubject), repo.data.value!!.hiddenOf(StoredData.ME))
        // A removed friend takes what I hid in their plan along.
        repo.deleteFriend(friendId)
        assertEquals(setOf(StoredData.ME), repo.data.value!!.hidden.keys)
    }

    /** A course only on Saturdays: in the overview, in the week (Saturday column), and removable with undo. */
    @Test
    fun weekendOnlyManualCourseIsReachableAndRemovable() = runBlocking {
        val repo = PlanRepository(dir())
        repo.load()
        repo.importIcs(File("../core/src/test/resources/lsf_scrubbed.ics").readText())
        val course = ManualCourse(
            id = ManualCourse.newId("00000000000000bb"), title = "Wochenendkurs", rhythm = ManualCourse.Rhythm.WEEKLY,
            startDate = LocalDate.parse("2026-10-18"), endDate = LocalDate.parse("2026-11-08"),
            start = java.time.LocalTime.parse("14:00"), end = java.time.LocalTime.parse("16:00"), skipFreeWeeks = false,
        )
        repo.saveManual(course, null)
        val data = repo.data.value!!
        assertTrue(Subjects.build(data.myEvents, data.courseSettings).any { s -> s.courses.any { it.key == course.courseKey } })
        val week = data.mySchedule().week(LocalDate.parse("2026-10-12"))
        assertEquals(LocalDate.parse("2026-10-18"), week.days.last().date)
        assertEquals(course.id, week.days.last().occurrences.single().event.uid)
        val previous = repo.deleteManual(course.id)!!
        assertEquals(5, repo.data.value!!.mySchedule().week(LocalDate.parse("2026-10-12")).days.size)
        repo.restore(previous)
        assertEquals(listOf(course), repo.data.value!!.manualCourses)
    }

    /**
     * "Termine auswählen": unselected dates either cancelled for me or hidden (then in the Ausgeblendet list and
     * restorable there); switching between the two never leaves both; all of it is in the backup. Stored with the
     * existing personal changes and hidden items, so no new storage version.
     */
    @Test
    fun pickedDatesAreCancelledOrHiddenAndRestorable() = runBlocking {
        val repo = PlanRepository(dir())
        repo.load()
        val ics = File("../core/src/test/resources/lsf_scrubbed.ics").readText()
        repo.importIcs(ics)
        val dates = repo.data.value!!.mySchedule().occurrences.filter { it.event.uid == "900066500555" }.take(2)
        val (a, b) = dates
        fun hiddenItem(t: de.luwify.stundenplanmd.core.schedule.Timed) =
            Hidden(Hidden.Kind.DATE, courses = setOf(t.event.courseKey), uid = t.ref.uid, date = t.ref.date, title = t.event.title)
        repo.setDates(cancelled = mapOf(a.ref to true, b.ref to false), hidden = mapOf(hiddenItem(a) to false, hiddenItem(b) to true), title = a.event.title)
        fun state(data: StoredData) = data.mySchedule().let { s ->
            listOf(a, b).map { t ->
                when {
                    s.occurrences.any { it.ref == t.ref } -> "on"
                    s.cancellations.any { it.ref == t.ref && it.byMe } -> "cancelled"
                    else -> "hidden"
                }
            }
        }
        assertEquals(listOf("cancelled", "hidden"), state(repo.data.value!!))
        assertEquals(listOf(hiddenItem(b)), repo.data.value!!.hiddenOf(StoredData.ME))
        // Backup round trip, and the next import keeps both.
        val restored = (BackupCodec.decode(BackupCodec.encode(repo.data.value!!, ics, theme, "de", UpdatesBackup())) as BackupResult.Ok).backup.data
        assertEquals(listOf("cancelled", "hidden"), state(restored))
        repo.importIcs(ics)
        assertEquals(listOf("cancelled", "hidden"), state(repo.data.value!!))
        // Switched: the cancelled one hidden, the hidden one cancelled; never both.
        repo.setDates(cancelled = mapOf(a.ref to false, b.ref to true), hidden = mapOf(hiddenItem(a) to true, hiddenItem(b) to false), title = a.event.title)
        assertEquals(listOf("hidden", "cancelled"), state(repo.data.value!!))
        assertTrue(repo.data.value!!.personalChanges.none { it.uid == a.ref.uid && it.date == a.ref.date && it.cancelled })
        // "Wiederherstellen" in the Ausgeblendet list brings it back.
        repo.unhide(StoredData.ME, hiddenItem(a))
        assertEquals(listOf("on", "cancelled"), state(repo.data.value!!))
    }

    /**
     * Version 6 holds friends' plans as apps before 1.2.2 decoded them: biweekly series counted every 14 days. Version 7
     * converts them once to even/odd calendar weeks and marks the plan where dates after the week 53 may differ; my plan,
     * .ics friends and friends without such a series stay as they are.
     */
    @Test
    fun version6ConvertsFriendsBiweeklySeriesOnce() {
        val d = dir()
        fun at(date: String) = LocalDateTime.parse("${date}T09:15")
        val lsfRule = de.luwify.stundenplanmd.core.model.Recurrence(
            interval = 2, byDay = setOf(java.time.DayOfWeek.TUESDAY), until = Instant.parse("2027-02-02T23:59:00Z"),
            exdates = setOf(at("2026-12-29"), at("2027-01-05")),
        )
        val lsf = event("900077000001", "Labor").copy(start = at("2026-12-15"), end = LocalDateTime.parse("2026-12-15T10:45"), recurrence = lsfRule)
        // What the older decoder stored for that series (14-day slots up to 26.01., 05.01. dropped).
        val old = lsf.copy(
            recurrence = de.luwify.stundenplanmd.core.model.Recurrence(
                interval = 2, until = LocalDate.parse("2027-01-26").atTime(23, 59).atZone(de.luwify.stundenplanmd.core.model.PlanZone).toInstant(),
                exdates = setOf(at("2026-12-29")),
            ),
        )
        val icsId = Person.ICS_ID_PREFIX + "fedcba9876543210"
        val quietId = "1111111111111111"
        val stored = withFriend.copy(
            people = withFriend.people + Person(icsId, "Ida", isMe = false) + Person(quietId, "Quinn", isMe = false),
            plans = withFriend.plans.map { it.copy(events = it.events + old) } +
                Plan(icsId, Instant.parse("2026-10-12T08:00:00Z"), "QIS-LSF HIS eG", listOf(old)) +
                Plan(quietId, Instant.parse("2026-10-12T08:00:00Z"), "share", events),
        )
        PlanStore(d).write(stored)
        val o = kotlinx.serialization.json.Json.parseToJsonElement(File(d, "data.json").readText()) as JsonObject
        val v6 = JsonObject(o + ("version" to JsonPrimitive(6))).toString()
        assertFalse(v6.contains("legacyShare\":true"))
        File(d, "data.json").writeText(v6)

        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals(6, read.migratedFrom)
        assertEquals(v6, File(d, "data.json.v6").readText())
        val friend = read.data.plans.single { it.personId == friendId }
        assertTrue(friend.legacyShare)
        val slots = Expansion.slots(friend.events.single { it.uid == old.uid })
        assertEquals(listOf("2026-12-15", "2026-12-29", "2027-01-05", "2027-01-19", "2027-02-02").map(::at), slots.map { it.start })
        assertEquals(listOf(at("2026-12-29")), slots.filter { it.cancelled }.map { it.start })
        assertEquals(withFriend.plans.single { it.personId == friendId }.events, friend.events.filter { it.uid != old.uid })
        // My plan and the .ics friend keep their events; a friend without such a series gets no hint.
        assertEquals(stored.plans.single { it.personId == StoredData.ME }, read.data.myPlan)
        assertEquals(listOf(old), read.data.plans.single { it.personId == icsId }.events)
        assertFalse(read.data.plans.single { it.personId == icsId }.legacyShare)
        assertEquals(events, read.data.plans.single { it.personId == quietId }.events)
        assertFalse(read.data.plans.single { it.personId == quietId }.legacyShare)
        assertEquals(stored.friendSettings, read.data.friendSettings)
        // Written once as version 7: reading again converts nothing more.
        val again = PlanStore(d).read() as StoreRead.Ok
        assertEquals(Migrations.CURRENT, again.data.version)
        assertEquals(read.data.plans, again.data.plans)
        // The hint survives a backup.
        val restored = BackupCodec.decode(BackupCodec.encode(read.data, null, theme, "de", UpdatesBackup())) as BackupResult.Ok
        assertTrue(restored.backup.data.plans.single { it.personId == friendId }.legacyShare)
    }

    @Test
    fun friendOrderAndFavoritesArePersistedAndInTheBackup() = runBlocking {
        val repo = PlanRepository(dir())
        repo.load()
        repo.restore(withFriend.copy(people = withFriend.people + Person("1111111111111111", "Anna", isMe = false)))
        repo.reorderFriends(listOf("1111111111111111", friendId))
        repo.setFavorite(friendId, true)
        val d = repo.data.value!!
        assertEquals(listOf(StoredData.ME, "1111111111111111", friendId), d.people.map { it.id })
        assertTrue(d.person(friendId)!!.favorite)
        val again = PlanRepository(File(tmp.root, "x").apply { mkdirs() })
        PlanStore(File(tmp.root, "x")).write(d)
        again.load()
        assertEquals(d, again.data.value)
        assertEquals(d, (BackupCodec.decode(BackupCodec.encode(d, null, theme, "system", UpdatesBackup())) as BackupResult.Ok).backup.data)
    }

    private val friendId = "0123456789abcdef"
    private val withFriend = data.copy(
        people = data.people + Person(friendId, "Tom", isMe = false, localName = "Tommy"),
        plans = data.plans + Plan(
            friendId, Instant.parse("2026-10-12T08:00:00Z"), "share",
            listOf(event("900066000001", "Numerik N4 (VL)").copy(notes = "kurz…", notesShortened = true)),
            exportedOn = LocalDate.parse("2026-10-12"), planDate = LocalDate.parse("2026-10-10"),
            sharedSettings = mapOf("lsf:900066" to CourseSetting("lsf:900066", "Numerik N4 (VL)", null, "Mathe", 0x00FF00)),
        ),
        friendSettings = mapOf(friendId to mapOf("lsf:900066" to CourseSetting("lsf:900066", "Numerik N4 (VL)", null, "MA", 0x0000FF))),
        share = MyShare("fedcba9876543210", "Ich selbst"),
    )

    @Test
    fun friendsRoundTripInStoreAndBackup() {
        val d = dir()
        PlanStore(d).write(withFriend)
        assertEquals(withFriend, (PlanStore(d).read() as StoreRead.Ok).data)
        val backup = BackupCodec.decode(BackupCodec.encode(withFriend, null, theme, "system", UpdatesBackup())) as BackupResult.Ok
        assertEquals(withFriend, backup.backup.data)
    }

    /** Data stored before the note-hint fix: my notes and a friend's file import carry no flag, so no hint shows. */
    @Test
    fun storedNotesFromBeforeTheHintFixShowNoHint() {
        val d = dir()
        val mine = event("900022000001", "Numerik N4 (HSÜ)").copy(notes = "Bitte Skript mitbringen")
        val stored = withFriend.copy(
            plans = listOf(
                Plan(StoredData.ME, Instant.parse("2026-10-12T07:07:15Z"), "QIS-LSF HIS eG", listOf(mine)),
                withFriend.plans.last().copy(events = listOf(mine.copy(notes = "Lange Notiz aus der Datei"))),
            ),
            version = 2,
        )
        val json = (kotlinx.serialization.json.Json.parseToJsonElement(PlanStore(d).also { it.write(stored) }.let { File(d, "data.json").readText() }) as JsonObject)
        File(d, "data.json").writeText(JsonObject(json + ("version" to JsonPrimitive(2))).toString())
        val loaded = (PlanStore(d).read() as StoreRead.Ok).data
        loaded.plans.flatMap { it.events }.forEach { assertEquals(de.luwify.stundenplanmd.core.model.NoteHint.NONE, it.noteHint) }
    }

    private fun shared(name: String, events: List<Event>) = SharedPlan(
        friendId, name, LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-11"), events,
        mapOf("lsf:900066" to CourseSetting("lsf:900066", "Numerik N4 (VL)", null, "Mathe", 0x00FF00)), complete = false,
    )

    @Test
    fun friendImportReplacesTheirPlanAndKeepsMySettingsForThem() = runBlocking {
        val repo = PlanRepository(dir())
        repo.load()
        val first = repo.importFriend(shared("Tom", listOf(event("900066000001", "Numerik N4 (VL)")))) as FriendImport.Imported
        assertFalse(first.replaced)
        repo.renameFriend(friendId, "Tommy")
        repo.updateFriendSettings(friendId) { it + ("lsf:900066" to CourseSetting("lsf:900066", "Numerik N4 (VL)", null, "MA", 0x0000FF)) }

        val second = repo.importFriend(shared("Thomas", listOf(event("900066000002", "Numerik N4 (VL)"), event("900077000001", "Neu")))) as FriendImport.Imported
        assertTrue(second.replaced)
        val d = repo.data.value!!
        assertEquals(1, d.friends.size)
        assertEquals("Thomas", d.person(friendId)!!.name)
        assertEquals("Tommy", d.person(friendId)!!.displayName)
        assertEquals(2, d.planOf(friendId)!!.events.size)
        assertEquals(LocalDate.parse("2026-10-11"), d.planOf(friendId)!!.planDate)
        assertEquals("MA", d.friendSettings.getValue(friendId).getValue("lsf:900066").shortName)

        val identity = repo.shareIdentity("Ich")
        assertTrue(identity.id!!.matches(Regex("[0-9a-f]{16}")))
        assertEquals(identity, repo.shareIdentity())
        assertEquals(FriendImport.Own, repo.importFriend(shared("Ich", emptyList()).copy(personId = identity.id!!)))

        val before = repo.deleteFriend(friendId)!!
        assertTrue(repo.data.value!!.friends.isEmpty())
        assertFalse(friendId in repo.data.value!!.friendSettings)
        repo.restore(before)
        assertEquals("Tommy", repo.data.value!!.person(friendId)!!.displayName)
    }

    /** Simulated format 2 in which `settings` was renamed to `courseSettings`; a version 1 file with the old name is migrated. */
    private val rename: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        1 to { o -> JsonObject(o.filterKeys { it != "settings" } + ("courseSettings" to o.getValue("settings"))) },
    )

    @Test
    fun olderVersionIsMigratedAndTheOldFileKept() {
        val d = dir()
        val old = PlanStore(d, current = 1).also { it.write(data) }.let { File(d, "data.json").readText() }
            .replace("\"courseSettings\"", "\"settings\"")
        File(d, "data.json").writeText(old)
        val store = PlanStore(d, current = 2, steps = rename)
        val read = store.read() as StoreRead.Ok
        assertEquals(1, read.migratedFrom)
        assertUserDataSurvived(read.data)
        assertEquals(old, File(d, "data.json.v1").readText())
        // rewritten in the new format: loads again without migration
        val again = PlanStore(d, current = 2, steps = rename).read() as StoreRead.Ok
        assertEquals(null, again.migratedFrom)
        assertUserDataSurvived(again.data)
    }

    @Test
    fun unreadableFileIsKeptNotOverwritten() {
        val d = dir()
        File(d, "data.json").writeText("{ this is not json")
        val store = PlanStore(d)
        val read = store.read() as StoreRead.Unreadable
        assertEquals("data.json.broken", read.keptAs)
        assertEquals("{ this is not json", File(d, "data.json.broken").readText())
        assertFalse(File(d, "data.json").exists())
        // the app goes on with an empty state and writes a new file: the broken one stays
        store.write(StoredData())
        assertEquals("{ this is not json", File(d, "data.json.broken").readText())
        // a second broken file does not replace the first
        File(d, "data.json").writeText("[1]")
        assertTrue(PlanStore(d).read() is StoreRead.Unreadable)
        assertEquals("{ this is not json", File(d, "data.json.broken").readText())
        assertEquals("[1]", File(d, "data.json.broken.1").readText())
    }

    @Test
    fun fileFromANewerAppIsKeptNotDiscarded() {
        val d = dir()
        val text = """{"version":99,"plans":[],"shiny":"new"}"""
        File(d, "data.json").writeText(text)
        assertTrue(PlanStore(d).read() is StoreRead.Unreadable)
        assertEquals(text, File(d, "data.json.broken").readText())
    }

    @Test
    fun failedWriteKeepsThePreviousFile() {
        val d = dir()
        val store = PlanStore(d)
        store.write(data)
        val before = File(d, "data.json").readText()
        try {
            AtomicFiles.write(File(d, "data.json")) { out ->
                out.write("{\"version\":1,\"half".encodeToByteArray())
                throw IOException("disk full")
            }
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(before, File(d, "data.json").readText())
        assertFalse(File(d, "data.json.tmp").exists())
        assertUserDataSurvived((PlanStore(d).read() as StoreRead.Ok).data)
    }

    @Test
    fun leftoverBackupOfAnInterruptedOldStyleWriteIsRestored() {
        val d = dir()
        PlanStore(d).write(data)
        val good = File(d, "data.json").readText()
        File(d, "data.json.bak").writeText(good)
        File(d, "data.json").writeText("{\"version\":1,\"hal") // half-written
        assertUserDataSurvived((PlanStore(d).read() as StoreRead.Ok).data)
    }

    @Test
    fun missingFileIsAFreshStart() {
        assertEquals(StoreRead.Fresh, PlanStore(dir()).read())
    }

    private val theme = ThemeChoice(
        ThemeMode.CUSTOM,
        CustomThemeSpec(CustomBase.BLACK, 0x00FF88, glow = true, overrides = mapOf(ColorRole.HIGHLIGHT to 0xFF6B9D)),
    )

    @Test
    fun backupFromBeforeDisplaySettingsKeepsTheCurrentOnes() {
        val v3 = BackupCodec.encode(data, null, theme, "de", UpdatesBackup(false, 7), DisplayBackup(false))
            .replace("\"version\":${BackupCodec.VERSION}", "\"version\":3").replace(",\"display\":{\"fitLandscape\":false}", "")
        assertTrue(v3.contains("\"version\":3") && !v3.contains("display"))
        val ok = BackupCodec.decode(v3) as BackupResult.Ok
        assertEquals(null, ok.backup.display)
        assertEquals(UpdatesBackup(false, 7), ok.backup.updates)
    }

    @Test
    fun backupRoundTripsEverything() {
        val ics = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"
        val text = BackupCodec.encode(data, ics, theme, "en", UpdatesBackup(autoCheck = false, skippedCode = 10002), DisplayBackup(fitLandscape = false), Instant.parse("2026-10-12T10:00:00Z"))
        val ok = BackupCodec.decode(text) as BackupResult.Ok
        assertUserDataSurvived(ok.backup.data)
        assertEquals(ics, ok.backup.ics)
        assertEquals(theme, ok.backup.theme.toChoice())
        assertEquals("2026-10-12T10:00:00Z", ok.backup.exportedAt)
        assertEquals("en", ok.backup.language)
        assertEquals(UpdatesBackup(autoCheck = false, skippedCode = 10002), ok.backup.updates)
        assertEquals(DisplayBackup(fitLandscape = false), ok.backup.display)
        assertEquals(de.luwify.stundenplanmd.AppLanguage.ENGLISH, de.luwify.stundenplanmd.AppLanguage.fromKey(ok.backup.language))
    }

    @Test
    fun backupVersion2HasNoUpdateSettingsAndKeepsTheCurrentOnes() {
        val v2 = BackupCodec.encode(data, null, theme, "de", UpdatesBackup(false, 7)).replace("\"version\":${BackupCodec.VERSION}", "\"version\":2")
            .replace(",\"updates\":{\"autoCheck\":false,\"skippedCode\":7}", "")
        assertTrue(v2.contains("\"version\":2") && !v2.contains("updates"))
        val ok = BackupCodec.decode(v2) as BackupResult.Ok
        assertUserDataSurvived(ok.backup.data)
        assertEquals("de", ok.backup.language)
        assertEquals(null, ok.backup.updates)
        assertEquals(BackupCodec.VERSION, BackupCodec.upgrade(kotlinx.serialization.json.Json.parseToJsonElement(v2) as JsonObject)["version"]?.let { (it as JsonPrimitive).content.toInt() })
        // Defaults for a block written without the skipped version.
        val partial = BackupCodec.encode(data, null, theme, "de", UpdatesBackup(true, null))
        assertEquals(UpdatesBackup(true, null), (BackupCodec.decode(partial) as BackupResult.Ok).backup.updates)
    }

    @Test
    fun backupVersion1HasNoLanguageAndKeepsTheCurrentOne() {
        val v1 = BackupCodec.encode(data, null, theme, "de", UpdatesBackup(false, 7)).replace("\"version\":${BackupCodec.VERSION}", "\"version\":1")
            .replace(",\"language\":\"de\"", "").replace(",\"updates\":{\"autoCheck\":false,\"skippedCode\":7}", "")
        assertTrue(v1.contains("\"version\":1") && !v1.contains("language"))
        val ok = BackupCodec.decode(v1) as BackupResult.Ok
        assertUserDataSurvived(ok.backup.data)
        assertEquals(null, ok.backup.language)
        assertEquals(null, ok.backup.updates)
        assertEquals(null, de.luwify.stundenplanmd.AppLanguage.fromKey(null)) // restore leaves the language alone
        assertEquals(de.luwify.stundenplanmd.AppLanguage.SYSTEM, de.luwify.stundenplanmd.AppLanguage.fromKey("system"))
        assertEquals(null, de.luwify.stundenplanmd.AppLanguage.fromKey("fr"))
    }

    @Test
    fun backupWithoutIcsAndGarbageAreHandled() {
        assertEquals(null, (BackupCodec.decode(BackupCodec.encode(data, null, theme, "system", UpdatesBackup())) as BackupResult.Ok).backup.ics)
        assertEquals(BackupResult.NotABackup, BackupCodec.decode("""{"hello":1}"""))
        assertTrue(BackupCodec.decode("nope") !is BackupResult.Ok)
        assertTrue(BackupCodec.decode("{\"format\":\"stundenplanmd-backup\",\"version\":1,\"data\":[1]}") is BackupResult.Broken)
        assertEquals(BackupResult.TooNew, BackupCodec.decode("""{"format":"stundenplanmd-backup","version":7,"data":{}}"""))
        val newerData = """{"format":"stundenplanmd-backup","version":1,"data":{"version":42}}"""
        assertEquals(BackupResult.TooNew, BackupCodec.decode(newerData))
    }

    @Test
    fun themePreferencesAreStampedAndNeverLost() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("theme_mode") to "amoled")
        de.luwify.stundenplanmd.data.settings.ThemeSettings(FakeStore).migratePrefs(prefs)
        assertEquals("amoled", prefs[stringPreferencesKey("theme_mode")])
        assertEquals(de.luwify.stundenplanmd.data.settings.ThemeSettings.SCHEMA, prefs[intPreferencesKey("schema_version")])
        val newer = mutablePreferencesOf(intPreferencesKey("schema_version") to 9)
        de.luwify.stundenplanmd.data.settings.ThemeSettings(FakeStore).migratePrefs(newer)
        assertEquals(9, newer[intPreferencesKey("schema_version")])
    }

    @Test
    fun updatePreferencesAreStampedAndNeverLost() {
        val prefs = mutablePreferencesOf(
            androidx.datastore.preferences.core.booleanPreferencesKey("auto_check") to false,
            stringPreferencesKey("manifest") to "{}",
        )
        de.luwify.stundenplanmd.data.update.UpdateSettings(FakeStore).migratePrefs(prefs)
        assertEquals(false, prefs[androidx.datastore.preferences.core.booleanPreferencesKey("auto_check")])
        assertEquals("{}", prefs[stringPreferencesKey("manifest")])
        assertEquals(de.luwify.stundenplanmd.data.update.UpdateSettings.SCHEMA, prefs[intPreferencesKey("schema_version")])
        val newer = mutablePreferencesOf(intPreferencesKey("schema_version") to 9)
        de.luwify.stundenplanmd.data.update.UpdateSettings(FakeStore).migratePrefs(newer)
        assertEquals(9, newer[intPreferencesKey("schema_version")])
    }

    @Test
    fun versionOfAFileWithoutTheFieldIsTheFirstFormat() {
        assertEquals(1, Migrations.versionOf(JsonObject(emptyMap())))
        assertEquals(3, Migrations.versionOf(JsonObject(mapOf("version" to JsonPrimitive(3)))))
        assertNotNull(Migrations.migrate(JsonObject(emptyMap())))
    }

    /** The shared name stays stored apart from my rename: kept by an update (the new shared name replaces it), and in the backup. */
    @Test
    fun originalFriendNameIsStoredUpdatedAndBackedUp() = runBlocking {
        val repo = PlanRepository(dir())
        repo.load()
        val events = listOf(event("900066000001", "Numerik N4 (VL)"))
        fun shared(name: String) = SharedPlan("0123456789abcdef", name, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 11), events, emptyMap(), complete = true)
        val added = (repo.importFriend(shared("Max Müller")) as FriendImport.Imported).person
        assertEquals("Max Müller", added.name)
        assertFalse(added.renamed)
        repo.renameFriend(added.id, "Maxi")
        assertTrue(repo.data.value!!.person(added.id)!!.renamed)
        // Update: my name stays, the original follows what they share now.
        repo.importFriend(shared("Maximilian Müller"))
        val updated = repo.data.value!!.person(added.id)!!
        assertEquals("Maximilian Müller", updated.name)
        assertEquals("Maxi", updated.localName)
        val restored = (BackupCodec.decode(BackupCodec.encode(repo.data.value!!, null, theme, "de", UpdatesBackup())) as BackupResult.Ok).backup.data
        assertEquals(updated, restored.person(added.id))
        // An .ics friend: the name given at import is the original; an update keeps it and my rename.
        val ics = repo.importIcsFriend(events, null, "Lena", null, LocalDate.of(2026, 10, 14)).person
        repo.renameFriend(ics.id, "Lenchen")
        repo.importIcsFriend(events, null, "Anders", ics.id, LocalDate.of(2026, 10, 15))
        assertEquals("Lena", repo.data.value!!.person(ics.id)!!.name)
        assertEquals("Lenchen", repo.data.value!!.person(ics.id)!!.localName)
        // Stored data from before (no extra field was ever needed): a friend never renamed needs no choice.
        assertFalse(Person("0123456789abcdef", "Max", isMe = false).renamed)
    }
}

private object FakeStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    override val data = kotlinx.coroutines.flow.emptyFlow<androidx.datastore.preferences.core.Preferences>()
    override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
        throw UnsupportedOperationException()
}
