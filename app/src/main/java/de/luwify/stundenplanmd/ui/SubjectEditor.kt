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
import androidx.compose.material3.AlertDialog
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
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import de.luwify.stundenplanmd.ui.theme.rgbColor
import de.luwify.stundenplanmd.ui.theme.withContrast

/**
 * Short name and color of a subject (saved on OK), and which courses belong to it (moves apply at once:
 * the grouping is a heuristic the user corrects). Closes itself if the subject loses its last course.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectEditor(
    plan: PlanUi,
    subjectKey: String,
    onSave: (shortName: String?, color: Int?) -> Unit,
    onMove: (Course, target: String) -> Unit,
    onPickCustomColor: ((current: Int, onPicked: (Int) -> Unit) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val subject = plan.subjects.firstOrNull { it.key == subjectKey }
    if (subject == null) {
        LaunchedEffect(subjectKey) { onDismiss() }
        return
    }
    val index = plan.subjects.indexOf(subject)
    var shortName by rememberSaveable(subjectKey) { mutableStateOf(subject.shortName.orEmpty()) }
    // null = automatic color.
    var color by rememberSaveable(subjectKey) { mutableStateOf(subject.color) }
    val shown = color ?: SubjectColors.defaultColor(index)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_subject)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(subject.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it.take(24) },
                    label = { Text(stringResource(R.string.short_name)) },
                    placeholder = { Text(subject.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.color), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubjectColors.palette.forEach { rgb ->
                        Swatch(rgb, selected = shown == rgb) { color = rgb }
                    }
                    if (onPickCustomColor != null) {
                        val custom = shown !in SubjectColors.palette
                        Swatch(shown, selected = custom, label = "+") { onPickCustomColor(shown) { color = it } }
                    }
                }
                if (color != null) {
                    TextButton(onClick = { color = null }) { Text(stringResource(R.string.color_automatic)) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.courses_of_subject), style = MaterialTheme.typography.titleSmall)
                subject.courses.forEach { course ->
                    CourseRow(plan, course, subjectKey, onMove)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(shortName.trim().ifEmpty { null }, color) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun Swatch(rgb: Int, selected: Boolean, label: String? = null, onClick: () -> Unit) {
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
private fun CourseRow(plan: PlanUi, course: Course, subjectKey: String, onMove: (Course, String) -> Unit) {
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
