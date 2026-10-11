package de.luwify.stundenplanmd.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import de.luwify.stundenplanmd.ui.theme.hsl
import de.luwify.stundenplanmd.ui.theme.toHsl

/** Typing and converting the custom theme's accent color (0xRRGGBB). Pure, unit-tested. */
object ColorInput {
    private val HEX = Regex("""^#?([0-9a-fA-F]{6})$""")

    /** "#7839D0" or "7839d0"; only the full 6 digits (a 3-digit shorthand would fire mid-typing). */
    fun parseHex(text: String): Int? = HEX.matchEntire(text.trim())?.groupValues?.get(1)?.toInt(16)

    fun formatHex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

    /** One RGB channel, 0..255. */
    fun parseChannel(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..255 }

    fun red(rgb: Int) = (rgb shr 16) and 0xFF
    fun green(rgb: Int) = (rgb shr 8) and 0xFF
    fun blue(rgb: Int) = rgb and 0xFF

    fun withChannel(rgb: Int, channel: Int, value: Int): Int {
        val shift = 16 - 8 * channel
        return (rgb and (0xFF shl shift).inv() and 0xFFFFFF) or ((value and 0xFF) shl shift)
    }

    /** Hue of [rgb] in degrees, 0..359. */
    fun hue(rgb: Int): Int = Color(0xFF000000.toInt() or rgb).toHsl().first.toInt().mod(360)

    /**
     * [rgb] turned to [hue], keeping its saturation and lightness (the slider only turns the color). A gray has no
     * hue to turn, so it takes the swatches' saturation and lightness instead.
     */
    fun withHue(rgb: Int, hue: Float): Int {
        val (_, s, l) = Color(0xFF000000.toInt() or rgb).toHsl()
        val color = if (s < 0.08f) hsl(hue, 0.62f, 0.52f) else hsl(hue, s, l)
        return color.toArgb() and 0xFFFFFF
    }

    // ---- the color picker of the advanced editor: hue ring around a saturation/value square ----

    /** HSV of [rgb]: hue 0..360, saturation and value 0..1. */
    fun toHsv(rgb: Int): Triple<Float, Float, Float> {
        val r = red(rgb) / 255f
        val g = green(rgb) / 255f
        val b = blue(rgb) / 255f
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d).mod(6f))
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return Triple(h, if (max == 0f) 0f else d / max, max)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int =
        Color.hsv(h.mod(360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)).toArgb() and 0xFFFFFF

    /** Hue at a point ([dx], [dy] from the ring's center, screen coordinates: 0° right, clockwise). */
    fun hueAt(dx: Float, dy: Float): Float = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat().mod(360f)

    /** Saturation (left to right) and value (top to bottom: 1 to 0) at a point in the square of [side]. */
    fun saturationValueAt(x: Float, y: Float, side: Float): Pair<Float, Float> =
        (x / side).coerceIn(0f, 1f) to (1f - y / side).coerceIn(0f, 1f)

    /**
     * Ten shades of [rgb]'s hue and saturation, light to dark (HSL lightness 92 % to 22 %), for quick picks. A gray
     * gives grays.
     */
    fun shades(rgb: Int): List<Int> {
        val (h, s, _) = Color(0xFF000000.toInt() or rgb).toHsl()
        return (0 until 10).map { i -> hsl(h, s, 0.92f - i * (0.70f / 9f)).toArgb() and 0xFFFFFF }
    }
}
