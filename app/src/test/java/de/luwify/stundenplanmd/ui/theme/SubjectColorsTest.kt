package de.luwify.stundenplanmd.ui.theme

import androidx.compose.ui.graphics.Color
import de.luwify.stundenplanmd.data.settings.CustomBase
import de.luwify.stundenplanmd.data.settings.CustomThemeSpec
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event blocks stay readable in every theme, for every palette color and every hue the picker can give. */
class SubjectColorsTest {
    private val pages: List<Pair<String, Color>> = buildList {
        add("Soft light" to SoftTheme.light.background)
        add("Soft dark" to SoftTheme.dark.background)
        add("AMOLED" to AmoledTheme.scheme.background)
        for (dark in listOf(false, true)) add("System dark=$dark" to (if (dark) DarkColors else LightColors).softened().withCardContrast(dark).background)
        for (base in CustomBase.entries) for (dark in listOf(false, true)) for (accent in listOf(0x7839D0, 0xFFFF00, 0x000080, 0x808080)) {
            val spec = CustomThemeSpec(base, accent)
            add("Custom $base $accent" to CustomTheme.scheme(spec, CustomTheme.isDark(spec, dark)).background)
        }
        add("white" to Color.White)
        add("black" to Color.Black)
    }

    private val colors = SubjectColors.palette + (0 until 360 step 10).map { ColorInput_hsl(it) } +
        listOf(0x000000, 0xFFFFFF, 0x808080, 0xFFFF00, 0x00FF00, 0x0000FF, 0x101010, 0xF0F0F0)

    private fun ColorInput_hsl(hue: Int) = hsl(hue.toFloat(), 0.8f, 0.5f).toRgb()

    @Test
    fun blocksAreReadableEverywhere() {
        val failures = mutableListOf<String>()
        for ((name, page) in pages) for (rgb in colors) {
            val b = SubjectColors.block(rgb, page)
            if (contrast(b.content, b.container) < 4.5) failures += "$name %06X text %.2f".format(rgb, contrast(b.content, b.container))
            if (contrast(b.stripe, b.container) < 3.0) failures += "$name %06X stripe %.2f".format(rgb, contrast(b.stripe, b.container))
            if (contrast(b.container, page) < 1.15) failures += "$name %06X fill/page %.2f".format(rgb, contrast(b.container, page))
        }
        assertTrue("${failures.size} failures, e.g. ${failures.take(5)}", failures.isEmpty())
    }

    @Test
    fun differentPaletteColorsGiveDifferentFills() {
        for ((name, page) in pages.take(5)) {
            val fills = SubjectColors.palette.map { SubjectColors.block(it, page).container }.toSet()
            assertTrue(name, fills.size == SubjectColors.palette.size)
        }
    }
}
