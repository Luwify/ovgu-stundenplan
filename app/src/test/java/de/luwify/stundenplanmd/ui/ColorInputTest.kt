package de.luwify.stundenplanmd.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorInputTest {
    @Test
    fun hex_parsesSixDigitsWithOrWithoutHash_anyCase() {
        assertEquals(0x7839D0, ColorInput.parseHex("#7839D0"))
        assertEquals(0x7839D0, ColorInput.parseHex("7839d0"))
        assertEquals(0x7839D0, ColorInput.parseHex("  #7839d0 "))
        assertNull(ColorInput.parseHex("#7839D")) // mid-typing: not applied yet
        assertNull(ColorInput.parseHex("#abc")) // no shorthand: it would fire while typing #abcdef
        assertNull(ColorInput.parseHex("#GGGGGG"))
        assertEquals("#7839D0", ColorInput.formatHex(0x7839D0))
        assertEquals("#000000", ColorInput.formatHex(0))
    }

    @Test
    fun rgb_channelsParseAndReplace() {
        assertEquals(255, ColorInput.parseChannel("255"))
        assertEquals(0, ColorInput.parseChannel("0"))
        assertNull(ColorInput.parseChannel("256"))
        assertNull(ColorInput.parseChannel(""))
        assertNull(ColorInput.parseChannel("-1"))
        val c = 0x112233
        assertEquals(0x11, ColorInput.red(c))
        assertEquals(0x22, ColorInput.green(c))
        assertEquals(0x33, ColorInput.blue(c))
        assertEquals(0xFF2233, ColorInput.withChannel(c, 0, 255))
        assertEquals(0x110033, ColorInput.withChannel(c, 1, 0))
        assertEquals(0x1122AB, ColorInput.withChannel(c, 2, 0xAB))
    }

    @Test
    fun hueSlider_keepsSaturationAndLightness_andGivesGrayAColor() {
        val pastel = 0xF4C2C2 // light pink
        val turned = ColorInput.withHue(pastel, 210f)
        assertEquals(210, ColorInput.hue(turned))
        // still pastel: every channel high
        assert(ColorInput.red(turned) > 150 && ColorInput.green(turned) > 150 && ColorInput.blue(turned) > 150)
        val fromGray = ColorInput.withHue(0x808080, 120f)
        assertEquals(120, ColorInput.hue(fromGray))
        assertEquals(ColorInput.hue(de.luwify.stundenplanmd.data.settings.hueToAccent(120)), ColorInput.hue(fromGray))
    }

    @Test
    fun defaultAccent_isTheVioletSwatch() {
        assertEquals(de.luwify.stundenplanmd.data.settings.CustomThemeSpec.DEFAULT_ACCENT, de.luwify.stundenplanmd.data.settings.hueToAccent(265))
    }

    @Test
    fun hsv_roundTrips() {
        for (rgb in listOf(0xFF0000, 0x00FF00, 0x0000FF, 0x7839D0, 0x808080, 0x000000, 0xFFFFFF, 0x1E88E5)) {
            val (h, s, v) = ColorInput.toHsv(rgb)
            assertEquals(ColorInput.formatHex(rgb), ColorInput.formatHex(ColorInput.fromHsv(h, s, v)))
        }
        assertEquals(Triple(0f, 1f, 1f), ColorInput.toHsv(0xFF0000))
        assertEquals(240f, ColorInput.toHsv(0x0000FF).first, 0.01f)
    }

    @Test
    fun ringAngle_isHue_clockwiseFromTheRight() {
        assertEquals(0f, ColorInput.hueAt(10f, 0f), 0.01f)
        assertEquals(90f, ColorInput.hueAt(0f, 10f), 0.01f) // below the center (screen y grows downward)
        assertEquals(180f, ColorInput.hueAt(-10f, 0f), 0.01f)
        assertEquals(270f, ColorInput.hueAt(0f, -10f), 0.01f)
    }

    @Test
    fun square_mapsSaturationRightAndBrightnessUp_clampedOutside() {
        assertEquals(0f to 1f, ColorInput.saturationValueAt(0f, 0f, 100f))
        assertEquals(1f to 0f, ColorInput.saturationValueAt(100f, 100f, 100f))
        assertEquals(0.5f to 0.75f, ColorInput.saturationValueAt(50f, 25f, 100f))
        assertEquals(1f to 1f, ColorInput.saturationValueAt(140f, -30f, 100f)) // finger left the square while dragging
    }

    @Test
    fun shades_areTenLightToDark_ofTheSameHue() {
        val shades = ColorInput.shades(0x1E88E5)
        assertEquals(10, shades.size)
        val lum = shades.map { androidx.compose.ui.graphics.Color(0xFF000000.toInt() or it).let { c -> c.red + c.green + c.blue } }
        assert(lum.zipWithNext().all { (a, b) -> a > b }) { "light to dark: $lum" }
        assert(shades.all { kotlin.math.abs(ColorInput.hue(it) - ColorInput.hue(0x1E88E5)) <= 2 })
    }
}
