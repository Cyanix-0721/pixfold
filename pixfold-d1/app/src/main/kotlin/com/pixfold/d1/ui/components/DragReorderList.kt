package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.SourceItem

/** 列表容器的 tag;与工作流 A 页面的 TAG_LIST 取同一值,便于测试定位。 */
const val TAG_LIST_CONTAINER = "view-list"

/**
 * 可拖拽排序的缩略图列表(验收清单第 4 项)。
 *
 * 与 [DragReorderGrid] **同一套语义**(归档设计结论,详见 HANGOFF §12.5):
 *  - 整行可拖,长按启动,无需拖拽把手;
 *  - 目标行由**父容器**用各行 `boundsInRoot` 判定(行自身看不到兄弟节点);
 *  - **松手才落地**——拖动中只高亮目标行,`onMoveTo` 仅在抬起时回调一次。
 */
@Composable
fun DragReorderList(
    items: List<SourceItem>,
    onMoveTo: (String, Int) -> Unit,
    onPin: (String) -> Unit,
    isPinned: (String) -> Boolean,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onScrollToTopClick: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var pointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(TAG_LIST_CONTAINER),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                Box(
                    modifier = Modifier.onGloballyPositioned { coords ->
                        slotBounds[index] = coords.boundsInRoot()
                    },
                ) {
                    ThumbRow(
                        item = item,
                        isPinned = isPinned(item.id),
                        isDragging = draggingIndex == index,
                        isHovered = hoveredIndex != null && hoveredIndex == index && draggingIndex != index,
                        onClick = { onClick(index) },
                        onPin = { onPin(item.id) },
                        onDragStart = { localOffset ->
                            draggingIndex = index
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
                            // 松手才落地;拖到自己行/无目标 -> 不提交
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

        // W2:下滑后显示回顶按钮(右下角);判据同网格(下方还有内容 且 确实下滑过)
        // 同网格:判据用 canScrollBackward(不在顶部即显示),
        // 不用 canScrollForward(滑到底部会变 false,导致按钮消失)。
        val showTop by remember {
            derivedStateOf { listState.canScrollBackward }
        }
        ScrollToTopButton(
            visible = showTop,
            onClick = {
                onScrollToTopClick?.invoke()
                scope.launch { listState.animateScrollToItem(0) }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}
