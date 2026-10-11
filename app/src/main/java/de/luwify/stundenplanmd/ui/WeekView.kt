package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.schedule.Cancellation
import de.luwify.stundenplanmd.core.schedule.DayLayout
import de.luwify.stundenplanmd.core.schedule.DayPlan
import de.luwify.stundenplanmd.core.schedule.Navigation
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import de.luwify.stundenplanmd.ui.theme.planColors
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private val Gutter = 26.dp
private val MinHour = 52.dp

/** Mon–Fri grid, one page per week; opens on this week (next week on weekends). */
@Composable
fun WeekScreen(plan: PlanUi, now: LocalDateTime, onOpen: (Timed) -> Unit, modifier: Modifier = Modifier) {
    val schedule = plan.schedule
    val openMonday = Navigation.weekToOpen(now.toLocalDate())
    val firstMonday = Schedule.monday(schedule.firstDate!!.atStartOfDay())
    val lastMonday = Schedule.monday(schedule.lastDate!!.atStartOfDay())
    val start = minOf(firstMonday, openMonday)
    val end = maxOf(lastMonday, openMonday)
    val count = ChronoUnit.WEEKS.between(start, end).toInt() + 1
    val openPage = ChronoUnit.WEEKS.between(start, openMonday).toInt()

    key(start, count) {
        val pager = rememberPagerState(initialPage = openPage) { count }
        val scope = rememberCoroutineScope()
        Column(modifier) {
            val monday = start.plusWeeks(pager.currentPage.toLong())
            WeekHeader(
                monday = monday,
                isOpenWeek = pager.currentPage == openPage,
                onPrev = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                onNext = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                onToday = { scope.launch { pager.animateScrollToPage(openPage) } },
                canPrev = pager.currentPage > 0,
                canNext = pager.currentPage < count - 1,
            )
            HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                WeekPage(plan, start.plusWeeks(page.toLong()), now, openMonday, firstMonday, lastMonday, onOpen)
            }
        }
    }
}

@Composable
private fun WeekHeader(
    monday: LocalDate,
    isOpenWeek: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    canPrev: Boolean,
    canNext: Boolean,
) {
    val locale = appLocale()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, enabled = canPrev) { Icon(ChevronLeftIcon, stringResource(R.string.previous_week)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.week_number, weekNumber(monday)), style = MaterialTheme.typography.titleMedium)
            Text(
                "${dayAndDate(monday, locale)} – ${dayAndDate(monday.plusDays(4), locale)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isOpenWeek) TextButton(onClick = onToday) { Text(stringResource(R.string.this_week)) }
        IconButton(onClick = onNext, enabled = canNext) { Icon(ChevronRightIcon, stringResource(R.string.next_week)) }
    }
}

@Composable
private fun WeekPage(
    plan: PlanUi,
    monday: LocalDate,
    now: LocalDateTime,
    openMonday: LocalDate,
    firstMonday: LocalDate,
    lastMonday: LocalDate,
    onOpen: (Timed) -> Unit,
) {
    val week = remember(plan, monday) { plan.schedule.week(monday) }
    val hours = plan.schedule.hours
    val today = now.toLocalDate()
    val locale = appLocale()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = Gutter, bottom = 4.dp)) {
            week.days.forEach { DayHeader(it.date, it.date == today, Modifier.weight(1f)) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val n = hours.last - hours.first
            val hourH = maxOf(MinHour, maxHeight / n)
            val lines = colors.outlineVariant
            Row(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .height(hourH * n)
                    .drawBehind {
                        val g = Gutter.toPx()
                        for (i in 0..n) {
                            val y = (hourH * i).toPx().coerceAtMost(size.height - 1f)
                            drawLine(lines, Offset(g, y), Offset(size.width, y), 1f)
                        }
                        val w = (size.width - g) / 5f
                        for (d in 1 until 5) drawLine(lines, Offset(g + w * d, 0f), Offset(g + w * d, size.height), 1f)
                    },
            ) {
                Box(Modifier.width(Gutter).fillMaxHeight()) {
                    for (h in hours.first until hours.last) {
                        Text(
                            "$h",
                            Modifier.offset(y = hourH * (h - hours.first) + 2.dp).fillMaxWidth().padding(end = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                week.days.forEach { day ->
                    DayColumn(
                        plan, day, hours.first, hourH,
                        isToday = day.date == today, now = now,
                        weekFree = week.free, strong = monday == openMonday, onOpen = onOpen,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            val empty = week.days.all { it.occurrences.isEmpty() && it.cancellations.isEmpty() }
            val message = when {
                week.free -> stringResource(R.string.lecture_free)
                empty && monday.isBefore(firstMonday) ->
                    stringResource(R.string.semester_starts, longDate(plan.schedule.firstDate!!, locale))
                empty && monday.isAfter(lastMonday) ->
                    stringResource(R.string.plan_ended, longDate(plan.schedule.lastDate!!, locale))
                else -> null
            }
            if (message != null) {
                Surface(
                    Modifier.align(Alignment.Center).padding(start = Gutter + 16.dp, end = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                ) {
                    Text(message, Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, isToday: Boolean, modifier: Modifier) {
    val locale = appLocale()
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(dayShort(date, locale), style = MaterialTheme.typography.labelMedium, color = if (isToday) colors.primary else colors.onSurfaceVariant)
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (isToday) colors.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.dayOfMonth}",
                style = MaterialTheme.typography.labelLarge,
                color = if (isToday) colors.onPrimary else colors.onSurface,
            )
        }
    }
}

@Composable
private fun DayColumn(
    plan: PlanUi,
    day: DayPlan,
    firstHour: Int,
    hourH: Dp,
    isToday: Boolean,
    now: LocalDateTime,
    weekFree: Boolean,
    strong: Boolean,
    onOpen: (Timed) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.background(if (isToday) colors.primary.copy(alpha = 0.07f) else Color.Transparent)) {
        fun y(t: LocalDateTime) = hourH * (Duration.between(t.toLocalDate().atTime(firstHour, 0), t).toMinutes() / 60f)
        when {
            weekFree -> Unit
            day.free -> Box(
                Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.lecture_free),
                    Modifier.verticalText(),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            else -> {
                val placed = remember(day) { DayLayout.place(day.items()) }
                val width = maxWidth
                for (p in placed) {
                    EventBlock(
                        plan, p.item, strong, onClick = { onOpen(p.item) },
                        modifier = Modifier
                            .offset(x = width * p.column / p.columns, y = y(p.item.start))
                            .width(width / p.columns)
                            .height(y(p.item.end) - y(p.item.start))
                            .padding(1.5.dp),
                    )
                }
            }
        }
        if (isToday) {
            val ny = y(now)
            if (ny >= 0.dp && ny <= maxHeight) {
                val red = colors.error
                Box(Modifier.offset(y = ny - 1.dp).fillMaxWidth().height(2.dp).background(red))
                Box(Modifier.offset(x = (-1).dp, y = ny - 4.dp).size(8.dp).clip(CircleShape).background(red))
            }
        }
    }
}

/** One block in the grid: occurrence (highlighted if changed/extra) or faint cancellation. */
@Composable
private fun EventBlock(plan: PlanUi, item: Timed, strong: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val page = MaterialTheme.colorScheme.background
    val rgb = plan.colorOf(item)
    val c = remember(rgb, page) { SubjectColors.block(rgb, page) }
    val subject = plan.subjectOf[item.event.courseKey]
    val cancelled = item is Cancellation
    val status = (item as? Occurrence)?.status
    val highlight = status is Status.Changed || status == Status.Extra
    val hl = planColors().highlight
    val shape = RoundedCornerShape(6.dp)
    val cancelledText = stringResource(R.string.cancelled)
    Box(
        modifier
            .alpha(if (cancelled) 0.5f else 1f)
            .clip(shape)
            .background(c.container)
            .then(if (highlight) Modifier.border(if (strong) 2.5.dp else 1.5.dp, hl, shape) else Modifier)
            .clickable(onClick = onClick)
            .then(if (cancelled) Modifier.semantics { contentDescription = cancelledText } else Modifier),
    ) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(c.stripe))
        Column(Modifier.fillMaxSize().padding(start = 6.dp, end = 3.dp, top = 3.dp, bottom = 3.dp)) {
            Text(
                blockName(subject, item),
                Modifier.weight(1f, fill = false),
                color = c.content,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )
            Text(
                if (cancelled) cancelledText else item.event.location?.room.orEmpty(),
                color = c.content,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                TypeLabel(item.event.type.short, border = c.stripe, text = c.content)
                if (highlight && strong) {
                    Text(
                        if (status == Status.Extra) "+" else "!",
                        Modifier.clip(RoundedCornerShape(4.dp)).background(hl).padding(horizontal = 4.dp),
                        color = planColors().onHighlight,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** The event type (V, Ü, P, S, ...) as a small outlined label. */
@Composable
fun TypeLabel(short: String, border: Color, text: Color, modifier: Modifier = Modifier) {
    if (short.isEmpty()) return
    Text(
        short,
        modifier.border(1.dp, border, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
        color = text,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** Text turned 90° counter-clockwise, measured with width and height swapped. */
private fun Modifier.verticalText() = layout { measurable, constraints ->
    val p = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
    layout(p.height, p.width) {
        p.placeWithLayer((p.height - p.width) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}
