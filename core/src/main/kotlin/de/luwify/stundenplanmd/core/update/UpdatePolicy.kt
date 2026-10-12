package de.luwify.stundenplanmd.core.update

/** Where the updater may connect: the manifest URL and APKs below [downloadPrefix], nothing else. */
data class UpdateSource(
    val manifestUrl: String,
    val downloadPrefix: String,
    /** Only for the debug-build override (a local test server); release sources are https only. */
    val allowHttp: Boolean = false,
) {
    /** Whether [url] is an APK of this source: https (unless allowed), below the prefix, no tricks in the path. */
    fun allowsApk(url: String): Boolean {
        if (!url.startsWith(downloadPrefix) || url.length == downloadPrefix.length) return false
        val rest = url.removePrefix(downloadPrefix)
        if (rest.split('/').any { it == ".." || it == "." || it.isEmpty() }) return false
        if (rest.any { it == '?' || it == '#' || it == '\\' || it == '@' || it.isWhitespace() }) return false
        return url.startsWith("https://") || (allowHttp && url.startsWith("http://"))
    }

    val secure: Boolean get() = manifestUrl.startsWith("https://") && downloadPrefix.startsWith("https://")

    companion object {
        private const val BASE = "https://github.com/Luwify/ovgu-stundenplan"

        /** The public release repo (SSO lets anyone read raw/ and releases/download/ there). */
        val RELEASES = UpdateSource(
            manifestUrl = "$BASE/releases/latest/download/updates.json",
            downloadPrefix = "$BASE/releases/download/",
        )

        /** Download link of a version as tools/release.sh publishes it (for the "share the app" QR code). */
        fun apkUrl(versionName: String): String = "$BASE/releases/download/v$versionName/stundenplan-md-$versionName.apk"

        /** A debug override: the manifest and APKs from one origin, e.g. `http://127.0.0.1:8765/updates.json`. */
        fun override(manifestUrl: String): UpdateSource? {
            val m = Regex("""^(https?://[^/?#@\s]+)/[^?#\s]*$""").matchEntire(manifestUrl) ?: return null
            return UpdateSource(manifestUrl, m.groupValues[1] + "/", allowHttp = true)
        }
    }
}

/** What the updater shows and offers, from a manifest and the installed version. */
object UpdatePolicy {
    const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

    /** The automatic check at app start: at most once per [CHECK_INTERVAL_MILLIS] (and again if the clock went back). */
    fun shouldAutoCheck(enabled: Boolean, lastCheckMillis: Long?, nowMillis: Long): Boolean =
        enabled && (lastCheckMillis == null || nowMillis - lastCheckMillis >= CHECK_INTERVAL_MILLIS || nowMillis < lastCheckMillis)

    /** The version overview: newest first, withdrawn ones hidden (except the installed one, so it can be marked). */
    fun versions(manifest: UpdateManifest, installedCode: Int): List<Release> =
        manifest.releases.filter { !it.withdrawn || it.versionCode == installedCode }

    /** Android can't downgrade: only newer, not withdrawn versions that run on this device can be installed. */
    fun canInstall(release: Release, installedCode: Int, sdk: Int): Boolean =
        !release.withdrawn && release.versionCode > installedCode && release.minSdk <= sdk

    /** The update to point at (badge, snackbar, dialog): the newest installable one, unless the user skipped it. */
    fun offer(manifest: UpdateManifest, installedCode: Int, sdk: Int, skippedCode: Int?): Release? =
        manifest.updateFor(installedCode, sdk)?.takeIf { skippedCode == null || it.versionCode > skippedCode }

    /** The snackbar shows once per version. */
    fun shouldAnnounce(offer: Release?, announcedCode: Int?): Boolean =
        offer != null && (announcedCode == null || offer.versionCode > announcedCode)
}

/** What a downloaded APK says about itself, and the installed app (PackageManager). */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    /** SHA-256 of each signing certificate, lowercase hex. */
    val signers: Set<String>,
)

enum class ApkProblem { SIZE, HASH, UNREADABLE, PACKAGE, NOT_NEWER, VERSION, SIGNER }

object ApkCheck {
    /**
     * Everything that speaks against installing [apk] (downloaded for [release]) over [installed]; empty = install.
     * [apk] is null when Android can't read the file as an APK.
     */
    fun problems(release: Release, sha256: String, size: Long, apk: ApkFacts?, installed: ApkFacts): List<ApkProblem> = buildList {
        if (size != release.size) add(ApkProblem.SIZE)
        if (!sha256.equals(release.sha256, ignoreCase = true)) add(ApkProblem.HASH)
        if (apk == null) {
            add(ApkProblem.UNREADABLE)
            return@buildList
        }
        if (apk.packageName != installed.packageName) add(ApkProblem.PACKAGE)
        if (apk.versionCode <= installed.versionCode) add(ApkProblem.NOT_NEWER)
        if (apk.versionCode != release.versionCode.toLong()) add(ApkProblem.VERSION)
        if (apk.signers.isEmpty() || installed.signers.isEmpty() || apk.signers != installed.signers) add(ApkProblem.SIGNER)
    }
}
