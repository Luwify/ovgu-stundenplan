package de.luwify.stundenplanmd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The "AMOLED" palette: always dark, pure black background (unlit pixels), very dark gray cards
 * with a blue 1dp border, a light desaturated blue as the accent. Text pairs are checked for AA in
 * ThemeContrastTest.
 */
object AmoledTheme {
    val scheme: ColorScheme = darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF06234D),
        primaryContainer = Color(0xFF1D3F73),
        onPrimaryContainer = Color(0xFFD6E3FF),
        inversePrimary = Color(0xFF2E5FA8),
        secondary = Color(0xFFA8B8D4),
        onSecondary = Color(0xFF1B2A42),
        secondaryContainer = Color(0xFF1E2024),
        onSecondaryContainer = Color(0xFFC9CCD4),
        tertiary = Color(0xFFC9B8F2),
        onTertiary = Color(0xFF2D1F52),
        tertiaryContainer = Color(0xFF3A2F5B),
        onTertiaryContainer = Color(0xFFE9DEFF),
        background = Color(0xFF000000),
        onBackground = Color(0xFFE6E7EB),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFE6E7EB),
        surfaceVariant = Color(0xFF2A2C32),
        onSurfaceVariant = Color(0xFFA9ACB5),
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFFE6E7EB),
        inverseOnSurface = Color(0xFF1B1C20),
        error = Color(0xFFFFB4A9),
        onError = Color(0xFF561E18),
        errorContainer = Color(0xFF73332B),
        onErrorContainer = Color(0xFFFFDAD5),
        outline = Color(0xFF7C7F89),
        outlineVariant = Color(0xFF45474F),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF2A2C32),
        surfaceDim = Color(0xFF000000),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF141518),
        surfaceContainer = Color(0xFF17181B),
        surfaceContainerHigh = Color(0xFF1C1D21),
        surfaceContainerHighest = Color(0xFF232428),
    )

    val cardColor = Color(0xFF0F0F11)

    /** A brighter blue edge around the cards (an accent, well above 3:1 on black). */
    val cardBorder = Color(0xFF4F7FD9)

    fun planColors(): PlanColors = planColors(page = scheme.background, card = cardColor, border = cardBorder, borderWidth = 1.dp, surfaces = favoriteSurfaces(scheme))
}
