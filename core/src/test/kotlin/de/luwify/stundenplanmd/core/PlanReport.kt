package de.luwify.stundenplanmd.core

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.schedule.Change
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.SharedPlan
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dev tool: prints what the app would make of an export, e.g.
 * `./gradlew :core:planReport --args="private/plan.ics 4"` (path, number of weeks).
 */
fun main(args: Array<String>) {
    val result = LsfImport.parse(File(args[0]).readText())
    val weeks = args.getOrNull(1)?.toLong() ?: 4
    val schedule = Schedule(result.events)
    val day = DateTimeFormatter.ofPattern("EE dd.MM.", Locale.GERMAN)
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    fun room(e: de.luwify.stundenplanmd.core.model.Event) = e.location?.room ?: "–"

    println("${result.events.size} events, ${schedule.occurrences.size} occurrences, ${schedule.firstDate} – ${schedule.lastDate}, source ${result.source}")
    result.warnings.forEach { println("WARNING $it") }
    val shared = SharedPlan("0000000000000000", "Name", java.time.LocalDate.now(), java.time.LocalDate.now(), result.events, emptyMap(), complete = false)
    val qr = ShareCodec.qrText(shared)
    println("share: QR ${qr.length} chars (max ${ShareCodec.QR_MAX_CHARS}), file ${ShareCodec.file(shared).size} B")

    val from = schedule.firstDate?.let { Schedule.monday(it.atStartOfDay()) } ?: return
    println("\n== Occurrences, $weeks weeks from $from ==")
    var week = -1L
    for (o in schedule.between(from, from.plusWeeks(weeks))) {
        val w = java.time.temporal.ChronoUnit.WEEKS.between(from, o.date)
        if (w != week) { week = w; println("-- week of ${from.plusWeeks(w)}") }
        val mark = when (o.status) { is Status.Changed -> " [GEÄNDERT]"; Status.Extra -> " [ZUSATZ]"; Status.Regular -> "" }
        println("  ${o.start.format(day)} ${o.start.format(hm)}–${o.end.format(hm)}  ${o.event.type.short.padEnd(4)} ${o.event.title.take(58).padEnd(58)} ${room(o.event)}$mark")
    }

    println("\n== Changes and extras ==")
    for (o in schedule.occurrences.filter { it.status != Status.Regular }) {
        val head = "${o.start.format(day)} ${o.start.format(hm)}–${o.end.format(hm)} ${o.event.title} (${o.event.type.short}, ${room(o.event)})"
        when (val s = o.status) {
            is Status.Changed -> {
                val orig = s.original
                val parts = s.changes.sorted().map { c ->
                    when (c) {
                        Change.DAY -> "Tag geändert: ${o.start.format(day)} statt ${orig.start.format(day)}"
                        Change.TIME -> "Zeit geändert: ${o.start.format(hm)}–${o.end.format(hm)} statt ${orig.start.format(hm)}–${orig.end.format(hm)}"
                        Change.ROOM -> "Raum geändert: ${room(o.event)} statt ${room(orig.event)}"
                        Change.LECTURER -> "Lehrende geändert"
                    }
                }
                println("  CHANGE $head\n         ${parts.joinToString("; ")}")
            }
            Status.Extra -> println("  EXTRA  $head${o.event.notes.takeIf { it.isNotBlank() }?.let { "\n         Notiz: ${it.lines().first()}" }.orEmpty()}")
            Status.Regular -> Unit
        }
    }

    println("\n== Cancelled without replacement ==")
    for ((date, list) in schedule.cancellations.groupBy { it.start.toLocalDate() }) {
        println("  ${date.format(day)}: ${list.joinToString(", ") { "${it.event.title.take(30)} ${it.start.format(hm)}" }}")
    }

    println("\n== Subjects ==")
    for (s in Subjects.build(result.events, emptyMap())) {
        println("  ${s.name}")
        s.courses.forEach { println("      ${it.key} ${it.type.short.padEnd(4)} ${it.title}  [${it.events.flatMap { e -> e.lecturers }.distinct().size} lecturer(s)]") }
    }
}
