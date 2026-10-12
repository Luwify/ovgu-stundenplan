package de.luwify.stundenplanmd.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A stored file that can't be brought to the current format (newer app version, or a gap in the steps). */
class MigrationException(message: String) : Exception(message)

/**
 * Schema versions of the stored plan file (`version` field). A file from an older version is migrated step by step
 * (`STEPS[n]` turns version n into n+1) and never discarded. Add a step whenever [CURRENT] is raised:
 * stored data must survive every update.
 */
object Migrations {
    const val CURRENT = 1

    /** Version n -> n+1. Empty while the format is still the first one. */
    val STEPS: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

    /** Version of a stored object; a file without the field is the first format. */
    fun versionOf(obj: JsonObject): Int = (obj["version"] as? JsonPrimitive)?.intOrNull ?: 1

    fun migrate(
        obj: JsonObject,
        current: Int = CURRENT,
        steps: Map<Int, (JsonObject) -> JsonObject> = STEPS,
    ): JsonObject {
        var version = versionOf(obj)
        if (version > current) throw MigrationException("stored version $version is newer than this app's $current")
        var out = obj
        while (version < current) {
            val step = steps[version] ?: throw MigrationException("no migration from version $version")
            out = step(out)
            version++
        }
        return JsonObject(out + ("version" to JsonPrimitive(current)))
    }
}
