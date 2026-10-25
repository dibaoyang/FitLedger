package com.fitledger.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive

val LocalPageScroll = staticCompositionLocalOf<ScrollState?> { null }

private data class SortDrag(val key: String, val startingKeys: List<String>, val startY: Float, val y: Float, val originalTop: Float)

/** Each independent instance reorders only its own items. Place handle() in the item's row. */
@Composable fun <T> DragSortColumn(
    items: List<T>, key: (T) -> String, busy: Boolean, onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier, spacing: Dp = 8.dp,
    content: @Composable (T, @Composable () -> Unit) -> Unit
) {
    val keys = items.map(key)
    require(keys.distinct().size == keys.size) { "排序项目编号不能重复" }
    val latestItems by rememberUpdatedState(items)
    val latestKey by rememberUpdatedState(key)
    val latestReorder by rememberUpdatedState(onReorder)
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var drag by remember { mutableStateOf<SortDrag?>(null) }
    val scroll = LocalPageScroll.current
    val view = LocalView.current
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val speed = with(LocalDensity.current) { 14.dp.toPx() }
    val markerColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(keys, busy) {
        bounds.keys.filter { it !in keys }.forEach { bounds.remove(it) }
        if (busy || drag?.startingKeys?.let { it != keys } == true) drag = null
    }
    LaunchedEffect(drag?.key, scroll, busy) {
        if (scroll == null || busy) return@LaunchedEffect
        while (isActive && drag != null) {
            withFrameNanos { }
            val current = drag ?: break
            val screen = IntArray(2); val window = IntArray(2)
            view.getLocationOnScreen(screen); view.getLocationInWindow(window)
            val visible = android.graphics.Rect()
            if (!view.getGlobalVisibleRect(visible)) continue
            val shift = screen[1] - window[1]
            val top = visible.top - shift
            val bottom = visible.bottom - shift
            val delta = when {
                current.y < top + edge -> -speed * ((top + edge - current.y) / edge).coerceIn(0f, 1f)
                current.y > bottom - edge -> speed * ((current.y - bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (delta != 0f) scroll.scrollBy(delta)
        }
    }
    val moving = drag
    val otherKeys = keys.filter { it != moving?.key }
    val before = moving?.let { d -> otherKeys.firstOrNull { bounds[it]?.center?.y?.let { center -> d.y < center } == true } }
    fun commit() {
        val current = drag ?: return
        drag = null
        val latest = latestItems
        if (latest.map(latestKey) != current.startingKeys) return
        val movingItem = latest.find { latestKey(it) == current.key } ?: return
        val rest = latest.filter { latestKey(it) != current.key }.toMutableList()
        val index = rest.indexOfFirst { bounds[latestKey(it)]?.center?.y?.let { center -> current.y < center } == true }
            .let { if (it < 0) rest.size else it }
        rest.add(index, movingItem)
        if (rest.map(latestKey) != current.startingKeys) latestReorder(rest)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.forEach { item ->
            val itemKey = key(item)
            androidx.compose.runtime.key(itemKey) {
                val isMoving = moving?.key == itemKey
                val markerTop = moving != null && before == itemKey
                val markerBottom = moving != null && before == null && otherKeys.lastOrNull() == itemKey
                Box(Modifier.fillMaxWidth().zIndex(if (isMoving) 1f else 0f)
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.localToWindow(Offset.Zero)
                        bounds[itemKey] = Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
                    }.drawWithContent {
                        drawContent()
                        if (markerTop) drawLine(markerColor, Offset.Zero, Offset(size.width, 0f), 4f)
                        if (markerBottom) drawLine(markerColor, Offset(0f, size.height), Offset(size.width, size.height), 4f)
                    }) {
                    Box(Modifier.fillMaxWidth().graphicsLayer {
                        if (isMoving && moving != null) {
                            translationY = moving.y - moving.startY + moving.originalTop - (bounds[itemKey]?.top ?: moving.originalTop)
                            alpha = 0.86f
                        }
                    }) {
                        content(item) {
                            var handleTop by remember { mutableFloatStateOf(0f) }
                            Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .onGloballyPositioned { handleTop = it.localToWindow(Offset.Zero).y }
                                .semantics {
                                    contentDescription = if (busy) "排序暂不可用" else "长按上下拖动排序"
                                    if (busy) disabled()
                                    if (!busy) customActions = listOf(-1 to "上移", 1 to "下移").map { (delta, label) ->
                                        CustomAccessibilityAction(label) {
                                            val latest = latestItems.toMutableList()
                                            val index = latest.indexOfFirst { latestKey(it) == itemKey }
                                            val target = index + delta
                                            if (index >= 0 && target in latest.indices) {
                                                latest.add(target, latest.removeAt(index)); latestReorder(latest); true
                                            } else false
                                        }
                                    }
                                }.pointerInput(itemKey, busy, keys.size) {
                                    if (!busy && keys.size > 1) detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            val top = bounds[itemKey]?.top ?: handleTop
                                            drag = SortDrag(itemKey, latestItems.map(latestKey), handleTop + offset.y, handleTop + offset.y, top)
                                        },
                                        onDrag = { change, amount -> change.consume(); drag = drag?.let { it.copy(y = it.y + amount.y) } },
                                        onDragEnd = { commit() },
                                        onDragCancel = { drag = null }
                                    )
                                }, contentAlignment = Alignment.Center) {
                                Text("⠿", color = if (busy) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
