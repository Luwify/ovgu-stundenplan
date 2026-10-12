package de.luwify.stundenplanmd.core

import de.luwify.stundenplanmd.core.update.Changelog
import de.luwify.stundenplanmd.core.update.ManifestResult
import de.luwify.stundenplanmd.core.update.Manifests
import de.luwify.stundenplanmd.core.update.Release
import de.luwify.stundenplanmd.core.update.UpdateManifest
import java.io.File
import kotlin.system.exitProcess

/**
 * Release tool (used by tools/release.sh and tools/withdraw.sh), so updates.json is written by the same code the
 * app parses it with. Absolute paths; `-` as input = no manifest yet.
 *
 * `./gradlew -q :core:manifest --args="add <in|-> <out> <appId> <versionName> <date> <minSdk> <de.md> <en.md> <apkUrl> <sha256> <size>"`
 * `./gradlew -q :core:manifest --args="withdraw <in> <out> <versionName>"`
 * `./gradlew -q :core:manifest --args="check <file>"`
 */
fun main(args: Array<String>) {
    fun fail(message: String): Nothing { System.err.println("manifest: $message"); exitProcess(1) }
    fun read(path: String, appId: String? = null): UpdateManifest =
        if (path == "-") UpdateManifest(app = appId ?: fail("no app id"))
        else when (val r = Manifests.parse(File(path).readText())) {
            is ManifestResult.Ok -> r.manifest
            is ManifestResult.TooNew -> fail("$path has schema ${r.schema}, this tool knows ${UpdateManifest.SCHEMA}")
            is ManifestResult.Broken -> fail("$path is broken: ${r.reason}")
        }
    fun write(path: String, m: UpdateManifest) {
        Manifests.problems(m).takeIf { it.isNotEmpty() }?.let { fail(it.joinToString("; ")) }
        File(path).writeText(Manifests.encode(m))
    }
    try {
        when (args.firstOrNull()) {
            "add" -> {
                if (args.size != 12) fail("add needs 11 arguments")
                val (inPath, out, appId, name, date) = args.drop(1)
                val m = read(inPath, appId)
                if (m.app != appId) fail("manifest is for ${m.app}, not $appId")
                val release = Release(
                    versionName = name,
                    versionCode = Manifests.versionCode(name) ?: fail("$name is not major.minor.patch"),
                    date = date,
                    minSdk = args[6].toInt(),
                    changelog = Changelog(de = File(args[7]).readText().trim(), en = File(args[8]).readText().trim()),
                    apkUrl = args[9],
                    sha256 = args[10],
                    size = args[11].toLong(),
                )
                write(out, m.withRelease(release))
            }
            "withdraw" -> {
                if (args.size != 4) fail("withdraw needs 3 arguments")
                write(args[2], read(args[1]).withdraw(args[3]))
            }
            "check" -> {
                if (args.size != 2) fail("check needs 1 argument")
                val m = read(args[1])
                println("ok: ${m.app}, ${m.releases.size} releases, newest ${m.newest?.versionName ?: "-"}")
            }
            else -> fail("usage: add|withdraw|check ...")
        }
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid")
    }
}
