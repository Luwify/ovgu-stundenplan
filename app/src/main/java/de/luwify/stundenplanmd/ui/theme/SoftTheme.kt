package de.luwify.stundenplanmd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The "Soft" palette: warm cream and warm brown-gray surfaces, muted sage as primary, terracotta
 * (text) and peach (fills) as the accent. Every text pair used in the app is checked for WCAG AA
 * (4.5:1) in ThemeContrastTest. All colors of the Soft look live in this file.
 */
object SoftTheme {
    val light: ColorScheme = lightColorScheme(
        primary = Color(0xFF43654A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB9D3B6),
        onPrimaryContainer = Color(0xFF16301C),
        inversePrimary = Color(0xFFA9C9A8),
        secondary = Color(0xFF5C6B58),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE7E3D2),
        onSecondaryContainer = Color(0xFF363C2D),
        tertiary = Color(0xFF9A4A2A),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFADCC8),
        onTertiaryContainer = Color(0xFF4A2210),
        background = Color(0xFFF7F1E6),
        onBackground = Color(0xFF2E2A24),
        surface = Color(0xFFF7F1E6),
        onSurface = Color(0xFF2E2A24),
        surfaceVariant = Color(0xFFE5DDCD),
        onSurfaceVariant = Color(0xFF645C51),
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFF3A352E),
        inverseOnSurface = Color(0xFFF4EDE0),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        outline = Color(0xFF8A8174),
        outlineVariant = Color(0xFFDDD3C2),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFBF6EC),
        surfaceDim = Color(0xFFE4DCCD),
        surfaceContainerLowest = Color(0xFFFFFCF6),
        surfaceContainerLow = Color(0xFFFBF6EC),
        surfaceContainer = Color(0xFFF3ECDF),
        surfaceContainerHigh = Color(0xFFEEE6D8),
        surfaceContainerHighest = Color(0xFFE8DFCF),
    )

    val dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFA9C9A8),
        onPrimary = Color(0xFF173320),
        primaryContainer = Color(0xFF3C5C43),
        onPrimaryContainer = Color(0xFFD3EBD1),
        inversePrimary = Color(0xFF43654A),
        secondary = Color(0xFFB9C7B5),
        onSecondary = Color(0xFF243024),
        secondaryContainer = Color(0xFF2E2B23),
        onSecondaryContainer = Color(0xFFE2DECB),
        tertiary = Color(0xFFF0AD8E),
        onTertiary = Color(0xFF4A2210),
        tertiaryContainer = Color(0xFF5B3524),
        onTertiaryContainer = Color(0xFFFFDCCB),
        background = Color(0xFF0D0B09),
        onBackground = Color(0xFFECE4D8),
        surface = Color(0xFF0D0B09),
        onSurface = Color(0xFFECE4D8),
        surfaceVariant = Color(0xFF3A342C),
        onSurfaceVariant = Color(0xFFB7AE9F),
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFFECE4D8),
        inverseOnSurface = Color(0xFF2E2A24),
        error = Color(0xFFFFB4A9),
        onError = Color(0xFF561E18),
        errorContainer = Color(0xFF73332B),
        onErrorContainer = Color(0xFFFFDAD5),
        outline = Color(0xFF8F8678),
        outlineVariant = Color(0xFF3F382F),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF2E2923),
        surfaceDim = Color(0xFF0D0B09),
        surfaceContainerLowest = Color(0xFF080706),
        surfaceContainerLow = Color(0xFF15110E),
        surfaceContainer = Color(0xFF1A1612),
        surfaceContainerHigh = Color(0xFF231E18),
        surfaceContainerHighest = Color(0xFF2D2720),
    )

    /** Rounder than the Material defaults (4/8/12/16/28). */
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

    fun planColors(darkTheme: Boolean): PlanColors {
        val scheme = if (darkTheme) dark else light
        // Paper cards: near-white on cream (light), a lifted brown on the dark background.
        return planColors(
            page = scheme.background,
            card = if (darkTheme) scheme.surfaceContainer else scheme.surfaceContainerLowest,
            border = scheme.outlineVariant,
        )
    }
}
