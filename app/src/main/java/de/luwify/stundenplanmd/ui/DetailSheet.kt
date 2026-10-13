package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.NoteHint
import de.luwify.stundenplanmd.core.model.Subject
import de.luwify.stundenplanmd.core.schedule.Cancellation
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import de.luwify.stundenplanmd.ui.theme.planColors
import de.luwify.stundenplanmd.ui.theme.withContrast
import java.time.LocalDate

/** Everything about one tapped occurrence or cancellation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    plan: PlanUi,
    item: Timed,
    onEditSubject: (Subject) -> Unit,
    onEditPersonal: () -> Unit,
    onCancelForMe: (Boolean) -> Unit,
    onPickDates: () -> Unit,
    onEditManual: () -> Unit,
    onDeleteManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val locale = appLocale()
    val event = item.event
    val subject = plan.subjectOf[event.courseKey]
    val rgb = plan.colorOf(item)
    val stripe = remember(rgb, scheme.surfaceContainerLow) { SubjectColors.block(rgb, scheme.surfaceContainerLow).stripe }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // An action closes the sheet by sliding it away, like a swipe or back, instead of removing it at once.
    fun leave(action: () -> Unit) {
        action()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(stripe))
                Text(
                    listOf(subject?.shortName, event.type.name.ifEmpty { null }).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            SelectionContainer { Text(event.title, style = MaterialTheme.typography.headlineSmall) }

            StatusBox(item)
            if (event.isManual) {
                ManualBox(
                    who = if (plan.isMe) null else plan.person.displayName,
                    onEdit = { leave(onEditManual) }.takeIf { plan.isMe },
                    onDelete = { leave(onDeleteManual) }.takeIf { plan.isMe },
                )
            }
            PersonalBox(item, who = if (plan.isMe) null else plan.person.displayName)

            Field(stringResource(R.string.detail_when), "${longDate(item.date, locale)}\n${timeRange(item.start, item.end)}" + rhythm(event, locale)?.let { "\n$it" }.orEmpty())
            Field(stringResource(R.string.detail_room), if (item.room != event.location?.room) item.room.orEmpty() else roomText(event))
            val with = plan.togetherWith(item)
            if (with.isNotEmpty()) TogetherField(plan, with)
            if (event.lecturers.isNotEmpty()) Field(stringResource(R.string.detail_lecturers), event.lecturers.joinToString(", "))
            val hint = when (event.noteHint) {
                NoteHint.NONE -> null
                NoteHint.SHORTENED -> stringResource(R.string.notes_shortened)
                NoteHint.NOT_SENT -> stringResource(R.string.notes_not_sent)
            }
            if (event.notes.isNotBlank() || hint != null) {
                Field(stringResource(R.string.detail_notes), listOfNotNull(event.notes.takeIf { it.isNotBlank() }, hint).joinToString("\n\n"))
            }
            if (event.summary != event.title) Field(stringResource(R.string.detail_lsf_title), event.summary)

            if (plan.isMe) {
                // Own changes: my plan only (a friend's plan is read-only).
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { leave(onEditPersonal) }) {
                        Icon(NoteIcon, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_edit_personal))
                    }
                    val lsfCancelled = item is Cancellation && !item.byMe
                    if (!lsfCancelled) {
                        val cancelled = item.cancelledForMe
                        OutlinedButton(onClick = { leave { onCancelForMe(!cancelled) } }) {
                            Text(stringResource(if (cancelled) R.string.action_uncancel else R.string.action_cancel_for_me))
                        }
                    }
                    OutlinedButton(onClick = { leave(onPickDates) }) { Text(stringResource(R.string.action_pick_dates)) }
                }
            }
            if (subject != null) {
                OutlinedButton(onClick = { leave { onEditSubject(subject) } }) {
                    Icon(EditIcon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.edit_subject))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusBox(item: Timed) {
    val scheme = MaterialTheme.colorScheme
    val hl = planColors()
    val locale = appLocale()
    val lines: List<String> = when {
        item is Cancellation && !item.byMe -> listOf(stringResource(R.string.cancelled_on, longDate(item.date, locale)))
        item is Occurrence && item.status is Status.Changed -> {
            val orig = (item.status as Status.Changed).original
            changeLines(item) + stringResource(
                R.string.regular_slot,
                "${dayAndDate(orig.date, locale)} ${timeRange(orig.start, orig.end)}",
                orig.event.location?.room ?: stringResource(R.string.no_room),
            )
        }
        item is Occurrence && item.status == Status.Extra -> listOf(stringResource(R.string.extra_long))
        else -> return
    }
    val bg = scheme.surfaceContainerHigh
    val text = remember(hl, bg) { hl.highlight.withContrast(4.5, listOf(bg)) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = bg) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEachIndexed { i, line ->
                Text(
                    line,
                    color = if (i == 0) text else scheme.onSurface,
                    fontWeight = if (i == 0) FontWeight.SemiBold else null,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** Who else attends: favorite friends first, each with a heart, then the others (and me in a friend's plan). */
@Composable
private fun TogetherField(plan: PlanUi, ids: List<String>) {
    val you = stringResource(R.string.together_you)
    val heart = stringResource(R.string.favorite)
    val red = planColors().favorite
    val (favorites, others) = ids.partition { it in plan.favorites }
    fun name(id: String) = if (id == de.luwify.stundenplanmd.data.StoredData.ME) you else plan.names[id] ?: "?"
    val text = buildAnnotatedString {
        (favorites + others).forEachIndexed { i, id ->
            if (i > 0) append(", ")
            if (id in plan.favorites) { appendInlineContent("heart", "♥"); append(" ") }
            append(name(id))
        }
    }
    val inline = mapOf(
        "heart" to InlineTextContent(Placeholder(16.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)) {
            Icon(painterResource(R.drawable.ic_favorite_filled), contentDescription = heart, tint = red, modifier = Modifier.fillMaxSize())
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.detail_together), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, inlineContent = inline, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun roomText(e: Event): String {
    val l = e.location ?: return stringResource(R.string.no_room)
    val parts = listOfNotNull(
        l.room + (l.alias?.let { " ($it)" } ?: ""),
        l.building?.let { stringResource(R.string.building, it) },
        l.capacity?.let { pluralStringResource(R.plurals.seats, it, it) },
    )
    return parts.joinToString(" · ")
}

/** "Weekly on Mon until 25.1." / "Every 2 weeks ..." for series. */
@Composable
private fun rhythm(e: Event, locale: java.util.Locale): String? {
    val r = e.recurrence ?: return null
    val until: LocalDate? = r.until?.atZone(de.luwify.stundenplanmd.core.model.PlanZone)?.toLocalDate()
    val day = dayShort(e.start.toLocalDate(), locale)
    val base = if (r.interval == 1) stringResource(R.string.rhythm_weekly, day) else stringResource(R.string.rhythm_n_weeks, r.interval, day)
    return if (until != null) stringResource(R.string.rhythm_until, base, dayAndDate(until, locale)) else base
}
