package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.SourceItem

/** 网格容器 tag;与工作流 A 页面的 TAG_GRID 取同一值,便于测试定位。 */
const val TAG_GRID_CONTAINER = "view-grid"



/**
 * 可拖拽排序的缩略图网格(**拖拽自研**)。
 *
 * 目标格判定由**父容器**负责:卡片只上报指针位置,这里用各格的实际
 * [boundsInRoot] 命中最接近的格子 —— 卡片自身看不到兄弟节点,无法自己算目标。
 *
 * **松手落地**:拖动中只更新 [hoveredIndex] 用于高亮,[onMoveTo] 只在抬起时回调一次。
 * 这避免了"悬停实时重排 → 指针微动反复换位"(HANGOFF §12.5 结论 2)。
 */
@Composable
fun DragReorderGrid(
    items: List<SourceItem>,
    onMoveTo: (String, Int) -> Unit,
    onPin: (String) -> Unit,
    isPinned: (String) -> Boolean,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    onScrollToTopClick: (() -> Unit)? = null,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var pointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .testTag(TAG_GRID_CONTAINER),
            contentPadding = PaddingValues(8.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .onGloballyPositioned { coords ->
                            slotBounds[index] = coords.boundsInRoot()
                        },
                ) {
                    DraggableThumbCard(
                        item = item,
                        isPinned = isPinned(item.id),
                        isHovered = hoveredIndex != null && hoveredIndex == index && draggingIndex != index,
                        isDragging = draggingIndex == index,
                        onClick = { onClick(index) },
                        onPin = { onPin(item.id) },
                        onDragStart = { localOffset ->
                            draggingIndex = index
                            // 本地坐标 -> 根坐标,便于与各格 bounds 比较
                            val b = slotBounds[index]
                            pointerInRoot = if (b != null) b.topLeft + localOffset else localOffset
                            hoveredIndex = null
                        },
                        onDrag = { position ->
                            val b = slotBounds[index]
                            pointerInRoot = if (b != null) b.topLeft + position else position
                            hoveredIndex = findSlotAt(pointerInRoot, slotBounds)
                        },
                        onDragEnd = {
                            val from = draggingIndex
                            val target = hoveredIndex
                            draggingIndex = null
                            hoveredIndex = null
                            // 松手才落地;拖到自己格/无目标 -> 不提交
                            if (from != null && target != null && target != from) {
                                onMoveTo(items[from].id, target)
                            }
                        },
                        onDragCancel = {
                            draggingIndex = null
                            hoveredIndex = null
                        },
                    )
                }
            }
        }

        // W2:下滑后显示回顶按钮(右下角)。判据同时要求"下方还有内容"与"确实下滑过",
        // 以免内容不足一屏或已在顶部时误显。
        // 判据用 canScrollBackward(**上方还有内容** = 不在顶部)。
        // 注意:不能用 canScrollForward —— 它表示"下方还有内容",**滑到底部时恰为 false**,
        // 会导致最需要回顶时按钮消失(2026-09-17 用户指出的缺陷)。
        val showTop by remember {
            derivedStateOf { gridState.canScrollBackward }
        }
        ScrollToTopButton(
            visible = showTop,
            onClick = {
                onScrollToTopClick?.invoke()
                scope.launch { gridState.animateScrollToItem(0) }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/** 在已知各格 bounds 中找出包含该点的下标;找不到则取**最近格中心**(容错)。 */
internal fun findSlotAt(point: Offset, bounds: Map<Int, Rect>): Int? {
    bounds.entries.firstOrNull { (_, r) -> r.contains(point) }?.let { return it.key }
    if (bounds.isEmpty()) return null
    return bounds.entries.minByOrNull { (_, r) ->
        val dx = point.x - r.center.x
        val dy = point.y - r.center.y
        dx * dx + dy * dy
    }?.key
}
