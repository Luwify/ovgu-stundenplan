package de.luwify.stundenplanmd.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * Where the menu of a [SettingsDropdown] goes: the current value's row centered on the field, the other values
 * above and below it (like a spinner), moved as little as needed to stay between [minTop] and [maxBottom]. All
 * pixels; rows are [item] high, the list has [pad] above and below. A list taller than the space starts at [minTop]
 * and scrolls ([scroll]: the offset that puts the current row on the field as far as the list allows).
 */
internal class MenuPlacement(val top: Int, val height: Int, val scroll: Int)

internal fun menuPlacement(anchorCenter: Int, index: Int, count: Int, item: Int, pad: Int, minTop: Int, maxBottom: Int): MenuPlacement {
    val full = 2 * pad + count * item
    val selectedCenter = pad + index * item + item / 2
    val space = maxBottom - minTop
    if (full <= space) return MenuPlacement((anchorCenter - selectedCenter).coerceIn(minTop, maxBottom - full), full, 0)
    return MenuPlacement(minTop, space, (selectedCenter - (anchorCenter - minTop)).coerceIn(0, full - space))
}

private val MenuItemHeight = 48.dp
private val MenuPadding = 8.dp
private val ScreenMargin = 8.dp

/** The panel's rounded rect inside the popup while it grows. */
private class PanelShape(val rect: Rect, val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(rect, CornerRadius(corner.coerceAtMost(rect.minDimension / 2f))))
}

/**
 * A drop-down for picking one of a few settings values: a rounded tonal field (small label above the value, a
 * chevron that turns when open) and a rounded menu in the same tone that marks the current value with a check.
 * The menu opens over the field with the current value on it ([menuPlacement]) and grows up and down from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val shape = RoundedCornerShape(16.dp)
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    // The field on screen, and where its window starts (a popup's window may start elsewhere, e.g. beside a cutout).
    var field by remember { mutableStateOf(Rect.Zero) }
    var window by remember { mutableStateOf(Offset.Zero) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = shape,
            color = container,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    field = Rect(it.positionOnScreen(), it.size.toSize())
                    window = it.positionOnScreen() - it.positionInWindow()
                }
                .semantics { role = Role.DropdownList },
        ) {
            Row(
                Modifier.heightIn(min = 64.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        options.firstOrNull { it.first == selected }?.second.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Icon(ChevronDownIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(turn))
            }
        }
        if (expanded) {
            val density = LocalDensity.current
            val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
            val margin = with(density) { ScreenMargin.roundToPx() }
            val insetTop = WindowInsets.safeDrawing.getTop(density) + margin
            val insetBottom = WindowInsets.safeDrawing.getBottom(density) + margin
            val item = with(density) { MenuItemHeight.roundToPx() }
            val pad = with(density) { MenuPadding.roundToPx() }
            val bottom = window.y.roundToInt() + LocalWindowInfo.current.containerSize.height - insetBottom
            val place = remember { menuPlacement(field.center.y.roundToInt(), index, options.size, item, pad, window.y.roundToInt() + insetTop, bottom) }
            Popup(popupPositionProvider = WholeWindow, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                // Like the detail sheet's menus: the field's rect grows into the panel, up and down at once, on a bouncy
                // spring (overshoots a little and settles); the entries fade in towards the end. Closing is instant.
                var origin by remember { mutableStateOf<Offset?>(null) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { origin = it.positionOnScreen() }
                        .pointerInput(Unit) { detectTapGestures { expanded = false } },
                ) {
                    val o = origin ?: return@Box
                    val animate = animationsEnabled()
                    val progress = remember { Animatable(if (animate) 0f else 1f) }
                    LaunchedEffect(Unit) { if (animate) progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
                    val width = field.width
                    val start = Rect(0f, field.top - place.top, width, field.bottom - place.top)
                    val full = Rect(0f, 0f, width, place.height.toFloat())
                    val corner = with(density) { 16.dp.toPx() }
                    val elevation = with(density) { 6.dp.toPx() }
                    Box(
                        Modifier
                            .offset { IntOffset((field.left - o.x).roundToInt(), (place.top - o.y).roundToInt()) }
                            .width(with(density) { width.toDp() })
                            .height(with(density) { place.height.toDp() })
                            .graphicsLayer {
                                val p = progress.value
                                this.shape = PanelShape(lerp(start, full, p), corner)
                                clip = true
                                shadowElevation = elevation * p.coerceIn(0f, 1f)
                            }
                            .background(container),
                    ) {
                        val scroll = rememberScrollState()
                        LaunchedEffect(Unit) { scroll.scrollTo(place.scroll) }
                        Column(
                            Modifier
                                .verticalScroll(scroll)
                                .padding(vertical = MenuPadding),
                        ) {
                            for ((value, text) in options) {
                                val isSelected = value == selected
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    trailingIcon = {
                                        if (isSelected) {
                                            Icon(CheckIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        } else {
                                            Spacer(Modifier.width(20.dp))
                                        }
                                    },
                                    onClick = {
                                        expanded = false
                                        onSelect(value)
                                    },
                                    // The current value stays on the field; the others fade in as the panel opens.
                                    modifier = Modifier.height(MenuItemHeight).graphicsLayer {
                                        alpha = if (isSelected) 1f else ((progress.value - 0.5f) / 0.4f).coerceIn(0f, 1f)
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
