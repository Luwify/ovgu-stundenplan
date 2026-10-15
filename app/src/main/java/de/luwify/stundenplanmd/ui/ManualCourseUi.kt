package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.ManualType
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import java.time.LocalDate
import java.time.ZoneOffset

/** "Own course" marker: a plus on a round surface badge (own changes have the violet pencil, LSF changes amber). */
@Composable
fun ManualBadge(size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.size(size).clip(CircleShape).background(scheme.surface), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_add),
            contentDescription = stringResource(R.string.manual_marker),
            tint = scheme.primary,
            modifier = Modifier.size(size * 0.8f),
        )
    }
}

/** The detail sheet's "own course" box: whose course it is; in my plan with edit and delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualBox(who: String?, onEdit: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualBadge(22.dp)
                Text(
                    if (who == null) stringResource(R.string.manual_own_course) else stringResource(R.string.manual_own_course_of, who),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (onEdit != null && onDelete != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.manual_edit)) }
                    OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.manual_delete)) }
                }
            }
        }
    }
}

private fun LocalDate.millis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun dateOfMillis(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

/** What the editor saves: the course and its subject (an existing subject's key, or null = a new one of its own). */
data class ManualEdit(val course: ManualCourse, val subjectKey: String?, val shortName: String?, val color: Int?) {
    /** The course setting to store: the chosen subject's style, or the new subject's short name and color. */
    fun setting(plan: PlanUi): CourseSetting {
        val key = course.courseKey
        val subject = subjectKey?.let { k -> plan.subjects.firstOrNull { it.key == k } }
        return if (subject != null) {
            CourseSetting(key, course.title, subject = subject.key, shortName = subject.shortName, color = subject.color)
        } else {
            CourseSetting(key, course.title, subject = Subjects.ownKey(key), shortName = shortName, color = color)
        }
    }
}

/**
 * Add or edit a manual course ([existing] null = new; [newId] gives the id of a new one). [defaultEnd]: last date of
 * the imported plan for a new series.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualCourseEditor(
    plan: PlanUi,
    existing: ManualCourse?,
    newId: () -> String,
    defaultEnd: (LocalDate) -> LocalDate,
    today: LocalDate,
    onSave: (ManualEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = appLocale()
    val setting = existing?.let { plan.settings[it.courseKey] }
    val ownKey = existing?.let { Subjects.ownKey(it.courseKey) }
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    // The "title required" hint only once the field was used (not on an empty new form).
    var titleTouched by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(existing?.type ?: ManualType.LECTURE) }
    var subjectKey by rememberSaveable { mutableStateOf(setting?.subject?.takeIf { it != ownKey }) }
    var shortName by rememberSaveable { mutableStateOf(setting?.shortName.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(setting?.color ?: SubjectColors.defaultColor(plan.subjects.size)) }
    var room by rememberSaveable { mutableStateOf(existing?.room.orEmpty()) }
    var lecturer by rememberSaveable { mutableStateOf(existing?.lecturer.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    var st by rememberSaveable { mutableStateOf(existing?.st ?: false) }
    var rhythm by rememberSaveable { mutableStateOf(existing?.rhythm ?: ManualCourse.Rhythm.WEEKLY) }
    val startInit = existing?.startDate ?: today
    var startDate by rememberSaveable { mutableStateOf(startInit.toEpochDay()) }
    var endDate by rememberSaveable { mutableStateOf((existing?.endDate ?: defaultEnd(startInit)).toEpochDay()) }
    var startText by rememberSaveable { mutableStateOf(existing?.start?.let { "%d:%02d".format(it.hour, it.minute) } ?: "9:15") }
    var endText by rememberSaveable { mutableStateOf(existing?.end?.let { "%d:%02d".format(it.hour, it.minute) } ?: "10:45") }
    var skipFree by rememberSaveable { mutableStateOf(existing?.skipFreeWeeks ?: true) }
    var picking by rememberSaveable { mutableStateOf<Int?>(null) } // 0 = first date, 1 = last date
    var picker by rememberSaveable { mutableStateOf(false) }

    val s = parseTime(startText)
    val e = parseTime(endText)
    val timesOk = s != null && e != null && e.isAfter(s)
    val once = rhythm == ManualCourse.Rhythm.ONCE
    val datesOk = once || endDate >= startDate
    val titleOk = title.isNotBlank()
    val valid = titleOk && timesOk && datesOk

    picking?.let { which ->
        val state = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(if (which == 0) startDate else endDate).millis())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                DialogWindowAnimation(R.style.Motion_Dialog)
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = dateOfMillis(ms).toEpochDay()
                        if (which == 0) {
                            // A new first date moves a default end along only while it would end up before it.
                            startDate = d
                            if (endDate < d) endDate = defaultEnd(LocalDate.ofEpochDay(d)).toEpochDay()
                        } else {
                            endDate = d
                        }
                    }
                    picking = null
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
    if (picker) {
        ColorPickerDialog(stringResource(R.string.color), color, onAuto = null, onDismiss = { picker = false }, onPick = { color = it; picker = false })
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogWindowAnimation(R.style.Motion_Screen)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel)) }
                    Text(
                        stringResource(if (existing == null) R.string.manual_add else R.string.manual_edit),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(enabled = valid, onClick = {
                        val course = ManualCourse(
                            id = existing?.id ?: newId(),
                            title = title.trim(),
                            type = type,
                            room = room.trim().ifEmpty { null },
                            lecturer = lecturer.trim().ifEmpty { null },
                            note = note.trim(),
                            st = st,
                            rhythm = rhythm,
                            startDate = LocalDate.ofEpochDay(startDate),
                            endDate = if (once) null else LocalDate.ofEpochDay(endDate),
                            start = s!!,
                            end = e!!,
                            skipFreeWeeks = skipFree,
                        )
                        onSave(ManualEdit(course, subjectKey, shortName.trim().ifEmpty { null }, color))
                    }) { Text(stringResource(R.string.save)) }
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        title, { title = it; titleTouched = true }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.manual_title)) }, isError = titleTouched && !titleOk,
                        supportingText = if (titleOk || !titleTouched) null else { { Text(stringResource(R.string.manual_title_required)) } },
                    )
                    Label(stringResource(R.string.manual_type))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            ManualType.LECTURE to R.string.manual_type_lecture, ManualType.EXERCISE to R.string.manual_type_exercise,
                            ManualType.LAB to R.string.manual_type_lab, ManualType.SEMINAR to R.string.manual_type_seminar,
                            ManualType.OTHER to R.string.manual_type_other,
                        ).forEach { (t, label) -> FilterChip(type == t, { type = t }, { Text(stringResource(label)) }) }
                    }
                    val subjects = plan.subjects.filter { it.key != ownKey }
                    SettingsDropdown(
                        stringResource(R.string.manual_subject),
                        listOf<Pair<String?, String>>(null to stringResource(R.string.manual_subject_new)) + subjects.map { it.key to it.displayName },
                        subjectKey,
                        { subjectKey = it },
                    )
                    if (subjectKey == null) {
                        OutlinedTextField(
                            shortName, { shortName = it.take(24) }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.short_name)) },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubjectColors.palette.forEach { rgb -> Swatch(rgb, selected = color == rgb) { color = rgb } }
                            Swatch(color, selected = color !in SubjectColors.palette, label = "+") { picker = true }
                        }
                    }
                    OutlinedTextField(room, { room = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.editor_room)) })
                    OutlinedTextField(lecturer, { lecturer = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.manual_lecturer)) })
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp), label = { Text(stringResource(R.string.editor_note)) })
                    SwitchLine(stringResource(R.string.editor_st), null, st) { st = it }

                    Label(stringResource(R.string.manual_rhythm))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            ManualCourse.Rhythm.ONCE to R.string.manual_once, ManualCourse.Rhythm.WEEKLY to R.string.manual_weekly,
                            ManualCourse.Rhythm.EVEN_WEEKS to R.string.manual_even_weeks, ManualCourse.Rhythm.ODD_WEEKS to R.string.manual_odd_weeks,
                            ManualCourse.Rhythm.BIWEEKLY to R.string.manual_biweekly,
                        ).forEach { (r, label) -> FilterChip(rhythm == r, { rhythm = r }, { Text(stringResource(label)) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateButton(stringResource(if (once) R.string.manual_date else R.string.manual_start_date), LocalDate.ofEpochDay(startDate), locale, Modifier.weight(1f)) { picking = 0 }
                        if (!once) DateButton(stringResource(R.string.manual_end_date), LocalDate.ofEpochDay(endDate), locale, Modifier.weight(1f)) { picking = 1 }
                    }
                    if (!datesOk) Text(stringResource(R.string.manual_end_before_start), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
                        OutlinedTextField(startText, { startText = it }, Modifier.weight(1f), singleLine = true, isError = !timesOk, keyboardOptions = keyboard, label = { Text(stringResource(R.string.editor_start)) })
                        OutlinedTextField(endText, { endText = it }, Modifier.weight(1f), singleLine = true, isError = !timesOk, keyboardOptions = keyboard, label = { Text(stringResource(R.string.editor_end)) })
                    }
                    if (!timesOk) Text(stringResource(R.string.editor_time_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!once) SwitchLine(stringResource(R.string.manual_skip_free), stringResource(R.string.manual_skip_free_hint), skipFree) { skipFree = it }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable
private fun SwitchLine(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, null)
    }
}

@Composable
private fun DateButton(label: String, date: LocalDate, locale: java.util.Locale, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text("${dayAndDate(date, locale)}${date.year}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
