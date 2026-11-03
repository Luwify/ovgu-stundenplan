package de.luwify.stundenplanmd.core.compat

import de.luwify.stundenplanmd.core.share.ShareCodec
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
}
