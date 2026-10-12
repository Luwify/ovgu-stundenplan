package de.luwify.stundenplanmd.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import de.luwify.stundenplanmd.data.settings.CustomThemeSpec
import de.luwify.stundenplanmd.data.settings.ThemeMode

internal val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FAF),
    secondary = Color(0xFF535F70),
    tertiary = Color(0xFF6B5778),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    secondary = Color(0xFFBBC7DB),
    tertiary = Color(0xFFD7BDE4),
)

/**
 * System colors: neutral surfaces; the cards of the Today list stand apart from the page by their fill (see
 * [withCardContrast]); the hairline border is only a finishing edge.
 */
internal fun systemPlanColors(darkTheme: Boolean, scheme: ColorScheme) = planColors(
    page = scheme.background,
    card = if (darkTheme) scheme.surfaceContainer else scheme.surfaceContainerLowest,
    border = scheme.outlineVariant,
    surfaces = favoriteSurfaces(scheme),
)

/**
 * The System theme's page background, one step deeper than Material's default so the cards stand
 * out: light surfaceContainer, dark surfaceContainerLowest. The top bar uses the same color, so the page
 * stays one plane.
 */
internal fun ColorScheme.withCardContrast(dark: Boolean): ColorScheme {
    val page = if (dark) surfaceContainerLowest else surfaceContainer
    return copy(background = page, surface = page)
}

/** Fraction of the wallpaper's color kept in accent roles / in surface roles of the System theme. */
private const val ACCENT_KEPT = 0.7f
private const val SURFACE_KEPT = 0.5f

/** The dynamic scheme with its colors slightly pulled toward gray: same lightness, less saturation. */
fun ColorScheme.softened(): ColorScheme {
    fun a(c: Color) = c.desaturated(ACCENT_KEPT)
    fun n(c: Color) = c.desaturated(SURFACE_KEPT)
    return copy(
        primary = a(primary), primaryContainer = a(primaryContainer), inversePrimary = a(inversePrimary),
        secondary = a(secondary), secondaryContainer = a(secondaryContainer),
        tertiary = a(tertiary), tertiaryContainer = a(tertiaryContainer),
        background = n(background), onBackground = n(onBackground),
        surface = n(surface), onSurface = n(onSurface), surfaceVariant = n(surfaceVariant), onSurfaceVariant = n(onSurfaceVariant),
        surfaceBright = n(surfaceBright), surfaceDim = n(surfaceDim),
        surfaceContainerLowest = n(surfaceContainerLowest), surfaceContainerLow = n(surfaceContainerLow),
        surfaceContainer = n(surfaceContainer), surfaceContainerHigh = n(surfaceContainerHigh),
        surfaceContainerHighest = n(surfaceContainerHighest),
        outline = n(outline), outlineVariant = n(outlineVariant),
        surfaceTint = Color.Transparent,
    )
}

val LocalPlanColors = compositionLocalOf { systemPlanColors(false, LightColors.withCardContrast(false)) }
val LocalThemeMode = compositionLocalOf { ThemeMode.SYSTEM }

@Composable
fun StundenplanTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    custom: CustomThemeSpec = CustomThemeSpec(),
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme
    val shapes: Shapes
    val plan: PlanColors
    when (mode) {
        ThemeMode.SOFT -> {
            colorScheme = if (darkTheme) SoftTheme.dark else SoftTheme.light
            shapes = SoftTheme.shapes
            plan = remember(darkTheme) { SoftTheme.planColors(darkTheme) }
        }
        ThemeMode.AMOLED -> {
            // Always dark, whatever the system setting says.
            colorScheme = AmoledTheme.scheme
            shapes = Shapes()
            plan = remember { AmoledTheme.planColors() }
        }
        ThemeMode.CUSTOM -> {
            val dark = CustomTheme.isDark(custom, darkTheme)
            colorScheme = remember(custom, dark) { CustomTheme.scheme(custom, dark) }
            shapes = Shapes()
            plan = remember(custom, dark, colorScheme) { CustomTheme.planColors(custom, dark, colorScheme) }
        }
        ThemeMode.SYSTEM -> {
            colorScheme = when {
                dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (darkTheme) dynamicDarkColorScheme(context).softened() else dynamicLightColorScheme(context).softened()
                darkTheme -> DarkColors.softened()
                else -> LightColors.softened()
            }.withCardContrast(darkTheme)
            shapes = Shapes()
            plan = remember(darkTheme, colorScheme) { systemPlanColors(darkTheme, colorScheme) }
        }
    }
    CompositionLocalProvider(LocalPlanColors provides plan, LocalThemeMode provides mode) {
        MaterialTheme(colorScheme = colorScheme, shapes = shapes, content = content)
    }
}
