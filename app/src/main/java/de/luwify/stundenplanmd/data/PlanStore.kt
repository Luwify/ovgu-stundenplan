package de.luwify.stundenplanmd.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes a file so that it is either the old or the new content, never half of each. */
object AtomicFiles {
    /** [body] writes to a temp file next to [file]; only a complete write replaces [file]. If it throws, [file] is untouched. */
    fun write(file: File, body: (OutputStream) -> Unit) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                body(out)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}

sealed interface StoreRead {
    /** No file yet: first start. */
    data object Fresh : StoreRead

    /** [migratedFrom] is set when the file had an older version (a copy of it was kept). */
    data class Ok(val data: StoredData, val migratedFrom: Int? = null) : StoreRead

    /** The file could not be read; it was kept as [keptAs] (null: it could not even be moved, so writing is blocked). */
    data class Unreadable(val keptAs: String?, val reason: String) : StoreRead
}

/**
 * `data.json` (plan + course settings) and `me.ics` (the raw import) in [dir]. A file that can't be read is renamed to
 * `*.broken`, never overwritten; an older format is migrated, with a copy of the old file kept.
 */
class PlanStore(
    private val dir: File,
    private val current: Int = Migrations.CURRENT,
    private val steps: Map<Int, (JsonObject) -> JsonObject> = Migrations.STEPS,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = File(dir, "data.json")
    private val ics get() = File(dir, "me.ics")

    /** True when an unreadable file could not be preserved: nothing may be written over it. */
    var blocked = false
        private set

    fun read(): StoreRead {
        // A leftover backup means an interrupted write of the previous (android AtomicFile) scheme: it is the good copy.
        val bak = File(dir, "data.json.bak")
        if (bak.exists()) runCatching { Files.move(bak.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        if (!file.exists()) return StoreRead.Fresh
        val bytes = file.readBytes()
        return try {
            val obj = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: throw MigrationException("not a JSON object")
            val version = Migrations.versionOf(obj)
            val data = json.decodeFromJsonElement(StoredData.serializer(), Migrations.migrate(obj, current, steps))
            if (version == current) {
                StoreRead.Ok(data)
            } else {
                File(dir, "data.json.v$version").writeBytes(bytes)
                write(data)
                StoreRead.Ok(data, migratedFrom = version)
            }
        } catch (e: Exception) {
            StoreRead.Unreadable(quarantine(), e.message ?: e::class.java.simpleName)
        }
    }

    private fun quarantine(): String? {
        var target = File(dir, "data.json.broken")
        var n = 1
        while (target.exists()) target = File(dir, "data.json.broken.${n++}")
        return try {
            Files.move(file.toPath(), target.toPath())
            target.name
        } catch (_: Exception) {
            blocked = true
            null
        }
    }

    fun write(data: StoredData) {
        check(!blocked) { "unreadable data.json could not be preserved" }
        val text = json.encodeToString(StoredData.serializer(), data.copy(version = current))
        AtomicFiles.write(file) { it.write(text.encodeToByteArray()) }
    }

    fun readIcs(): String? = ics.takeIf { it.exists() }?.readText()

    /** Null removes the file. */
    fun writeIcs(text: String?) {
        if (text == null) ics.delete() else AtomicFiles.write(ics) { it.write(text.encodeToByteArray()) }
    }
}
