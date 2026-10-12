package de.luwify.stundenplanmd.ui

import android.provider.Settings
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
private const val FadeMillis = 180

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
 * ends of the list allow.
 */
@Composable
fun ChipStrip(
    labels: List<ChipLabel>,
    selected: Int,
    chipWidth: Dp,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animate = animationsEnabled()
    val chipPx = with(LocalDensity.current) { chipWidth.roundToPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 1).coerceAtLeast(0))
    LaunchedEffect(selected) {
        val info = state.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        if (viewport <= 0) return@LaunchedEffect
        val offset = -((viewport - chipPx) / 2)
        if (animate) state.animateScrollToItem(selected, offset) else state.scrollToItem(selected, offset)
    }
    LazyRow(
        modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(horizontal = StripPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
    ) {
        itemsIndexed(labels) { i, label ->
            StripChip(label, selected = i == selected, animate = animate, onClick = { onSelect(i) }, Modifier.width(chipWidth))
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
