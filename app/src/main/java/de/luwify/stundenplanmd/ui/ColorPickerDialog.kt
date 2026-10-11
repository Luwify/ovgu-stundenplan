package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.luwify.stundenplanmd.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun opaque(rgb: Int) = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/**
 * The color picker of the advanced editor: a hue ring around a saturation/brightness square, a row of shades of
 * the current color, and the hex value. [onAuto] (null = not offered) gives the color back to the theme.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Int,
    onAuto: (() -> Unit)?,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    // HSV is the picker's own state: going through RGB would lose the hue of grays while dragging.
    var hue by remember { mutableFloatStateOf(ColorInput.toHsv(initial).first) }
    var sat by remember { mutableFloatStateOf(ColorInput.toHsv(initial).second) }
    var value by remember { mutableFloatStateOf(ColorInput.toHsv(initial).third) }
    val rgb = ColorInput.fromHsv(hue, sat, value)
    fun set(newRgb: Int) {
        val (h, s, v) = ColorInput.toHsv(newRgb)
        if (s > 0f && v > 0f) hue = h // keep the hue the user chose when picking a gray or black
        sat = s
        value = v
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                val pickerLabel = stringResource(R.string.color_picker)
                HueRingSquare(
                    hue, sat, value,
                    onHue = { hue = it },
                    onSaturationValue = { s, v ->
                        sat = s
                        value = v
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(248.dp).semantics { contentDescription = pickerLabel },
                )
                FlowRow(
                    Modifier.align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    maxItemsInEachRow = 5,
                ) {
                    for (shade in ColorInput.shades(rgb)) Shade(shade, selected = shade == rgb) { set(shade) }
                }
                HexField(rgb, onRgb = ::set)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onAuto != null) TextButton(onClick = onAuto) { Text(stringResource(R.string.color_auto)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    TextButton(onClick = { onPick(rgb) }) { Text(stringResource(android.R.string.ok)) }
                }
            }
        }
    }
}

/**
 * The hue ring and, inside it, the square of saturation (left to right) and brightness (top to bottom). A drag
 * that starts on the ring turns the hue, one that starts in the square moves within it, even if the finger then
 * leaves it.
 */
@Composable
private fun HueRingSquare(
    hue: Float,
    sat: Float,
    value: Float,
    onHue: (Float) -> Unit,
    onSaturationValue: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnHue by rememberUpdatedState(onHue)
    val latestOnSv by rememberUpdatedState(onSaturationValue)
    val ringWidthDp = 22.dp
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val ring = ringWidthDp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)
                val outer = minOf(size.width, size.height) / 2f
                val inner = outer - ring - 8.dp.toPx()
                val side = inner * sqrt(2f) * 0.94f
                val topLeft = center - Offset(side / 2f, side / 2f)
                val down = awaitFirstDown()
                val onRing = (down.position - center).getDistance() > inner
                fun apply(p: Offset) {
                    if (onRing) {
                        latestOnHue(ColorInput.hueAt(p.x - center.x, p.y - center.y))
                    } else {
                        val (s, v) = ColorInput.saturationValueAt(p.x - topLeft.x, p.y - topLeft.y, side)
                        latestOnSv(s, v)
                    }
                }
                apply(down.position)
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    apply(change.position)
                    change.consume()
                }
            }
        },
    ) {
        val ring = ringWidthDp.toPx()
        val outer = size.minDimension / 2f
        val inner = outer - ring - 8.dp.toPx()
        val side = inner * sqrt(2f) * 0.94f
        val topLeft = center - Offset(side / 2f, side / 2f)
        val hues = (0..360 step 30).map { Color.hsv(it.toFloat().mod(360f), 1f, 1f) }
        drawCircle(Brush.sweepGradient(hues, center), radius = outer - ring / 2f, style = Stroke(ring))
        val pure = Color.hsv(hue, 1f, 1f)
        drawRect(Brush.horizontalGradient(listOf(Color.White, pure), topLeft.x, topLeft.x + side), topLeft, Size(side, side))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), topLeft.y, topLeft.y + side), topLeft, Size(side, side))

        fun thumb(at: Offset, fill: Color, radius: Float) {
            drawCircle(Color.Black.copy(alpha = 0.35f), radius + 3.dp.toPx(), at)
            drawCircle(Color.White, radius + 1.5.dp.toPx(), at)
            drawCircle(fill, radius - 1.5.dp.toPx(), at)
        }
        val angle = Math.toRadians(hue.toDouble())
        val ringAt = center + Offset((cos(angle) * (outer - ring / 2f)).toFloat(), (sin(angle) * (outer - ring / 2f)).toFloat())
        thumb(ringAt, pure, ring / 2f)
        thumb(topLeft + Offset(sat * side, (1f - value) * side), Color.hsv(hue, sat, value), 10.dp.toPx())
    }
}

/** A shade of the current color, 40dp in a 48dp touch target; the current one gets a check. */
@Composable
private fun Shade(rgb: Int, selected: Boolean, onClick: () -> Unit) {
    val color = opaque(rgb)
    val label = ColorInput.formatHex(rgb)
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(CheckIcon, contentDescription = null, tint = if (color.luminance() > 0.4f) Color.Black else Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** The current color and its hex value; a valid typed value moves the picker. */
@Composable
private fun HexField(rgb: Int, onRgb: (Int) -> Unit) {
    var hex by remember { mutableStateOf(ColorInput.formatHex(rgb)) }
    LaunchedEffect(rgb) { if (ColorInput.parseHex(hex) != rgb) hex = ColorInput.formatHex(rgb) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(opaque(rgb)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = hex,
            onValueChange = { text ->
                hex = text.take(7)
                ColorInput.parseHex(hex)?.let(onRgb)
            },
            label = { Text(stringResource(R.string.custom_hex)) },
            singleLine = true,
            isError = ColorInput.parseHex(hex) == null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
