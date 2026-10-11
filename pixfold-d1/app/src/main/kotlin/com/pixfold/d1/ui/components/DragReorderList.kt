package com.pixfold.d1.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
 *
 * **手势挂在容器上,不挂在行上**:理由与网格完全相同(HANGOFF §11 第 18 条)——
 * LazyColumn 会在行滚出视口时销毁它,挂在行上的手势随之被取消,跨视口拖拽失败;
 * 且拖动期间必须 `userScrollEnabled = false` 让 LazyColumn 不与容器争手势,
 * 滚动改由 [edgeAutoScrollStep] 程序化驱动。详情见 [DragReorderGrid] 的 KDoc。
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
    scrollToTopSignal: Int = 0,
    header: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    // 手势块只随容器创建一次,里面读到的参数会是首帧的值 -> 用最新值快照。
    val currentItems by rememberUpdatedState(items)
    val currentOnMoveTo by rememberUpdatedState(onMoveTo)

    // 外部驱动的回顶(W4:排序规则变更后自动回顶,用户 2026-09-17 指定)。
    //
    // 三个必须同时满足的点(每一条都有回归测试/失败教训):
    // 1) **只在 signal 变化时**归位 —— 若每次重组都请求,规则变更后用户往下滚时
    //    会被无关重组反复拽回顶部(已由 `after a rule change user can still scroll freely` 守护)。
    // 2) **在同一布局内生效** —— 用 requestScrollToItem 而不是 LaunchedEffect+scrollToItem。
    //    重排发生在组合/布局阶段,LaunchedEffect 在**布局之后**才跑,
    //    中间存在一帧"新顺序 + 旧滚动偏移" -> 视口顶部露出上一行卡片的**下半截**,
    //    即用户反馈的"顶部卡片上方像是还有卡片(仅底部)的半透明遮罩"。
    // 3) **瞬时**无动画 —— animateScrollToItem 会在刚重排的列表上逐项动画滚动,
    //    真机表现为"滑动卡一下"(用户 2026-09-17 反馈)。
    val handledSignal = remember { intArrayOf(-1) }
    if (scrollToTopSignal != handledSignal[0]) {
        handledSignal[0] = scrollToTopSignal
        if (scrollToTopSignal > 0) listState.requestScrollToItem(0)
    }
    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var pointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    // 浮层的锚点:容器自身的左上角根坐标,用于把指针的根坐标换算成容器内局部坐标。
    var containerInRoot by remember { mutableStateOf(Offset.Zero) }
    // 边缘自动滚动需要的容器高度(px)与连续触边事件数(第 18 条)。
    var containerHeight by remember { mutableFloatStateOf(0f) }
    var edgeBurst by remember { mutableIntStateOf(0) }
    // 浮层尺寸在起拖瞬间定格:源行滚出视口后被销毁,再取 slotBounds 会拿到过期值。
    var dragSize by remember { mutableStateOf(Size.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned {
                containerInRoot = it.boundsInRoot().topLeft
                containerHeight = it.size.height.toFloat()
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        // 起点按**包含**判定:长按空白处不该起拖。
                        val root = containerInRoot + local
                        val index = findContainedSlotAt(root, slotBounds)
                        if (index != null) {
                            draggingIndex = index
                            pointerInRoot = root
                            hoveredIndex = null
                            dragSize = slotBounds[index]?.size ?: Size.Zero
                            edgeBurst = 0
                        }
                    },
                    onDrag = { change, _ ->
                        val from = draggingIndex
                        if (from != null) {
                            change.consume()
                            pointerInRoot = containerInRoot + change.position
                            hoveredIndex = findSlotAt(pointerInRoot, slotBounds)
                            // 第 18 条:指针贴边时带动列表滚动,否则跨视口拖拽物理上不可能。
                            val step = edgeAutoScrollStep(
                                pointerYInRoot = pointerInRoot.y,
                                containerTopInRoot = containerInRoot.y,
                                containerHeight = containerHeight,
                                density = density,
                                burst = edgeBurst,
                            )
                            if (step == 0f) {
                                edgeBurst = 0
                            } else {
                                edgeBurst++
                                scope.launch { listState.scrollBy(step) }
                            }
                        }
                    },
                    onDragEnd = {
                        val from = draggingIndex
                        val target = hoveredIndex
                        draggingIndex = null
                        hoveredIndex = null
                        edgeBurst = 0
                        // 松手才落地;拖到自己行/无目标 -> 不提交
                        if (from != null && target != null && target != from) {
                            currentOnMoveTo(currentItems[from].id, target)
                        }
                    },
                    onDragCancel = {
                        draggingIndex = null
                        hoveredIndex = null
                        edgeBurst = 0
                    },
                )
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(TAG_LIST_CONTAINER),
            contentPadding = PaddingValues(vertical = 4.dp),
            // 拖动期间交出滚动权(见 [DragReorderGrid] KDoc):否则 LazyColumn
            // 作为后代先拿到事件,会把拖拽抢成 cancel。
            userScrollEnabled = draggingIndex == null,
        ) {
            // 头部区块(排序规则编辑器)。理由与 [DragReorderGrid] 完全一致:
            // 独立 item、不参与 itemsIndexed,保证 `onClick(index)` / `slotBounds[index]`
            // 的**逻辑索引**不被前导项污染。
            if (header != null) {
                item(key = DRAG_HEADER_ITEM_KEY) { header() }
            }
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                // 滚出视口的行被销毁时清掉它的过期坐标,免得拖拽起点命中错误的行。
                val latestIndex by rememberUpdatedState(index)
                DisposableEffect(item.id) {
                    onDispose { slotBounds.remove(latestIndex) }
                }
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
                    )
                }
            }
        }

        // 跟手浮层(HANGOFF §11 第 9 条):拖动中手里的那一行跟着指针走。
        // 只画不参与命中判定,故不与"松手落地"冲突。
        val dragFrom = draggingIndex
        if (dragFrom != null && currentItems.size > dragFrom) {
            DragGhost(
                item = currentItems[dragFrom],
                pointerInRoot = pointerInRoot,
                containerInRoot = containerInRoot,
                sizeInRoot = dragSize,
                style = DragGhostStyle.Row,
            )
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
