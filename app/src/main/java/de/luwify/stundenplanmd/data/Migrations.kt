package de.luwify.stundenplanmd.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A stored file that can't be brought to the current format (newer app version, or a gap in the steps). */
class MigrationException(message: String) : Exception(message)

/**
 * Schema versions of the stored plan file (`version` field). A file from an older version is migrated step by step
 * (`STEPS[n]` turns version n into n+1) and never discarded. Add a step whenever [CURRENT] is raised:
 * stored data must survive every update.
 */
object Migrations {
    const val CURRENT = 5

    /** Version n -> n+1. */
    val STEPS: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        // 2: friends. New: `friendSettings` (my local course settings per friend), `share` (my share id and name);
        // Person.localName, Plan.exportedOn/planDate/sharedSettings and Event.notesShortened have defaults.
        1 to { o ->
            JsonObject(
                o + mapOf(
                    "friendSettings" to (o["friendSettings"] ?: JsonObject(emptyMap())),
                    "share" to (o["share"] ?: JsonObject(emptyMap())),
                ),
            )
        },
        // 3: the order of `people` is the drawer order (was sorted by name on screen): friends are put in the order
        // they were shown, so nothing jumps. Person.favorite has a default.
        2 to { o ->
            val people = (o["people"] as? JsonArray).orEmpty().map { it as JsonObject }
            fun isMe(p: JsonObject) = (p["isMe"] as? JsonPrimitive)?.booleanOrNull == true
            fun shown(p: JsonObject) = ((p["localName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (p["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()).lowercase()
            val (me, friends) = people.partition(::isMe)
            if (people.isEmpty()) o else JsonObject(o + ("people" to JsonArray(me + friends.sortedBy(::shown))))
        },
        // 4: own changes. New: `personalChanges` (my notes, s.t., cancellations, rooms, times); MyShare.withChanges and
        // Plan.sharedChanges (a friend's shared changes) have defaults.
        3 to { o -> JsonObject(o + ("personalChanges" to (o["personalChanges"] ?: JsonArray(emptyList())))) },
        // 5: manual courses. New: `manualCourses` (courses I added by hand); Event.st has a default. Version 4 keeps its
        // meaning (released to a device on 2026-10-13).
        4 to { o -> JsonObject(o + ("manualCourses" to (o["manualCourses"] ?: JsonArray(emptyList())))) },
    )

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
