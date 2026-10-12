package de.luwify.stundenplanmd.data

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.Plan
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
        val m3 = subjects.single { it.shortName == "N4x" }
        assertEquals(0xE91E63, m3.color)
        assertEquals(3, m3.courses.size) // the moved course is still in the subject
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
        assertEquals(2, (PlanStore(d).read() as StoreRead.Ok).data.version)
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
        val backup = BackupCodec.decode(BackupCodec.encode(withFriend, null, theme)) as BackupResult.Ok
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
    fun backupRoundTripsEverything() {
        val ics = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"
        val text = BackupCodec.encode(data, ics, theme, Instant.parse("2026-10-12T10:00:00Z"))
        val ok = BackupCodec.decode(text) as BackupResult.Ok
        assertUserDataSurvived(ok.backup.data)
        assertEquals(ics, ok.backup.ics)
        assertEquals(theme, ok.backup.theme.toChoice())
        assertEquals("2026-10-12T10:00:00Z", ok.backup.exportedAt)
    }

    @Test
    fun backupWithoutIcsAndGarbageAreHandled() {
        assertEquals(null, (BackupCodec.decode(BackupCodec.encode(data, null, theme)) as BackupResult.Ok).backup.ics)
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
    fun versionOfAFileWithoutTheFieldIsTheFirstFormat() {
        assertEquals(1, Migrations.versionOf(JsonObject(emptyMap())))
        assertEquals(3, Migrations.versionOf(JsonObject(mapOf("version" to JsonPrimitive(3)))))
        assertNotNull(Migrations.migrate(JsonObject(emptyMap())))
    }
}

private object FakeStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    override val data = kotlinx.coroutines.flow.emptyFlow<androidx.datastore.preferences.core.Preferences>()
    override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
        throw UnsupportedOperationException()
}
