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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.core.schedule.DayProgress
import androidx.compose.ui.unit.sp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.schedule.Cancellation
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
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private val Gutter = 26.dp
private val BlockTitleStyle = TextStyle(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
private val MinHour = 40.dp

/** Space between the top of the first hour label and the top of the day bar. */
private val BarGap = 3.dp

/** Height of a digit relative to the font size (cap height of Roboto and most UI fonts). */
private const val DIGIT_HEIGHT = 0.71f

/** The now line across the week: primary at this alpha, faint on purpose (ThemeContrastTest keeps it visible). */
internal const val NOW_LINE_ALPHA = 0.4f

/**
 * Week strip above a Mon–Fri grid (Saturday/Sunday added in weeks with something on them), one pager page per week; opens on [week] (shared between people, null = this week,
 * next week on weekends), clamped to this plan's weeks. While [active], weeks the user moves to are reported to [onWeek].
 */
@Composable
fun WeekScreen(
    plan: PlanUi,
    now: LocalDateTime,
    week: LocalDate?,
    active: Boolean,
    onWeek: (LocalDate) -> Unit,
    onOpen: (Timed) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedule = plan.schedule
    val openMonday = Navigation.weekToOpen(schedule, now.toLocalDate())
    val firstMonday = Schedule.monday(schedule.firstDate!!.atStartOfDay())
    val lastMonday = Schedule.monday(schedule.lastDate!!.atStartOfDay())
    val weeks = remember(schedule, openMonday) { Navigation.weeks(schedule, openMonday) }
    val start = weeks.first()
    fun pageOf(monday: LocalDate) = weeks.indexOf(monday).takeIf { it >= 0 } ?: if (monday < weeks.first()) 0 else weeks.lastIndex
    val openPage = week?.let(::pageOf) ?: weeks.indexOf(openMonday)
    val thisMonday = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    key(start, weeks.size) {
        val pager = rememberPagerState(initialPage = openPage) { weeks.size }
        FollowSharedPage(pager, week?.let(::pageOf), active) { onWeek(weeks[it]) }
        val scope = rememberCoroutineScope()
        val animate = animationsEnabled()
        val locale = appLocale()
        val goTo: (Int) -> Unit = { page -> scope.launch { if (animate) pager.animateScrollToPage(page, animationSpec = tween(Motion.Long, easing = Motion.Standard)) else pager.scrollToPage(page) } }
        val labels = remember(plan, weeks, thisMonday, locale) {
            weeks.map { monday ->
                ChipLabel(
                    top = weekLabel(monday, locale),
                    bottom = "${monday.dayOfMonth}.${monday.monthValue}. - ${schedule.week(monday).lastDay.let { "${it.dayOfMonth}.${it.monthValue}." }}",
                    muted = schedule.week(monday).days.all { it.occurrences.isEmpty() },
                    current = monday == thisMonday,
                )
            }
        }
        Column(modifier) {
            ChipStrip(labels, pager.currentPage, WeekChipWidth, stringResource(R.string.back_to_this_week), onSelect = goTo)
            HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = rememberBeyondPages(active)) { page ->
                // A page composed before it is shown (the pager's neighbors) fills in column by column, not in one frame.
                val eager = remember { page == pager.currentPage }
                WeekPage(plan, weeks[page], now, openMonday, firstMonday, lastMonday, eager, onOpen)
            }
        }
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
    eager: Boolean,
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
            // Hour labels sit centered on their line. The day bar starts a small gap above the first label (its "lead"),
            // and the grid starts below that, so the first label lies fully inside the bar.
            val labelStyle = MaterialTheme.typography.labelSmall
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val labelMiddle = remember(labelStyle, density) {
                with(density) { (measurer.measure("7", labelStyle).firstBaseline - labelStyle.fontSize.toPx() * DIGIT_HEIGHT / 2).toDp() }
            }
            val lead = labelMiddle + BarGap
            val top = lead + 1.dp
            val hourH = maxOf(MinHour, (maxHeight - top) / n)
            val lines = colors.outlineVariant
            // Now, across the whole week behind the events: only in the current week, on a day shown, inside the hour range.
            val nowLine = colors.primary.copy(alpha = NOW_LINE_ALPHA)
            val todayShown = week.days.any { it.date == today }
            val columns = week.days.size
            val nowHours = (Duration.between(now.toLocalDate().atTime(hours.first, 0), now).toMinutes() / 60f)
                .takeIf { todayShown && it > 0f && it < n }
            Row(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = top)
                    .height(hourH * n)
                    .drawBehind {
                        val g = Gutter.toPx()
                        for (i in 0..n) {
                            val y = (hourH * i).toPx().coerceAtMost(size.height - 1f)
                            drawLine(lines, Offset(g, y), Offset(size.width, y), 1f)
                        }
                        val w = (size.width - g) / columns
                        for (d in 1 until columns) drawLine(lines, Offset(g + w * d, 0f), Offset(g + w * d, size.height), 1f)
                        if (nowHours != null) {
                            val y = (hourH * nowHours).toPx()
                            drawLine(nowLine, Offset(g, y), Offset(size.width, y), 1.5.dp.toPx())
                        }
                    },
            ) {
                Box(Modifier.width(Gutter).fillMaxHeight()) {
                    val fill = dayProgress(DayProgress.of(monday, now, hours.first, hours.last, todayShown))
                    val started = DayProgress.started(monday, now, hours.first, todayShown)
                    DayProgressFill(fill, started, lead, Modifier.fillMaxSize().padding(horizontal = 3.dp))
                    for (h in hours.first until hours.last) {
                        // A label counts as covered once the fill passes its middle, which is on the hour's line (the
                        // first one as soon as the day has started: the lead above its line is passed then).
                        val covered = started && (h == hours.first || fill * n > (h - hours.first))
                        val lineY = hourH * (h - hours.first)
                        Text(
                            "$h",
                            Modifier.fillMaxWidth().layout { measurable, constraints ->
                                val p = measurable.measure(constraints)
                                // The digits' middle (half the cap height above the baseline) on the line.
                                val baseline = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: (p.height / 2)
                                val middle = baseline - (labelStyle.fontSize.toPx() * DIGIT_HEIGHT / 2).roundToInt()
                                layout(p.width, p.height) { p.place(0, lineY.roundToPx() - middle) }
                            },
                            style = labelStyle,
                            color = if (covered) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                week.days.forEachIndexed { index, day ->
                    DayColumn(
                        plan, day, hours.first, hourH,
                        wait = if (eager) 0 else index + 1,
                        isToday = day.date == today,
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
    /** Frames to wait before the blocks are composed (spreads a page that is built ahead over several frames). */
    wait: Int,
    isToday: Boolean,
    weekFree: Boolean,
    strong: Boolean,
    onOpen: (Timed) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var ready by remember { mutableStateOf(wait == 0) }
    if (!ready) {
        LaunchedEffect(Unit) {
            repeat(wait) { withFrameNanos { } }
            ready = true
        }
    }
    BoxWithConstraints(modifier.background(if (isToday) colors.primary.copy(alpha = 0.07f) else Color.Transparent)) {
        fun y(t: LocalDateTime) = hourH * (Duration.between(t.toLocalDate().atTime(firstHour, 0), t).toMinutes() / 60f)
        when {
            weekFree || !ready -> Unit
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
                val placed = day.placed
                val width = maxWidth
                for (p in placed) {
                    EventBlock(
                        plan, p.item, strong, narrow = p.columns > 1, onClick = { onOpen(p.item) },
                        modifier = Modifier
                            .offset(x = width * p.column / p.columns, y = y(p.item.start))
                            .width(width / p.columns)
                            .height(y(p.item.end) - y(p.item.start))
                            .padding(1.5.dp),
                    )
                }
            }
        }
    }
}

/** The day progress, animated unless animations are off. */
@Composable
private fun dayProgress(progress: Float): Float {
    val fill by animateFloatAsState(progress, if (animationsEnabled()) tween(Motion.Long * 2, easing = Motion.Standard) else snap(), label = "day progress")
    return fill
}

/**
 * The hour column's background from the top of the day range down to now, in the tonal accent, with a slim accent
 * edge at now while the day is running. [fill] maps now onto the grid (0 = the first hour's line); the bar's track
 * starts [lead] above that line (room for the first label), and that part counts as passed once the day has [started].
 */
@Composable
private fun DayProgressFill(fill: Float, started: Boolean, lead: Dp, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.drawBehind {
            if (!started) return@drawBehind
            val top = -lead.toPx()
            val h = size.height * fill
            drawRoundRect(scheme.primaryContainer, Offset(0f, top), Size(size.width, h - top), CornerRadius(4.dp.toPx()))
            if (fill < 1f) {
                val edge = 2.dp.toPx()
                drawRect(scheme.primary, Offset(0f, maxOf(h - edge, top)), Size(size.width, minOf(edge, h - top)))
            }
        },
    )
}

/** One block in the grid: occurrence (highlighted if changed/extra) or faint cancellation. */
@Composable
private fun EventBlock(plan: PlanUi, item: Timed, strong: Boolean, narrow: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val page = MaterialTheme.colorScheme.background
    val rgb = plan.colorOf(item)
    val c = remember(rgb, page) { SubjectColors.block(rgb, page) }
    val subject = plan.subjectOf[item.event.courseKey]
    val cancelled = item is Cancellation
    val status = (item as? Occurrence)?.status
    val highlight = status is Status.Changed || status == Status.Extra
    val hl = planColors().highlight
    val own = planColors().own
    val ownChange = item.ownChange
    val personal = item.personal
    val shape = RoundedCornerShape(6.dp)
    val cancelledText = stringResource(R.string.cancelled)
    Box(
        modifier
            .alpha(if (cancelled) 0.5f else 1f)
            .clip(shape)
            .background(c.container)
            .then(if (highlight) Modifier.border(if (strong) 2.5.dp else 1.5.dp, hl, shape) else Modifier)
            // My own changes: dashed violet (an LSF change keeps its solid amber border, the pencil badge says the rest).
            .then(if (ownChange && !highlight) Modifier.ownBorder(own, 2.dp, 6.dp) else Modifier)
            .clickable(onClick = onClick)
            .then(if (cancelled) Modifier.semantics { contentDescription = cancelledText } else Modifier),
    ) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(c.stripe))
        // Attended with friends: a group badge at the top right; in narrow split lanes next to the type label instead,
        // so the title keeps the full width.
        val count = plan.togetherWith(item).size
        val shared = count > 0
        val favorite = plan.withFavorite(item)
        // Own changes and notes: badges below it, same column (the bottom row is too narrow for more than type and s.t.).
        val note = personal?.note != null
        val manual = item.event.isManual
        val badges = !narrow && (shared || note || ownChange || manual)
        if (badges) {
            Column(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (manual) ManualBadge(15.dp)
                if (shared) TogetherBadge(count, favorite, 15.dp)
                if (ownChange) MarkerBadge(note = false, 15.dp)
                if (note) MarkerBadge(note = true, 15.dp)
            }
        }
        Column(Modifier.fillMaxSize().padding(start = 6.dp, end = if (badges) 17.dp else 3.dp, top = 3.dp, bottom = 3.dp)) {
            Text(
                blockName(subject, item),
                Modifier.weight(1f, fill = false),
                color = c.content,
                fontSize = if (narrow) 10.sp else 11.sp,
                lineHeight = if (narrow) 12.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                // Long German words in narrow columns: hyphenate instead of breaking anywhere.
                style = BlockTitleStyle,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )
            Text(
                if (cancelled) cancelledText else item.room.orEmpty(),
                color = c.content,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                TypeLabel(item.event.type.short, border = c.stripe, text = c.content)
                if (shared && narrow) {
                    TogetherBadge(count, favorite, 13.dp)
                }
                if (item.st) StBadge()
                if (narrow && manual) ManualBadge(13.dp)
                if (narrow && ownChange) MarkerBadge(note = false, 13.dp)
                if (narrow && note) MarkerBadge(note = true, 13.dp)
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

/**
 * "Attended together" marker: person (1 friend), group (2) or groups (3 or more) on a small round badge in the surface color, so it stays visible on
 * every subject color; red (the favorite color, 3:1 on surface) when a favorite friend is there.
 */
@Composable
fun TogetherBadge(count: Int, favorite: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.size(size).clip(CircleShape).background(scheme.surface), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(
                when {
                    count <= 1 -> R.drawable.ic_person
                    count == 2 -> R.drawable.ic_group
                    else -> R.drawable.ic_groups
                },
            ),
            contentDescription = pluralStringResource(if (favorite) R.plurals.together_marker_favorite else R.plurals.together_marker, count, count),
            tint = if (favorite) planColors().favorite else scheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.74f),
        )
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
