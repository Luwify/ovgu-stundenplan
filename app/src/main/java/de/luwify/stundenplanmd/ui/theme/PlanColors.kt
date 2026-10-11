package de.luwify.stundenplanmd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** App colors that Material's scheme has no role for. */
data class PlanColors(
    /** Border and badge of changed and extra appointments (amber), 3:1 on the page. */
    val highlight: Color,
    val onHighlight: Color,
)

@Composable
fun planColors(): PlanColors {
    val page = MaterialTheme.colorScheme.background
    return remember(page) { planColorsFor(page) }
}

fun planColorsFor(page: Color): PlanColors {
    val dark = page.luminance() < 0.18f
    val highlight = (if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)).withContrast(3.0, listOf(page))
    val on = (if (dark) Color(0xFF2B1700) else Color.White).withContrast(4.5, listOf(highlight))
    return PlanColors(highlight, on)
}
