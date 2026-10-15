package de.luwify.stundenplanmd.data

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.Together
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** .ics imports: where they go (settings, drawer, open with), added LSF courses, friends from an .ics. */
class IcsImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val text = File("../core/src/test/resources/lsf_scrubbed.ics").readText()
    private val all: List<Event> = LsfImport.parse(text).events
    private val n4vl = "lsf:900066"
    private val single = all.filter { it.courseKey == n4vl }

    /** The fixture without one course (my plan), as .ics text. */
    private val planText: String by lazy {
        val blocks = text.split("BEGIN:VEVENT")
        blocks.first() + blocks.drop(1).filterNot { b -> Regex("UID:900066").containsMatchIn(b) }.joinToString("") { "BEGIN:VEVENT$it" }
            .let { if (it.contains("END:VCALENDAR")) it else "$it\r\nEND:VCALENDAR\r\n" }
    }

    private fun repo() = PlanRepository(tmp.newFolder()).also { runBlocking { it.load() } }

    private val today = LocalDate.parse("2026-10-15")

    @Test
    fun dispatcherChoices() = runBlocking {
        val empty = StoredData()
        // Settings: a full plan replaces mine at once, a single course asks (if I have a plan).
        assertEquals(IcsRoute.ReplaceMine, ImportRouting.route(IcsEntry.SETTINGS, empty, single))
        val r = repo()
        r.importIcs(text)
        val mine = r.data.value!!
        assertEquals(IcsRoute.ReplaceMine, ImportRouting.route(IcsEntry.SETTINGS, mine, all))
        assertEquals(IcsRoute.AskReplaceOrAdd, ImportRouting.route(IcsEntry.SETTINGS, mine, single))
        // Drawer / open with / share: always asks, never replaces my plan; without a plan it can become mine.
        assertEquals(IcsRoute.AskFriendOrAdd(canBeMine = false), ImportRouting.route(IcsEntry.ELSEWHERE, mine, all))
        assertEquals(IcsRoute.AskFriendOrAdd(canBeMine = false), ImportRouting.route(IcsEntry.ELSEWHERE, mine, single))
        assertEquals(IcsRoute.AskFriendOrAdd(canBeMine = true), ImportRouting.route(IcsEntry.ELSEWHERE, empty, all))
        assertTrue(ImportRouting.icsFriends(mine).isEmpty())
    }

    @Test
    fun addedCoursesNextToThePlanWithUndoAndOwnChanges() = runBlocking {
        val r = repo()
        r.importIcs(planText)
        assertFalse(r.data.value!!.myEvents.any { it.courseKey == n4vl })
        val out = r.addCourses(single)
        assertEquals(1, out.added)
        val d = r.data.value!!
        assertEquals(single, d.addedEvents)
        assertEquals(setOf(n4vl), d.addedKeys)
        // Kept with their LSF UIDs: together with a friend who has the full plan.
        val mySchedule = d.mySchedule()
        val t = Together.of(mySchedule, listOf("f" to de.luwify.stundenplanmd.core.schedule.Schedule(all)))
        assertTrue(t.keys.any { k -> single.any { it.uid == k.uid } })
        // Own changes and rhythms work on them.
        val o = mySchedule.occurrences.first { it.event.courseKey == n4vl }
        r.updatePersonalChanges { it + PersonalChange(o.event.uid, o.date, title = o.event.title, cancelled = true) }
        r.setRhythm(StoredData.ME, o.event.uid, WeekRhythm.EVERY_14_DAYS)
        assertTrue(r.data.value!!.mySchedule().cancellations.any { it.byMe && it.event.uid == o.event.uid })
        // Undo of the add.
        val back = r.data.value!!
        r.restore(out.previous)
        assertTrue(r.data.value!!.addedEvents.isEmpty())
        r.restore(back)
        // Survives store and backup.
        val stored = (PlanStore(tmp.newFolder().also { PlanStore(it).write(back) }).read() as StoreRead.Ok).data
        assertEquals(back.addedEvents, stored.addedEvents)
        val restored = BackupCodec.decode(BackupCodec.encode(back, null, de.luwify.stundenplanmd.data.settings.ThemeChoice(de.luwify.stundenplanmd.data.settings.ThemeMode.SYSTEM), "de", UpdatesBackup())) as BackupResult.Ok
        assertEquals(back.addedEvents, restored.backup.data.addedEvents)
        // Remove (detail sheet) takes my changes and rhythms for it along; undo brings everything back.
        val previous = r.removeAdded(n4vl)!!
        val after = r.data.value!!
        assertTrue(after.addedEvents.isEmpty())
        assertTrue(after.personalChanges.isEmpty())
        assertTrue(after.rhythmsOf(StoredData.ME).isEmpty())
        r.restore(previous)
        assertEquals(back, r.data.value)
    }

    @Test
    fun reimports() = runBlocking {
        val r = repo()
        r.importIcs(planText)
        r.addCourses(single)
        // The single course again (a newer export): updated, not doubled.
        val newer = single.map { it.copy(notes = "neu") }
        val again = r.addCourses(newer)
        assertEquals(0 to 1, again.added to again.updated)
        assertEquals(newer, r.data.value!!.addedEvents)
        // A course the plan already has is never added.
        val dup = r.addCourses(all.filter { it.courseKey == "lsf:900011" })
        assertEquals(1, dup.alreadyInPlan)
        assertEquals(newer, r.data.value!!.addedEvents)
        // A full re-import without the course keeps it ...
        assertEquals(0, (r.importIcs(planText) as ImportOutcome.Imported).droppedAdded)
        assertEquals(newer, r.data.value!!.addedEvents)
        // ... one that has it wins: the duplicate is dropped (with a notice), no event shows twice.
        val full = r.importIcs(text) as ImportOutcome.Imported
        assertEquals(1, full.droppedAdded)
        val d = r.data.value!!
        assertTrue(d.addedEvents.isEmpty())
        assertEquals(all.size, d.myEvents.size)
        // Undo of that import brings the added course back.
        r.restore(full.previous)
        assertEquals(newer, r.data.value!!.addedEvents)
    }

    @Test
    fun icsFriendsKeepMySettingsOnUpdate() = runBlocking {
        val r = repo()
        r.importIcs(text)
        val added = r.importIcsFriend(planText.let { LsfImport.parse(it).events }, "QIS-LSF HIS eG", "  Tim ", null, today)
        val id = added.person.id
        assertFalse(added.replaced)
        assertTrue(id.startsWith("ics-"))
        assertTrue(added.person.fromIcs)
        assertEquals("Tim", added.person.name)
        val plan = r.data.value!!.planOf(id)!!
        assertEquals(today, plan.planDate)
        assertNull(plan.exportedOn)
        assertTrue(plan.sharedChanges.isEmpty())
        assertNull(r.data.value!!.share.id) // no share identity is made
        // My settings for them: name, favorite, color, rhythm, order.
        r.renameFriend(id, "Timmy")
        r.setFavorite(id, true)
        r.updateFriendSettings(id) { it + ("lsf:900011" to CourseSetting("lsf:900011", "x", shortName = "AS", color = 0x123456)) }
        r.setRhythm(id, "900066500518", WeekRhythm.EVEN_WEEKS)
        r.restore(r.data.value!!.copy(people = r.data.value!!.people + de.luwify.stundenplanmd.core.model.Person("1111111111111111", "Anna", isMe = false)))
        r.reorderFriends(listOf("1111111111111111", id))
        val before = r.data.value!!
        assertEquals(listOf(before.person(id)!!), ImportRouting.icsFriends(before))
        val update = r.importIcsFriend(all, "QIS-LSF HIS eG", "Tim", id, today.plusDays(7))
        assertTrue(update.replaced)
        val d = r.data.value!!
        assertEquals(before.people, d.people)
        assertEquals("Timmy", d.person(id)!!.displayName)
        assertTrue(d.person(id)!!.favorite)
        assertEquals("AS", d.friendSettings.getValue(id).getValue("lsf:900011").shortName)
        assertEquals(mapOf("900066500518" to WeekRhythm.EVEN_WEEKS), d.rhythmsOf(id))
        assertEquals(all, d.planOf(id)!!.events)
        assertEquals(today.plusDays(7), d.planOf(id)!!.planDate)
        // My own plan is never touched by it.
        assertEquals(before.myPlan, d.myPlan)
        // Undo.
        r.restore(update.previous)
        assertEquals(before, r.data.value)
    }

    @Test
    fun version5IsMigratedWithNoAddedCourses() {
        val d = tmp.newFolder()
        PlanStore(d).write(StoredData(addedEvents = single))
        val o = kotlinx.serialization.json.Json.parseToJsonElement(File(d, "data.json").readText()) as JsonObject
        File(d, "data.json").writeText(JsonObject(o - "addedEvents" - "rhythms" + ("version" to JsonPrimitive(5))).toString())
        val read = PlanStore(d).read() as StoreRead.Ok
        assertEquals(5, read.migratedFrom)
        assertTrue(read.data.addedEvents.isEmpty())
        // The step only adds what is missing.
        val step = Migrations.STEPS.getValue(5)(JsonObject(mapOf("addedEvents" to JsonArray(emptyList()))))
        assertEquals(JsonArray(emptyList()), step["addedEvents"])
        assertEquals(JsonObject(emptyMap()), step["rhythms"])
    }
}
