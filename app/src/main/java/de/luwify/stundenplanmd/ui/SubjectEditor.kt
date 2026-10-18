package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.CourseKeys
import de.luwify.stundenplanmd.core.model.Course
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.Subject
import androidx.compose.material3.FilterChip
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.Expansion
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import de.luwify.stundenplanmd.ui.theme.rgbColor
import de.luwify.stundenplanmd.ui.theme.withContrast

/**
 * Short name (a prefix for all types plus a part per type) and color of a subject (saved on OK), and which courses
 * belong to it (moves apply at once: the grouping is a heuristic the user corrects). [courseKey]: the type opened from
 * (its part is edited first). Closes itself if the subject loses its last course.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectEditor(
    plan: PlanUi,
    subjectKey: String,
    courseKey: String?,
    onSave: (shortName: String?, color: Int?, typed: Map<String, String?>) -> Unit,
    onMove: (Course, target: String) -> Unit,
    onRhythm: (uid: String, WeekRhythm?) -> Unit,
    onHide: (Hidden) -> Unit,
    onDismiss: () -> Unit,
) {
    val subject = plan.subjects.firstOrNull { it.key == subjectKey }
    if (subject == null) {
        LaunchedEffect(subjectKey) { onDismiss() }
        return
    }
    val index = plan.subjects.indexOf(subject)
    var shortName by rememberSaveable(subjectKey) { mutableStateOf(subject.shortName.orEmpty()) }
    // Course key -> its part of the short name, for every course of the subject.
    // (Held as a HashMap, which the saved state can store; replaced on every change, never mutated.)
    var typeParts by rememberSaveable(subjectKey) { mutableStateOf<Map<String, String>>(HashMap(subject.courses.associate { it.key to subject.typeShortNames[it.key].orEmpty() })) }
    var focus by rememberSaveable(subjectKey) { mutableStateOf(courseKey?.takeIf { k -> subject.courses.any { it.key == k } } ?: subject.courses.first().key) }
    val focused = subject.courses.firstOrNull { it.key == focus } ?: subject.courses.first()
    // null = automatic color.
    var color by rememberSaveable(subjectKey) { mutableStateOf(subject.color) }
    val shown = color ?: SubjectColors.defaultColor(index)
    var picking by rememberSaveable(subjectKey) { mutableStateOf(false) }
    if (picking) {
        ColorPickerDialog(
            title = stringResource(R.string.color),
            initial = shown,
            onAuto = null,
            onDismiss = { picking = false },
            onPick = {
                color = it
                picking = false
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_subject)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(subject.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it.take(24) },
                    label = { Text(stringResource(R.string.short_name_all)) },
                    placeholder = { Text(subject.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (subject.courses.size > 1) {
                    // Which type's part is edited.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subject.courses.forEach { c ->
                            FilterChip(
                                selected = c.key == focused.key,
                                onClick = { focus = c.key },
                                label = { Text(HideTargets.typeName(c), maxLines = 1) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = typeParts[focused.key].orEmpty(),
                    onValueChange = { v -> typeParts = HashMap(typeParts).apply { put(focused.key, v.take(16)) } },
                    label = { Text(stringResource(R.string.short_name_type)) },
                    supportingText = { Text(HideTargets.typeName(focused) + " · " + focused.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.short_name_preview), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val preview = Subject.compose(shortName.trim().ifEmpty { subject.name }, typeParts[focused.key])
                    val page = MaterialTheme.colorScheme.surfaceContainerHigh
                    val tint = remember(shown, page) { SubjectColors.block(shown, page) }
                    Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(tint.container).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(preview, color = tint.content, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(stringResource(R.string.color), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubjectColors.palette.forEach { rgb ->
                        Swatch(rgb, selected = shown == rgb) { color = rgb }
                    }
                    Swatch(shown, selected = shown !in SubjectColors.palette, label = "+") { picking = true }
                }
                if (color != null) {
                    TextButton(onClick = { color = null }) { Text(stringResource(R.string.color_automatic)) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.courses_of_subject), style = MaterialTheme.typography.titleSmall)
                subject.courses.forEach { course ->
                    CourseRow(plan, course, subjectKey, onMove, onHide)
                    // LSF's "every 2 weeks" is meant as even/odd calendar weeks; each series can be set by hand.
                    course.events.filter { it.recurrence?.biweekly == true && !it.isManual }.sortedBy { it.start }.forEach { e ->
                        RhythmRow(e, plan.rhythms[e.uid]) { onRhythm(e.uid, it) }
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { onHide(HideTargets.subject(subject)) }) { Text(stringResource(R.string.hide_subject)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(shortName.trim().ifEmpty { null }, color, typeParts.mapValues { it.value.trim().ifEmpty { null } }) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
internal fun Swatch(rgb: Int, selected: Boolean, label: String? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val c = rgbColor(rgb)
    val desc = "#%06X".format(rgb)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(c)
            .border(if (selected) 3.dp else 1.dp, if (selected) scheme.onSurface else scheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, color = androidx.compose.ui.graphics.Color.White.withContrast(4.5, listOf(c)))
    }
}

@Composable
private fun CourseRow(plan: PlanUi, course: Course, subjectKey: String, onMove: (Course, String) -> Unit, onHide: (Hidden) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val id = CourseKeys.courseId(course.events.first().uid)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TypeLabel(course.type.short, border = MaterialTheme.colorScheme.outline, text = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f)) {
            Text(course.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (id != null) {
                Text(stringResource(R.string.course_id, id), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text(stringResource(R.string.move)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val own = Subjects.ownKey(course.key)
                val alone = plan.subjects.first { it.key == subjectKey }.courses.size == 1
                if (!alone) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_to_own)) },
                        onClick = { menu = false; onMove(course, own) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.hide_type, HideTargets.typeName(course))) },
                        onClick = { menu = false; onHide(HideTargets.course(course)) },
                    )
                }
                plan.subjects.filter { it.key != subjectKey }.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(target.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = { menu = false; onMove(course, target.key) },
                    )
                }
            }
        }
    }
}

/** Rhythm of one biweekly series: automatic (from the data) or set by hand; applies at once. */
@Composable
private fun RhythmRow(event: Event, chosen: WeekRhythm?, onPick: (WeekRhythm?) -> Unit) {
    val locale = appLocale()
    var menu by remember { mutableStateOf(false) }
    val auto = remember(event) { Expansion.rhythmOf(event.copy(recurrence = event.recurrence?.copy(rhythm = null))) }
    val autoLabel = stringResource(R.string.rhythm_auto, rhythmName(auto))
    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${dayShort(event.start.toLocalDate(), locale)} ${timeRange(event.start, event.end)}",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            TextButton(onClick = { menu = true }) { Text(if (chosen == null) autoLabel else rhythmName(chosen)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(autoLabel) }, onClick = { menu = false; onPick(null) })
                WeekRhythm.entries.forEach { r ->
                    DropdownMenuItem(text = { Text(rhythmName(r)) }, onClick = { menu = false; onPick(r) })
                }
            }
        }
    }
}

@Composable
internal fun rhythmName(r: WeekRhythm?): String = when (r) {
    WeekRhythm.EVEN_WEEKS -> stringResource(R.string.manual_even_weeks)
    WeekRhythm.ODD_WEEKS -> stringResource(R.string.manual_odd_weeks)
    WeekRhythm.EVERY_14_DAYS, null -> stringResource(R.string.manual_biweekly)
}
