package de.luwify.stundenplanmd.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CompanionTest {
    private val mensa = CompanionApp.MENSA
    private val source = mensa.source!!

    private fun release(name: String, minSdk: Int = 26, withdrawn: Boolean = false, url: String = source.apkUrl(name)) = Release(
        versionName = name,
        versionCode = Manifests.versionCode(name)!!,
        date = "2026-10-12",
        minSdk = minSdk,
        changelog = Changelog("de", "en"),
        apkUrl = url,
        sha256 = "ab".repeat(32),
        size = 1000,
        withdrawn = withdrawn,
    )

    private fun manifest(vararg releases: Release, app: String = mensa.packageName) =
        releases.fold(UpdateManifest(app = app)) { m, r -> m.withRelease(r) }

    private fun apk(pkg: String = mensa.packageName, code: Long = 10000, signers: Set<String> = mensa.signers) = ApkFacts(pkg, code, signers)

    private fun problems(apk: ApkFacts?, release: Release = release("1.0.0"), sha: String = "AB".repeat(32), size: Long = 1000) =
        CompanionPolicy.problems(release, sha, size, apk, mensa)

    @Test
    fun theBannerFollowsInstalledDownloadFailedAndHidden() {
        val b = CompanionPolicy::banner
        assertEquals(BannerState.DOWNLOAD, b(false, false, CompanionPhase.IDLE))
        assertEquals(BannerState.OPEN, b(false, true, CompanionPhase.IDLE))
        for (phase in listOf(CompanionPhase.DOWNLOADING, CompanionPhase.VERIFYING, CompanionPhase.INSTALLING)) {
            assertEquals(BannerState.BUSY, b(false, false, phase))
        }
        assertEquals(BannerState.FAILED, b(false, false, CompanionPhase.FAILED))
        assertEquals(BannerState.HIDDEN, b(true, false, CompanionPhase.IDLE))
        assertEquals(BannerState.HIDDEN, b(true, true, CompanionPhase.IDLE))
        // A running download stays visible even if the banner was hidden meanwhile; once installed it says "Open".
        assertEquals(BannerState.BUSY, b(true, false, CompanionPhase.DOWNLOADING))
        assertEquals(BannerState.OPEN, b(false, true, CompanionPhase.INSTALLING))
    }

    @Test
    fun theBannerIsWantedWhenChosenOrElseOnlyWhileNotInstalled() {
        val wanted = CompanionPolicy::wanted
        assertEquals(true, wanted(null, false))
        assertEquals(false, wanted(null, true))
        // An explicit choice wins either way.
        assertEquals(true, wanted(false, true))
        assertEquals(false, wanted(true, false))
        assertEquals(false, wanted(true, true))
        assertEquals(true, wanted(false, false))
        // Installed through the banner: hidden without a choice once the install is done, but not while it runs.
        assertEquals(BannerState.HIDDEN, CompanionPolicy.banner(!wanted(null, true), installed = true, phase = CompanionPhase.IDLE))
        assertEquals(BannerState.BUSY, CompanionPolicy.banner(!wanted(null, false), installed = false, phase = CompanionPhase.INSTALLING))
    }

    @Test
    fun theMensaSourceIsTheGithubReleaseRepo() {
        assertNotNull(source)
        assertEquals("https://github.com/Luwify/ovgu-mensa/releases/download/", source.downloadPrefix)
        assertEquals("https://github.com/Luwify/ovgu-mensa/releases/latest/download/updates.json", mensa.manifestUrl)
        assertEquals("de.luwify.mensamd", mensa.packageName)
    }

    /** The pinned certificate is the one in release/signing-cert.sha256: same key as this app. */
    @Test
    fun theMensaCertificateIsThisAppsPin() {
        val file = listOf("../release/signing-cert.sha256", "release/signing-cert.sha256").map(::File).first { it.exists() }
        val pin = file.readLines().first { it.isNotBlank() && !it.startsWith("#") }.replace(":", "").trim().lowercase()
        assertEquals(setOf(pin), mensa.signers)
    }

    @Test
    fun theReleaseIsTheNewestInstallableOneOfMensasManifest() {
        val m = manifest(release("1.0.0"), release("1.1.0", withdrawn = true), release("1.2.0", minSdk = 99))
        assertEquals("1.0.0", CompanionPolicy.release(m, mensa, source, 36)?.versionName)
        // Newer one that runs: taken; withdrawn ones never.
        assertEquals("1.3.0", CompanionPolicy.release(m.withRelease(release("1.3.0")), mensa, source, 36)?.versionName)
        assertEquals("1.2.0", CompanionPolicy.release(m, mensa, source, 99)?.versionName)
        assertNull(CompanionPolicy.release(manifest(release("1.0.0", withdrawn = true)), mensa, source, 36))
        assertNull(CompanionPolicy.release(manifest(), mensa, source, 36))
    }

    @Test
    fun aManifestOfAnotherAppOrWithForeignUrlsOffersNothing() {
        assertNull(CompanionPolicy.release(manifest(release("1.0.0"), app = "de.luwify.stundenplanmd"), mensa, source, 36))
        assertNull(CompanionPolicy.release(manifest(release("1.0.0", url = "https://example.com/mensa-md-1.0.0.apk")), mensa, source, 36))
        assertNull(CompanionPolicy.release(manifest(release("1.0.0", url = "http://github.com/Luwify/ovgu-mensa/releases/download/v1.0.0/a.apk")), mensa, source, 36))
        // Another repo's APK below the same host.
        assertNull(CompanionPolicy.release(manifest(release("1.0.0", url = "https://github.com/Luwify/ovgu-stundenplan/releases/download/v1.0.0/a.apk")), mensa, source, 36))
    }

    @Test
    fun theDownloadMustBeMensaSignedWithThePinnedKey() {
        assertEquals(emptyList<ApkProblem>(), problems(apk()))
        assertEquals(listOf(ApkProblem.SIZE), problems(apk(), size = 999))
        assertEquals(listOf(ApkProblem.HASH), problems(apk(), sha = "cd".repeat(32)))
        assertEquals(listOf(ApkProblem.UNREADABLE), problems(null))
        assertEquals(listOf(ApkProblem.PACKAGE), problems(apk(pkg = "de.luwify.stundenplanmd")))
        assertEquals(listOf(ApkProblem.VERSION), problems(apk(code = 10100)))
        assertEquals(listOf(ApkProblem.SIGNER), problems(apk(signers = setOf("00".repeat(32)))))
        assertEquals(listOf(ApkProblem.SIGNER), problems(apk(signers = emptySet())))
        // Signed with the pinned key and another one: not the pinned set.
        assertEquals(listOf(ApkProblem.SIGNER), problems(apk(signers = mensa.signers + "00".repeat(32))))
    }
}
