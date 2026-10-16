package de.luwify.stundenplanmd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * Sits between a page's content and the vertical people [pager] and is the only thing that moves it by touch (the pager
 * itself has user scrolling off; apply with [edgeSwitching]): the content scrolls first, and a drag only moves the pager
 * when it started with the content already at its top or bottom edge. A drag or fling that scrolled the content never
 * spills over into the next person. After a drag that moved the pager, this connection settles it on a page.
 *
 * With the pager's own drag on as well, a touch while it was still settling went to the pager at once (cutting off the
 * content's drag), and the two snapped it to different pages: swipes right after a fling or a switch did nothing.
 *
 * One connection serves every page (a quick second swipe lands on the next person's page and must stop the settle the
 * first one started), and the pager never stays between two people: every gesture end settles it, and [keepSettled]
 * catches whatever slips through (it once stuck halfway after a fast double swipe).
 */
class EdgeOnlyConnection(private val pager: PagerState, private val animate: Boolean) : NestedScrollConnection {
    private var contentMoved = false
    private var settling: Job? = null
    private var touching by mutableStateOf(false)

    /** Vertical drags outside any scrolling content (headers, strips): nothing to scroll, so straight to the pager. */
    internal val outside = DraggableState { dy -> onPostScroll(Offset.Zero, Offset(0f, dy), NestedScrollSource.UserInput) }

    internal suspend fun outsideStopped(velocity: Float) {
        onPostFling(Velocity.Zero, Velocity(0f, velocity))
    }

    /** A finger went down: a new gesture. A settle still running stops; the last gesture's state goes (a fling cut short by a touch never reports its end). */
    internal fun onGestureStart() {
        settling?.cancel()
        settling = null
        contentMoved = false
        touching = true
    }

    /** The last finger went up. */
    internal fun onGestureEnd() {
        touching = false
    }

    /**
     * Runs while the pager is shown: when no finger is down, nothing moves the pager and it still sits between two
     * people for a moment, it goes to the nearer one.
     */
    internal suspend fun keepSettled() = coroutineScope {
        snapshotFlow { !touching && !pager.isScrollInProgress && pager.currentPageOffsetFraction != 0f }
            .collectLatest { loose ->
                if (loose) {
                    delay(LOOSE_MS)
                    if (settling?.isActive != true) this@coroutineScope.settle(0f)
                }
            }
    }

    /** Onto a page: the nearer one, or the next one for a flick faster than [FLING] px/s in its direction. */
    private fun CoroutineScope.settle(velocity: Float) {
        val fraction = pager.currentPageOffsetFraction
        if (fraction == 0f) return
        val page = pager.currentPage
        val target = when {
            velocity < -FLING && fraction > 0f -> page + 1
            velocity > FLING && fraction < 0f -> page - 1
            else -> page
        }.coerceIn(0, pager.pageCount - 1)
        settling?.cancel()
        settling = launch { if (animate) pager.animateScrollToPage(target) else pager.scrollToPage(target) }
    }

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
        // A fling the content took never carries on to the next person, but the pager still goes onto a page.
        val velocity = if (contentMoved) 0f else available.y
        contentMoved = false
        coroutineScope { settle(velocity) }
        return available.copy(x = 0f)
    }

    private companion object {
        /** px/s: a flick this fast goes on to the next person even before halfway. */
        const val FLING = 800f

        /** ms the pager may sit between two people with nothing moving it before [keepSettled] steps in. */
        const val LOOSE_MS = 300L
    }
}

/** A people pager page: vertical drags in it go through [edge] (one for all pages, see there), also where nothing in it scrolls. */
fun Modifier.edgeSwitching(edge: EdgeOnlyConnection): Modifier = this
    .pointerInput(edge) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            edge.onGestureStart()
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                edge.onGestureEnd()
            }
        }
    }
    .nestedScroll(edge)
    // Not a scrollable: that would also be a nested scroll parent of the content and report its flings a second time.
    .draggable(edge.outside, Orientation.Vertical, onDragStopped = { edge.outsideStopped(it) })

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
