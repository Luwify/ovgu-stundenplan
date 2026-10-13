package de.luwify.stundenplanmd.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.Subject
import de.luwify.stundenplanmd.core.schedule.Change
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

@Composable
@ReadOnlyComposable
fun appLocale(): Locale = LocalConfiguration.current.locales[0]

private val hm = DateTimeFormatter.ofPattern("H:mm")

fun time(t: LocalDateTime): String = t.format(hm)

/** "13–15" for full hours, else "13:15–14:45". */
fun timeRange(start: LocalDateTime, end: LocalDateTime): String =
    if (start.minute == 0 && end.minute == 0) "${start.hour}–${end.hour}" else "${time(start)}–${time(end)}"

fun dayShort(date: LocalDate, locale: Locale): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')

/** "Mo 12.10." */
fun dayAndDate(date: LocalDate, locale: Locale): String = "${dayShort(date, locale)} ${date.dayOfMonth}.${date.monthValue}."

/** "Montag, 12. Oktober" */
fun longDate(date: LocalDate, locale: Locale): String = date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", locale))

fun weekNumber(monday: LocalDate) = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

/** "KW 42" (German) or "Week 42": the ISO week number. */
fun weekLabel(monday: LocalDate, locale: Locale): String = (if (locale.language == "de") "KW " else "Week ") + weekNumber(monday)

/** Name shown on a block: the subject's short name, else its title stem (the type has its own label). */
fun blockName(subject: Subject?, item: Timed): String = subject?.displayName ?: item.event.title

fun PlanUi.colorOf(item: Timed): Int {
    val subject = subjectOf[item.event.courseKey] ?: return SubjectColors.defaultColor(0)
    return subject.color ?: SubjectColors.defaultColor(subjects.indexOf(subject))
}

/** "Raum geändert: K11-117 statt K14-102" etc., one line per change. */
@Composable
fun changeLines(o: Occurrence): List<String> {
    val s = o.status as? Status.Changed ?: return emptyList()
    val locale = appLocale()
    val orig = s.original
    val none = stringResource(R.string.no_room)
    return s.changes.sorted().map { c ->
        when (c) {
            Change.DAY -> stringResource(R.string.change_day, dayAndDate(o.date, locale), dayAndDate(orig.date, locale))
            Change.TIME -> stringResource(R.string.change_time, timeRange(o.start, o.end), timeRange(orig.start, orig.end))
            Change.ROOM -> stringResource(
                R.string.change_room,
                o.event.location?.room ?: none,
                orig.event.location?.room ?: none,
            )
            Change.LECTURER -> stringResource(R.string.change_lecturer, o.event.lecturers.joinToString(", ").ifEmpty { "–" })
        }
    }.ifEmpty { listOf(stringResource(R.string.changed)) }
}

/** The app's locale outside composition (snackbar texts). */
fun appLocaleOf(res: android.content.res.Resources): Locale = res.configuration.locales[0]
