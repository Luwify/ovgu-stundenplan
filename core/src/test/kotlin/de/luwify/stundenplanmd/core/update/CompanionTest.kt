package de.luwify.stundenplanmd.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionTest {
    @Test
    fun theBannerFollowsInstalledAndHidden() {
        assertEquals(BannerState.DOWNLOAD, CompanionPolicy.banner(hidden = false, installed = false))
        assertEquals(BannerState.OPEN, CompanionPolicy.banner(hidden = false, installed = true))
        assertEquals(BannerState.HIDDEN, CompanionPolicy.banner(hidden = true, installed = false))
        assertEquals(BannerState.HIDDEN, CompanionPolicy.banner(hidden = true, installed = true))
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
    }

    @Test
    fun theDownloadButtonGoesToTheGithubReleasePage() {
        assertEquals("de.luwify.mensamd", CompanionApp.MENSA.packageName)
        assertEquals("https://github.com/Luwify/ovgu-mensa/releases/latest", CompanionApp.MENSA.releasesPage)
    }
}
