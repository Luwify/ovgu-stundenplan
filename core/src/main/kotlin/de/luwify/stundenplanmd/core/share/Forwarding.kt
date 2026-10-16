package de.luwify.stundenplanmd.core.share

import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.Plan
import java.time.LocalDate
import java.time.ZoneId

/**
 * A friend's plan passed on to someone else, as I have it from them: their id, the name they shared, their events,
 * subject defaults and shared changes. Nothing of mine goes along (no local name, colors, subjects or rhythms).
 * The receiver imports it like a share from that friend, so a later share from the friend replaces it; the friend
 * themselves gets "own timetable" (their id).
 */
object Forwarding {
    private val hexId = Regex("^[0-9a-f]{16}$")

    /**
     * Null for me, an unknown id format or an empty plan. An .ics friend has no share id: the 16 hex digits of the
     * local id stand in, so passing them on twice updates the same friend on the other phone.
     */
    fun of(person: Person, plan: Plan, today: LocalDate, zone: ZoneId): SharedPlan? {
        if (person.isMe || plan.personId != person.id || plan.events.isEmpty()) return null
        val id = person.id.removePrefix(Person.ICS_ID_PREFIX).takeIf { hexId.matches(it) } ?: return null
        val planDate = plan.planDate ?: plan.importedAt.atZone(zone).toLocalDate()
        return SharedPlan(
            personId = id,
            name = person.name,
            exportedOn = plan.exportedOn ?: planDate,
            planDate = planDate,
            events = plan.events,
            settings = plan.sharedSettings,
            complete = true,
            changes = plan.sharedChanges,
            legacy = plan.legacyShare,
        )
    }
}
