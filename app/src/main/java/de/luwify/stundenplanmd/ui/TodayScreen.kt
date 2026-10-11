package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.schedule.Cancellation
import de.luwify.stundenplanmd.core.schedule.DayPlan
import de.luwify.stundenplanmd.core.schedule.Navigation
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.core.schedule.TodayView
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import de.luwify.stundenplanmd.ui.theme.planColors
import de.luwify.stundenplanmd.ui.theme.withContrast
import java.time.LocalDateTime

/** Today's events, or the next day with events; cancellations of the current and next week included. */
@Composable
fun TodayScreen(plan: PlanUi, now: LocalDateTime, onOpen: (Timed) -> Unit, modifier: Modifier = Modifier) {
    val view = remember(plan, now) { Navigation.today(plan.schedule, now) }
    val locale = appLocale()
    val today = now.toLocalDate()
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (view) {
            TodayView.NoPlan -> Unit
            is TodayView.Ended -> item { InfoCard(stringResource(R.string.plan_ended, longDate(view.last, locale))) }
            is TodayView.BeforeStart -> {
                item { InfoCard(stringResource(R.string.semester_starts, longDate(view.day.date, locale))) }
                dayItems(plan, view.day, withCancellations = false, now, onOpen) {
                    longDate(view.day.date, locale)
                }
            }
            is TodayView.Day -> {
                if (view.skippedFree.isNotEmpty()) {
                    item {
                        val text = if (view.skippedFree.size == 1 && view.skippedFree.first() == today) {
                            stringResource(R.string.today_free)
                        } else {
                            stringResource(R.string.free_until, longDate(view.skippedFree.last(), locale))
                        }
                        InfoCard(text)
                    }
                }
                dayItems(plan, view.day, view.withCancellations, now, onOpen) {
                    when (view.day.date) {
                        today -> stringResource(R.string.today_heading, longDate(view.day.date, locale))
                        today.plusDays(1) -> stringResource(R.string.tomorrow_heading, longDate(view.day.date, locale))
                        else -> longDate(view.day.date, locale)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dayItems(
    plan: PlanUi,
    day: DayPlan,
    withCancellations: Boolean,
    now: LocalDateTime,
    onOpen: (Timed) -> Unit,
    heading: @Composable () -> String,
) {
    item { Text(heading(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp)) }
    items(day.items(withCancellations), key = { "${it.event.uid}@${it.start}" }) { item ->
        TodayRow(plan, item, now, onClick = { onOpen(item) })
    }
}

@Composable
private fun InfoCard(text: String) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TodayRow(plan: PlanUi, item: Timed, now: LocalDateTime, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val page = scheme.background
    val card = scheme.surfaceContainerLow
    val rgb = plan.colorOf(item)
    val c = remember(rgb, page) { SubjectColors.block(rgb, page) }
    val hl = planColors()
    val hlText = remember(hl, card) { hl.highlight.withContrast(4.5, listOf(card)) }
    val cancelled = item is Cancellation
    val status = (item as? Occurrence)?.status
    val highlighted = cancelled || status is Status.Changed || status == Status.Extra
    val past = !item.end.isAfter(now)
    val running = !past && !item.start.isAfter(now)
    val shape = RoundedCornerShape(14.dp)
    val subject = plan.subjectOf[item.event.courseKey]

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .alpha(if (past) 0.55f else 1f)
            .clip(shape)
            .background(card)
            .then(if (highlighted) Modifier.border(2.dp, hl.highlight, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(c.stripe))
        Column(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp).width(52.dp)) {
            Text(time(item.start), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(time(item.end), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    blockName(subject, item),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                )
                TypeLabel(item.event.type.short, border = c.stripe, text = scheme.onSurface)
                if (running) {
                    Text(
                        stringResource(R.string.now),
                        Modifier.clip(RoundedCornerShape(6.dp)).background(scheme.primary).padding(horizontal = 6.dp),
                        color = scheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            val room = item.event.location?.room ?: stringResource(R.string.no_room)
            Text(
                listOf(room, item.event.lecturers.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val notes = when {
                cancelled -> listOf(stringResource(R.string.cancelled_full))
                status is Status.Changed -> changeLines(item as Occurrence)
                status == Status.Extra -> listOf(stringResource(R.string.extra)) + item.event.notes.lines().take(1).filter { it.isNotBlank() }
                else -> emptyList()
            }
            notes.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = hlText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
