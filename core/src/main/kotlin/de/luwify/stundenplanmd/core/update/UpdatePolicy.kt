package de.luwify.stundenplanmd.core.update

import java.net.URI
import java.net.URISyntaxException

/**
 * Where the updater may connect: the manifest URL, APKs below [downloadPrefix], and redirects of those requests to
 * [redirectPrefixes] or [redirectHosts]; nothing else.
 */
data class UpdateSource(
    val manifestUrl: String,
    val downloadPrefix: String,
    /** URL prefixes a redirect may lead to (on GitHub: this repo's releases, e.g. latest -> the tag). */
    val redirectPrefixes: List<String> = emptyList(),
    /** Hosts a redirect may lead to with any path and query (GitHub's CDN serves signed asset URLs). https only. */
    val redirectHosts: Set<String> = emptySet(),
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

    /** Download link of a version as the release script publishes it (for the "share the app" QR code). */
    fun apkUrl(versionName: String): String = "${downloadPrefix}v$versionName/stundenplan-md-$versionName.apk"

    /**
     * The next URL after a redirect from [current] with the `Location` header [location] (may be relative), or null
     * if the redirect leads anywhere else than this source allows: https only, no user info, default port, and
     * either below one of [redirectPrefixes] (no dot segments, no query) or on one of [redirectHosts].
     */
    fun redirect(current: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val target = try {
            URI(current).resolve(location.trim())
        } catch (_: URISyntaxException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (target.scheme != "https" || target.rawUserInfo != null || target.port != -1 || target.rawFragment != null) return null
        val host = target.host?.lowercase() ?: return null
        val url = target.toASCIIString()
        if (host in redirectHosts) return url
        val prefix = redirectPrefixes.firstOrNull { url.startsWith(it) } ?: return null
        val rest = url.removePrefix(prefix)
        if (target.rawQuery != null || rest.isEmpty()) return null
        if (rest.split('/').any { it == ".." || it == "." || it.isEmpty() || it.contains('%') }) return null
        return url
    }

    companion object {
        /** Redirects one request may follow (GitHub: latest -> tag -> CDN = 2). */
        const val MAX_REDIRECTS = 3

        /** GitHub's host for release assets (github.com/.../releases/download/... redirects there). */
        val GITHUB_ASSET_HOSTS = setOf("release-assets.githubusercontent.com")

        private val githubManifest = Regex("""^https://github\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)/releases/latest/download/([A-Za-z0-9._-]+)$""")

        /**
         * The source for a manifest at `https://github.com/<owner>/<repo>/releases/latest/download/<file>` (the
         * build's update URL, see app/build.gradle.kts); null for any other URL.
         */
        fun forManifestUrl(manifestUrl: String): UpdateSource? {
            val m = githubManifest.matchEntire(manifestUrl) ?: return null
            val (owner, repo) = m.destructured
            if (repo == "." || repo == "..") return null
            val releases = "https://github.com/$owner/$repo/releases/"
            return UpdateSource(
                manifestUrl = manifestUrl,
                downloadPrefix = "${releases}download/",
                redirectPrefixes = listOf("${releases}download/"),
                redirectHosts = GITHUB_ASSET_HOSTS,
            )
        }

        /** A debug override: the manifest and APKs from one origin, e.g. `http://127.0.0.1:8765/updates.json`. */
        fun override(manifestUrl: String): UpdateSource? {
            val m = Regex("""^(https?://[^/?#@\s]+)/[^?#\s]*$""").matchEntire(manifestUrl) ?: return null
            return UpdateSource(manifestUrl, m.groupValues[1] + "/", allowHttp = true)
        }
    }
}

/** What the updater shows and offers, from a manifest and the installed version. */
object UpdatePolicy {
    const val CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L

    /**
     * The automatic check when the app comes to the foreground: at most once per [CHECK_INTERVAL_MILLIS] since the
     * last successful one (and again if the clock went back).
     */
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
