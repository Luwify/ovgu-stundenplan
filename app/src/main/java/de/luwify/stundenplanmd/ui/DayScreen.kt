package de.luwify.stundenplanmd.ui

import androidx.compose.animation.core.tween
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
import de.luwify.stundenplanmd.core.schedule.FreeGap
import de.luwify.stundenplanmd.core.schedule.Navigation
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.core.schedule.Schedule
import kotlinx.coroutines.launch
import java.time.LocalDate
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import de.luwify.stundenplanmd.ui.theme.planColors
import java.time.LocalDateTime

/**
 * Day strip above a pager with one page per day shown (Mon–Fri, Saturday/Sunday when something is on them); opens on [day] (shared between people, null = today or
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
    val days = remember(schedule, weeks) { Navigation.days(schedule, weeks) }
    val count = days.size
    fun pageOf(date: LocalDate) = Navigation.dayPage(days, date)
    fun dateOf(page: Int): LocalDate = days[page.coerceIn(0, count - 1)]
    val openPage = pageOf(day ?: openDate)

    key(days) {
        val pager = rememberPagerState(initialPage = openPage) { count }
        FollowSharedPage(pager, day?.let(::pageOf), active) { onDay(dateOf(it)) }
        val scope = rememberCoroutineScope()
        val animate = animationsEnabled()
        val locale = appLocale()
        val goTo: (Int) -> Unit = { page -> scope.launch { if (animate) pager.animateScrollToPage(page, animationSpec = tween(Motion.Long, easing = Motion.Standard)) else pager.scrollToPage(page) } }
        val labels = remember(plan, days, today, locale) {
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
            ChipStrip(labels, pager.currentPage, if (LocalCompactHeight.current) CompactDayChipWidth else DayChipWidth, stringResource(R.string.back_to_today), onSelect = goTo)
            HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = rememberBeyondPages(active)) { page ->
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
    val rows = remember(items, day) { dayRows(items, day.gaps) }
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
        items(rows, key = { it.key }) { row ->
            when (row) {
                is DayRow.Card -> TodayRow(plan, row.item, now, onClick = { onOpen(row.item) })
                is DayRow.Free -> FreeRow(row.gap)
            }
        }
    }
}

/** A row of the day list: a course card, or a break between two courses. */
private sealed interface DayRow {
    val key: String
    val start: LocalDateTime

    class Card(val item: Timed) : DayRow {
        override val key get() = "${item.event.uid}@${item.start}"
        override val start get() = item.start
    }

    class Free(val gap: FreeGap) : DayRow {
        override val key get() = "free@${gap.start}"
        override val start get() = gap.start
    }
}

/** Cards and breaks by time; a card that starts where a break starts (cancelled in it) comes first. */
private fun dayRows(items: List<Timed>, gaps: List<FreeGap>): List<DayRow> =
    (items.map { DayRow.Card(it) } + gaps.map { DayRow.Free(it) }).sortedWith(compareBy({ it.start }, { it is DayRow.Free }))

/** A break between two courses: dashed outline, no fill, no tap. */
@Composable
private fun FreeRow(gap: FreeGap) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    Row(
        Modifier.fillMaxWidth().ownBorder(MaterialTheme.colorScheme.outlineVariant, 1.5.dp, 14.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.free_gap, timeRange(gap.start, gap.end)),
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(durationText(gap.minutes), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, softWrap = false)
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
                plan.togetherWith(item).size.let { if (it > 0) TogetherBadge(it, plan.withFavorite(item), 24.dp) }
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
