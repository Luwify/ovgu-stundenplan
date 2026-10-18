package de.luwify.stundenplanmd.core.model

import de.luwify.stundenplanmd.core.ical.LsfText
import kotlinx.serialization.Serializable

/**
 * What the user set for one course. Stored per course key (not per subject) so a re-import keeps it;
 * [title] lets [Subjects.carryOver] find the course again if its id changed.
 */
@Serializable
data class CourseSetting(
    val courseKey: String,
    val title: String,
    /** Subject the user moved this course to; null = automatic (title stem). */
    val subject: String? = null,
    /** The short name of the subject, for all its types ("Kürzel (alle Typen)"); the same in every course of it. */
    val shortName: String? = null,
    /** 0xRRGGBB. */
    val color: Int? = null,
    /** Only this course's part of the short name ("Kürzel (dieser Typ)"), shown after [shortName], e.g. "N4" + "VL". */
    val typeShortName: String? = null,
)

/** All events with one course key, e.g. "Numerik N4 (VL)" (both weekly series). */
data class Course(val key: String, val title: String, val type: CourseType, val events: List<Event>)

/** Courses grouped as one subject (VL, Übung, Praktikum, ... of the same title stem). */
data class Subject(
    val key: String,
    val name: String,
    val courses: List<Course>,
    val shortName: String?,
    val color: Int?,
    /** Course key -> its own part of the short name ([CourseSetting.typeShortName]). */
    val typeShortNames: Map<String, String> = emptyMap(),
) {
    val displayName: String get() = shortName?.takeIf { it.isNotBlank() } ?: name

    /** The short name shown for course [courseKey]: prefix + space + type part; only the prefix without one. */
    fun displayNameOf(courseKey: String): String = compose(displayName, typeShortNames[courseKey])

    /** Like [displayNameOf], null when neither prefix nor type part is set (the full name is shown elsewhere). */
    fun shortNameOf(courseKey: String): String? {
        val type = typeShortNames[courseKey]
        return if (shortName.isNullOrBlank() && type.isNullOrBlank()) null else displayNameOf(courseKey)
    }

    companion object {
        fun compose(prefix: String, type: String?): String =
            if (type.isNullOrBlank()) prefix else listOf(prefix.trim(), type.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

object Subjects {
    fun autoKey(title: String) = "stem:" + LsfText.titleStem(title).lowercase()
    fun ownKey(courseKey: String) = "own:$courseKey"

    fun courses(events: List<Event>): List<Course> = events.groupBy { it.courseKey }.map { (key, list) ->
        val title = list.groupingBy { it.title }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
        Course(key, title, list.first().type, list)
    }

    fun subjectKey(course: Course, settings: Map<String, CourseSetting>): String =
        settings[course.key]?.subject ?: autoKey(course.title)

    /** Subjects sorted by name; style (short name, color) from the first course that has one set. */
    fun build(events: List<Event>, settings: Map<String, CourseSetting>): List<Subject> =
        courses(events).groupBy { subjectKey(it, settings) }.map { (key, list) ->
            val courses = list.sortedWith(compareBy({ typeOrder(it.type.short) }, { it.key }))
            val styled = courses.mapNotNull { settings[it.key] }
            Subject(
                key = key,
                name = LsfText.titleStem(courses.first().title),
                courses = courses,
                shortName = styled.firstNotNullOfOrNull { it.shortName },
                color = styled.firstNotNullOfOrNull { it.color },
                typeShortNames = courses.mapNotNull { c -> settings[c.key]?.typeShortName?.takeIf { it.isNotBlank() }?.let { c.key to it } }.toMap(),
            )
        }.sortedBy { it.name.lowercase() }

    /** Sets short name and color for every course of [subject]. */
    fun setStyle(settings: Map<String, CourseSetting>, subject: Subject, shortName: String?, color: Int?) =
        settings + subject.courses.associate { c ->
            c.key to (settings[c.key] ?: CourseSetting(c.key, c.title)).copy(shortName = shortName?.trim()?.ifEmpty { null }, color = color)
        }

    /** Sets the type part of the short name of [courseKey] only (null or blank = none). */
    fun setTypeShortName(settings: Map<String, CourseSetting>, course: Course, typeShortName: String?) =
        settings + (course.key to (settings[course.key] ?: CourseSetting(course.key, course.title)).copy(typeShortName = typeShortName?.trim()?.ifEmpty { null }))

    /** Moves [course] into [target] (an existing subject key or [ownKey]) and gives it the target's style. */
    fun move(settings: Map<String, CourseSetting>, course: Course, target: String, subjects: List<Subject>): Map<String, CourseSetting> {
        val style = subjects.firstOrNull { it.key == target }
        val explicit = target.takeIf { it != autoKey(course.title) }
        val base = settings[course.key] ?: CourseSetting(course.key, course.title)
        return settings + (course.key to base.copy(subject = explicit, shortName = style?.shortName ?: base.shortName, color = style?.color ?: base.color))
    }

    /**
     * After a re-import: settings of courses whose key is new but whose title matches a stored course that
     * is no longer in the plan are copied to the new key. Stored settings are never dropped.
     */
    fun carryOver(settings: Map<String, CourseSetting>, events: List<Event>): Map<String, CourseSetting> {
        val courses = courses(events)
        val present = courses.map { it.key }.toSet()
        val orphans = settings.values.filter { it.courseKey !in present }.toMutableList()
        val out = settings.toMutableMap()
        for (course in courses) {
            if (course.key in settings) continue
            val match = orphans.firstOrNull { normalize(it.title) == normalize(course.title) } ?: continue
            orphans -= match
            out[course.key] = match.copy(courseKey = course.key, title = course.title)
        }
        return out
    }

    /**
     * Settings for every course of [events] with the style as shown: short name and color of its subject, automatic
     * colors resolved by [autoColor] (subject index -> color). Shared with friends and used for courses we both take.
     */
    fun resolved(events: List<Event>, settings: Map<String, CourseSetting>, autoColor: (Int) -> Int): Map<String, CourseSetting> {
        val subjects = build(events, settings)
        return subjects.withIndex().flatMap { (i, subject) ->
            subject.courses.map { c ->
                val base = settings[c.key] ?: CourseSetting(c.key, c.title)
                c.key to base.copy(shortName = subject.shortName, color = subject.color ?: autoColor(i))
            }
        }.toMap()
    }

    /**
     * Settings for showing a friend's plan, per course: my [local] override for that friend, else [mine] (resolved, for
     * courses I take too, so they look like in my plan), else the friend's [shared] defaults, else automatic.
     */
    fun forFriend(
        events: List<Event>,
        local: Map<String, CourseSetting>,
        mine: Map<String, CourseSetting>,
        shared: Map<String, CourseSetting>,
    ): Map<String, CourseSetting> = courses(events).mapNotNull { c ->
        (local[c.key] ?: mine[c.key] ?: shared[c.key])?.let { c.key to it }
    }.toMap()

    /** What an edit of [shown] (e.g. [setStyle] on [forFriend]'s result) changed: stored as my local override. */
    fun overrides(local: Map<String, CourseSetting>, shown: Map<String, CourseSetting>, edited: Map<String, CourseSetting>) =
        local + edited.filter { (key, value) -> shown[key] != value }

    private fun normalize(title: String) = LsfText.normalizeSpaces(title).lowercase()

    private val typeOrder = listOf("V", "V/Ü", "Ü", "S", "P")
    private fun typeOrder(short: String) = typeOrder.indexOf(short).let { if (it < 0) typeOrder.size else it }
}
