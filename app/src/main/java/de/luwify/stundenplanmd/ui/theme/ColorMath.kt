package de.luwify.stundenplanmd.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/** Color helpers shared by the themes and the subject colors (from Mensa MD). Pure, unit-tested. */

/** Color to HSL: hue in degrees 0..360, saturation and lightness 0..1. */
internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val maxC = maxOf(red, green, blue)
    val minC = minOf(red, green, blue)
    val l = (maxC + minC) / 2f
    val d = maxC - minC
    if (d == 0f) return Triple(0f, 0f, l)
    val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
    val h = when (maxC) {
        red -> ((green - blue) / d + (if (green < blue) 6f else 0f)) * 60f
        green -> ((blue - red) / d + 2f) * 60f
        else -> ((red - green) / d + 4f) * 60f
    }
    return Triple(h, s, l)
}

/** HSL to color; [h] in degrees, [s] and [l] 0..1. */
internal fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = (h.mod(360f)) / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r, g, b) = when (hp.toInt().coerceIn(0, 5)) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

/**
 * Keeps hue and lightness, scales HSL saturation by [kept] (1 = unchanged, 0 = gray).
 */
fun Color.desaturated(kept: Float): Color {
    val (h, s, l) = toHsl()
    if (s == 0f) return this
    return hsl(h, (s * kept).coerceIn(0f, 1f), l).copy(alpha = alpha)
}

/** 0xRRGGBB -> opaque color. */
fun rgbColor(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/** Opaque color -> 0xRRGGBB. */
fun Color.toRgb(): Int =
    ((red * 255f + 0.5f).toInt() shl 16) or ((green * 255f + 0.5f).toInt() shl 8) or (blue * 255f + 0.5f).toInt()

internal fun contrast(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * This color, moved toward white (on dark backgrounds) or black (on light ones) in small steps until it reaches
 * [min] contrast on every one of [backgrounds]. Hue is kept as far as the lightness allows.
 */
internal fun Color.withContrast(min: Double, backgrounds: List<Color>): Color {
    if (backgrounds.all { contrast(this, it) >= min }) return this
    val towardWhite = backgrounds.map { it.luminance() }.average() < 0.18
    val target = if (towardWhite) Color.White else Color.Black
    for (step in 1..50) {
        val c = lerp(this, target, step / 50f)
        if (backgrounds.all { contrast(c, it) >= min }) return c
    }
    return target
}
