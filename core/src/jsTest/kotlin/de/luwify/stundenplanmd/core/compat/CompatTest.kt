package de.luwify.stundenplanmd.core.compat

import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.store.StoreEdits
import de.luwify.stundenplanmd.core.store.StoredData
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.share.ShareResult
import de.luwify.stundenplanmd.web.FrameDecoder
import de.luwify.stundenplanmd.web.WebCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The JS build of :core against the golden values of the JVM build ([CompatProbe], generated [CompatGolden]). */
class CompatTest {
    private val golden = CompatGolden.values.toMap()
    private fun g(key: String) = golden[key] ?: fail("no golden $key")

    private fun assertSame(key: String, expected: String, actual: String) {
        if (expected == actual) return
        val e = expected.lines()
        val a = actual.lines()
        val i = e.indices.firstOrNull { it >= a.size || e[it] != a[it] } ?: e.size
        fail("$key differs at line ${i + 1}:\n JVM: ${e.getOrNull(i)?.take(400)}\n JS:  ${a.getOrNull(i)?.take(400)}")
    }

    @Test
    fun sameAsJvm() {
        val js = CompatProbe.describe(CompatGolden.ics)
        assertEquals(CompatGolden.values.map { it.first }, js.map { it.first })
        for ((k, v) in js) assertSame(k, g(k), v)
    }

    @Test
    fun webCoreImportGivesTheJvmState() {
        val w = WebCore(null)
        assertTrue(w.importIcs(CompatGolden.ics, CompatProbe.NOW_MS).contains("\"ok\":true"))
        assertSame("importedState", g("importedState"), w.stateJson())
    }

    @Test
    fun webCoreSharesAreTheJvmBytes() {
        val w = WebCore(g("richState"))
        assertSame("richState", g("richState"), w.stateJson())
        assertEquals(g("qr:me"), CompatProbe.b64(w.qrPayload("me", CompatProbe.NOW_MS)!!))
        assertEquals(g("file:me"), CompatProbe.b64(w.shareFile("me", CompatProbe.NOW_MS)!!))
        assertEquals(g("qr:friend"), CompatProbe.b64(w.qrPayload(CompatProbe.FRIEND_ID, CompatProbe.NOW_MS)!!))
        assertEquals(g("file:friend"), CompatProbe.b64(w.shareFile(CompatProbe.FRIEND_ID, CompatProbe.NOW_MS)!!))
    }

    @Test
    fun jsReadsJvmSharesAndFrames() {
        val file = ShareCodec.parse(CompatProbe.unb64(g("file:me")))
        assertTrue(file is ShareResult.Ok, "file: $file")
        assertTrue(ShareCodec.parse(g("qrText:me")) is ShareResult.Ok)
        // Frames in a shuffled order, with a repeat, as a camera sees them.
        val frames = g("frames:me").lines()
        val d = FrameDecoder()
        var last = 0.0
        for (f in frames.reversed() + frames.first()) last = maxOf(last, d.offer(f))
        assertEquals(1.0, last)
        assertEquals(g("qrText:me"), d.result())
        val w = WebCore(null)
        assertTrue(w.importShareText(d.result()!!, CompatProbe.NOW_MS).contains("\"name\":\"Alex\""))
    }

    @Test
    fun backupRoundTripsThroughTheWebApp() {
        val w = WebCore(null)
        val r = w.restore(g("backup"))
        assertTrue(r.contains("\"ok\":true"), r)
        assertSame("richState", g("richState"), w.stateJson())
        val extras = r.substringAfter("\"extras\":\"").substringBefore("\",\"").replace("\\\"", "\"")
        assertSame("backup", g("backup"), w.backup(CompatGolden.ics, extras, CompatProbe.NOW_MS))
    }

    /** [CompatEdits] through WebCore's API (keys and JSON, as the web app calls it): the JVM's state after every step. */
    @Test
    fun webCoreEditsGiveTheJvmStates() {
        val ics = CompatGolden.ics
        val w = WebCore(g("richState"))
        fun d() = CompatProbe.json.decodeFromString(StoredData.serializer(), w.stateJson())
        fun check(name: String) = assertSame("edit:$name", g("edit:$name"), w.stateJson())
        val me = StoredData.ME
        val e = CompatEdits

        e.styleSubject(d()).let { s -> w.setSubjectStyle(me, s.key, "Ana2", 0x00897B, "{\"${s.courses.first().key}\":\"Ü\"}") }
        check("style")
        e.moveCourse(d()).let { c -> w.moveCourse(me, c.key, Subjects.ownKey(c.key)) }
        check("move")
        w.setRhythm(me, e.rhythmUid(d()), WeekRhythm.EVEN_WEEKS.name)
        check("rhythm")
        assertTrue(w.hide(me, e.key(e.groupDate(d())), "group") != null)
        check("hideGroup")
        assertTrue(w.hide(me, e.key(e.typeDate(d())), "type") != null)
        check("hideType")
        assertTrue(w.setCancelledForMe(e.key(e.cancelDate(d())), true) != null)
        check("cancel")
        e.personalDate(d()).let { t ->
            val v = e.personalValues(t)
            assertTrue(w.savePersonal(e.key(t), true, v.note, v.st, v.room!!, v.start.toString(), v.end.toString()))
        }
        check("personal")
        e.pickDate(d()).let { t ->
            val dates = StoreEdits.pickDates(d(), t).map { e.key(it.first) }
            w.setDates(e.key(t), "{\"${dates[0]}\":\"CANCELLED\",\"${dates[1]}\":\"HIDDEN\"}")
        }
        check("dates")
        assertTrue(w.saveManual(CompatProbe.json.encodeToString(ManualCourse.serializer(), e.manual), null, "LG", 0xF4511E))
        check("manual")
        assertTrue(w.readIcs(e.friendIcs(ics), false).contains("askFriendOrAdd"))
        assertTrue(w.icsAddFriend("Kim", null, CompatEdits.ICS_FRIEND_HEX, CompatProbe.NOW_MS).contains(e.icsFriendId()))
        check("icsFriend")
        StoreEdits.subjectsOf(d(), e.icsFriendId()).first().let { s -> w.setSubjectStyle(e.icsFriendId(), s.key, "K", -1, "{}") }
        check("friendStyle")
        assertTrue(w.addFromFriend(e.icsFriendId(), e.key(e.friendDate(d()))) != null)
        check("addFromFriend")
        w.readIcs(e.addedIcs(ics), false)
        w.icsAddCourses()
        check("addCourses")
        assertTrue(w.removeAdded(e.key(e.addedDate(d()))) != null)
        check("removeAdded")
        w.renameFriend(e.icsFriendId(), "Kimmy")
        check("rename")
        w.setFavorite(e.icsFriendId(), true)
        check("favorite")
        w.reorderFriends("[\"${e.icsFriendId()}\",\"${CompatProbe.FRIEND_ID}\"]")
        check("reorder")
        w.setShareChanges(false)
        check("shareChanges")
        w.unhide(me, 0)
        check("unhide")
        // Shared and backed up from the web: the JVM's bytes.
        assertEquals(g("edits:file:me"), CompatProbe.b64(w.shareFile(me, CompatProbe.NOW_MS)!!))
        assertEquals(g("edits:qr:me"), CompatProbe.b64(w.qrPayload(me, CompatProbe.NOW_MS)!!))
        assertSame("edits:backup", g("edits:backup"), w.backup(ics, null, CompatProbe.NOW_MS))
        // And an Android backup with all of it restores on the web to the same state.
        val r = WebCore(null)
        assertTrue(r.restore(g("edits:backup")).contains("\"ok\":true"))
        assertSame("edit:unhide", g("edit:unhide"), r.stateJson())
    }
}
