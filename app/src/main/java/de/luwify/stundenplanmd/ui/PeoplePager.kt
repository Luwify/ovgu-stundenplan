package de.luwify.stundenplanmd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * Sits between a page's content and the vertical people [pager]: the content scrolls first, and a drag only moves the
 * pager when it started with the content already at its top or bottom edge. A drag or fling that scrolled the
 * content never spills over into the next person. The pager does not take leftover scroll from a child that started
 * the drag, so this connection moves it and settles it on a page itself.
 */
class EdgeOnlyConnection(private val pager: PagerState, private val animate: Boolean) : NestedScrollConnection {
    private var contentMoved = false

    private fun movePager(dy: Float): Offset = Offset(0f, -pager.dispatchRawDelta(-dy))

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        // Halfway between two people: the pager follows the finger first, the content stays put.
        if (source == NestedScrollSource.UserInput && pager.currentPageOffsetFraction != 0f) movePager(available.y) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y != 0f) contentMoved = true
        return when {
            contentMoved -> available.copy(x = 0f)
            source == NestedScrollSource.UserInput && available.y != 0f -> movePager(available.y)
            else -> Offset.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val swallow = contentMoved
        contentMoved = false
        val fraction = pager.currentPageOffsetFraction
        if (!swallow && fraction != 0f) {
            val page = pager.currentPage
            val target = when {
                available.y < -FLING && fraction > 0f -> page + 1
                available.y > FLING && fraction < 0f -> page - 1
                else -> page
            }.coerceIn(0, pager.pageCount - 1)
            if (animate) pager.animateScrollToPage(target) else pager.scrollToPage(target)
        }
        return available.copy(x = 0f)
    }

    private companion object {
        /** px/s: a flick this fast goes on to the next person even before halfway. */
        const val FLING = 800f
    }
}

/**
 * Keeps a week/day pager on the [shared] page chosen in another person's plan (when it changes there), and while
 * [active] reports the pages the user settles on to [onSettled] (not the page it opened on).
 */
@Composable
fun FollowSharedPage(pager: PagerState, shared: Int?, active: Boolean, onSettled: (Int) -> Unit) {
    val report by rememberUpdatedState(onSettled)
    LaunchedEffect(shared) {
        if (shared != null && shared != pager.currentPage && !pager.isScrollInProgress) pager.scrollToPage(shared)
    }
    if (active) {
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { report(it) }
        }
    }
}

/** Whose plan is coming up, shown briefly while the vertical pager moves between people. */
@Composable
fun PersonIndicator(pager: PagerState, name: String, modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    // Only real movement between people counts (a fling the content swallowed leaves the offset at 0).
    val moving by remember(pager) { derivedStateOf { pager.isScrollInProgress && pager.currentPageOffsetFraction != 0f } }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (moving) visible = true else { delay(700); visible = false }
    }
    AnimatedVisibility(
        visible,
        modifier,
        enter = if (animate) fadeIn(tween(Motion.Short)) else EnterTransition.None,
        exit = if (animate) fadeOut(tween(Motion.Medium)) else ExitTransition.None,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp,
        ) {
            Text(
                name,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * How many pages a week/day pager keeps composed beyond the visible one: none while the screen appears (so the first
 * frame composes one page, not three), then [target] once the opening animation is over ([active]: this person is shown).
 */
@Composable
fun rememberBeyondPages(active: Boolean, target: Int = 1): Int {
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        // Out of sight: later, so that every person's pager does not add its neighbors in the same frame.
        delay(if (active) Motion.Long + 100L else 1500L)
        count = target
    }
    return count
}
