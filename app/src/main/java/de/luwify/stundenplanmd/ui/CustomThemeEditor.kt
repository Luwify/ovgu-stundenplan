package de.luwify.stundenplanmd.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import de.luwify.stundenplanmd.data.settings.CustomBase
import de.luwify.stundenplanmd.data.settings.CustomThemeSpec
import de.luwify.stundenplanmd.data.settings.hueToAccent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import de.luwify.stundenplanmd.ui.theme.rgbColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/** The slider track shows each hue as the old hue-only setting meant it: HSL saturation 62 %, lightness 52 %. */
private fun swatchRgb(hue: Int) = hueToAccent(hue)


/**
 * The editor for the user's own theme (shown on the settings page while "Eigenes" is selected): background
 * (radio list), accent color (a hue slider that keeps saturation and lightness, or typed as hex / RGB) and the
 * card border. [onChange] gets (spec, commit): commit false only previews (slider moving).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CustomThemeEditor(spec: CustomThemeSpec, onChange: (CustomThemeSpec, Boolean) -> Unit, onAdvanced: () -> Unit) {
    val latest by rememberUpdatedState(spec)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(R.string.custom_base)
        SettingsRadioGroup(
            options = listOf(
                CustomBase.SYSTEM to stringResource(R.string.custom_base_system),
                CustomBase.LIGHT to stringResource(R.string.custom_base_light),
                CustomBase.DARK to stringResource(R.string.custom_base_dark),
                CustomBase.BLACK to stringResource(R.string.custom_base_black),
            ),
            selected = spec.base,
            onSelect = { onChange(latest.copy(base = it), true) },
        )

        Label(R.string.custom_accent)
        var dragging by remember { mutableFloatStateOf(-1f) }
        val hueLabel = stringResource(R.string.custom_hue)
        val hue = ColorInput.hue(spec.accent)
        Slider(
            value = if (dragging >= 0f) dragging else hue.toFloat(),
            onValueChange = {
                dragging = it
                onChange(latest.copy(accent = ColorInput.withHue(latest.accent, it)), false)
            },
            onValueChangeFinished = {
                if (dragging >= 0f) onChange(latest.copy(accent = ColorInput.withHue(latest.accent, dragging)), true)
                dragging = -1f
            },
            valueRange = 0f..359f,
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Brush.horizontalGradient((0..360 step 30).map { rgbColor(swatchRgb(it)) })),
                )
            },
            modifier = Modifier.semantics {
                contentDescription = hueLabel
                stateDescription = "$hue°"
            },
        )
        ColorFields(spec.accent) { onChange(latest.copy(accent = it), true) }
        // The theme keeps text readable: say so when the accent had to be darkened/lightened for that.
        if (MaterialTheme.colorScheme.primary != rgbColor(spec.accent)) {
            Text(
                stringResource(R.string.custom_accent_adjusted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AdvancedRow(spec.overrides.size, onAdvanced)
    }
}

/**
 * The accent as text: a hex field and R/G/B fields, kept in sync. A field applies its value as soon as it is valid;
 * it is only rewritten from outside (swatch, slider, another field) when it no longer matches the color, so typing
 * is never interrupted.
 */
@Composable
private fun ColorFields(accent: Int, onAccent: (Int) -> Unit) {
    val latest by rememberUpdatedState(accent)
    var hex by remember { mutableStateOf(ColorInput.formatHex(accent)) }
    LaunchedEffect(accent) { if (ColorInput.parseHex(hex) != accent) hex = ColorInput.formatHex(accent) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(rgbColor(accent)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = hex,
            onValueChange = { text ->
                hex = text.take(7)
                ColorInput.parseHex(hex)?.let(onAccent)
            },
            label = { Text(stringResource(R.string.custom_hex)) },
            singleLine = true,
            isError = ColorInput.parseHex(hex) == null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((channel, label) in listOf(0 to R.string.custom_red, 1 to R.string.custom_green, 2 to R.string.custom_blue)) {
            val current = when (channel) {
                0 -> ColorInput.red(accent)
                1 -> ColorInput.green(accent)
                else -> ColorInput.blue(accent)
            }
            var text by remember(channel) { mutableStateOf(current.toString()) }
            LaunchedEffect(current) { if (ColorInput.parseChannel(text) != current) text = current.toString() }
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new.filter { it.isDigit() }.take(3)
                    ColorInput.parseChannel(text)?.let { onAccent(ColorInput.withChannel(latest, channel, it)) }
                },
                label = { Text(stringResource(label)) },
                singleLine = true,
                isError = ColorInput.parseChannel(text) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Label(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Opens the advanced color editor; shows how many colors are set by hand. */
@Composable
private fun AdvancedRow(customized: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.color_advanced), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (customized == 0) stringResource(R.string.color_advanced_hint)
                    else pluralStringResource(R.plurals.color_advanced_count, customized, customized),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(ChevronDownIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(-90f))
        }
    }
}
