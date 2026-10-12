package de.luwify.stundenplanmd.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp

/** The colors Material's scheme has no role for, per theme, so no screen hardcodes a color. */
@Immutable
class PlanColors(
    /** Cards of the Today list. */
    val cardContainer: Color,
    val cardBorder: Color,
    val cardBorderWidth: Dp = Dp.Hairline,
    /** Border and badge of changed and extra appointments (amber by default), 3:1 on page and card. */
    val highlight: Color,
    /** Text on [highlight]. */
    val onHighlight: Color,
    /** Highlight color used as text (change lines), 4.5:1 on page and card. */
    val highlightText: Color,
    /** Hearts and the together icon with a favorite friend (red by default), 3:1 on the badge (surface), sheets, page and card. */
    val favorite: Color = Color.Red,
)

/** Amber in light and dark, or the custom theme's [wish]: nudged to 3:1 (text 4.5:1) on [page] and [card]. */
fun highlightColors(page: Color, card: Color, wish: Color? = null): Triple<Color, Color, Color> {
    val dark = page.luminance() < 0.18f
    val base = wish ?: if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
    val highlight = base.withContrast(3.0, listOf(page, card))
    val on = (if (highlight.luminance() > 0.4f) Color(0xFF2B1700) else Color.White).withContrast(4.5, listOf(highlight))
    return Triple(highlight, on, base.withContrast(4.5, listOf(page, card)))
}

/** Red in light and dark, or the custom theme's [wish], nudged to 3:1 on every one of [backgrounds]. */
fun favoriteColor(backgrounds: List<Color>, wish: Color? = null): Color {
    val dark = backgrounds.first().luminance() < 0.18f
    return (wish ?: if (dark) Color(0xFFFF5C6C) else Color(0xFFD3203A)).withContrast(3.0, backgrounds)
}

/** [surfaces]: where hearts and the together badge sit besides page and card (surface = badge, drawer sheet, ...). */
fun planColors(
    page: Color,
    card: Color,
    border: Color,
    borderWidth: Dp = Dp.Hairline,
    highlightWish: Color? = null,
    surfaces: List<Color> = emptyList(),
    favoriteWish: Color? = null,
): PlanColors {
    val (h, on, text) = highlightColors(page, card, highlightWish)
    return PlanColors(card, border, borderWidth, h, on, text, favoriteColor(listOf(page, card) + surfaces, favoriteWish))
}

/** The surfaces a favorite red must stand out on: the together badge and top bar (surface) and the drawer sheet. */
fun favoriteSurfaces(scheme: androidx.compose.material3.ColorScheme) = listOf(scheme.surface, scheme.surfaceContainerLow)

@Composable
@ReadOnlyComposable
fun planColors(): PlanColors = LocalPlanColors.current
