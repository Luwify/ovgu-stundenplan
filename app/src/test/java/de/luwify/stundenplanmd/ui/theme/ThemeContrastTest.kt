package de.luwify.stundenplanmd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import de.luwify.stundenplanmd.data.settings.ColorRole
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast for every text/background pair the screens use, in the themes with a fixed
 * palette (Soft light and dark, AMOLED), the System fallback palettes and the custom theme. Text needs 4.5:1, control borders 3:1.
 */
class ThemeContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun check(name: String, fg: Color, bg: Color, min: Double, failures: MutableList<String>) {
        val r = ratio(fg, bg)
        if (r < min) failures += "$name: %.2f < $min".format(r)
    }

    /** [cardEdgeMin]: the card hairline is decorative, so it only has to be visible, not 3:1. */
    private fun pairs(s: ColorScheme, m: PlanColors, cardEdgeMin: Double, failures: MutableList<String>) {
        val card = m.cardContainer
        val sheet = s.surfaceContainerLow
        val text = 4.5
        check("onSurface/background", s.onSurface, s.background, text, failures)
        check("onSurfaceVariant/background (hours, week dates)", s.onSurfaceVariant, s.background, text, failures)
        check("primary/background (today label)", s.primary, s.background, text, failures)
        check("onPrimary/primary (today date)", s.onPrimary, s.primary, text, failures)
        check("onSurface/card", s.onSurface, card, text, failures)
        check("onSurfaceVariant/card", s.onSurfaceVariant, card, text, failures)
        check("onSurface/sheet", s.onSurface, sheet, text, failures)
        check("onSurfaceVariant/sheet", s.onSurfaceVariant, sheet, text, failures)
        check("info card", s.onSecondaryContainer, s.secondaryContainer, text, failures)
        check("person indicator", s.inverseOnSurface, s.inverseSurface, text, failures)
        check("strip arrow icon", s.onSecondaryContainer, s.secondaryContainer, 3.0, failures)
        check("onSurface/surfaceContainerHigh (status box, settings rows)", s.onSurface, s.surfaceContainerHigh, text, failures)
        check("change text/card", m.highlightText, card, text, failures)
        check("change text/page", m.highlightText, s.background, text, failures)
        check("badge text", m.onHighlight, m.highlight, text, failures)
        check("day progress bar (primary)/background", s.primary, s.background, 3.0, failures)
        check("QR warning icon (error)/surface", s.error, s.surface, 3.0, failures)
        check("change border/page", m.highlight, s.background, 3.0, failures)
        check("change border/card", m.highlight, card, 3.0, failures)
        check("favorite/together badge (surface)", m.favorite, s.surface, 3.0, failures)
        check("favorite/drawer sheet", m.favorite, s.surfaceContainerLow, 3.0, failures)
        check("favorite/page", m.favorite, s.background, 3.0, failures)
        check("favorite/card", m.favorite, card, 3.0, failures)
        check("together icon (onSurfaceVariant)/badge", s.onSurfaceVariant, s.surface, 3.0, failures)
        // Week/day strip chips: selected = primary fill, today = primary outline on a card-filled or recessed chip
        check("chip selected text/fill", s.onPrimary, s.primary, text, failures)
        check("chip selected fill/background", s.primary, s.background, 3.0, failures)
        check("chip today outline/card chip", s.primary, card, 3.0, failures)
        check("chip today outline/background (muted chip)", s.primary, s.background, 3.0, failures)
        check("chip text/card chip", s.onSurface, card, text, failures)
        check("chip muted text/background", s.onSurfaceVariant, s.background, text, failures)
        check("chip outline/background", s.outline, s.background, 3.0, failures)
        check("card hairline/background", m.cardBorder, s.background, cardEdgeMin, failures)
    }

    private fun run(name: String, s: ColorScheme, m: PlanColors, cardEdgeMin: Double) {
        val failures = mutableListOf<String>()
        pairs(s, m, cardEdgeMin, failures)
        assertTrue("$name: $failures", failures.isEmpty())
    }

    @Test fun softLightPassesAA() = run("Soft light", SoftTheme.light, SoftTheme.planColors(false), 1.05)

    @Test fun softDarkPassesAA() = run("Soft dark", SoftTheme.dark, SoftTheme.planColors(true), 1.05)

    @Test fun amoledPassesAA() = run("AMOLED", AmoledTheme.scheme, AmoledTheme.planColors(), 3.0)

    @Test
    fun systemFallbackPassesAA() {
        for (dark in listOf(false, true)) {
            val s = (if (dark) DarkColors else LightColors).softened().withCardContrast(dark)
            run("System dark=$dark", s, systemPlanColors(dark, s), 1.05)
            val card = systemPlanColors(dark, s).cardContainer
            assertTrue("card stands out ${ratio(card, s.background)}", ratio(card, s.background) >= 1.1)
        }
    }

    @Test
    fun customHighlightWishIsKeptWhereReadable() {
        val spec = de.luwify.stundenplanmd.data.settings.CustomThemeSpec(
            base = de.luwify.stundenplanmd.data.settings.CustomBase.DARK,
            overrides = mapOf(ColorRole.HIGHLIGHT to 0xFF6B9D),
        )
        val s = CustomTheme.scheme(spec, true)
        assertTrue(CustomTheme.planColors(spec, true, s).highlight == Color(0xFFFF6B9D))
    }

    @Test
    fun favoriteIsRedByDefaultAndTheCustomWishIsKeptWhereReadable() {
        for (dark in listOf(false, true)) {
            val s = (if (dark) DarkColors else LightColors).softened().withCardContrast(dark)
            val red = systemPlanColors(dark, s).favorite
            assertTrue("red dark=$dark: $red", red.red > 0.6f && red.green < 0.45f && red.blue < 0.5f)
        }
        val spec = de.luwify.stundenplanmd.data.settings.CustomThemeSpec(
            base = de.luwify.stundenplanmd.data.settings.CustomBase.DARK,
            overrides = mapOf(ColorRole.FAVORITE to 0xFF8FB0),
        )
        val s = CustomTheme.scheme(spec, true)
        assertTrue(CustomTheme.planColors(spec, true, s).favorite == Color(0xFFFF8FB0))
    }




    /** The System theme with the fallback palettes (dynamic colors depend on the wallpaper; these are the same tones). */

    /**
     * The user's own theme: every swatch-tone hue (5° steps) plus extreme typed colors (black, white, grays, pure
     * primaries, pastels, navy) on every base, with and without the card border, must pass.
     */
    @Test
    fun customThemePassesAA_forEveryHueAndBase() {
        val failures = mutableListOf<String>()
        for (base in de.luwify.stundenplanmd.data.settings.CustomBase.entries) for (dark in listOf(false, true)) for (glow in listOf(false, true)) {
            val typed = listOf(0x000000, 0xFFFFFF, 0x808080, 0x101010, 0xF0F0F0, 0xFFFF00, 0x0000FF, 0x00FF00, 0xFF0000, 0xFF00FF, 0x00FFFF, 0xFFE0F0, 0x000080, 0x3D2B1F)
            for (accent in (0 until 360 step 5).map { de.luwify.stundenplanmd.data.settings.hueToAccent(it) } + typed) {
                val spec = de.luwify.stundenplanmd.data.settings.CustomThemeSpec(base, accent, glow)
                val isDark = spec.isDark(systemDark = dark)
                val s = CustomTheme.scheme(spec, isDark)
                val m = CustomTheme.planColors(spec, isDark, s)
                val local = mutableListOf<String>()
                pairs(s, m, if (glow) 3.0 else 1.05, local)
                check("card/page (cards stand out)", m.cardContainer, s.background, if (base == de.luwify.stundenplanmd.data.settings.CustomBase.BLACK) 1.08 else 1.15, local)
                if (glow) check("border edge/card", m.cardBorder, m.cardContainer, 3.0, local)
                local.forEach { failures += "$base dark=$isDark glow=$glow accent=%06X: $it".format(accent) }
            }
        }
        val kinds = failures.groupBy { it.substringAfter(": ").substringBefore(":") }.mapValues { it.value.size }
        assertTrue("${failures.size} failures by kind $kinds, e.g. ${failures.take(6)}", failures.isEmpty())
    }


    @Test
    fun customAccent_isUsedExactlyWhereReadable_andOnlyAdjustedWhereNot() {
        val dark = de.luwify.stundenplanmd.data.settings.CustomThemeSpec(de.luwify.stundenplanmd.data.settings.CustomBase.DARK, 0x8AB4F8)
        assertTrue("light blue on dark stays exact", CustomTheme.scheme(dark, true).primary == Color(0xFF8AB4F8))
        val light = de.luwify.stundenplanmd.data.settings.CustomThemeSpec(de.luwify.stundenplanmd.data.settings.CustomBase.LIGHT, 0x1D4ED8)
        assertTrue("strong blue on light stays exact", CustomTheme.scheme(light, false).primary == Color(0xFF1D4ED8))
        val yellow = light.copy(accent = 0xFFFF00)
        assertTrue("yellow on light is darkened", CustomTheme.scheme(yellow, false).primary != Color(0xFFFFFF00))
    }

    @Test
    fun customGrayAccent_givesANeutralTheme() {
        val s = CustomTheme.scheme(de.luwify.stundenplanmd.data.settings.CustomThemeSpec(de.luwify.stundenplanmd.data.settings.CustomBase.LIGHT, 0x808080), false)
        for (c in listOf(s.background, s.surfaceContainerLowest, s.secondaryContainer)) {
            assertTrue("gray: $c", kotlin.math.abs(c.red - c.green) < 0.01f && kotlin.math.abs(c.green - c.blue) < 0.01f)
        }
    }

    /**
     * Colors set by hand in the advanced editor are wishes: whatever they are (random subsets of roles, random
     * colors, every base, card border on/off), every text pair must still pass. Seeded, so a failure is reproducible.
     */
    @Test
    fun customOverrides_neverBreakReadability() {
        val random = java.util.Random(20261001)
        val roles = de.luwify.stundenplanmd.data.settings.ColorRole.entries
        val failures = mutableListOf<String>()
        repeat(400) { run ->
            val overrides = roles.filter { random.nextInt(3) == 0 }.associateWith { random.nextInt(0x1000000) }
            val spec = de.luwify.stundenplanmd.data.settings.CustomThemeSpec(
                base = de.luwify.stundenplanmd.data.settings.CustomBase.entries[random.nextInt(4)],
                accent = random.nextInt(0x1000000),
                glow = random.nextBoolean(),
                overrides = overrides,
            )
            val dark = CustomTheme.isDark(spec, systemDark = random.nextBoolean())
            val s = CustomTheme.scheme(spec, dark)
            val m = CustomTheme.planColors(spec, dark, s)
            val local = mutableListOf<String>()
            // a hand-picked hairline (no border option) is decorative and may be invisible on purpose
            val edgeMin = if (spec.glow) 3.0 else 1.0
            pairs(s, m, edgeMin, local)
            local.forEach { failures += "run $run $spec dark=$dark: $it" }
        }
        val kinds = failures.groupBy { it.substringAfterLast("dark=").substringAfter(": ").substringBefore(":") }.mapValues { it.value.size }
        assertTrue("${failures.size} failures by kind $kinds, e.g. ${failures.take(3)}", failures.isEmpty())
    }


    @Test
    fun customLightBackgroundByHand_makesTheThemeLight_evenOnADarkBase() {
        val spec = de.luwify.stundenplanmd.data.settings.CustomThemeSpec(
            base = de.luwify.stundenplanmd.data.settings.CustomBase.BLACK,
            overrides = mapOf(de.luwify.stundenplanmd.data.settings.ColorRole.BACKGROUND to 0xF5F0E6),
        )
        assertTrue(!CustomTheme.isDark(spec, systemDark = true))
        assertTrue(CustomTheme.isDark(spec.copy(overrides = emptyMap()), systemDark = false))
    }
}
