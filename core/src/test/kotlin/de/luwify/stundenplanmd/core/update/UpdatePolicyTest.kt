package de.luwify.stundenplanmd.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val github = UpdateSource.forManifestUrl("https://github.com/Luwify/ovgu-stundenplan/releases/latest/download/updates.json")!!

    private fun release(name: String, minSdk: Int = 26, withdrawn: Boolean = false) = Release(
        versionName = name,
        versionCode = Manifests.versionCode(name)!!,
        date = "2026-10-12",
        minSdk = minSdk,
        changelog = Changelog("de $name", "en $name"),
        apkUrl = github.apkUrl(name),
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
    fun autoCheckAtMostEverySixHours() {
        val hour = 60 * 60 * 1000L
        assertEquals(6 * hour, UpdatePolicy.CHECK_INTERVAL_MILLIS)
        assertTrue(UpdatePolicy.shouldAutoCheck(true, null, 5))
        assertFalse(UpdatePolicy.shouldAutoCheck(true, 1000, 1000 + 6 * hour - 1))
        assertTrue(UpdatePolicy.shouldAutoCheck(true, 1000, 1000 + 6 * hour))
        assertTrue(UpdatePolicy.shouldAutoCheck(true, 1000 + 6 * hour, 1000)) // clock went back
        assertFalse(UpdatePolicy.shouldAutoCheck(false, null, 5))
    }

    @Test
    fun restoredProcessDecidesFromTheStoredCheckOnly() {
        // After Android killed the process, the restore starts a new process: ON_START fires again and the only
        // state is the stored last check (DataStore), so the throttle holds across the restore: within 6 h of the
        // last successful check nothing is fetched, after that it is.
        val hour = 60 * 60 * 1000L
        val lastCheck = 1_000_000L
        assertFalse(UpdatePolicy.shouldAutoCheck(true, lastCheck, lastCheck + 2 * hour))
        assertTrue(UpdatePolicy.shouldAutoCheck(true, lastCheck, lastCheck + 7 * hour))
        // The snackbar isn't shown again for the version announced before (announcedCode is stored too).
        val offer = UpdatePolicy.offer(manifest, 10000, 28, null)
        assertFalse(UpdatePolicy.shouldAnnounce(offer, offer!!.versionCode))
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
        val s = github
        assertTrue(s.secure)
        assertEquals("https://github.com/Luwify/ovgu-stundenplan/releases/download/", s.downloadPrefix)
        assertEquals("https://github.com/Luwify/ovgu-stundenplan/releases/download/v1.0.1/stundenplan-md-1.0.1.apk", s.apkUrl("1.0.1"))
        assertTrue(s.allowsApk(s.apkUrl("1.0.1")))
        assertFalse(s.allowsApk("https://github.com/Luwify/other/releases/download/v1/a.apk"))
        assertFalse(s.allowsApk("https://github.com.evil.example/Luwify/ovgu-stundenplan/releases/download/v1/a.apk"))
        assertFalse(s.allowsApk("https://release-assets.githubusercontent.com/x.apk")) // only via a redirect
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
        assertEquals("http://127.0.0.1:8765/v1.0.1/stundenplan-md-1.0.1.apk", o.apkUrl("1.0.1"))
        assertNull(o.redirect("http://127.0.0.1:8765/updates.json", "/other.json")) // the override never redirects
        assertNull(UpdateSource.override("file:///sdcard/x.json"))
        assertNull(UpdateSource.override("http://user@host/x.json"))
    }

    @Test
    fun manifestUrlOfTheBuild() {
        assertNull(UpdateSource.forManifestUrl("http://github.com/Luwify/ovgu-stundenplan/releases/latest/download/updates.json"))
        assertNull(UpdateSource.forManifestUrl("https://github.com/Luwify/ovgu-stundenplan/releases/download/v1/updates.json"))
        assertNull(UpdateSource.forManifestUrl("https://example.org/Luwify/ovgu-stundenplan/releases/latest/download/updates.json"))
        assertNull(UpdateSource.forManifestUrl("https://github.com/Luwify/../releases/latest/download/updates.json"))
        assertNull(UpdateSource.forManifestUrl("https://github.com/Luwify/ovgu-stundenplan/releases/latest/download/updates.json?x"))
        assertNull(UpdateSource.forManifestUrl("https://github.com/Luwify/ovgu-stundenplan/releases/latest/download/../x"))
    }

    @Test
    fun githubRedirectChain() {
        val s = github
        // What GitHub answers (checked with curl, 2026-10-13): latest -> the tag's asset -> signed CDN URL.
        val latest = s.manifestUrl
        val tag = s.redirect(latest, "https://github.com/Luwify/ovgu-stundenplan/releases/download/v1.0.3/updates.json")
        assertEquals("https://github.com/Luwify/ovgu-stundenplan/releases/download/v1.0.3/updates.json", tag)
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2b?sp=r&sv=2018-11-09&sig=a%2Bb&jwt=x.y.z"
        assertEquals(cdn, s.redirect(tag!!, cdn))
        assertEquals(cdn, s.redirect(s.apkUrl("1.0.3"), cdn))
        // A relative Location stays on github.com.
        assertEquals(
            "https://github.com/Luwify/ovgu-stundenplan/releases/download/v1.0.3/updates.json",
            s.redirect(latest, "/Luwify/ovgu-stundenplan/releases/download/v1.0.3/updates.json"),
        )
    }

    @Test
    fun redirectsOnlyToTheAllowedHosts() {
        val s = github
        val from = s.apkUrl("1.0.3")
        fun no(location: String?) = assertNull(location, s.redirect(from, location))
        no(null)
        no("")
        no("http://release-assets.githubusercontent.com/x") // https only
        no("https://objects.githubusercontent.com/x") // not a host GitHub uses for release assets now
        no("https://raw.githubusercontent.com/x")
        no("https://release-assets.githubusercontent.com.evil.example/x")
        no("https://evil.example/release-assets.githubusercontent.com/x")
        no("https://user@release-assets.githubusercontent.com/x")
        no("https://release-assets.githubusercontent.com:8443/x")
        no("https://release-assets.githubusercontent.com/x#frag")
        no("https://github.com/login?return_to=x") // a login page instead of the file
        no("https://github.com/Luwify/other/releases/download/v1/a.apk")
        no("https://github.com/Luwify/ovgu-stundenplan/releases/download/../../other/x")
        no("https://github.com/Luwify/ovgu-stundenplan/releases/download/v1/%2e%2e/x")
        no("https://github.com/Luwify/ovgu-stundenplan/releases/download/v1/a.apk?x=1")
        no("https://github.com/Luwify/ovgu-stundenplan/releases/download/")
        no("https://github.com/Luwify/ovgu-stundenplan/archive/main.zip")
        no("https://gist.github.com/x")
        no("javascript:alert(1)")
        no("ht tp://bad")
        assertEquals("https://release-assets.githubusercontent.com/x", s.redirect(from, "https://Release-Assets.githubusercontent.com/x")?.lowercase())
        assertEquals(setOf("release-assets.githubusercontent.com"), UpdateSource.GITHUB_ASSET_HOSTS)
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
