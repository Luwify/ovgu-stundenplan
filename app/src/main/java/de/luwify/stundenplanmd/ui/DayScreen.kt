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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.schedule.Cancellation
import de.luwify.stundenplanmd.core.schedule.Navigation
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.core.schedule.Schedule
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import de.luwify.stundenplanmd.ui.theme.planColors
import java.time.LocalDateTime

/**
 * Day strip above a pager with one page per weekday (Mon–Fri); opens on [day] (shared between people, null = today or
 * the next day with events), clamped to this plan's range. While [active], days the user moves to go to [onDay].
 */
@Composable
fun DayScreen(
    plan: PlanUi,
    now: LocalDateTime,
    day: LocalDate?,
    active: Boolean,
    onDay: (LocalDate) -> Unit,
    onOpen: (Timed) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedule = plan.schedule
    val today = now.toLocalDate()
    val openDate = Navigation.dayToOpen(schedule, now)
    val weeks = remember(schedule, openDate) { Navigation.weeks(schedule, Schedule.monday(openDate.atStartOfDay())) }
    val start = weeks.first()
    val count = weeks.size * 5
    fun pageOf(date: LocalDate) =
        (ChronoUnit.WEEKS.between(start, Schedule.monday(date.atStartOfDay())).toInt() * 5 + minOf(date.dayOfWeek.value - 1, 4)).coerceIn(0, count - 1)
    fun dateOf(page: Int): LocalDate = start.plusWeeks((page / 5).toLong()).plusDays((page % 5).toLong())
    val openPage = pageOf(day ?: openDate)

    key(start, count) {
        val pager = rememberPagerState(initialPage = openPage) { count }
        FollowSharedPage(pager, day?.let(::pageOf), active) { onDay(dateOf(it)) }
        val scope = rememberCoroutineScope()
        val animate = animationsEnabled()
        val locale = appLocale()
        val goTo: (Int) -> Unit = { page -> scope.launch { if (animate) pager.animateScrollToPage(page) else pager.scrollToPage(page) } }
        val labels = remember(plan, start, count, today, locale) {
            (0 until count).map { page ->
                val date = dateOf(page)
                ChipLabel(
                    top = dayShort(date, locale),
                    bottom = "${date.dayOfMonth}.${date.monthValue}.",
                    muted = schedule.on(date).isEmpty(),
                    current = date == today,
                )
            }
        }
        Column(modifier) {
            ChipStrip(labels, pager.currentPage, DayChipWidth, stringResource(R.string.back_to_today), onSelect = goTo)
            HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                DayPage(plan, dateOf(page), now, onOpen)
            }
        }
    }
}

@Composable
private fun DayPage(plan: PlanUi, date: LocalDate, now: LocalDateTime, onOpen: (Timed) -> Unit) {
    val schedule = plan.schedule
    val day = remember(plan, date) { schedule.day(date) }
    val locale = appLocale()
    val today = now.toLocalDate()
    // Cancellations are shown for the current and next week only, as before.
    val near = date.isBefore(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusWeeks(2))
    val items = remember(day, near) { day.items(near) }
    val heading = when (date) {
        today -> stringResource(R.string.today_heading, longDate(date, locale))
        today.plusDays(1) -> stringResource(R.string.tomorrow_heading, longDate(date, locale))
        else -> longDate(date, locale)
    }
    val message = when {
        day.free -> stringResource(R.string.lecture_free)
        items.isNotEmpty() -> null
        schedule.firstDate?.let { date.isBefore(it) } == true -> stringResource(R.string.semester_starts, longDate(schedule.firstDate!!, locale))
        schedule.lastDate?.let { date.isAfter(it) } == true -> stringResource(R.string.plan_ended, longDate(schedule.lastDate!!, locale))
        else -> stringResource(R.string.no_events_day)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(heading, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp)) }
        if (message != null) item { InfoCard(message) }
        items(items, key = { "${it.event.uid}@${it.start}" }) { item ->
            TodayRow(plan, item, now, onClick = { onOpen(item) })
        }
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
    val hl = planColors()
    val card = hl.cardContainer
    val rgb = plan.colorOf(item)
    val c = remember(rgb, page) { SubjectColors.block(rgb, page) }
    val cancelled = item is Cancellation
    val status = (item as? Occurrence)?.status
    val highlighted = (cancelled && !item.cancelledForMe) || status is Status.Changed || status == Status.Extra
    val past = !item.end.isAfter(now)
    val running = !past && !item.start.isAfter(now)
    val shape = RoundedCornerShape(14.dp)
    val subject = plan.subjectOf[item.event.courseKey]
    val personal = item.personal
    val ownChange = item.ownChange && !highlighted

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .alpha(if (past) 0.55f else 1f)
            .clip(shape)
            .background(card)
            .border(if (highlighted) 2.dp else hl.cardBorderWidth, if (highlighted) hl.highlight else hl.cardBorder, shape)
            .then(if (ownChange) Modifier.ownBorder(hl.own, 2.dp, 14.dp) else Modifier)
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
                if (plan.togetherWith(item).isNotEmpty()) TogetherBadge(plan.withFavorite(item), 24.dp)
                if (item.st) StBadge(12)
                if (item.event.isManual) ManualBadge(24.dp)
                if (item.ownChange) MarkerBadge(note = false, 24.dp)
                if (running) {
                    Text(
                        stringResource(R.string.now),
                        Modifier.clip(RoundedCornerShape(6.dp)).background(scheme.primary).padding(horizontal = 6.dp),
                        color = scheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            val room = item.room ?: stringResource(R.string.no_room)
            Text(
                listOf(room, item.event.lecturers.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val notes = when {
                item.cancelledForMe -> emptyList()
                cancelled -> listOf(stringResource(R.string.cancelled_full))
                status is Status.Changed -> changeLines(item as Occurrence)
                status == Status.Extra -> listOf(stringResource(R.string.extra)) + item.event.notes.lines().take(1).filter { it.isNotBlank() }
                else -> emptyList()
            }
            notes.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = hl.highlightText, fontWeight = FontWeight.SemiBold)
            }
            if (personal != null && personal.changesPlan) {
                val who = if (plan.isMe) null else plan.person.displayName
                Text(
                    when {
                        personal.cancelled -> if (who == null) stringResource(R.string.personal_cancelled) else stringResource(R.string.personal_cancelled_by, who)
                        who == null -> stringResource(R.string.personal_changed_by_you)
                        else -> stringResource(R.string.personal_changed_by, who)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (item.event.isManual) {
                Text(
                    if (plan.isMe) stringResource(R.string.manual_own_course) else stringResource(R.string.manual_own_course_of, plan.person.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            personal?.note?.let { note ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(NoteIcon, stringResource(R.string.note_marker), Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
