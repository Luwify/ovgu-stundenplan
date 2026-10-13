package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.schedule.Cancellation
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.OccurrenceRef
import de.luwify.stundenplanmd.core.schedule.OccurrenceValues
import de.luwify.stundenplanmd.core.schedule.Personal
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.ui.theme.planColors
import java.time.LocalTime

/** My changes of [this], or an empty [Personal] to start editing from. */
fun Timed.personalOrBlank(): Personal = personal ?: Personal(
    ref, null, null, Personal.values(event, start, end),
    lsfChanged = (this is Cancellation && !byMe) || (this is Occurrence && status is Status.Changed),
)

/** The event of the series my changes of [this] belong to (for a moved date: the series it was moved out of). */
val Timed.seriesEvent: Event
    get() = ((this as? Occurrence)?.status as? Status.Changed)?.original?.event ?: event

/** Cancelled for me (by me, or by the friend whose plan this is). */
val Timed.cancelledForMe: Boolean get() = this is Cancellation && byMe

/** Room, time or cancellation changed by hand: the violet own-change marker. */
val Timed.ownChange: Boolean get() = personal?.changesPlan == true

/** A dashed border in the own-change color: blocks and cards I changed myself (LSF changes have a solid amber one). */
fun Modifier.ownBorder(color: Color, width: Dp, radius: Dp) = drawWithContent {
    drawContent()
    val w = width.toPx()
    drawRoundRect(
        color,
        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** "s.t." as a small filled badge. */
@Composable
fun StBadge(fontSize: Int = 9, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        stringResource(R.string.st_badge),
        modifier.clip(RoundedCornerShape(4.dp)).background(scheme.inverseSurface).padding(horizontal = 3.dp),
        color = scheme.inverseOnSurface,
        fontSize = fontSize.sp,
        lineHeight = (fontSize + 2).sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** Note and own-change markers on a round surface badge, like the together badge. */
@Composable
fun MarkerBadge(note: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.size(size).clip(CircleShape).background(scheme.surface), contentAlignment = Alignment.Center) {
        Icon(
            if (note) NoteIcon else EditIcon,
            contentDescription = stringResource(if (note) R.string.note_marker else R.string.own_marker),
            tint = if (note) scheme.onSurfaceVariant else planColors().own,
            modifier = Modifier.size(size * 0.74f),
        )
    }
}

/**
 * Own changes in the detail sheet: what was changed against LSF's values, s.t., the note. [who] is null in my plan,
 * else the friend's name.
 */
@Composable
fun PersonalBox(item: Timed, who: String?) {
    val p = item.personal
    if (p == null) {
        // No change of mine, but the course itself (a manual one) can be s.t.
        if (item.st) StLine()
        return
    }
    val scheme = MaterialTheme.colorScheme
    val own = planColors().own
    val locale = appLocale()
    val lines = buildList {
        if (p.overruled) add(stringResource(R.string.personal_overruled))
        if (p.cancelled) add(if (who == null) stringResource(R.string.personal_cancelled) else stringResource(R.string.personal_cancelled_by, who))
        if (p.roomChanged) add(stringResource(R.string.personal_room, p.current.room ?: "–", p.original.room ?: stringResource(R.string.no_room)))
        if (p.timeChanged) {
            val d = item.date
            add(stringResource(R.string.personal_time, timeRange(d.atTime(p.current.start), d.atTime(p.current.end)), timeRange(d.atTime(p.original.start), d.atTime(p.original.end))))
        }
        val series = p.series
        if (series != null && p.single?.let { it.room == null && it.start == null && !it.cancelled } != false &&
            (series.room != null || series.start != null || series.cancelled)
        ) {
            add(stringResource(R.string.personal_series, dayAndDate(series.date, locale)))
        }
    }
    if (p.changesPlan) {
        Surface(Modifier.fillMaxWidth().ownBorder(own, 1.5.dp, 12.dp), shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerHigh) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(EditIcon, null, Modifier.size(18.dp), tint = own)
                    Text(
                        if (who == null) stringResource(R.string.personal_changed_by_you) else stringResource(R.string.personal_changed_by, who),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
    if (p.st) StLine()
    p.note?.let { note ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(NoteIcon, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                Text(
                    stringResource(if (p.noteFromSeries) R.string.personal_note_series else R.string.personal_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            androidx.compose.foundation.text.selection.SelectionContainer { Text(note, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun StLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StBadge(12)
        Text(stringResource(R.string.st_long), style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun parseTime(text: String): LocalTime? {
    val t = text.trim().replace('.', ':')
    val m = Regex("^(\\d{1,2})(?::(\\d{2}))?$").find(t) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].ifEmpty { "0" }.toInt()
    return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
}

private fun LocalTime.text() = "%d:%02d".format(hour, minute)

/** Note, s.t., room and time of one date or (for series) of every date from this one on. */
@Composable
fun PersonalEditor(item: Timed, onSave: (series: Boolean, OccurrenceValues) -> Unit, onDismiss: () -> Unit) {
    val p = remember(item) { item.personalOrBlank() }
    val locale = appLocale()
    val canSeries = item.seriesEvent.recurrence != null
    val cur = p.current
    var series by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf(cur.note) }
    var st by rememberSaveable { mutableStateOf(cur.st) }
    var room by rememberSaveable { mutableStateOf(if (cur.room == p.original.room) "" else cur.room.orEmpty()) }
    var start by rememberSaveable { mutableStateOf(cur.start.text()) }
    var end by rememberSaveable { mutableStateOf(cur.end.text()) }
    val s = parseTime(start)
    val e = parseTime(end)
    val valid = s != null && e != null && e.isAfter(s)
    val original = item.date.atTime(p.original.start)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_edit_personal)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canSeries) {
                    ScopeRow(stringResource(R.string.editor_scope_one, dayAndDate(p.ref.date, locale)), !series) { series = false }
                    ScopeRow(
                        stringResource(R.string.editor_scope_series, "${dayShort(original.toLocalDate(), locale)} ${time(original)}", dayAndDate(p.ref.date, locale)),
                        series,
                    ) { series = true }
                }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp), label = { Text(stringResource(R.string.editor_note)) })
                Row(
                    Modifier.fillMaxWidth().toggleable(st, role = Role.Switch) { st = it }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.editor_st), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(st, null)
                }
                OutlinedTextField(
                    room, { room = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.editor_room)) },
                    supportingText = { Text(stringResource(R.string.editor_room_hint, p.original.room ?: stringResource(R.string.no_room))) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), singleLine = true, isError = !valid, keyboardOptions = keyboard, label = { Text(stringResource(R.string.editor_start)) })
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), singleLine = true, isError = !valid, keyboardOptions = keyboard, label = { Text(stringResource(R.string.editor_end)) })
                }
                if (!valid) Text(stringResource(R.string.editor_time_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(series, OccurrenceValues(note.trim(), st, room.trim().ifEmpty { null } ?: p.original.room, s!!, e!!))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ScopeRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * "Termine auswählen": every date of [item]'s course in my plan (LSF cancellations left out), checked = takes place for
 * me. Saves only what was toggled.
 */
@Composable
fun PickDatesDialog(plan: PlanUi, item: Timed, onSave: (Map<OccurrenceRef, Boolean>) -> Unit, onDismiss: () -> Unit) {
    val locale = appLocale()
    val course = item.event.courseKey
    val dates = remember(plan, course) {
        (plan.schedule.occurrences + plan.schedule.cancellations.filter { it.byMe })
            .filter { it.event.courseKey == course }
            .sortedBy { it.start }
    }
    val choices = remember { mutableStateMapOf<OccurrenceRef, Boolean>() }
    val name = blockName(plan.subjectOf[course], item)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_dates_title, name)) },
        text = {
            Column {
                Text(stringResource(R.string.pick_dates_hint), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(dates, key = { "${it.ref}@${it.event.uid}" }) { d ->
                        val checked = choices[d.ref] ?: (d is Occurrence)
                        Row(
                            Modifier.fillMaxWidth().clickable { choices[d.ref] = !checked }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked, { choices[d.ref] = it })
                            Text(
                                "${dayAndDate(d.date, locale)}  ${timeRange(d.start, d.end)}" + (d.room?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(choices.filter { (ref, on) -> dates.first { it.ref == ref }.let { (it is Occurrence) != on } })
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
