package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Long-press-and-drag reordering for a vertical LazyColumn whose item keys are [key] (the friends in the drawer),
 * after Mensa MD's tab reorder. While dragging only the local [order] changes; [onReorder] gets the final key list
 * once, when the finger lifts (and only if it changed). The list auto-scrolls near its edges.
 *
 * Usage: `LazyColumn(state, Modifier.then(reorder.gestures), userScrollEnabled = !reorder.isDragging)`, iterate
 * [order], and give each item `Modifier.reorderItem(this, reorder, key)`.
 */
@Stable
class ColumnReorder<T> internal constructor(
    private val listState: LazyListState,
    private val key: (T) -> String,
) {
    var order: List<T> by mutableStateOf(emptyList())
        internal set
    var draggedKey: String? by mutableStateOf(null)
        internal set
    val isDragging: Boolean get() = draggedKey != null

    internal var dragStart by mutableFloatStateOf(0f) // dragged item's top edge at drag start, in list coordinates
    internal var dragDelta by mutableFloatStateOf(0f) // finger movement since drag start
    internal var pointerY by mutableFloatStateOf(0f)
    internal lateinit var gesturesModifier: Modifier

    /** Goes on the LazyColumn. */
    val gestures: Modifier get() = gesturesModifier

    /** Swap once the dragged item's center passes the center of a neighbor: no flip-flopping. */
    internal fun maybeSwap() {
        val id = draggedKey ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val info = items.firstOrNull { it.key == id } ?: return
        val center = dragStart + dragDelta + info.size / 2f
        val target = items.firstOrNull { it.key != id && center >= it.offset && center <= it.offset + it.size } ?: return
        val from = order.indexOfFirst { key(it) == id }
        val to = order.indexOfFirst { key(it) == target.key }
        if (from < 0 || to < 0) return
        val targetCenter = target.offset + target.size / 2f
        if ((to > from && center > targetCenter) || (to < from && center < targetCenter)) {
            order = order.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    internal fun draggedTranslation(itemKey: String): Float {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
        return if (info != null) dragStart + dragDelta - info.offset else 0f
    }
}

@Composable
fun <T> rememberColumnReorder(
    items: List<T>,
    listState: LazyListState,
    key: (T) -> String,
    onReorder: (List<String>) -> Unit,
): ColumnReorder<T> {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val latestItems by rememberUpdatedState(items)
    val latestOnReorder by rememberUpdatedState(onReorder)
    val state = remember(listState) { ColumnReorder(listState, key).also { it.order = items } }

    // Follow committed changes (our own commit coming back from storage, new items).
    LaunchedEffect(items) { if (state.draggedKey == null) state.order = items }

    fun endDrag(commit: Boolean) {
        val keys = state.order.map(key)
        state.draggedKey = null
        if (commit && keys != latestItems.map(key)) latestOnReorder(keys) else state.order = latestItems
    }

    // Auto-scroll while the finger sits near an edge.
    LaunchedEffect(state.draggedKey) {
        if (state.draggedKey == null) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val height = listState.layoutInfo.viewportSize.height
            val y = state.pointerY
            val step = when {
                y < edge -> -(edge - y).coerceAtMost(edge) / edge * maxStep
                y > height - edge -> (y - (height - edge)).coerceAtMost(edge) / edge * maxStep
                else -> 0f
            }
            if (step != 0f) {
                listState.scrollBy(step)
                state.maybeSwap()
            }
        }
    }

    state.gesturesModifier = Modifier.pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val item = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { offset.y >= it.offset && offset.y <= it.offset + it.size }
                if (item != null) {
                    state.dragStart = item.offset.toFloat()
                    state.dragDelta = 0f
                    state.pointerY = offset.y
                    state.draggedKey = item.key as? String
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDrag = { change, amount ->
                if (state.draggedKey != null) {
                    change.consume()
                    state.dragDelta += amount.y
                    state.pointerY = change.position.y
                    state.maybeSwap()
                }
            },
            onDragEnd = { endDrag(commit = true) },
            onDragCancel = { endDrag(commit = false) },
        )
    }
    return state
}

/**
 * For each item of a [ColumnReorder] list ([scope] is the item's LazyItemScope): the dragged item is lifted and follows
 * the finger, the others animate into their new places.
 */
fun Modifier.reorderItem(scope: LazyItemScope, reorder: ColumnReorder<*>, itemKey: String): Modifier =
    if (itemKey == reorder.draggedKey) {
        this.zIndex(1f).graphicsLayer {
            // Follow the finger regardless of where the list has laid the item out.
            translationY = reorder.draggedTranslation(itemKey)
            scaleX = 1.03f
            scaleY = 1.03f
        }
    } else {
        with(scope) { this@reorderItem.animateItem() }
    }
