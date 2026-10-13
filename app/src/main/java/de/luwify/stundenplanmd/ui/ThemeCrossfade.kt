package de.luwify.stundenplanmd.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * Fades from the old look to the new one when [key] (theme mode, light/dark) changes. The screen is always drawn through a
 * graphics layer; on a change that layer stops updating and keeps the last frame of the old theme, which is drawn over the
 * new theme and fades out. Nothing is recomposed while it fades (animating the colors themselves recomposes the whole
 * app every frame). With [enabled] false the change is a cut.
 */
@Composable
fun ThemeCrossfade(key: Any, enabled: Boolean, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val old = remember { Animatable(0f) }
    val state = remember { object { var key = key; var frozen = false } }
    var changes by remember { mutableIntStateOf(0) }
    if (state.key != key) {
        // While composing the new theme: from here on the layer keeps what the old theme drew last.
        state.key = key
        if (enabled) state.frozen = true
        changes++
    }
    LaunchedEffect(changes) {
        if (!state.frozen) return@LaunchedEffect
        old.snapTo(1f)
        old.animateTo(0f, tween(Motion.Long, easing = Motion.Standard))
        state.frozen = false
    }
    Box(
        Modifier.drawWithContent {
            if (state.frozen) {
                drawContent()
                layer.alpha = old.value
                drawLayer(layer)
            } else {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
        },
    ) { content() }
}
