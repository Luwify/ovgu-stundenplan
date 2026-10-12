package de.luwify.stundenplanmd.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `updates.json` in the release repo: every release, newest first, full history kept. Written by tools/release.sh
 * (through `:core:manifest`), read by the in-app updater. [schema] is raised only for changes old apps can't read;
 * new optional fields keep the schema (readers ignore unknown keys).
 */
@Serializable
data class UpdateManifest(
    val schema: Int = SCHEMA,
    val app: String,
    val releases: List<Release> = emptyList(),
) {
    /** Newest release that isn't withdrawn. */
    val newest: Release? get() = releases.firstOrNull { !it.withdrawn }

    /** The release to offer to an install of [installedCode] on [sdk]: newer, not withdrawn, runs on that device. */
    fun updateFor(installedCode: Int, sdk: Int): Release? =
        releases.firstOrNull { !it.withdrawn && it.versionCode > installedCode && it.minSdk <= sdk }

    /** Whether the installed version was withdrawn (the updater may say so). */
    fun isWithdrawn(versionCode: Int): Boolean = releases.any { it.versionCode == versionCode && it.withdrawn }

    /** A new release on top; it must be newer than every release so far. */
    fun withRelease(release: Release): UpdateManifest {
        val top = releases.firstOrNull()
        require(top == null || release.versionCode > top.versionCode) {
            "${release.versionName} is not newer than ${top?.versionName}"
        }
        return copy(releases = listOf(release) + releases)
    }

    fun withdraw(versionName: String): UpdateManifest {
        require(releases.any { it.versionName == versionName }) { "no release $versionName" }
        return copy(releases = releases.map { if (it.versionName == versionName) it.copy(withdrawn = true) else it })
    }

    companion object {
        const val SCHEMA = 1
    }
}

@Serializable
data class Release(
    val versionName: String,
    val versionCode: Int,
    /** Release day, ISO `yyyy-mm-dd`. */
    val date: String,
    val minSdk: Int,
    val changelog: Changelog,
    val apkUrl: String,
    /** SHA-256 of the APK, lowercase hex. */
    val sha256: String,
    /** APK size in bytes. */
    val size: Long,
    val withdrawn: Boolean = false,
)

/** Short, user-facing release notes (Markdown as written in changelog/<version>.<lang>.md). */
@Serializable
data class Changelog(
    val de: String,
    val en: String,
) {
    fun forLanguage(language: String): String = if (language == "de") de else en
}

sealed interface ManifestResult {
    data class Ok(val manifest: UpdateManifest) : ManifestResult
    /** Written by a newer release tool: the app should ask the user to update by hand. */
    data class TooNew(val schema: Int) : ManifestResult
    data class Broken(val reason: String) : ManifestResult
}

object Manifests {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }
    private val semver = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d{0,1})\.(0|[1-9]\d{0,1})""")
    private val sha = Regex("[0-9a-f]{64}")

    /** Size cap for a downloaded manifest (it's text with one short entry per release). */
    const val MAX_BYTES = 512 * 1024

    /** versionCode of a semver name: major*10000 + minor*100 + patch, minor and patch < 100; null if not semver. */
    fun versionCode(versionName: String): Int? =
        semver.matchEntire(versionName)?.groupValues?.drop(1)?.map(String::toInt)?.let { (a, b, c) -> a * 10000 + b * 100 + c }

    fun encode(manifest: UpdateManifest): String = json.encodeToString(UpdateManifest.serializer(), manifest) + "\n"

    fun parse(text: String): ManifestResult {
        if (text.length > MAX_BYTES) return ManifestResult.Broken("too large")
        val schema = Regex(""""schema"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (schema != null && schema > UpdateManifest.SCHEMA) return ManifestResult.TooNew(schema)
        val manifest = try {
            json.decodeFromString(UpdateManifest.serializer(), text)
        } catch (e: SerializationException) {
            return ManifestResult.Broken(e.message ?: "not a manifest")
        } catch (e: IllegalArgumentException) {
            return ManifestResult.Broken(e.message ?: "not a manifest")
        }
        if (manifest.schema > UpdateManifest.SCHEMA) return ManifestResult.TooNew(manifest.schema)
        return problems(manifest).firstOrNull()?.let(ManifestResult::Broken) ?: ManifestResult.Ok(manifest)
    }

    /** Everything wrong with a manifest; empty = valid. */
    fun problems(manifest: UpdateManifest): List<String> = buildList {
        if (manifest.schema < 1) add("schema ${manifest.schema}")
        if (manifest.app.isBlank()) add("no app id")
        manifest.releases.forEach { r ->
            val name = r.versionName
            when (val code = versionCode(name)) {
                null -> add("$name: versionName is not major.minor.patch")
                r.versionCode -> {}
                else -> add("$name: versionCode ${r.versionCode}, expected $code")
            }
            try { LocalDate.parse(r.date) } catch (_: DateTimeParseException) { add("$name: date ${r.date}") }
            if (r.minSdk < 1) add("$name: minSdk ${r.minSdk}")
            if (!r.apkUrl.startsWith("https://")) add("$name: apkUrl is not https")
            if (!sha.matches(r.sha256)) add("$name: sha256 is not 64 lowercase hex digits")
            if (r.size <= 0) add("$name: size ${r.size}")
            if (r.changelog.de.isBlank() || r.changelog.en.isBlank()) add("$name: changelog missing")
        }
        manifest.releases.zipWithNext().forEach { (newer, older) ->
            if (newer.versionCode <= older.versionCode) add("${newer.versionName} listed before ${older.versionName}: not newest first")
        }
    }
}

