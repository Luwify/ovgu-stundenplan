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
    val shortName: String? = null,
    /** 0xRRGGBB. */
    val color: Int? = null,
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
) {
    val displayName: String get() = shortName?.takeIf { it.isNotBlank() } ?: name
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
            )
        }.sortedBy { it.name.lowercase() }

    /** Sets short name and color for every course of [subject]. */
    fun setStyle(settings: Map<String, CourseSetting>, subject: Subject, shortName: String?, color: Int?) =
        settings + subject.courses.associate { c ->
            c.key to (settings[c.key] ?: CourseSetting(c.key, c.title)).copy(shortName = shortName?.trim()?.ifEmpty { null }, color = color)
        }

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

    private fun normalize(title: String) = LsfText.normalizeSpaces(title).lowercase()

    private val typeOrder = listOf("V", "V/Ü", "Ü", "S", "P")
    private fun typeOrder(short: String) = typeOrder.indexOf(short).let { if (it < 0) typeOrder.size else it }
}
