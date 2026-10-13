package de.luwify.stundenplanmd.data.update

import de.luwify.stundenplanmd.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSourceTest {
    @Test
    fun buildManifestUrlIsAGithubSource() {
        val s = releaseSource
        assertEquals(BuildConfig.UPDATE_MANIFEST_URL, s.manifestUrl)
        assertTrue(s.secure)
        assertTrue(s.allowsApk(s.apkUrl("1.0.3")))
    }
}
