package de.luwify.stundenplanmd.data

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.share.LegacyShares
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    const val CURRENT = 8

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
        // 6: even/odd calendar weeks, added LSF courses, .ics friends. New: `rhythms` (my rhythm per biweekly series, per
        // person), `addedEvents` (LSF courses added to my plan from another .ics); ManualCourse.Rhythm gets
        // EVEN_WEEKS/ODD_WEEKS, BIWEEKLY keeps its meaning (every 14 days); friends imported from an .ics have `ics-` ids.
        // Version 5 keeps its meaning (on a device).
        5 to { o ->
            JsonObject(
                o + mapOf(
                    "rhythms" to (o["rhythms"] ?: JsonObject(emptyMap())),
                    "addedEvents" to (o["addedEvents"] ?: JsonArray(emptyList())),
                ),
            )
        },
        // 7: friends' plans shared by apps before 1.2.2 counted biweekly series every 14 days; they are converted to
        // LSF's even/odd calendar weeks once (as on import of such a share), and marked `legacyShare` where some dates
        // after a week 53 may differ. My plan and .ics friends hold LSF's own rules and stay as they are.
        6 to { o ->
            val plans = (o["plans"] as? JsonArray)?.map { el ->
                val plan = el as? JsonObject ?: return@map el
                val id = (plan["personId"] as? JsonPrimitive)?.contentOrNull
                val events = plan["events"]
                if (id == null || id == StoredData.ME || id.startsWith(Person.ICS_ID_PREFIX) || events == null) return@map plan
                val old = json.decodeFromJsonElement(eventList, events)
                val converted = LegacyShares.convert(old)
                if (converted.events == old) return@map plan
                JsonObject(
                    plan + mapOf(
                        "events" to json.encodeToJsonElement(eventList, converted.events),
                        "legacyShare" to JsonPrimitive(converted.incomplete),
                    ),
                )
            }
            if (plans == null) o else JsonObject(o + ("plans" to JsonArray(plans)))
        },
        // 8: hiding and short names per type. New: `hidden` (what I hid, per person); CourseSetting.typeShortName and
        // Plan.sharedHidden have defaults. The existing short name stays as it is and becomes the prefix shared by all
        // types of the subject, so names look the same as before.
        7 to { o -> JsonObject(o + ("hidden" to (o["hidden"] ?: JsonObject(emptyMap())))) },
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val eventList = ListSerializer(Event.serializer())

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
