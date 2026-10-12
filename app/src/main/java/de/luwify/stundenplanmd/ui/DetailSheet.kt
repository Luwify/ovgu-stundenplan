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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.Event
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
fun DetailSheet(plan: PlanUi, item: Timed, onEditSubject: (Subject) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val locale = appLocale()
    val event = item.event
    val subject = plan.subjectOf[event.courseKey]
    val rgb = plan.colorOf(item)
    val stripe = remember(rgb, scheme.surfaceContainerLow) { SubjectColors.block(rgb, scheme.surfaceContainerLow).stripe }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
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

            Field(stringResource(R.string.detail_when), "${longDate(item.date, locale)}\n${timeRange(item.start, item.end)}" + rhythm(event, locale)?.let { "\n$it" }.orEmpty())
            Field(stringResource(R.string.detail_room), roomText(event))
            val with = plan.togetherWith(item)
            if (with.isNotEmpty()) {
                val you = stringResource(R.string.together_you)
                Field(stringResource(R.string.detail_together), with.joinToString(", ") { if (it == de.luwify.stundenplanmd.data.StoredData.ME) you else plan.names[it] ?: "?" })
            }
            if (event.lecturers.isNotEmpty()) Field(stringResource(R.string.detail_lecturers), event.lecturers.joinToString(", "))
            if (event.notes.isNotBlank() || event.notesShortened) {
                val hint = stringResource(if (event.notes.isBlank()) R.string.notes_not_sent else R.string.notes_shortened)
                Field(stringResource(R.string.detail_notes), listOf(event.notes, hint).filter { it.isNotBlank() }.joinToString("\n\n"))
            }
            if (event.summary != event.title) Field(stringResource(R.string.detail_lsf_title), event.summary)

            if (subject != null) {
                OutlinedButton(onClick = { onEditSubject(subject) }) {
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
        item is Cancellation -> listOf(stringResource(R.string.cancelled_on, longDate(item.date, locale)))
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
