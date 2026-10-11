package de.luwify.stundenplanmd.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {
    private fun Color.close(o: Color, eps: Float = 0.01f) =
        abs(red - o.red) < eps && abs(green - o.green) < eps && abs(blue - o.blue) < eps

    @Test fun keptOne_isUnchanged() {
        val c = Color(0xFF6B8E23)
        assertTrue(c.desaturated(1f).close(c))
    }

    @Test fun keptZero_isGrayWithTheSameLightness() {
        val g = Color(0xFF6B8E23).desaturated(0f)
        assertTrue(abs(g.red - g.green) < 0.005f && abs(g.green - g.blue) < 0.005f)
        val l = (maxOf(0x6B, 0x8E, 0x23) + minOf(0x6B, 0x8E, 0x23)) / 2f / 255f
        assertEquals(l, g.red, 0.01f)
    }

    @Test fun partial_pullsTowardGray_keepsHueOrder() {
        val c = Color(0xFFC0392B)
        val d = c.desaturated(0.5f)
        assertTrue(d.red > d.green && d.red > d.blue) // still reddish
        assertTrue(d.red - d.green < c.red - c.green) // but less saturated
    }

    @Test fun grays_andAlphaSurvive() {
        val gray = Color(0xFF808080)
        assertEquals(gray, gray.desaturated(0.3f))
        assertEquals(0.5f, Color(0x80FF0000).desaturated(0.5f).alpha, 0.01f)
    }
}
