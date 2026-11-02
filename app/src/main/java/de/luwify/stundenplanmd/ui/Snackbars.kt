package de.luwify.stundenplanmd.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import kotlinx.coroutines.Job
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

/**
 * Every snackbar of the app goes through here. [onAction] runs only when the action is tapped: a time-out, a swipe
 * or the close button leave the change as it is.
 */
suspend fun SnackbarHostState.notify(message: String, actionLabel: String? = null, sticky: Boolean = false, onAction: () -> Unit = {}) {
    val r = showSnackbar(message, actionLabel = actionLabel, withDismissAction = sticky, duration = snackbarDuration(actionLabel != null, sticky))
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
    val visuals = data.visuals
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
        val actionColor = SnackbarDefaults.actionColor
        Snackbar(
            action = visuals.actionLabel?.let { label ->
                {
                    TextButton(onClick = { data.performAction() }, colors = ButtonDefaults.textButtonColors(contentColor = actionColor)) {
                        Text(label)
                    }
                }
            },
            dismissAction = if (visuals.withDismissAction) {
                {
                    IconButton(onClick = { data.dismiss() }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close), tint = SnackbarDefaults.dismissActionContentColor)
                    }
                }
            } else null,
            shape = SnackbarDefaults.shape,
            containerColor = SnackbarDefaults.color,
            contentColor = SnackbarDefaults.contentColor,
        ) {
            // Two lines at most, so the action keeps its place; a sticky error message is shown in full.
            Text(
                visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (visuals.duration == SnackbarDuration.Indefinite) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
