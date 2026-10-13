package de.luwify.stundenplanmd.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.res.painterResource
import de.luwify.stundenplanmd.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.ui.theme.planColors

private val ChipHeight = 56.dp
private val ChipGap = 8.dp
private val StripPadding = 16.dp
private const val FadeMillis = Motion.Short

/** False when the system's "remove animations" (animator duration scale 0) is on. */
@Composable
fun animationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}

/** What a chip shows; [muted] chips (no events) are recessed. */
class ChipLabel(val top: String, val bottom: String, val muted: Boolean, val current: Boolean)

/**
 * Horizontally scrolling row of chips: the [selected] one is filled with the accent, the [ChipLabel.current] one (this
 * week / today) outlined. Chips at the edges are cut off on purpose; the selected chip is kept centered where the
 * ends of the list allow. While the current chip is scrolled out of view (less than half of it shows), a small round
 * arrow at that edge points toward it; tapping it selects the current chip.
 */
@Composable
fun ChipStrip(
    labels: List<ChipLabel>,
    selected: Int,
    chipWidth: Dp,
    /** Description of the arrow back to the current chip ("Back to this week"). */
    backLabel: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animate = animationsEnabled()
    val chipPx = with(LocalDensity.current) { chipWidth.roundToPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 1).coerceAtLeast(0))
    suspend fun center(index: Int) {
        val info = state.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        if (viewport <= 0) return
        val offset = -((viewport - chipPx) / 2)
        if (animate) state.animateScrollToItem(index, offset) else state.scrollToItem(index, offset)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selected) { center(selected) }
    val current = labels.indexOfFirst { it.current }
    // -1: the current chip is off to the left, 1: to the right, 0: visible (or there is none).
    val away by remember(state, current) {
        derivedStateOf {
            if (current < 0) return@derivedStateOf 0
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == current }
            when {
                item != null -> {
                    val shown = minOf(item.offset + item.size, info.viewportEndOffset) - maxOf(item.offset, info.viewportStartOffset)
                    if (shown * 2 >= item.size) 0 else if (item.offset < info.viewportStartOffset) -1 else 1
                }
                info.visibleItemsInfo.isEmpty() -> 0
                current < info.visibleItemsInfo.first().index -> -1
                else -> 1
            }
        }
    }
    Box(modifier.fillMaxWidth()) {
        LazyRow(
            Modifier.fillMaxWidth(),
            state = state,
            contentPadding = PaddingValues(horizontal = StripPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ChipGap),
        ) {
            itemsIndexed(labels) { i, label ->
                StripChip(label, selected = i == selected, animate = animate, onClick = { onSelect(i) }, Modifier.width(chipWidth))
            }
        }
        for (side in listOf(-1, 1)) {
            AnimatedVisibility(
                visible = away == side,
                modifier = Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 6.dp),
                enter = if (animate) fadeIn(tween(FadeMillis)) + scaleIn(tween(FadeMillis), initialScale = 0.6f) else EnterTransition.None,
                exit = if (animate) fadeOut(tween(FadeMillis)) + scaleOut(tween(FadeMillis), targetScale = 0.6f) else ExitTransition.None,
            ) {
                // The pager may already be there with only the strip scrolled away: bring the chip back as well.
                BackToCurrentButton(side, backLabel, onClick = { onSelect(current); scope.launch { center(current) } })
            }
        }
    }
}

/** Small round tonal button with a chevron pointing toward the current chip ([side] -1 = left). */
@Composable
private fun BackToCurrentButton(side: Int, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (side < 0) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right),
                contentDescription = label,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun StripChip(label: ChipLabel, selected: Boolean, animate: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val spec: AnimationSpec<Color> = if (animate) tween(FadeMillis) else snap()
    val container by animateColorAsState(
        when {
            selected -> scheme.primary
            label.muted -> scheme.background
            else -> planColors().cardContainer
        },
        spec, label = "chip fill",
    )
    val content by animateColorAsState(
        when {
            selected -> scheme.onPrimary
            label.muted -> scheme.onSurfaceVariant
            else -> scheme.onSurface
        },
        spec, label = "chip text",
    )
    val outline by animateColorAsState(if (label.current && !selected) scheme.primary else Color.Transparent, spec, label = "chip outline")
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .height(ChipHeight)
            .clip(shape)
            .background(container)
            .border(1.5.dp, outline, shape)
            .selectable(selected, role = Role.Tab, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label.top, color = content, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
        Text(label.bottom, color = content, style = MaterialTheme.typography.bodyMedium, maxLines = 1, textAlign = TextAlign.Center)
    }
}

val WeekChipWidth = 112.dp
val DayChipWidth = 72.dp
