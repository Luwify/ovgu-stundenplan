package de.luwify.stundenplanmd.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Colors of one event block. */
data class BlockColors(val container: Color, val content: Color, val stripe: Color)

/**
 * Subject colors: the user picks a base color (or gets one from [palette]); blocks show a tint of its hue as
 * fill and the color itself as a stripe. Like the Mensa MD accents, everything is nudged to stay readable on the
 * current theme: text 4.5:1 on the fill, stripe 3:1 on the fill, fill apart from the page. SubjectColorsTest
 * checks every palette color and hue on every theme.
 */
object SubjectColors {
    /** Default colors, assigned to subjects in order. 0xRRGGBB. */
    val palette = listOf(
        0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA, 0x00897B, 0xE53935,
        0x3949AB, 0xC0CA33, 0xD81B60, 0x6D4C41, 0x00ACC1, 0xFDD835,
    )

    fun defaultColor(index: Int): Int = palette[index.mod(palette.size)]

    /** [page] is the background the blocks sit on. */
    fun block(rgb: Int, page: Color): BlockColors {
        val dark = page.luminance() < 0.18f
        val (h, s, _) = rgbColor(rgb).toHsl()
        val fill = if (dark) hsl(h, s * 0.45f, 0.22f) else hsl(h, s * 0.55f, 0.88f)
        val container = fill.withContrast(1.15, listOf(page))
        val content = (if (dark) hsl(h, s * 0.30f, 0.92f) else hsl(h, s * 0.60f, 0.15f)).withContrast(4.5, listOf(container))
        val stripe = rgbColor(rgb).withContrast(3.0, listOf(container))
        return BlockColors(container, content, stripe)
    }
}
