package de.luwify.stundenplanmd.core

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.ical.LsfText
import de.luwify.stundenplanmd.core.model.CourseKeys
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.Schedule
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Dev tool: compares two LSF exports (me and a friend) to check the course-id hypothesis and the "together"
 * matching, e.g. `./gradlew :core:planCompare --args="private/a.ics private/b.ics 4"` (paths, number of weeks).
 * Prints titles, types, rooms and times; never lecturers or notes.
 */
fun main(args: Array<String>) {
    val a = LsfImport.parse(File(args[0]).readText()).events
    val b = LsfImport.parse(File(args[1]).readText()).events
    val weeks = args.getOrNull(2)?.toLong() ?: 4
    val day = DateTimeFormatter.ofPattern("EE dd.MM.", Locale.GERMAN)
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    fun courseId(e: Event) = CourseKeys.courseId(e.uid)
    fun room(e: Event) = e.location?.room ?: "–"
    fun slot(e: Event) = "${e.start.dayOfWeek.toString().take(2)} ${e.start.format(hm)}–${e.end.format(hm)} ${room(e)} " +
        (e.recurrence?.let { "every ${it.interval} wk from ${e.start.toLocalDate()}" } ?: "single ${e.start.toLocalDate()}")

    println("A: ${a.size} events, ${a.map { it.courseKey }.distinct().size} courses, UIDs LSF-shaped ${a.count { courseId(it) != null }}/${a.size}")
    println("B: ${b.size} events, ${b.map { it.courseKey }.distinct().size} courses, UIDs LSF-shaped ${b.count { courseId(it) != null }}/${b.size}")

    println("\n== Shared course ids ==")
    val byCourseA = a.groupBy { courseId(it) }
    val byCourseB = b.groupBy { courseId(it) }
    val shared = (byCourseA.keys intersect byCourseB.keys).filterNotNull().sorted()
    for (id in shared) {
        val ea = byCourseA.getValue(id)
        val eb = byCourseB.getValue(id)
        val titles = (ea + eb).map { it.title }.distinct()
        val cats = (ea + eb).map { it.category }.distinct()
        println("  $id ${titles.first()}")
        if (titles.size > 1) println("      TITLES DIFFER: $titles")
        if (cats.size > 1) println("      CATEGORIES DIFFER: $cats") else println("      category ${cats.single()}")
        val uidsA = ea.associateBy { it.uid }
        val uidsB = eb.associateBy { it.uid }
        for (uid in (uidsA.keys intersect uidsB.keys).sorted()) {
            val x = uidsA.getValue(uid)
            val y = uidsB.getValue(uid)
            val diffs = buildList {
                if (x.summary != y.summary) add(if (LsfText.normalizeSpaces(x.summary) == LsfText.normalizeSpaces(y.summary)) "summary (spaces only)" else "summary")
                if (x.title != y.title) add("title")
                if (x.category != y.category) add("category")
                if (x.location?.raw != y.location?.raw) add("location ${x.location?.raw} / ${y.location?.raw}")
                if (x.start != y.start || x.end != y.end) add("time ${slot(x)} / ${slot(y)}")
                if (x.recurrence?.interval != y.recurrence?.interval || x.recurrence?.until != y.recurrence?.until) add("rrule")
                if (x.recurrence?.exdates != y.recurrence?.exdates || x.recurrence?.exdays != y.recurrence?.exdays) add("exdates")
                if (x.notes != y.notes) add("notes")
                if (x.lecturers != y.lecturers) add("lecturers")
            }
            println("      same UID   ${slot(x)}: " + if (diffs.isEmpty()) "identical" else "DIFFERS in ${diffs.joinToString()}")
        }
        (uidsA.keys - uidsB.keys).sorted().forEach { println("      only A     ${slot(uidsA.getValue(it))}") }
        (uidsB.keys - uidsA.keys).sorted().forEach { println("      only B     ${slot(uidsB.getValue(it))}") }
    }

    println("\n== Same subject (title stem), different course ids ==")
    val stemsA = a.groupBy { LsfText.titleStem(it.title).lowercase() }
    val stemsB = b.groupBy { LsfText.titleStem(it.title).lowercase() }
    for (stem in (stemsA.keys intersect stemsB.keys).sorted()) {
        val onlyA = stemsA.getValue(stem).filter { courseId(it) !in byCourseB.keys }.map { it.title + " [" + it.category + "]" }.distinct()
        val onlyB = stemsB.getValue(stem).filter { courseId(it) !in byCourseA.keys }.map { it.title + " [" + it.category + "]" }.distinct()
        if (onlyA.isEmpty() && onlyB.isEmpty()) continue
        println("  $stem")
        onlyA.forEach { println("      only A: $it") }
        onlyB.forEach { println("      only B: $it") }
    }

    val sa = Schedule(a)
    val sb = Schedule(b)
    val bySlot = sb.occurrences.groupBy { Triple(it.date, it.start, it.end) }

    /** The proposed "together" rule; null = not together. */
    fun rule(x: Occurrence, y: Occurrence): String? {
        if (x.start != y.start || x.end != y.end) return null
        val ex = x.event
        val ey = y.event
        val sameCourse = courseId(ex) != null && courseId(ex) == courseId(ey)
        val rx = ex.location?.room?.lowercase()
        val ry = ey.location?.room?.lowercase()
        return when {
            sameCourse && ex.uid == ey.uid -> "same UID"
            sameCourse && rx != null && rx == ry -> "course + room"
            sameCourse && (rx == null || ry == null) -> "course, a room missing"
            ex.title == ey.title && ex.category == ey.category && ex.location != null && ex.location?.raw == ey.location?.raw -> "title fallback"
            else -> null
        }
    }

    println("\n== Same date and time, not together (rejected or missed) ==")
    val timeOnly = mutableListOf<String>()
    for (x in sa.occurrences) {
        for (y in bySlot[Triple(x.date, x.start, x.end)].orEmpty()) {
            if (rule(x, y) != null) continue
            val why = when {
                courseId(x.event) == courseId(y.event) -> "same course, rooms ${room(x.event)} / ${room(y.event)}"
                x.event.location != null && x.event.location?.raw == y.event.location?.raw -> "same room, different course: ${x.event.title} / ${y.event.title}"
                else -> null
            }
            if (why != null) println("  ${x.start.format(day)} ${x.start.format(hm)} $why")
            else timeOnly += "${x.event.title} [${room(x.event)}] / ${y.event.title} [${room(y.event)}]"
        }
    }
    println("  same time only (different course and room), by pair:")
    timeOnly.groupingBy { it }.eachCount().forEach { (pair, n) -> println("    ${n}x $pair") }

    val all = sa.occurrences.flatMap { x -> bySlot[Triple(x.date, x.start, x.end)].orEmpty().mapNotNull { y -> rule(x, y)?.let { Triple(x, y, it) } } }
    println("\n== Together, whole semester: ${all.size} occurrences; by rule ${all.groupingBy { it.third }.eachCount()} ==")
    val from = listOfNotNull(sa.firstDate, sb.firstDate).minOrNull()?.let { Schedule.monday(it.atStartOfDay()) } ?: return
    println("\n== Together, $weeks weeks from $from ==")
    var week = -1L
    for ((x, y, why) in all.filter { it.first.date < from.plusWeeks(weeks) }) {
        val w = ChronoUnit.WEEKS.between(from, x.date)
        if (w != week) { week = w; println("-- week of ${from.plusWeeks(w)}") }
        val rooms = if (room(x.event) == room(y.event)) room(x.event) else "${room(x.event)} / ${room(y.event)}"
        println("  ${x.start.format(day)} ${x.start.format(hm)}–${x.end.format(hm)}  ${x.event.type.short.padEnd(4)} ${x.event.title.take(56).padEnd(56)} ${rooms.padEnd(10)} $why")
    }
    println("\n(per week from $from: ${(0 until weeks).map { w -> all.count { ChronoUnit.WEEKS.between(from, it.first.date) == w } }})")
    println("first dates: A ${sa.firstDate}, B ${sb.firstDate}; last: A ${sa.lastDate}, B ${sb.lastDate}")
}
