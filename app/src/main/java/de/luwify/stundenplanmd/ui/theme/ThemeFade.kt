package de.luwify.stundenplanmd.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import de.luwify.stundenplanmd.ui.Motion

/** The scheme [t] of the way from [a] to [b] (the colors the app uses; Material's fixed roles are taken from [b]). */
internal fun lerp(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme = b.copy(
    primary = lerp(a.primary, b.primary, t), onPrimary = lerp(a.onPrimary, b.onPrimary, t),
    primaryContainer = lerp(a.primaryContainer, b.primaryContainer, t),
    onPrimaryContainer = lerp(a.onPrimaryContainer, b.onPrimaryContainer, t),
    inversePrimary = lerp(a.inversePrimary, b.inversePrimary, t),
    secondary = lerp(a.secondary, b.secondary, t), onSecondary = lerp(a.onSecondary, b.onSecondary, t),
    secondaryContainer = lerp(a.secondaryContainer, b.secondaryContainer, t),
    onSecondaryContainer = lerp(a.onSecondaryContainer, b.onSecondaryContainer, t),
    tertiary = lerp(a.tertiary, b.tertiary, t), onTertiary = lerp(a.onTertiary, b.onTertiary, t),
    tertiaryContainer = lerp(a.tertiaryContainer, b.tertiaryContainer, t),
    onTertiaryContainer = lerp(a.onTertiaryContainer, b.onTertiaryContainer, t),
    background = lerp(a.background, b.background, t), onBackground = lerp(a.onBackground, b.onBackground, t),
    surface = lerp(a.surface, b.surface, t), onSurface = lerp(a.onSurface, b.onSurface, t),
    surfaceVariant = lerp(a.surfaceVariant, b.surfaceVariant, t),
    onSurfaceVariant = lerp(a.onSurfaceVariant, b.onSurfaceVariant, t),
    surfaceTint = lerp(a.surfaceTint, b.surfaceTint, t),
    inverseSurface = lerp(a.inverseSurface, b.inverseSurface, t),
    inverseOnSurface = lerp(a.inverseOnSurface, b.inverseOnSurface, t),
    error = lerp(a.error, b.error, t), onError = lerp(a.onError, b.onError, t),
    errorContainer = lerp(a.errorContainer, b.errorContainer, t),
    onErrorContainer = lerp(a.onErrorContainer, b.onErrorContainer, t),
    outline = lerp(a.outline, b.outline, t), outlineVariant = lerp(a.outlineVariant, b.outlineVariant, t),
    scrim = lerp(a.scrim, b.scrim, t),
    surfaceBright = lerp(a.surfaceBright, b.surfaceBright, t), surfaceDim = lerp(a.surfaceDim, b.surfaceDim, t),
    surfaceContainer = lerp(a.surfaceContainer, b.surfaceContainer, t),
    surfaceContainerHigh = lerp(a.surfaceContainerHigh, b.surfaceContainerHigh, t),
    surfaceContainerHighest = lerp(a.surfaceContainerHighest, b.surfaceContainerHighest, t),
    surfaceContainerLow = lerp(a.surfaceContainerLow, b.surfaceContainerLow, t),
    surfaceContainerLowest = lerp(a.surfaceContainerLowest, b.surfaceContainerLowest, t),
)

internal fun lerp(a: PlanColors, b: PlanColors, t: Float) = PlanColors(
    cardContainer = lerp(a.cardContainer, b.cardContainer, t),
    cardBorder = lerp(a.cardBorder, b.cardBorder, t),
    cardBorderWidth = b.cardBorderWidth,
    highlight = lerp(a.highlight, b.highlight, t),
    onHighlight = lerp(a.onHighlight, b.onHighlight, t),
    highlightText = lerp(a.highlightText, b.highlightText, t),
    favorite = lerp(a.favorite, b.favorite, t),
    own = lerp(a.own, b.own, t),
)

/**
 * Fades the colors to a new theme instead of cutting: when [key] (the theme mode and its light/dark state) changes,
 * the shown scheme runs from the old colors to the new ones over [Motion.Long]. Other changes of [scheme] (the custom
 * theme's live editing) apply at once. [enabled] false = no animation ("remove animations").
 */
@Composable
internal fun fadedTheme(scheme: ColorScheme, plan: PlanColors, key: Any, enabled: Boolean): Pair<ColorScheme, PlanColors> {
    var shown by remember { mutableStateOf(scheme to plan) }
    var fading by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf(scheme to plan) }
    val latest by rememberUpdatedState(scheme to plan)
    var first by remember { mutableStateOf(true) }
    // Read while composing the new theme, before the SideEffect below moves it on: the colors we fade from.
    val from = remember(key) { previous }
    LaunchedEffect(key) {
        if (first || !enabled) {
            first = false
            return@LaunchedEffect
        }
        fading = true
        try {
            Animatable(0f).animateTo(1f, tween(Motion.Long, easing = Motion.Standard)) {
                val to = latest
                shown = lerp(from.first, to.first, value) to lerp(from.second, to.second, value)
            }
        } finally {
            fading = false
        }
    }
    if (!fading) SideEffect { previous = scheme to plan }
    return if (fading) shown else scheme to plan
}
