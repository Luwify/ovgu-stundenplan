package de.luwify.stundenplanmd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.data.settings.ColorRole
import de.luwify.stundenplanmd.data.settings.CustomBase
import de.luwify.stundenplanmd.data.settings.CustomThemeSpec
import kotlin.math.abs

/**
 * The user's own theme, generated from a [CustomThemeSpec] (base brightness, accent hue, card border). Tones are picked
 * in HSL, then every text color is pushed lighter or darker until it meets WCAG AA (4.5:1) on each surface it is
 * used on, and control borders 3:1, so no hue can produce unreadable text. ThemeContrastTest checks every hue.
 * Pure (no Android), like the other palettes.
 */
object CustomTheme {
    private const val TEXT = 4.5
    private const val BORDER = 3.0

    /** The accent as typed/picked, opaque. */
    fun accentColor(spec: CustomThemeSpec): Color = Color(0xFF000000.toInt() or (spec.accent and 0xFFFFFF))

    /**
     * Hue of the accent, and how far its saturation scales the built-in tones (the tones were tuned for a swatch
     * with 62 % saturation): a gray accent gives a neutral theme, a vivid one a more colorful one.
     */
    private fun hueAndScale(spec: CustomThemeSpec): Pair<Float, Float> {
        val (h, s, _) = accentColor(spec).toHsl()
        return h to (s / 0.62f).coerceIn(0f, 1.4f)
    }

    /**
     * Light or dark. A background set by hand decides by its own brightness (otherwise no text color could be read
     * both on that page and on cards made for the other mode); else the base (and for "Wie System" the system).
     */
    fun isDark(spec: CustomThemeSpec, systemDark: Boolean): Boolean =
        spec.overrides[ColorRole.BACKGROUND]?.let { Color(0xFF000000.toInt() or it).luminance() < 0.18f } ?: spec.isDark(systemDark)

    fun scheme(spec: CustomThemeSpec, dark: Boolean): ColorScheme {
        val (h, k) = hueAndScale(spec)
        val t = (h + 40f) % 360f // tertiary (badges): a neighbor of the accent
        val black = spec.base == CustomBase.BLACK
        val o = overrides(spec)
        return if (!dark) light(h, t, k, accentColor(spec), o) else dark(h, t, k, accentColor(spec), black, o)
    }

    private fun overrides(spec: CustomThemeSpec): Map<ColorRole, Color> =
        spec.overrides.mapValues { Color(0xFF000000.toInt() or (it.value and 0xFFFFFF)) }

    private fun light(h: Float, t: Float, k: Float, accent: Color, o: Map<ColorRole, Color>): ColorScheme {
        // Page one step below the cards, like the System theme: cards stand out by their fill. A page the user set
        // pushes a generated card away from it instead.
        // Surfaces set by hand are wishes too: page and cards share one text color, so each is moved just far
        // enough from this mode's text to keep it readable (a mid-gray card in a light theme gets lighter).
        val modeText = listOf(tone(h, 0.15f, 0.12f, k))
        val card = o[ColorRole.CARD]?.withContrast(TEXT, modeText)
            ?: tone(h, 0.30f, 0.995f, k).let { c -> o[ColorRole.BACKGROUND]?.let { c.withContrast(1.17, listOf(it)) } ?: c }
        // Yellow is brighter than blue at the same HSL lightness: darken the page until it reads against the card.
        val page = o[ColorRole.BACKGROUND]?.withContrast(TEXT, modeText) ?: tone(h, 0.18f, 0.93f, k).withContrast(1.17, listOf(card))
        val sheet = tone(h, 0.20f, 0.97f, k)
        val surfaces = listOf(page, card, sheet)
        // The user's exact color wherever it is readable; otherwise darkened just enough.
        val primary = accent.withContrast(TEXT, surfaces)
        val primaryContainer = tone(h, 0.65f, 0.87f, k)
        val secondaryContainer = tone(h, 0.28f, 0.88f, k).withContrast(1.1, listOf(card))
        val tertiaryContainer = tone(t, 0.60f, 0.89f, k)
        // Also readable on the tonal rows (settings fields, status box).
        val text = (o[ColorRole.TEXT] ?: tone(h, 0.15f, 0.12f, k)).withContrast(TEXT, surfaces + tone(h, 0.16f, 0.90f, k))
        return lightColorScheme(
            primary = primary,
            onPrimary = Color.White.withContrast(TEXT, listOf(primary)),
            primaryContainer = primaryContainer,
            onPrimaryContainer = tone(h, 0.60f, 0.16f, k).withContrast(TEXT, listOf(primaryContainer)),
            inversePrimary = tone(h, 0.60f, 0.78f, k),
            secondary = tone(h, 0.20f, 0.36f, k).withContrast(TEXT, surfaces),
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = tone(h, 0.25f, 0.18f, k).withContrast(TEXT, listOf(secondaryContainer)),
            tertiary = tone(t, 0.55f, 0.36f, k).withContrast(TEXT, surfaces),
            onTertiary = Color.White,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = tone(t, 0.60f, 0.16f, k).withContrast(TEXT, listOf(tertiaryContainer)),
            background = page,
            onBackground = text,
            surface = page,
            onSurface = text,
            surfaceVariant = tone(h, 0.16f, 0.88f, k),
            onSurfaceVariant = tone(h, 0.10f, 0.34f, k).withContrast(TEXT, surfaces + secondaryContainer),
            surfaceTint = Color.Transparent,
            inverseSurface = tone(h, 0.12f, 0.20f, k),
            inverseOnSurface = tone(h, 0.20f, 0.95f, k),
            error = Color(0xFFB3261E).withContrast(TEXT, listOf(page)),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            outline = tone(h, 0.08f, 0.48f, k).withContrast(BORDER, surfaces),
            outlineVariant = tone(h, 0.14f, 0.82f, k),
            scrim = Color.Black,
            surfaceBright = sheet,
            surfaceDim = tone(h, 0.16f, 0.87f, k),
            surfaceContainerLowest = card,
            surfaceContainerLow = sheet,
            surfaceContainer = page,
            surfaceContainerHigh = tone(h, 0.16f, 0.90f, k),
            surfaceContainerHighest = tone(h, 0.14f, 0.87f, k),
        )
    }

    private fun dark(h: Float, t: Float, k: Float, accent: Color, black: Boolean, o: Map<ColorRole, Color>): ColorScheme {
        // Black: pure black page and a barely lifted card, like AMOLED. Dark: a tinted near-black page.
        val modeText = listOf(tone(h, 0.12f, 0.90f, k))
        val page = o[ColorRole.BACKGROUND]?.withContrast(TEXT, modeText) ?: if (black) Color.Black else tone(h, 0.15f, 0.05f, k)
        // Blue is darker than yellow at the same HSL lightness: lift the card until it reads against the page.
        val card = o[ColorRole.CARD]?.withContrast(TEXT, modeText)
            ?: if (black && ColorRole.BACKGROUND !in o) tone(h, 0.08f, 0.065f, k) else tone(h, 0.14f, 0.12f, k).withContrast(1.2, listOf(page))
        val sheet = if (black) tone(h, 0.08f, 0.08f, k) else tone(h, 0.14f, 0.09f, k)
        val chip = (if (black) tone(h, 0.12f, 0.15f, k) else tone(h, 0.16f, 0.21f, k)).withContrast(1.1, listOf(card))
        val surfaces = listOf(page, card, sheet)
        // The user's exact color wherever it is readable; otherwise lightened just enough.
        val primary = accent.withContrast(TEXT, surfaces)
        val primaryContainer = tone(h, 0.45f, 0.28f, k)
        val tertiaryContainer = tone(t, 0.35f, 0.28f, k)
        // Also readable on the tonal rows (settings fields, status box).
        val text = (o[ColorRole.TEXT] ?: tone(h, 0.12f, 0.90f, k)).withContrast(TEXT, surfaces + chip)
        return darkColorScheme(
            primary = primary,
            onPrimary = tone(h, 0.70f, 0.14f, k).withContrast(TEXT, listOf(primary)),
            primaryContainer = primaryContainer,
            onPrimaryContainer = tone(h, 0.70f, 0.90f, k).withContrast(TEXT, listOf(primaryContainer)),
            inversePrimary = tone(h, 0.55f, 0.42f, k),
            secondary = tone(h, 0.25f, 0.76f, k).withContrast(TEXT, surfaces),
            onSecondary = tone(h, 0.25f, 0.15f, k),
            secondaryContainer = chip,
            onSecondaryContainer = tone(h, 0.18f, 0.84f, k).withContrast(TEXT, listOf(chip)),
            tertiary = tone(t, 0.60f, 0.79f, k).withContrast(TEXT, surfaces),
            onTertiary = tone(t, 0.60f, 0.15f, k),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = tone(t, 0.70f, 0.90f, k).withContrast(TEXT, listOf(tertiaryContainer)),
            background = page,
            onBackground = text,
            surface = page,
            onSurface = text,
            surfaceVariant = tone(h, 0.10f, 0.20f, k),
            onSurfaceVariant = tone(h, 0.10f, 0.72f, k).withContrast(TEXT, surfaces + chip),
            surfaceTint = Color.Transparent,
            inverseSurface = tone(h, 0.12f, 0.90f, k),
            inverseOnSurface = tone(h, 0.12f, 0.12f, k),
            error = Color(0xFFFFB4A9).withContrast(TEXT, listOf(page)),
            onError = Color(0xFF561E18),
            errorContainer = Color(0xFF73332B),
            onErrorContainer = Color(0xFFFFDAD5),
            outline = tone(h, 0.08f, 0.55f, k).withContrast(BORDER, surfaces),
            outlineVariant = tone(h, 0.10f, 0.27f, k),
            scrim = Color.Black,
            surfaceBright = tone(h, 0.10f, 0.20f, k),
            surfaceDim = page,
            surfaceContainerLowest = if (black) Color.Black else tone(h, 0.15f, 0.03f, k),
            surfaceContainerLow = sheet,
            surfaceContainer = card,
            surfaceContainerHigh = chip,
            surfaceContainerHighest = tone(h, 0.12f, if (black) 0.18f else 0.25f, k),
        )
    }

    fun planColors(spec: CustomThemeSpec, dark: Boolean, scheme: ColorScheme): PlanColors {
        val card = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLowest
        val o = overrides(spec)
        // Card border option: the accent (or the user's border color) as a real 3:1 edge, like AMOLED's blue one.
        val edge = (o[ColorRole.CARD_BORDER] ?: accentColor(spec)).withContrast(BORDER, listOf(scheme.background, card))
        return planColors(
            page = scheme.background,
            card = card,
            // Without the option the edge is a decorative hairline: the user's color as it is.
            border = if (spec.glow) edge else o[ColorRole.CARD_BORDER] ?: scheme.outlineVariant,
            borderWidth = if (spec.glow) 1.dp else Dp.Hairline,
            highlightWish = o[ColorRole.HIGHLIGHT],
            surfaces = favoriteSurfaces(scheme),
            favoriteWish = o[ColorRole.FAVORITE],
        )
    }
}

/** A tone of the palette: [s] scaled by the accent's saturation [k], so gray accents give neutral themes. */
private fun tone(h: Float, s: Float, l: Float, k: Float): Color = hsl(h, (s * k).coerceIn(0f, 1f), l)
