package de.luwify.stundenplanmd.data

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.schedule.AddedCourses

/** Where an .ics comes from: the import in the settings (or the empty plan), or anywhere else (drawer "+", open with, share). */
enum class IcsEntry { SETTINGS, ELSEWHERE }

/** What happens with an .ics. Shares (`SPMD`) never get here: they are always a friend's plan. */
sealed interface IcsRoute {
    /** Replace my plan at once (settings, a full plan). */
    data object ReplaceMine : IcsRoute

    /** Settings, but far fewer courses than my plan has: "Plan ersetzen" or "Kurse zu meinem Plan hinzufügen". */
    data object AskReplaceOrAdd : IcsRoute

    /**
     * Not from the settings: "Als Freund hinzufügen" (new or update an .ics friend) or "Kurse zu meinem Plan
     * hinzufügen"; never replaces my plan. [canBeMine]: I have no plan yet, so it can also become mine.
     */
    data class AskFriendOrAdd(val canBeMine: Boolean) : IcsRoute
}

object ImportRouting {
    fun route(entry: IcsEntry, data: StoredData, incoming: List<Event>): IcsRoute = when (entry) {
        IcsEntry.SETTINGS -> if (AddedCourses.looksPartial(data.myPlan?.events.orEmpty(), incoming)) IcsRoute.AskReplaceOrAdd else IcsRoute.ReplaceMine
        IcsEntry.ELSEWHERE -> IcsRoute.AskFriendOrAdd(canBeMine = data.myPlan == null)
    }

    /** The friends an .ics can update (the ones imported from an .ics, in drawer order). */
    fun icsFriends(data: StoredData): List<Person> = data.friends.filter { it.fromIcs }
}
