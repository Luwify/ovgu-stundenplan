package de.luwify.stundenplanmd.core.update

/**
 * Another app of the same author the settings point at ("Mensa MD"): where its releases are and which signing
 * certificate its APK must carry. The APK is checked against [signers] (SHA-256 of the certificate, lowercase hex),
 * not against this app's own signature, so a debug build of this app installs it the same way.
 */
data class CompanionApp(
    val packageName: String,
    val manifestUrl: String,
    /** Opened in the browser when the in-app install fails. */
    val releasesPage: String,
    val signers: Set<String>,
) {
    /** Null if [manifestUrl] isn't a GitHub latest-release URL. */
    val source: UpdateSource? get() = UpdateSource.forManifestUrl(manifestUrl)

    companion object {
        /** Mensa MD; the certificate is the one in release/signing-cert.sha256 (same key as this app). */
        val MENSA = CompanionApp(
            packageName = "de.luwify.mensamd",
            manifestUrl = "https://github.com/Luwify/ovgu-mensa/releases/latest/download/updates.json",
            releasesPage = "https://github.com/Luwify/ovgu-mensa/releases/latest",
            signers = setOf("d717d70ff7fc196d0e5efdbed339a4bfe0a01cbfbbdd66de751115cd941549af"),
        )
    }
}

/** What a banner for a [CompanionApp] shows. */
enum class BannerState { HIDDEN, OPEN, DOWNLOAD, BUSY, FAILED }

/** Where a download of a [CompanionApp] stands (the app's own state flow maps onto this). */
enum class CompanionPhase { IDLE, DOWNLOADING, VERIFYING, INSTALLING, FAILED }

object CompanionPolicy {
    /**
     * Whether the banner is wanted: what the user chose in the settings ([chosenHidden], null = never chosen), else only
     * while the app isn't installed yet.
     */
    fun wanted(chosenHidden: Boolean?, installed: Boolean): Boolean = chosenHidden?.not() ?: !installed

    /** The banner: hidden by the user, "Open" once installed, else the download button, its progress or its failure. */
    fun banner(hidden: Boolean, installed: Boolean, phase: CompanionPhase): BannerState = when {
        hidden && phase == CompanionPhase.IDLE -> BannerState.HIDDEN
        installed -> BannerState.OPEN
        phase == CompanionPhase.FAILED -> BannerState.FAILED
        phase != CompanionPhase.IDLE -> BannerState.BUSY
        else -> BannerState.DOWNLOAD
    }

    /**
     * The release to install for a first install: the newest one that isn't withdrawn and runs on [sdk], from a
     * manifest of [app] (its `app` id must match) with an APK URL the [source] allows. Null = nothing safe to offer.
     */
    fun release(manifest: UpdateManifest, app: CompanionApp, source: UpdateSource, sdk: Int): Release? {
        if (manifest.app != app.packageName) return null
        return manifest.releases.firstOrNull { !it.withdrawn && it.minSdk <= sdk && source.allowsApk(it.apkUrl) }
    }

    /**
     * Everything that speaks against installing [apk] (downloaded for [release]) as [app]; empty = install. The
     * signer must be exactly [CompanionApp.signers]; there is no installed version to compare with.
     */
    fun problems(release: Release, sha256: String, size: Long, apk: ApkFacts?, app: CompanionApp): List<ApkProblem> = buildList {
        if (size != release.size) add(ApkProblem.SIZE)
        if (!sha256.equals(release.sha256, ignoreCase = true)) add(ApkProblem.HASH)
        if (apk == null) {
            add(ApkProblem.UNREADABLE)
            return@buildList
        }
        if (apk.packageName != app.packageName) add(ApkProblem.PACKAGE)
        if (apk.versionCode != release.versionCode.toLong()) add(ApkProblem.VERSION)
        if (apk.signers.isEmpty() || apk.signers != app.signers) add(ApkProblem.SIGNER)
    }
}
