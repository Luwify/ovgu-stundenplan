package de.luwify.stundenplanmd.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private fun release(name: String, minSdk: Int = 26, withdrawn: Boolean = false) = Release(
        versionName = name,
        versionCode = Manifests.versionCode(name)!!,
        date = "2026-10-12",
        minSdk = minSdk,
        changelog = Changelog(de = "Neu: $name", en = "New: $name"),
        apkUrl = "https://example.org/releases/download/v$name/app-$name.apk",
        sha256 = "ab".repeat(32),
        size = 2_400_000,
        withdrawn = withdrawn,
    )

    private fun ok(text: String) = (Manifests.parse(text) as? ManifestResult.Ok ?: error("not ok: ${Manifests.parse(text)}")).manifest

    @Test
    fun versionCodes() {
        assertEquals(10000, Manifests.versionCode("1.0.0"))
        assertEquals(10203, Manifests.versionCode("1.2.3"))
        assertEquals(129999, Manifests.versionCode("12.99.99"))
        for (bad in listOf("1.0", "1.0.0-beta", "01.0.0", "1.100.0", "v1.0.0", "")) assertNull(bad, Manifests.versionCode(bad))
    }

    @Test
    fun roundTripKeepsEverything() {
        val m = UpdateManifest(app = "de.luwify.stundenplanmd")
            .withRelease(release("1.0.0"))
            .withRelease(release("1.1.0").copy(changelog = Changelog("Zeilen\n- „Umlaute“ äöü", "lines\n- \"quotes\"")))
        val text = Manifests.encode(m)
        assertTrue(text.contains("\"schema\": 1"))
        assertEquals(m, ok(text))
        assertEquals(listOf("1.1.0", "1.0.0"), m.releases.map { it.versionName })
    }

    @Test
    fun readsTheDocumentedShape() {
        // As written by tools/release.sh; unknown keys (added later within schema 1) are ignored.
        val m = ok(
            """
            {"schema": 1, "app": "de.luwify.stundenplanmd", "future": {"x": 1}, "releases": [
              {"versionName": "1.0.1", "versionCode": 10001, "date": "2026-10-20", "minSdk": 26,
               "changelog": {"de": "Fehler behoben", "en": "Bug fixed"},
               "apkUrl": "https://git.example.org/r/releases/download/v1.0.1/a-1.0.1.apk",
               "sha256": "${"0f".repeat(32)}", "size": 2500000, "withdrawn": true, "mirror": "later"},
              {"versionName": "1.0.0", "versionCode": 10000, "date": "2026-10-12", "minSdk": 26,
               "changelog": {"de": "Erste Version", "en": "First version"},
               "apkUrl": "https://git.example.org/r/releases/download/v1.0.0/a-1.0.0.apk",
               "sha256": "${"1e".repeat(32)}", "size": 2400000}
            ]}
            """,
        )
        assertTrue(m.releases[0].withdrawn)
        assertFalse(m.releases[1].withdrawn) // default
        assertEquals("Erste Version", m.releases[1].changelog.forLanguage("de"))
        assertEquals("First version", m.releases[1].changelog.forLanguage("fr"))
    }

    @Test
    fun updateSkipsWithdrawnAndTooNewSdk() {
        val m = UpdateManifest(app = "a")
            .withRelease(release("1.0.0"))
            .withRelease(release("1.1.0"))
            .withRelease(release("1.2.0", withdrawn = true))
            .withRelease(release("2.0.0", minSdk = 30))
        assertEquals("2.0.0", m.updateFor(10000, 34)?.versionName)
        assertEquals("1.1.0", m.updateFor(10000, 28)?.versionName) // 2.0.0 needs SDK 30, 1.2.0 is withdrawn
        assertNull(m.updateFor(10100, 28))
        assertNull(m.updateFor(20000, 34))
        assertEquals("2.0.0", m.newest?.versionName)
        assertTrue(m.isWithdrawn(10200))
        assertFalse(m.isWithdrawn(10100))
        assertTrue(m.withdraw("2.0.0").isWithdrawn(20000))
        assertEquals("1.1.0", m.withdraw("2.0.0").newest?.versionName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aReleaseMustBeNewer() {
        UpdateManifest(app = "a").withRelease(release("1.1.0")).withRelease(release("1.0.5"))
    }

    @Test
    fun rejectsBrokenOrNewerManifests() {
        assertEquals(ManifestResult.TooNew(2), Manifests.parse("""{"schema": 2, "app": "a", "releases": "other shape"}"""))
        assertTrue(Manifests.parse("") is ManifestResult.Broken)
        assertTrue(Manifests.parse("<html>SSO login</html>") is ManifestResult.Broken)
        assertTrue(Manifests.parse("""{"schema": 1}""") is ManifestResult.Broken)
        assertTrue(Manifests.parse(" ".repeat(Manifests.MAX_BYTES + 1)) is ManifestResult.Broken)

        val good = UpdateManifest(app = "a").withRelease(release("1.0.0"))
        fun broken(r: Release) = Manifests.parse(Manifests.encode(good.copy(releases = listOf(r)))) as? ManifestResult.Broken
        val r = release("1.0.0")
        assertTrue(broken(r.copy(versionCode = 1))!!.reason.contains("expected 10000"))
        assertTrue(broken(r.copy(apkUrl = "http://example.org/a.apk")) != null)
        assertTrue(broken(r.copy(apkUrl = "http://127.0.0.1.example.org/a.apk")) != null)
        assertEquals(null, broken(r.copy(apkUrl = "http://127.0.0.1:8765/a.apk"))) // local test server
        assertTrue(broken(r.copy(sha256 = "AB".repeat(32))) != null)
        assertTrue(broken(r.copy(size = 0)) != null)
        assertTrue(broken(r.copy(date = "12.10.2026")) != null)
        assertTrue(broken(r.copy(changelog = Changelog("", "x"))) != null)
        val unordered = good.copy(releases = listOf(release("1.0.0"), release("1.1.0")))
        assertTrue((Manifests.parse(Manifests.encode(unordered)) as ManifestResult.Broken).reason.contains("newest first"))
    }
}
