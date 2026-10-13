package de.luwify.stundenplanmd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import de.luwify.stundenplanmd.R

/**
 * The one set of durations and easings for every transition (Material motion). Screens and overlays use a shared axis
 * (fade + 30 dp slide), dialogs fade and scale, chips and small state changes use the short duration. With "remove
 * animations" (see [animationsEnabled]) everything is skipped; the window animations of dialogs follow the system's
 * window animation scale on their own.
 */
object Motion {
    const val Short = 150
    const val Medium = 250
    const val Long = 300
    const val FadeInDelay = 90

    /** Material 3 "emphasized decelerate" / "emphasized accelerate" (simplified single curves). */
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    private val Slide = 30.dp

    /** Shared axis X, forward: slides in from the end while fading in (the fade starts after [FadeInDelay]). */
    @Composable
    fun screenEnter(): EnterTransition {
        if (!animationsEnabled()) return EnterTransition.None
        val px = with(LocalDensity.current) { Slide.roundToPx() }
        return slideInHorizontally(tween(Long, easing = Decelerate)) { px } +
            fadeIn(tween(Long - FadeInDelay, FadeInDelay, LinearEasing))
    }

    /** Shared axis X, back: slides out to the end while fading out. */
    @Composable
    fun screenExit(): ExitTransition {
        if (!animationsEnabled()) return ExitTransition.None
        val px = with(LocalDensity.current) { Slide.roundToPx() }
        return slideOutHorizontally(tween(Medium - 50, easing = Accelerate)) { px } +
            fadeOut(tween(Short, easing = LinearEasing))
    }
}

/** A full-screen page over the main screen (settings, QR, versions, ...): shared axis instead of a hard cut. */
@Composable
fun Overlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = Motion.screenEnter(), exit = Motion.screenExit()) { content() }
}

/**
 * [Overlay] for a page that exists while [value] is not null. The last value is kept while the page leaves, so the
 * exit animation shows the page as it was instead of an empty one.
 */
@Composable
fun <T : Any> Overlay(value: T?, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    Overlay(visible = value != null) { last?.let { content(it) } }
}

/** Gives the enclosing Compose dialog's window the animations of [style] (R.style.Motion_Dialog or Motion_Screen). */
@Composable
fun DialogWindowAnimation(style: Int) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setWindowAnimations(style) }
}

/** `AlertDialog` of Material 3 with the app's dialog animation (fade + scale in, fade out); same parameters. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    properties: DialogProperties = DialogProperties(),
) {
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            DialogWindowAnimation(R.style.Motion_Dialog)
            confirmButton()
        },
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        properties = properties,
    )
}
