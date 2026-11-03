package de.luwify.stundenplanmd.core.store

import de.luwify.stundenplanmd.core.ical.ImportResult
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.Plan
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.schedule.AddedCourses
import de.luwify.stundenplanmd.core.schedule.Hiding
import de.luwify.stundenplanmd.core.share.SharedPlan
import java.time.Instant
import java.time.LocalDate

/** Pure state changes shared by the Android app (PlanRepository, MainViewModel) and the web app. */
object StoreOps {
    /** [data] with my plan replaced by [result]; course settings are carried over. [dropped]: how many added appointments the plan has itself (removed). */
    class Imported(val data: StoredData, val dropped: Int)

    /** Replaces my plan; course settings are kept and carried over to changed course ids by title. */
    fun importIcs(current: StoredData, result: ImportResult, now: Instant): Imported {
        val plan = Plan(StoredData.ME, now, result.source, result.events)
        // The full plan wins over courses added on their own.
        val (added, dropped) = AddedCourses.dedupe(result.events, current.addedEvents)
        val next = current.copy(
            plans = current.plans.filterNot { it.personId == StoredData.ME } + plan,
            courseSettings = Subjects.carryOver(current.courseSettings, result.events),
            addedEvents = added,
        )
        return Imported(next, dropped)
    }

    sealed interface FriendResult {
        /** [replaced]: a plan of this person was there before. */
        data class Imported(val data: StoredData, val person: Person, val replaced: Boolean) : FriendResult

        /** My own share. */
        data object Own : FriendResult
    }

    /** A friend's share: added, or replacing that person's plan (keeping my local settings, name and favorite). */
    fun importFriend(current: StoredData, shared: SharedPlan, now: Instant): FriendResult {
        if (shared.personId == current.share.id || shared.personId == StoredData.ME) return FriendResult.Own
        val old = current.person(shared.personId)
        val person = (old ?: Person(shared.personId, shared.name, isMe = false)).copy(name = shared.name)
        val plan = Plan(
            personId = shared.personId,
            importedAt = now,
            source = "share",
            events = shared.events,
            exportedOn = shared.exportedOn,
            planDate = shared.planDate,
            sharedSettings = shared.settings,
            sharedChanges = shared.changes,
            legacyShare = shared.legacy,
            sharedHidden = shared.hidden,
        )
        val local = current.friendSettings[shared.personId]
        val next = current.copy(
            people = if (old == null) current.people + person else current.people.map { if (it.id == person.id) person else it },
            plans = current.plans.filterNot { it.personId == shared.personId } + plan,
            friendSettings = if (local == null) current.friendSettings else current.friendSettings + (shared.personId to Subjects.carryOver(local, shared.events)),
        )
        return FriendResult.Imported(next, person, replaced = old != null)
    }

    /**
     * My plan as shared: events, and short names and colors of every course as shown (automatic ones resolved). Null
     * without a share id or name, or without events. [today] is the export day, [now] stands in for a missing import time.
     */
    fun mySharedPlan(data: StoredData, identity: MyShare, today: LocalDate, now: Instant): SharedPlan? {
        val id = identity.id ?: return null
        val name = identity.name ?: return null
        val plan = data.myPlan
        // Manual courses go with my own changes ("Eigene Änderungen teilen"), without their notes (notes are mine).
        val withChanges = data.share.withChanges
        // So do my rhythms for biweekly series (sent as the dates they give).
        val all = if (withChanges) data.myEvents.map { if (it.isManual) it.copy(notes = "") else it } else data.myLsfEvents
        // What I hid stays on my phone: hidden courses are left out, hidden dates listed so the friend never sees them.
        // Notes never leave the phone ([PersonalChange.forSharing]); my changes only while the setting is on.
        val shared = Hiding.forSharing(
            all,
            if (withChanges) data.personalChanges.mapNotNull { it.forSharing() } else emptyList(),
            data.hidingOf(StoredData.ME, data.myEvents),
        )
        val events = shared.events
        if (events.isEmpty()) return null
        val keys = events.mapTo(HashSet()) { it.courseKey }
        return SharedPlan(
            personId = id,
            name = name,
            exportedOn = today,
            planDate = (plan?.importedAt ?: now).atZone(PlanZone).toLocalDate(),
            events = events,
            // Resolved over my whole plan, so automatic colors are the ones I see.
            settings = Subjects.resolved(data.myEvents, data.courseSettings, SubjectPalette::defaultColor).filterKeys { it in keys },
            complete = true,
            changes = shared.changes,
            hidden = shared.hidden,
        )
    }
}
