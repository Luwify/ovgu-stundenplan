package de.luwify.stundenplanmd.data.update

import de.luwify.stundenplanmd.BuildConfig
import de.luwify.stundenplanmd.core.update.UpdateSource

/** The release repo of this build (`updateManifestUrl` in app/build.gradle.kts). */
val releaseSource: UpdateSource by lazy {
    UpdateSource.forManifestUrl(BuildConfig.UPDATE_MANIFEST_URL)
        ?: error("updateManifestUrl is not a GitHub latest-release URL: ${BuildConfig.UPDATE_MANIFEST_URL}")
}
