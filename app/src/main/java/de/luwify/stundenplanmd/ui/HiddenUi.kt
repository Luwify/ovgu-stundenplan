package de.luwify.stundenplanmd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.Course
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.Subject
import de.luwify.stundenplanmd.core.schedule.Groups
import de.luwify.stundenplanmd.core.schedule.Timed
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** What can be hidden: built from the item tapped (or the subject edited) in [PlanUi]. */
object HideTargets {
    fun subject(subject: Subject) = Hidden(
        Hidden.Kind.SUBJECT, subject = subject.key, courses = subject.courses.mapTo(LinkedHashSet()) { it.key }, title = subject.name,
    )

    fun course(course: Course) = Hidden(Hidden.Kind.COURSE, courses = setOf(course.key), title = course.title, type = typeName(course))

    /** The group of [item]: its series (or the series it was moved out of), described by that series' day, time, room. */
    fun group(plan: PlanUi, item: Timed): Hidden {
        val base = Groups.baseUid(item)
        val series = plan.schedule.events.firstOrNull { it.uid == base } ?: item.event
        return Hidden(
            Hidden.Kind.GROUP, courses = setOf(item.event.courseKey), uid = base, title = series.title, type = series.type.name,
            day = series.start.dayOfWeek.value, start = series.start.toLocalTime(), end = series.end.toLocalTime(), room = series.location?.room,
        )
    }

    /** One date ([Timed.ref]: an occurrence LSF moved is kept by the date it was moved from). */
    fun date(item: Timed) = Hidden(
        Hidden.Kind.DATE, courses = setOf(item.event.courseKey), uid = item.ref.uid, date = item.ref.date, title = item.event.title,
        type = item.event.type.name, start = item.start.toLocalTime(), end = item.end.toLocalTime(), room = item.room,
    )

    fun typeName(course: Course): String = course.type.name.ifEmpty { course.type.short }
}

/** "Mo 9–11" */
private fun slotText(day: Int?, start: LocalTime?, end: LocalTime?, locale: Locale): String = listOfNotNull(
    day?.let { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale).trimEnd('.') },
    if (start != null && end != null) timeRange(java.time.LocalDate.MIN.atTime(start), java.time.LocalDate.MIN.atTime(end)) else null,
).joinToString(" ")

/** What a hidden item is called in the snackbar ("Numerik (Übung)", "Numerik, Gruppe Mo 9–11", ...). */
fun hiddenName(res: android.content.res.Resources, h: Hidden): String {
    val locale = appLocaleOf(res)
    return when (h.kind) {
        Hidden.Kind.SUBJECT -> h.title
        Hidden.Kind.COURSE -> res.getString(R.string.hidden_named_type, h.title, h.type)
        Hidden.Kind.GROUP -> res.getString(R.string.hidden_named_group, h.title, slotText(h.day, h.start, h.end, locale))
        Hidden.Kind.DATE -> res.getString(R.string.hidden_named_date, h.title, h.date?.let { dayAndDate(it, locale) }.orEmpty())
    }
}

/**
 * "Ausblenden" in the detail sheet: the whole subject, only this type (all its groups) when the subject has more than
 * one, only this group when the course has more than one. One choice is a plain button.
 */
@Composable
fun HideSubjectButton(plan: PlanUi, item: Timed, subject: Subject, onHide: (Hidden) -> Unit) {
    val course = subject.courses.firstOrNull { it.key == item.event.courseKey }
    val groups = remember(plan.schedule, item) { Groups.count(plan.schedule, item) }
    val choices = buildList {
        add(stringResource(R.string.hide_subject) to { HideTargets.subject(subject) })
        if (course != null && subject.courses.size > 1) {
            add(stringResource(R.string.hide_type, HideTargets.typeName(course)) to { HideTargets.course(course) })
        }
        if (groups > 1) add(stringResource(R.string.hide_group) to { HideTargets.group(plan, item) })
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { if (choices.size == 1) onHide(choices.single().second()) else menu = true }) {
            Text(stringResource(if (choices.size == 1) R.string.hide_subject else R.string.hide))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            choices.forEach { (label, make) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onHide(make()) })
            }
        }
    }
}

/** "Ausgeblendet": everything hidden, per person (me first), each with "Wiederherstellen". An overlay closed with back. */
@Composable
fun HiddenScreen(entries: List<Pair<Person, List<Hidden>>>, onRestore: (personId: String, Hidden) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val locale = appLocale()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(R.string.hidden_title), style = MaterialTheme.typography.titleLarge)
            }
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.hidden_empty),
                    Modifier.padding(horizontal = 16.dp, vertical = 24.dp).windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom + 24.dp),
            ) {
                item(key = "hint") {
                    Text(
                        stringResource(R.string.hidden_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entries.forEach { (person, list) ->
                    item(key = "person:${person.id}") {
                        SectionHeading(if (person.isMe) stringResource(R.string.my_plan) else person.displayName)
                    }
                    items(list, key = { h -> "h:${person.id}:${h.kind}:${h.subject}:${h.courses}:${h.uid}:${h.date}" }) { h ->
                        HiddenRow(h, locale) { onRestore(person.id, h) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HiddenRow(h: Hidden, locale: Locale, onRestore: () -> Unit) {
    val what = when (h.kind) {
        Hidden.Kind.SUBJECT -> listOf(stringResource(R.string.hidden_kind_subject))
        Hidden.Kind.COURSE -> listOf(stringResource(R.string.hidden_kind_type), h.type)
        Hidden.Kind.GROUP -> listOf(stringResource(R.string.hidden_kind_group), h.type, slotText(h.day, h.start, h.end, locale), h.room.orEmpty())
        Hidden.Kind.DATE -> listOf(
            stringResource(R.string.hidden_kind_date), h.type,
            listOfNotNull(h.date?.let { dayAndDate(it, locale) }, slotText(null, h.start, h.end, locale)).joinToString(" "), h.room.orEmpty(),
        )
    }.filter { it.isNotBlank() }.joinToString(" · ")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(h.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onRestore) { Text(stringResource(R.string.hidden_restore)) }
    }
}
