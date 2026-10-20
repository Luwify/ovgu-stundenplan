package de.luwify.stundenplanmd.ui

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                .padding(start = 2.dp, top = 6.dp, end = 2.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .clearAndSetSemantics {}
                    .height(32.dp)
                    .width(56.dp)
                    .clip(CircleShape)
                    .background(if (a.selected) scheme.secondaryContainer else scheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(a.icon, null, Modifier.size(22.dp), tint = if (a.selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
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
        if (a.menu != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                a.menu.forEach { e -> DropdownMenuItem(text = { Text(e.label) }, onClick = { open = false; e.onClick() }) }
            }
        }
    }
}
