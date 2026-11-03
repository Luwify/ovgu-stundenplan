package de.luwify.stundenplanmd.core.compat

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.ManualType
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.Cancellation
import de.luwify.stundenplanmd.core.schedule.FriendCourses
import de.luwify.stundenplanmd.core.schedule.Groups
import de.luwify.stundenplanmd.core.schedule.OccurrenceValues
import de.luwify.stundenplanmd.core.schedule.Personal
import de.luwify.stundenplanmd.core.schedule.PersonalChanges
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.core.store.HideTargets
import de.luwify.stundenplanmd.core.store.StoreEdits
import de.luwify.stundenplanmd.core.store.StoredData
import de.luwify.stundenplanmd.core.store.SubjectPalette
import java.time.LocalDate
import java.time.LocalTime

/**
 * The edits the web app makes (every [StoreEdits] function), as a fixed sequence on [CompatProbe.rich]. The JVM run
 * gives the state after each step as golden values; the JS test drives the same steps through WebCore (keys, JSON) and
 * must reach the same states byte for byte. The parameters of each step are picked from the state before it by the
 * helpers here, so both sides pick the same dates and courses.
 */
object CompatEdits {
    const val ICS_FRIEND_HEX = "00000000000000b1"
    const val MANUAL_ID = "manual-00000000000000c1"
    val TODAY: LocalDate = LocalDate.of(2026, 11, 3)

    /** The web app's key of a date ([de.luwify.stundenplanmd.web.WebCore]). */
    fun key(t: Timed) = "${t.event.uid}|${t.start}|" + if (t is Cancellation) "c" else "o"

    /** A friend's export: the same courses under other course ids. */
    fun friendIcs(ics: String) = ics.replace("UID:90", "UID:70")

    /** Courses of another export added to my plan: other ids again. */
    fun addedIcs(ics: String) = ics.replace("UID:90", "UID:60")

    /** A weekly manual course on Saturdays (a weekend column), its own subject. */
    val manual = ManualCourse(
        id = MANUAL_ID, title = "Lerngruppe Numerik", type = ManualType.SEMINAR, room = "Bibliothek", lecturer = "Selbst", note = "Bring Blätter",
        st = true, rhythm = ManualCourse.Rhythm.WEEKLY, startDate = LocalDate.of(2026, 10, 17), endDate = LocalDate.of(2027, 1, 30),
        start = LocalTime.of(10, 0), end = LocalTime.of(12, 0),
    )

    fun styleSubject(d: StoredData) = StoreEdits.subjectsOf(d, StoredData.ME)[1]
    fun moveCourse(d: StoredData) = StoreEdits.subjectsOf(d, StoredData.ME).first { it.courses.size > 1 }.courses.last()
    fun rhythmUid(d: StoredData) = d.myEvents.first { it.recurrence?.biweekly == true && !it.isManual }.uid
    fun groupDate(d: StoredData): Timed = d.mySchedule().let { s -> s.occurrences.first { Groups.count(s, it) > 1 } }
    fun typeDate(d: StoredData): Timed {
        val subjects = StoreEdits.subjectsOf(d, StoredData.ME)
        return d.mySchedule().occurrences.first { o -> subjects.first { s -> s.courses.any { it.key == o.event.courseKey } }.courses.size > 1 }
    }
    fun cancelDate(d: StoredData): Timed = d.mySchedule().occurrences[20]
    fun personalDate(d: StoredData): Timed = d.mySchedule().occurrences.first { it.event.recurrence != null && it.personal == null && it.status == Status.Regular }
    fun pickDate(d: StoredData): Timed = d.mySchedule().occurrences[30]
    fun icsFriendId() = "ics-$ICS_FRIEND_HEX"
    fun friendDate(d: StoredData): Timed = d.schedule(d.planOf(icsFriendId())!!).occurrences[5]
    fun addedDate(d: StoredData): Timed = d.mySchedule().occurrences.first { it.event.uid in d.addedUids }

    /** The personal values of step "personal": a series note, s.t., another room, a quarter of an hour later. */
    fun personalValues(t: Timed): OccurrenceValues = OccurrenceValues("Notiz", true, "K99-001", t.start.toLocalTime().plusMinutes(15), t.end.toLocalTime().plusMinutes(15))

    fun personalOf(t: Timed) = t.personal ?: Personal(t.ref, null, null, Personal.values(t.event, t.start, t.end), lsfChanged = false)

    /** step name -> state after it. */
    fun run(ics: String): List<Pair<String, StoredData>> {
        val out = mutableListOf<Pair<String, StoredData>>()
        var d = CompatProbe.rich(ics)
        fun step(name: String, next: StoredData) { d = next; out += name to d }

        styleSubject(d).let { s -> step("style", StoreEdits.setSubjectStyle(d, StoredData.ME, s, "Ana2", 0x00897B, mapOf(s.courses.first().key to "Ü"))) }
        moveCourse(d).let { c -> step("move", StoreEdits.moveCourse(d, StoredData.ME, c, Subjects.ownKey(c.key))) }
        step("rhythm", StoreEdits.setRhythm(d, StoredData.ME, rhythmUid(d), WeekRhythm.EVEN_WEEKS))
        groupDate(d).let { t -> step("hideGroup", StoreEdits.hide(d, StoredData.ME, HideTargets.group(d.mySchedule(), t))) }
        typeDate(d).let { t ->
            val course = StoreEdits.subjectsOf(d, StoredData.ME).flatMap { it.courses }.first { it.key == t.event.courseKey }
            step("hideType", StoreEdits.hide(d, StoredData.ME, HideTargets.course(course)))
        }
        cancelDate(d).let { t -> step("cancel", d.copy(personalChanges = PersonalChanges.setCancelled(d.personalChanges, t.ref, t.event.title, true))) }
        personalDate(d).let { t -> step("personal", d.copy(personalChanges = PersonalChanges.setSeries(d.personalChanges, personalOf(t), t.event.title, personalValues(t)))) }
        pickDate(d).let { t ->
            val dates = StoreEdits.pickDates(d, t).map { it.first }
            step("dates", StoreEdits.setDates(d, mapOf(dates[0] to StoreEdits.DateState.CANCELLED, dates[1] to StoreEdits.DateState.HIDDEN), t.event.title))
        }
        step("manual", StoreEdits.saveManual(d, manual, StoreEdits.manualSetting(manual, StoreEdits.subjectsOf(d, StoredData.ME), null, "LG", 0xF4511E)))
        LsfImport.parse(friendIcs(ics)).let { r ->
            step("icsFriend", StoreEdits.importIcsFriend(d, r.events, r.source, "Kim", null, ICS_FRIEND_HEX, TODAY, CompatProbe.NOW).data)
        }
        StoreEdits.subjectsOf(d, icsFriendId()).first().let { s -> step("friendStyle", StoreEdits.setSubjectStyle(d, icsFriendId(), s, "K", null)) }
        friendDate(d).let { t ->
            val subjects = StoreEdits.subjectsOf(d, icsFriendId())
            val subject = subjects.first { s -> s.courses.any { it.key == t.event.courseKey } }
            val color = subject.color ?: SubjectPalette.defaultColor(subjects.indexOf(subject))
            step("addFromFriend", StoreEdits.addFromFriend(d, FriendCourses.group(d.planOf(icsFriendId())!!.events, t), color)!!)
        }
        step("addCourses", StoreEdits.addCourses(d, LsfImport.parse(addedIcs(ics)).events).data)
        addedDate(d).let { t -> step("removeAdded", StoreEdits.removeAdded(d, Groups.of(d.mySchedule(), t))!!) }
        step("rename", StoreEdits.renameFriend(d, icsFriendId(), "Kimmy"))
        step("favorite", StoreEdits.setFavorite(d, icsFriendId(), true))
        step("reorder", StoreEdits.reorderFriends(d, listOf(icsFriendId(), CompatProbe.FRIEND_ID)))
        step("shareChanges", StoreEdits.setShareChanges(d, false))
        step("unhide", StoreEdits.unhide(d, StoredData.ME, d.hiddenOf(StoredData.ME).first()))
        return out
    }
}
