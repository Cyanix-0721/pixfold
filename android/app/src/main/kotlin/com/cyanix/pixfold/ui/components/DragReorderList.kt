package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.model.SourceItem

/** 列表容器的 tag;与工作流 A 页面的 TAG_LIST 取同一值,便于测试定位。 */
const val TAG_LIST_CONTAINER = "view-list"

/**
 * 列表的 `contentPadding`。**必须只有这一处** —— 它同时喂给 [LazyColumn] 与命中几何换算
 * [layoutSlotBounds],两处若各写一遍就会漂移,直接后果是"指针明明在行里却命不中"。
 */
private val ListContentPadding = PaddingValues(vertical = 4.dp)

/**
 * 可拖拽排序的缩略图列表(验收清单第 4 项)。
 *
 * 与 [DragReorderGrid] **同一套语义**(详见该文件 KDoc):
 *  - 整行可拖,长按启动,无需拖拽把手;
 *  - 目标行由**父容器**用各行 `boundsInRoot` 判定(行自身看不到兄弟节点);
 *  - **实时让位**(用户 2026-10-14 裁决,推翻早先的「松手落地」):拖动中其他行
 *    立刻让出位置,被拖的行落在新位置上(半透明占位)。界面实时变,**数据只在
 *    松手时提交一次**([onMoveTo] 在拖动中一次都不调)。
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
    // 命中判定用的行几何**每次由 layoutInfo 现算**(见 [layoutSlotBounds] 与
    // [DragReorderGrid] 同类缺陷记录):item 节点读到的几何会被 `animateItem`
    // 的飞行中位移污染,导致边缘抽搐(用户 2026-10-15 真机反馈)。
    // 拖动中按 **id** 记住"手里是哪一项"(实时重排会不断改下标,记下标易失同步),
    // 以及拖动期间的**本地展示顺序**(null = 未拖动)。分工与 [DragReorderGrid] 一致:
    // **界面实时变,数据只在松手时提交一次**。
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragItems by remember { mutableStateOf<List<SourceItem>?>(null) }
    // 浮层的锚点:容器自身的左上角根坐标,用于把指针的根坐标换算成容器内局部坐标。
    var pointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var containerInRoot by remember { mutableStateOf(Offset.Zero) }
    // 边缘自动滚动需要的容器高度(px)与连续触边事件数(第 18 条)。
    var containerHeight by remember { mutableFloatStateOf(0f) }
    // 容器宽度:与高度一起判断"指针是否已拖出容器"(理由见 [DragReorderGrid])。
    var containerWidth by remember { mutableFloatStateOf(0f) }
    var edgeBurst by remember { mutableIntStateOf(0) }
    // 浮层尺寸在起拖瞬间定格:源行滚出视口后被销毁,再取 layoutInfo 会拿到空值。
    var dragSize by remember { mutableStateOf(Size.Zero) }

    // 命中几何的换算参数与取值函数（放在状态声明**之后**：Kotlin 局部函数只能引用
    // 在它之前声明的局部变量）。列表行的 `size` 是**整数高度**、没有宽度,
    // 故宽度一律取容器宽(行总是占满整行)。
    val listDensity = LocalDensity.current
    val listLayoutDirection = LocalLayoutDirection.current
    val listPadLeftPx = with(listDensity) { ListContentPadding.calculateLeftPadding(listLayoutDirection).toPx() }
    val listPadTopPx = with(listDensity) { ListContentPadding.calculateTopPadding().toPx() }
    val listHeaderOffset = if (header != null) 1 else 0
    fun currentSlotBounds(): Map<Int, Rect> = layoutSlotBounds(
        visible = listSlotInfos(listState.layoutInfo.visibleItemsInfo, containerWidth),
        containerTopLeft = containerInRoot,
        padLeft = listPadLeftPx,
        padTop = listPadTopPx,
        headerOffset = listHeaderOffset,
    )

    Box(
        modifier = modifier
            .onGloballyPositioned {
                containerInRoot = it.boundsInRoot().topLeft
                containerHeight = it.size.height.toFloat()
                containerWidth = it.size.width.toFloat()
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        // 起点按**包含**判定:长按空白处不该起拖。
                        val root = containerInRoot + local
                        val bounds = currentSlotBounds()
                        val index = findContainedSlotAt(root, bounds)
                        if (index != null && index < currentItems.size) {
                                dragStartIndex = index
                            dragItems = currentItems
                            draggingId = currentItems[index].id
                            pointerInRoot = root
                            dragSize = bounds[index]?.size ?: Size.Zero
                            edgeBurst = 0
                        }
                    },
                    onDrag = { change, _ ->
                        if (draggingId != null) {
                            change.consume()
                            pointerInRoot = containerInRoot + change.position
                            // 实时让位:容器**内部**用严格包含判定(收敛性关键,见 [DragReorderGrid] KDoc);
                            // 落在行间缝隙时返回 null -> 保持原目标不动。
                            // 拖到容器**外**时兜底到最近的行 —— 否则贴边自动滚动期间让位停摆。
                            val hit = resolveReflowTarget(
                                point = pointerInRoot,
                                containerTopLeft = containerInRoot,
                                containerWidth = containerWidth,
                                containerHeight = containerHeight,
                                bounds = currentSlotBounds(),
                            )
                            val list = dragItems
                            if (hit != null && list != null) {
                                val from = list.indexOfFirst { it.id == draggingId }
                                if (from >= 0 && hit != from && hit < list.size) {
                                    val moved = list.toMutableList()
                                    val item = moved.removeAt(from)
                                    moved.add(hit, item)
                                    dragItems = moved
                                }
                            }
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
                        val id = draggingId
                        val finalIndex = dragItems?.indexOfFirst { it.id == id } ?: -1
                        draggingId = null
                        dragItems = null
                        edgeBurst = 0
                        // **数据只在松手时提交一次**:拖动中一次都没提交过,故领域层的
                        // manualIds 只增长一次、撤销记录里也只有一个动作(见文件 KDoc)。
                        if (id != null && finalIndex >= 0 && finalIndex != dragStartIndex) {
                            currentOnMoveTo(id, finalIndex)
                        }
                    },
                    onDragCancel = {
                        draggingId = null
                        dragItems = null
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
            contentPadding = ListContentPadding,
            // 拖动期间交出滚动权(见 [DragReorderGrid] KDoc):否则 LazyColumn
            // 作为后代先拿到事件,会把拖拽抢成 cancel。
            userScrollEnabled = draggingId == null,
        ) {
            // 头部区块(排序规则编辑器)。理由与 [DragReorderGrid] 完全一致:
            // 独立 item、不参与 itemsIndexed,保证 `onClick(index)` / 命中几何的索引
            // 的**逻辑索引**不被前导项污染。
            if (header != null) {
                item(key = DRAG_HEADER_ITEM_KEY) { header() }
            }
            // 渲染源 = 拖动中的本地顺序(实时让位),否则入参顺序。
            val displayItems = dragItems ?: items
            // ⚠️ 过渡动画**只在拖动期间挂载**,与 [DragReorderGrid] 同因同法:
            // 拖动中项目集合恒定(只有顺序变);非拖动重排时 `animateItem` 会把移出视线的项
            // 留在组合树里并量成 0 尺寸,污染取 `min()` / 最小 top 的既有几何断言。
            val reflowAnimating = draggingId != null
            itemsIndexed(displayItems, key = { _, it -> it.id }) { index, item ->
                Box(
                    modifier = Modifier
                        // 实时让位的**过渡**,与 [DragReorderGrid] 同源同规格:
                        // 规格与选型理由见 [DragReorderPlacementSpec]。
                        // 前提是 **key 稳定**;淡入淡出置 null 的理由同 Grid。
                        .then(
                            if (reflowAnimating) {
                                Modifier.animateItem(
                                    fadeInSpec = null,
                                    placementSpec = DragReorderPlacementSpec,
                                    fadeOutSpec = null,
                                )
                            } else {
                                Modifier
                            },
                        )
                ) {
                    ThumbRow(
                        item = item,
                        isPinned = isPinned(item.id),
                        isDragging = draggingId == item.id,
                        onClick = { onClick(index) },
                        onPin = { onPin(item.id) },
                    )
                }
            }
        }

        // 跟手浮层:拖动中手里的那一行跟着指针走。只画不参与命中判定。
        val ghostId = draggingId
        if (ghostId != null) {
            (dragItems ?: items).firstOrNull { it.id == ghostId }?.let { ghostItem ->
                DragGhost(
                    item = ghostItem,
                    pointerInRoot = pointerInRoot,
                    containerInRoot = containerInRoot,
                    sizeInRoot = dragSize,
                    style = DragGhostStyle.Row,
                )
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

/** 把 `LazyColumn` 的可见项映射成 [SlotInfo](换算逻辑与网格共用,见 [layoutSlotBounds])。 */
internal fun listSlotInfos(visible: List<LazyListItemInfo>, containerWidth: Float): List<SlotInfo> =
    visible.map {
        SlotInfo(
            index = it.index,
            offset = Offset(0f, it.offset.toFloat()),
            // ⚠️ `LazyListItemInfo.size` 是 Int(**行高**),不是 IntSize —— 列表行没有独立宽度,
            // 一律取容器宽度(行总是占满整行)。
            size = Size(containerWidth, it.size.toFloat()),
        )
    }
