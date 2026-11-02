package de.luwify.stundenplanmd.ui

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * How long a snackbar stays: one with an action (undo) long enough to react (Material's long = 10 s, as in Mensa MD),
 * a plain info one the short default; [sticky] ones (errors with a close button) until closed.
 */
internal fun snackbarDuration(hasAction: Boolean, sticky: Boolean = false): SnackbarDuration = when {
    sticky -> SnackbarDuration.Indefinite
    hasAction -> SnackbarDuration.Long
    else -> SnackbarDuration.Short
}

/** Milliseconds of a [SnackbarDuration] (Material's values), null = until closed. */
internal fun SnackbarDuration.millis(): Long? = when (this) {
    SnackbarDuration.Short -> 4_000L
    SnackbarDuration.Long -> 10_000L
    SnackbarDuration.Indefinite -> null
}

/**
 * What [notify] hands the host. The host itself always gets [SnackbarDuration.Indefinite]: the snackbar runs its own
 * clock ([SnackbarTimer]) for [timeout], which pauses while it is touched or expanded.
 */
internal class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean,
    val timeout: SnackbarDuration,
) : SnackbarVisuals {
    override val duration: SnackbarDuration get() = SnackbarDuration.Indefinite
}

/**
 * Every snackbar of the app goes through here. [onAction] runs only when the action is tapped: a time-out, a swipe
 * or the close button leave the change as it is.
 */
suspend fun SnackbarHostState.notify(message: String, actionLabel: String? = null, sticky: Boolean = false, onAction: () -> Unit = {}) {
    val r = showSnackbar(AppSnackbarVisuals(message, actionLabel, sticky, snackbarDuration(actionLabel != null, sticky)))
    if (r == SnackbarResult.ActionPerformed) onAction()
}

/** Where a released swipe goes: back to the middle, or out to the left (-1) or right (+1). */
internal object SnackbarSwipe {
    /** Fraction of the snackbar's width a slow drag has to cover. */
    const val DISTANCE = 0.4f
    /** Fling speed (dp/s) that dismisses whatever the distance, in the direction of the drag. */
    const val FLING_DP = 1000f

    /** -1, 0 (snap back) or +1 for a release at [offset] px with [velocity] px/s on a snackbar [width] px wide. */
    fun settle(offset: Float, velocity: Float, width: Int, density: Float): Int {
        val fast = abs(velocity) >= FLING_DP * density
        // A fling back toward the middle snaps back, even from far out.
        if (fast && (offset == 0f || sign(velocity) == sign(offset))) return sign(velocity).toInt()
        if (fast) return 0
        return if (width > 0 && abs(offset) >= DISTANCE * width) sign(offset).toInt() else 0
    }

    /** Fades out on the way: gone at 3/4 of the width. */
    fun alpha(offset: Float, width: Int): Float =
        if (width <= 0) 1f else (1f - abs(offset) / (width * 0.75f)).coerceIn(0f, 1f)
}

/**
 * The snackbar's clock: [total] ms, counted only while not paused. Paused while a finger is on it, and while it is
 * expanded until nobody touched it for [EXPANDED_IDLE] ms (then it runs out even if left open). After a pause at
 * least [AFTER_PAUSE] ms are left, so it never vanishes the moment the reader lets go.
 */
internal class SnackbarTimer(total: Long) {
    var remaining = total; private set
    private var wasPaused = false

    /** [elapsed] ms passed; true when the time is up. */
    fun advance(elapsed: Long, paused: Boolean): Boolean {
        if (wasPaused && !paused) remaining = maxOf(remaining, AFTER_PAUSE)
        wasPaused = paused
        if (!paused) remaining -= elapsed
        return remaining <= 0
    }

    companion object {
        const val EXPANDED_IDLE = 5_000L
        const val AFTER_PAUSE = 4_000L

        fun paused(touching: Boolean, expanded: Boolean, sinceTouch: Long) = touching || (expanded && sinceTouch < EXPANDED_IDLE)
    }
}

/**
 * Two lines, expandable: [truncated] when the collapsed text didn't fit (then a chevron says it can be opened),
 * [expanded] after a tap. The expanded layout fits by definition, so it never clears [truncated].
 */
internal data class SnackbarFold(val truncated: Boolean = false, val expanded: Boolean = false) {
    fun laidOut(overflow: Boolean) = if (expanded) this else copy(truncated = overflow)
    fun toggled() = if (truncated) copy(expanded = !expanded) else this

    companion object {
        const val COLLAPSED_LINES = 2
    }
}

/**
 * The app's snackbar host: Material 3 snackbars (inverse surface, inverse primary action), at most [MaxWidth] wide and
 * centered, swipeable to the left or right (= dismissed, like a time-out: the action doesn't run). The host only
 * takes touches on the snackbar itself, so drags beside it still reach the pagers below.
 */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { SwipeableSnackbar(it) }
}

private val MaxWidth = 600.dp

@Composable
private fun SwipeableSnackbar(data: SnackbarData) {
    val animate = animationsEnabled()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    // Follows the finger directly; a release animates it back or out (the settle stops when a new drag starts).
    var offset by remember(data) { mutableFloatStateOf(0f) }
    var settle by remember { mutableStateOf<Job?>(null) }
    var width by remember { mutableIntStateOf(0) }
    var fold by remember(data) { mutableStateOf(SnackbarFold()) }
    var touching by remember(data) { mutableStateOf(false) }
    var lastTouch by remember(data) { mutableLongStateOf(0L) }
    val visuals = data.visuals
    val timeout = (visuals as? AppSnackbarVisuals)?.timeout ?: visuals.duration

    // The clock (Material's own is off: the host gets "indefinite"), with the accessibility service's longer times.
    val accessibility = LocalAccessibilityManager.current
    val currentFold by rememberUpdatedState(fold)
    LaunchedEffect(data) {
        val base = timeout.millis() ?: return@LaunchedEffect
        val total = accessibility?.calculateRecommendedTimeoutMillis(base, containsIcons = true, containsText = true, containsControls = visuals.actionLabel != null) ?: base
        val timer = SnackbarTimer(total)
        var last = SystemClock.uptimeMillis()
        while (true) {
            delay(100)
            val now = SystemClock.uptimeMillis()
            val paused = SnackbarTimer.paused(touching, currentFold.expanded, now - lastTouch)
            if (timer.advance(now - last, paused)) { data.dismiss(); break }
            last = now
        }
    }

    Box(
        Modifier
            .padding(12.dp)
            .widthIn(max = MaxWidth)
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .graphicsLayer {
                translationX = offset
                alpha = SnackbarSwipe.alpha(offset, width)
            }
            // Any finger on it pauses the clock (seen before the children take the touch).
            .pointerInput(data) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    waitForUpOrCancellation(PointerEventPass.Initial)
                    touching = false
                    lastTouch = SystemClock.uptimeMillis()
                }
            }
            .draggable(
                rememberDraggableState { delta -> offset += delta },
                Orientation.Horizontal,
                onDragStarted = { settle?.cancel() },
                onDragStopped = { velocity ->
                    val direction = SnackbarSwipe.settle(offset, velocity, width, density)
                    val target = direction * width.toFloat()
                    settle = scope.launch {
                        if (animate) {
                            animate(offset, target, velocity, if (direction == 0) spring() else tween(Motion.Short)) { v, _ -> offset = v }
                        } else {
                            offset = target
                        }
                        if (direction != 0) data.dismiss()
                    }
                },
            ),
    ) {
        SnackbarContent(data, fold, animate, onFold = { fold = it })
    }
}

/**
 * Material 3 snackbar look (inverse surface, theme shape, elevation), own layout: the text grows upward when expanded
 * while the action and the close button stay at the bottom, where they were.
 */
@Composable
private fun SnackbarContent(data: SnackbarData, fold: SnackbarFold, animate: Boolean, onFold: (SnackbarFold) -> Unit) {
    val visuals = data.visuals
    val sticky = (visuals as? AppSnackbarVisuals)?.timeout == SnackbarDuration.Indefinite
    Surface(
        shape = SnackbarDefaults.shape,
        color = SnackbarDefaults.color,
        contentColor = SnackbarDefaults.contentColor,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = if (visuals.actionLabel != null || visuals.withDismissAction) 8.dp else 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // A sticky error is shown in full; the rest two lines, a tap opens the whole text.
            val collapsible = !sticky
            val expandLabel = stringResource(if (fold.expanded) R.string.snackbar_collapse else R.string.snackbar_expand)
            Row(
                Modifier
                    .weight(1f)
                    .then(if (collapsible && fold.truncated) Modifier.clickable(onClickLabel = expandLabel) { onFold(fold.toggled()) } else Modifier)
                    .padding(vertical = 14.dp)
                    .animateContentSize(if (animate) tween(Motion.Medium, easing = Motion.Standard) else snap()),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (!collapsible || fold.expanded) Int.MAX_VALUE else SnackbarFold.COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (collapsible) onFold(fold.laidOut(it.hasVisualOverflow)) },
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (collapsible && fold.truncated) {
                    Icon(
                        ChevronDownIcon,
                        contentDescription = null,
                        tint = SnackbarDefaults.contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(if (fold.expanded) 180f else 0f),
                    )
                }
            }
            visuals.actionLabel?.let { label ->
                TextButton(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                ) { Text(label) }
            }
            if (visuals.withDismissAction) {
                IconButton(onClick = { data.dismiss() }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close), tint = SnackbarDefaults.dismissActionContentColor)
                }
            }
        }
    }
}
