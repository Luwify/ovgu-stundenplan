package de.luwify.stundenplanmd.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luwify.stundenplanmd.R

/** A menu entry of an action ("Ausblenden" choices, "Mehr"). */
class MenuEntry(val label: String, val onClick: () -> Unit)

/**
 * One slot of the detail sheet's action row: icon + one short [label]; [description] is the full name (TalkBack, and
 * the menu entry when the action ends up under "Mehr"). With [menu] the slot opens that menu instead of [onClick].
 * [selected]: a toggle that is on (the icon sits on a filled pill).
 */
class SheetAction(
    val icon: ImageVector,
    val label: String,
    val description: String,
    val selected: Boolean = false,
    val menu: List<MenuEntry>? = null,
    val onClick: () -> Unit = {},
)

/** Columns of the action row: primary actions + "Mehr". Always this many, so the row keeps its layout. */
private const val ActionSlots = 4

/**
 * The detail sheet's actions in one row of equal columns (like a map's place sheet). [primary] fills at most
 * [ActionSlots] - 1 columns; the rest and [more] go into the last column's menu ("Mehr", left out when empty). Fewer
 * columns are centered at the same width. The height depends only on the font scale, never on the labels: a label
 * that doesn't fit its column shrinks instead of wrapping.
 */
@Composable
fun SheetActionRow(primary: List<SheetAction>, more: List<MenuEntry>, modifier: Modifier = Modifier) {
    val overflow = primary.drop(ActionSlots - 1).map { MenuEntry(it.description, it.onClick) } + more
    val slots = primary.take(ActionSlots - 1) + listOfNotNull(
        SheetAction(MoreIcon, stringResource(R.string.action_more), stringResource(R.string.action_more_long), menu = overflow)
            .takeIf { overflow.isNotEmpty() },
    )
    val style = MaterialTheme.typography.labelMedium
    val labelHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    // 6 above, 32 icon pill, 4, label, 8 below: never below 48 dp, whatever the font scale.
    Row(modifier.fillMaxWidth().height(50.dp + labelHeight)) {
        val gap = ActionSlots - slots.size
        if (gap > 0) Spacer(Modifier.weight(gap / 2f))
        slots.forEach { ActionSlot(it, style, Modifier.weight(1f)) }
        if (gap > 0) Spacer(Modifier.weight(gap / 2f))
    }
}

@Composable
private fun ActionSlot(a: SheetAction, style: TextStyle, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { if (a.menu != null) open = true else a.onClick() }
                .semantics { contentDescription = a.description }
                .padding(start = 2.dp, top = PillTop, end = 2.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Filled while on (a toggle) or while its menu is open (the menu grows out of the lit pill).
            val lit = a.selected || open
            Box(
                Modifier
                    .clearAndSetSemantics {}
                    .height(PillHeight)
                    .width(56.dp)
                    .clip(CircleShape)
                    .background(if (lit) scheme.secondaryContainer else scheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(a.icon, null, Modifier.size(22.dp), tint = if (lit) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                a.label,
                Modifier.fillMaxWidth().clearAndSetSemantics {},
                style = style,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
            )
        }
        if (a.menu != null) GrowingMenu(open, a.icon, a.menu) { open = false }
    }
}

/** Space above a slot's icon pill, and the pill's height. */
private val PillTop = 6.dp
private val PillHeight = 32.dp

/** Space kept between the menu and the window's sides. */
private val MenuMargin = 12.dp

/** A thin line between menu entries: tells them apart without more space. */
@Composable
private fun EntryDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * A slot's menu: it grows upward out of the slot's pill, which stays where it is (lit, with its icon) at the bottom of
 * the panel: rect, corners and tone move from the pill to the panel, the entries fade in towards the end. Opening on a
 * bouncy spring (it overshoots a little and settles), closing without bounce.
 */
@Composable
private fun GrowingMenu(open: Boolean, icon: ImageVector, entries: List<MenuEntry>, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val animate = animationsEnabled()
    val progress = remember { Animatable(0f) }
    var present by remember { mutableStateOf(false) }
    if (open) present = true
    LaunchedEffect(open) {
        progress.animateTo(
            if (open) 1f else 0f,
            when {
                !animate -> snap()
                open -> spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
                else -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            },
        )
        if (!open) present = false
    }
    // The slot and the popup in screen coordinates: the popup's window does not start where the sheet's does.
    val slot = remember { mutableStateOf<Rect?>(null) }
    Spacer(Modifier.fillMaxSize().onGloballyPositioned { slot.value = Rect(it.positionOnScreen(), it.size.toSize()) })
    if (!present) return
    val origin = remember { mutableStateOf<Offset?>(null) }
    Popup(popupPositionProvider = WholeWindow, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val from = scheme.secondaryContainer
        val to = scheme.surfaceContainerHighest
        val line = scheme.outlineVariant
        // Rects of this frame (px): set while laying out, read while drawing.
        val geo = remember { GrowGeometry() }
        Layout(
            content = {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .graphicsLayer { alpha = ((progress.value - 0.5f) / 0.4f).coerceIn(0f, 1f) }
                        .padding(vertical = 8.dp),
                ) {
                    entries.forEachIndexed { i, e ->
                        if (i > 0) EntryDivider()
                        DropdownMenuItem(text = { Text(e.label) }, onClick = { onDismiss(); e.onClick() })
                    }
                }
                Icon(icon, null, Modifier.size(22.dp), tint = scheme.onSecondaryContainer)
            },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin.value = it.positionOnScreen() }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .drawWithContent {
                    // The spring overshoots past 1: the rect too (the wobble); tone, outline and corners stay in range.
                    val p = progress.value
                    val q = p.coerceIn(0f, 1f)
                    val r = lerp(geo.pill, geo.panel, p)
                    val corner = androidx.compose.ui.util.lerp(geo.pill.height / 2f, 16.dp.toPx(), q).coerceAtMost(r.minDimension / 2f)
                    val shape = Path().apply { addRoundRect(RoundRect(r, CornerRadius(corner))) }
                    drawPath(shape, androidx.compose.ui.graphics.lerp(from, to, q))
                    drawPath(shape, line.copy(alpha = line.alpha * q), style = Stroke(1.dp.toPx()))
                    clipPath(shape) {
                        // The button stays: its lit pill at the bottom of the panel.
                        drawRoundRect(from, geo.pill.topLeft, geo.pill.size, CornerRadius(geo.pill.height / 2f))
                        this@drawWithContent.drawContent()
                    }
                },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val menu = measurables[0].measure(loose)
            val glyph = measurables[1].measure(loose)
            val o = origin.value
            val a = slot.value?.let { if (o == null) null else it.translate(-o) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (a == null) return@layout
                val pillW = with(density) { 56.dp.toPx() }
                val pillH = with(density) { PillHeight.toPx() }
                val pillTop = a.top + with(density) { PillTop.toPx() }
                val cx = a.center.x
                geo.pill = Rect(cx - pillW / 2, pillTop, cx + pillW / 2, pillTop + pillH)
                // The entries above the pill, centered on it where they fit; the panel holds both (the pill at its bottom).
                val margin = with(density) { MenuMargin.toPx() }
                val left = (cx - menu.width / 2f).coerceAtMost(constraints.maxWidth - margin - menu.width).coerceAtLeast(margin)
                val inset = with(density) { 4.dp.toPx() }
                val top = geo.pill.top - menu.height
                geo.panel = Rect(minOf(left, geo.pill.left - inset), top, maxOf(left + menu.width, geo.pill.right + inset), geo.pill.bottom + inset)
                menu.place(left.toInt(), top.toInt())
                glyph.place((cx - glyph.width / 2f).toInt(), (geo.pill.center.y - glyph.height / 2f).toInt())
            }
        }
    }
}

private class GrowGeometry {
    var pill = Rect.Zero
    var panel = Rect.Zero
}

/** A popup over the whole window. */
private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}
