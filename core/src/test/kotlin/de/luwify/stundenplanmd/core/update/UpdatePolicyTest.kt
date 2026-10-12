package de.luwify.stundenplanmd.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private fun release(name: String, minSdk: Int = 26, withdrawn: Boolean = false) = Release(
        versionName = name,
        versionCode = Manifests.versionCode(name)!!,
        date = "2026-10-12",
        minSdk = minSdk,
        changelog = Changelog("de $name", "en $name"),
        apkUrl = UpdateSource.apkUrl(name),
        sha256 = "ab".repeat(32),
        size = 1000,
        withdrawn = withdrawn,
    )

    private val manifest = UpdateManifest(app = "de.luwify.stundenplanmd")
        .withRelease(release("1.0.0"))
        .withRelease(release("1.0.1", withdrawn = true))
        .withRelease(release("1.1.0"))
        .withRelease(release("2.0.0", minSdk = 30))

    @Test
    fun autoCheckAtMostDaily() {
        val day = UpdatePolicy.CHECK_INTERVAL_MILLIS
        assertTrue(UpdatePolicy.shouldAutoCheck(true, null, 5))
        assertFalse(UpdatePolicy.shouldAutoCheck(true, 1000, 1000 + day - 1))
        assertTrue(UpdatePolicy.shouldAutoCheck(true, 1000, 1000 + day))
        assertTrue(UpdatePolicy.shouldAutoCheck(true, 1000 + day, 1000)) // clock went back
        assertFalse(UpdatePolicy.shouldAutoCheck(false, null, 5))
    }

    @Test
    fun overviewHidesWithdrawnButKeepsTheInstalledOne() {
        assertEquals(listOf("2.0.0", "1.1.0", "1.0.0"), UpdatePolicy.versions(manifest, 10000).map { it.versionName })
        assertEquals(listOf("2.0.0", "1.1.0", "1.0.1", "1.0.0"), UpdatePolicy.versions(manifest, 10001).map { it.versionName })
    }

    @Test
    fun onlyNewerInstallableVersions() {
        val r = manifest.releases.associateBy { it.versionName }
        assertTrue(UpdatePolicy.canInstall(r.getValue("1.1.0"), 10000, 28))
        assertFalse(UpdatePolicy.canInstall(r.getValue("1.0.0"), 10000, 28)) // installed
        assertFalse(UpdatePolicy.canInstall(r.getValue("1.0.0"), 10100, 28)) // downgrade
        assertFalse(UpdatePolicy.canInstall(r.getValue("1.0.1"), 10000, 28)) // withdrawn
        assertFalse(UpdatePolicy.canInstall(r.getValue("2.0.0"), 10000, 28)) // needs Android 11
        assertTrue(UpdatePolicy.canInstall(r.getValue("2.0.0"), 10000, 30))
    }

    @Test
    fun offerAndAnnounce() {
        assertEquals("2.0.0", UpdatePolicy.offer(manifest, 10000, 34, null)?.versionName)
        assertEquals("1.1.0", UpdatePolicy.offer(manifest, 10000, 28, null)?.versionName)
        assertNull(UpdatePolicy.offer(manifest, 10000, 28, skippedCode = 10100))
        assertEquals("2.0.0", UpdatePolicy.offer(manifest, 10000, 34, skippedCode = 10100)?.versionName) // newer than skipped
        assertNull(UpdatePolicy.offer(manifest, 20000, 34, null))

        val offer = UpdatePolicy.offer(manifest, 10000, 28, null)
        assertTrue(UpdatePolicy.shouldAnnounce(offer, null))
        assertFalse(UpdatePolicy.shouldAnnounce(offer, 10100))
        assertTrue(UpdatePolicy.shouldAnnounce(offer, 10000))
        assertFalse(UpdatePolicy.shouldAnnounce(null, null))
    }

    @Test
    fun sourceAllowsOnlyItsOwnApks() {
        val s = UpdateSource.RELEASES
        assertTrue(s.secure)
        assertTrue(s.allowsApk(UpdateSource.apkUrl("1.0.1")))
        assertFalse(s.allowsApk("https://github.com/Luwify/ovgu-stundenplan"))
        assertFalse(s.allowsApk("https://github.com.evil.example/Luwify/ovgu-stundenplan/releases/download/v1/a.apk"))
        assertFalse(s.allowsApk(s.downloadPrefix + "v1/../../../x.apk"))
        assertFalse(s.allowsApk(s.downloadPrefix + "v1/a.apk?x=1"))
        assertFalse(s.allowsApk(s.downloadPrefix + "v1//a.apk"))
        assertFalse(s.allowsApk(s.downloadPrefix))
        assertFalse(s.allowsApk(s.downloadPrefix.replace("https://", "http://") + "v1/a.apk"))

        val o = UpdateSource.override("http://127.0.0.1:8765/updates.json")!!
        assertEquals("http://127.0.0.1:8765/", o.downloadPrefix)
        assertFalse(o.secure)
        assertTrue(o.allowsApk("http://127.0.0.1:8765/app.apk"))
        assertFalse(o.allowsApk("http://127.0.0.2:8765/app.apk"))
        assertNull(UpdateSource.override("file:///sdcard/x.json"))
        assertNull(UpdateSource.override("http://user@host/x.json"))
    }

    @Test
    fun apkChecks() {
        val r = release("1.1.0")
        val installed = ApkFacts("de.luwify.stundenplanmd", 10000, setOf("cert"))
        val good = ApkFacts("de.luwify.stundenplanmd", 10100, setOf("cert"))
        assertEquals(emptyList<ApkProblem>(), ApkCheck.problems(r, "AB".repeat(32), 1000, good, installed))
        assertEquals(listOf(ApkProblem.SIZE, ApkProblem.HASH), ApkCheck.problems(r, "cd".repeat(32), 999, good, installed))
        assertEquals(listOf(ApkProblem.UNREADABLE), ApkCheck.problems(r, r.sha256, 1000, null, installed))
        assertEquals(listOf(ApkProblem.PACKAGE), ApkCheck.problems(r, r.sha256, 1000, good.copy(packageName = "x.y"), installed))
        assertEquals(
            listOf(ApkProblem.NOT_NEWER, ApkProblem.VERSION),
            ApkCheck.problems(r, r.sha256, 1000, good.copy(versionCode = 10000), installed),
        )
        assertEquals(listOf(ApkProblem.VERSION), ApkCheck.problems(r, r.sha256, 1000, good.copy(versionCode = 10200), installed))
        assertEquals(listOf(ApkProblem.SIGNER), ApkCheck.problems(r, r.sha256, 1000, good.copy(signers = setOf("other")), installed))
        assertEquals(listOf(ApkProblem.SIGNER), ApkCheck.problems(r, r.sha256, 1000, good.copy(signers = emptySet()), installed.copy(signers = emptySet())))
        assertEquals(listOf(ApkProblem.SIGNER), ApkCheck.problems(r, r.sha256, 1000, good.copy(signers = setOf("cert", "extra")), installed))
    }
}
