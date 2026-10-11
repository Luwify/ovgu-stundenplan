package de.luwify.stundenplanmd.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.data.settings.ColorRole
import de.luwify.stundenplanmd.data.settings.CustomThemeSpec
import de.luwify.stundenplanmd.ui.theme.LocalPlanColors

/** One editable color: [role] null is the accent (stored in the spec itself, not as an override). */
private class ColorEntry(val role: ColorRole?, @StringRes val title: Int, @StringRes val hint: Int?)

private val GENERAL = listOf(
    ColorEntry(null, R.string.color_accent, R.string.color_accent_hint),
    ColorEntry(ColorRole.BACKGROUND, R.string.color_background, null),
    ColorEntry(ColorRole.CARD, R.string.color_card, null),
    ColorEntry(ColorRole.TEXT, R.string.color_text, null),
)

private val CARDS = listOf(
    ColorEntry(ColorRole.CARD_BORDER, R.string.color_card_border, R.string.color_card_border_hint),
    ColorEntry(ColorRole.HIGHLIGHT, R.string.color_highlight, R.string.color_highlight_hint),
)

private fun Color.rgb() = toArgb() and 0xFFFFFF

/**
 * The advanced color editor of the custom theme ("Farbeditor", from the settings page): every color the theme
 * generates, one row each with its current color; tap opens [ColorPickerDialog]. Shown while the custom theme is
 * active, so the swatches show the real, live colors (after the readability nudges).
 */
@Composable
fun ColorEditorScreen(spec: CustomThemeSpec, onChange: (CustomThemeSpec) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scheme = MaterialTheme.colorScheme
    val plan = LocalPlanColors.current
    fun effective(role: ColorRole?): Color = when (role) {
        null -> scheme.primary
        ColorRole.BACKGROUND -> scheme.background
        ColorRole.CARD -> plan.cardContainer
        ColorRole.TEXT -> scheme.onSurface
        ColorRole.CARD_BORDER -> plan.cardBorder
        ColorRole.HIGHLIGHT -> plan.highlight
    }
    fun requested(role: ColorRole?): Int? = if (role == null) spec.accent else spec.overrides[role]

    var editing by rememberSaveable { mutableStateOf<String?>(null) } // role key, "accent", or null
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(R.string.color_editor_title), style = MaterialTheme.typography.titleLarge)
            }
            LazyColumn(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom + 24.dp),
            ) {
                for ((titleRes, entries) in listOf(R.string.color_section_general to GENERAL, R.string.color_section_cards to CARDS)) {
                    item(key = "section-$titleRes") { SectionHeading(stringResource(titleRes)) }
                    for (entry in entries) {
                        item(key = entry.role?.key ?: "accent") {
                            ColorRow(entry, effective(entry.role), requested(entry.role), isAccent = entry.role == null) {
                                editing = entry.role?.key ?: "accent"
                            }
                        }
                    }
                }
                if (spec.overrides.isNotEmpty()) {
                    item(key = "reset") {
                        TextButton(onClick = { onChange(spec.copy(overrides = emptyMap())) }, modifier = Modifier.padding(top = 12.dp)) {
                            Text(stringResource(R.string.color_reset_all))
                        }
                    }
                }
            }
        }
    }

    editing?.let { key ->
        val role = ColorRole.fromKey(key)
        val entry = (GENERAL + CARDS).first { it.role == role }
        ColorPickerDialog(
            title = stringResource(entry.title),
            initial = effective(role).rgb(),
            onAuto = role?.let {
                {
                    onChange(spec.copy(overrides = spec.overrides - it))
                    editing = null
                }
            },
            onDismiss = { editing = null },
            onPick = { rgb ->
                onChange(if (role == null) spec.copy(accent = rgb) else spec.copy(overrides = spec.overrides + (role to rgb)))
                editing = null
            },
        )
    }
}

/** Title, what it is / its state, and the live color on the right. */
@Composable
private fun ColorRow(entry: ColorEntry, effective: Color, requested: Int?, isAccent: Boolean, onClick: () -> Unit) {
    val state = when {
        requested == null -> stringResource(R.string.color_auto)
        requested != effective.rgb() -> stringResource(R.string.color_adjusted, ColorInput.formatHex(requested))
        isAccent -> null
        else -> ColorInput.formatHex(requested)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(entry.title), style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(entry.hint?.let { stringResource(it) }, state).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier
                .size(36.dp)
                .background(effective, RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        )
    }
}
