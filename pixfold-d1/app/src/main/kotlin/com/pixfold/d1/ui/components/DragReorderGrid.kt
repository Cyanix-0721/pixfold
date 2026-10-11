package com.pixfold.d1.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.SourceItem

/** 网格容器 tag;与工作流 A 页面的 TAG_GRID 取同一值,便于测试定位。 */
const val TAG_GRID_CONTAINER = "view-grid"

/**
 * 滚动容器头部区块(排序规则编辑器)的 Lazy item key。
 *
 * 网格与列表共用同一个值:头部在两个视图之间切换时**不该被当成新项**。
 * 取这个名字是为了绝不与 [SourceItem.id](形如 `trip-0`/`scan-12`)撞车。
 *
 * 这个 key 是「折叠时不回顶」的前提:LazyList 用 `updateScrollPositionIfTheFirstItemWasMoved`
 * 按 **key** 修正锚点,折叠移除头部后,当前可见的那张图仍是同一张。
 */
const val DRAG_HEADER_ITEM_KEY = "drag-reorder-header"

/**
 * 自适应列数时每列的最小宽度(用户 2026-09-26 指定「默认选自适应」)。
 *
 * **取 80dp 的依据(2026-09-26 第二轮裁决「调窄自适应的最小卡宽,让你机器上自适应 = 4 列」)**:
 * PJX110 竖屏 361dp 宽,容器 `contentPadding` 左右各 8dp ⇒ 可用宽 `361 − 16 = 345dp`。
 * `345 / 80 = 4.31` ⇒ 自适应给出 **4 列**(用户明确要的默认观感)。
 *
 * 算式与 Compose 官方 `GridCells.Adaptive` 一致(见 [adaptiveColumnCount]):
 * `count = floor(available / minWidth)`,此处 `available` 已扣除 `contentPadding`、
 * 且本仓**没传** `Arrangement.spacedBy` ⇒ spacing = 0(格间间隙来自每格的 `padding(4.dp)`,
 * 不参与网格列宽计算)。这是 [adaptiveColumnCount] 里不出现 spacing 项的原因。
 *
 * 旧值 110dp 曾在同机上算出 3 列(`345 / 110 = 3.13`);本次按用户裁决调窄到 80dp。
 * 各档预期:361dp(竖屏,可用 345)⇒ 4 列;400dp(可用 384)⇒ 4 列;320dp(可用 304)⇒ 3 列;
 * 794dp(横屏,可用 778)⇒ 9 列。
 *
 * 这里用 `val` 而非 `const val`:`Dp` 是对象,不能作编译期常量。
 */
val ADAPTIVE_COLUMN_MIN_WIDTH: Dp = 80.dp

/** 网格容器左右 `contentPadding` 之和;自适应算式要从可用宽里扣掉它。 */
private const val GRID_CONTENT_PADDING_DP = 16

/**
 * 由容器宽度算出**自适应列数** —— 网格列数算式的**唯一事实来源**。
 *
 * 页面用 `LocalConfiguration.current.screenWidthDp` 折成 `Dp` 调它(设置页预览、
 * 排序页默认值、测试断言三处共用同一算式,才不会各算各的)。
 *
 * 算式与 Compose 官方 `GridCells.Adaptive.calculateCrossAxisCellSizes` 对齐 ——
 * 源码 `androidx/compose/foundation/lazy/grid/LazyGridDsl.kt` 为
 * `maxOf((availableSize + spacing) / (minSize.roundToPx() + spacing), 1)`,
 * 本仓 spacing = 0(见 [ADAPTIVE_COLUMN_MIN_WIDTH] 的说明),故退化为
 * `floor(available / minWidth)`,且**下限为 1**(容器再窄也至少一列,
 * 这与官方 `maxOf(…, 1)` 一致 —— 返回 0 会让 `GridCells.Fixed(0)` 直接崩)。
 *
 * ⚠️ 本函数**不是** `@Composable`,`minColumnWidth` 用 `Dp` 参与浮点除法而非
 * `roundToPx()`:手机/平板上 `minWidth` 都是整数 dp,`roundToPx()` 只是把同一
 * 整数换算成 px 再换回来,不改变比值;这样做是为了能在**纯 JVM 单测**里直接断言
 * (无需 Robolectric 的 `Density`)。刻意保留这一取舍的说明,免得后人以为漏了 `Density`。
 *
 * @param containerWidth 网格容器的实际宽度(含其 `contentPadding`)。
 * @param minColumnWidth 每列最小宽度,默认 [ADAPTIVE_COLUMN_MIN_WIDTH]。
 */
fun adaptiveColumnCount(
    containerWidth: Dp,
    minColumnWidth: Dp = ADAPTIVE_COLUMN_MIN_WIDTH,
): Int {
    val available = containerWidth.value - GRID_CONTENT_PADDING_DP
    if (minColumnWidth.value <= 0f) return 1
    return maxOf((available / minColumnWidth.value).toInt(), 1)
}




/**
 * 可拖拽排序的缩略图网格(**拖拽自研**)。
 *
 * 目标格判定由**父容器**负责:卡片只上报指针位置,这里用各格的实际
 * [boundsInRoot] 命中最接近的格子 —— 卡片自身看不到兄弟节点,无法自己算目标。
 *
 * **松手落地**:拖动中只更新 [hoveredIndex] 用于高亮,[onMoveTo] 只在抬起时回调一次。
 * 这避免了"悬停实时重排 → 指针微动反复换位"(HANGOFF §12.5 结论 2)。
 *
 * **手势挂在容器上,不挂在卡片上**(HANGOFF §11 第 18 条)。这是跨视口拖拽的
 * 必要前提:LazyGrid 会在卡片滚出视口时**销毁**它,挂在卡片上的 `pointerInput`
 * 随之被取消 —— 实测滚到第 18 格时手势已死(`onDragEnd` 再也不回调),
 * 于是"把被规则排到末尾的首图拖回来"永远失败。挂在容器上则手势与格子存活无关。
 *
 * 代价是容器(祖先)与 LazyGrid(后代)会争夺同一次手势。后代在 Main pass 先收到
 * 事件,故拖动期间必须把 LazyGrid 自己的滚动关掉([LazyVerticalGrid] 的
 * `userScrollEnabled = false`),否则它抢走手势并让拖拽以 cancel 结束。
 * 滚动改由 [edgeAutoScrollStep] 程序化驱动 —— 两者恰好互斥,不会叠加成双倍滚动。
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
    scrollToTopSignal: Int = 0,
    header: (@Composable () -> Unit)? = null,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    // 手势块只随容器创建一次(`pointerInput(Unit)`),里面读到的参数会是**首帧**
    // 的值 —— 重排后 items 变了却仍是旧列表,`onMoveTo` 也会是旧回调。
    // 用 rememberUpdatedState 拿最新值,避免"拖了却按旧顺序找源项"。
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
        if (scrollToTopSignal > 0) gridState.requestScrollToItem(0)
    }
    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var pointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    // 浮层的锚点:容器自身的左上角根坐标,用于把指针的根坐标换算成容器内局部坐标。
    var containerInRoot by remember { mutableStateOf(Offset.Zero) }
    // 边缘自动滚动需要的容器高度(px)与连续触边事件数。
    // burst 每有一个"仍在边缘带内"的拖拽事件就 +1,用于加速;离开边缘带清零。
    var containerHeight by remember { mutableFloatStateOf(0f) }
    var edgeBurst by remember { mutableIntStateOf(0) }
    // 浮层尺寸在**起拖瞬间**定格:源卡一旦滚出视口就被销毁,
    // 再去 slotBounds 里取它的尺寸会拿到过期值导致浮层变形或消失。
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
                        // 起点按**包含**判定(不许兜底到最近格):长按空白处不该起拖,
                        // 否则"点空白/长按空白"会莫名抓住一张卡片。
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
                            // change.position 已是**容器局部**坐标(手势挂在容器上),
                            // 比原先"卡片左上角 + 卡片内偏移"的换算少一层易错源。
                            pointerInRoot = containerInRoot + change.position
                            hoveredIndex = findSlotAt(pointerInRoot, slotBounds)
                            // 第 18 条:指针贴边时带动网格滚动,否则跨视口拖拽物理上不可能。
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
                                scope.launch { gridState.scrollBy(step) }
                            }
                        }
                    },
                    onDragEnd = {
                        val from = draggingIndex
                        val target = hoveredIndex
                        draggingIndex = null
                        hoveredIndex = null
                        edgeBurst = 0
                        // 松手才落地;拖到自己格/无目标 -> 不提交
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
        LazyVerticalGrid(
            state = gridState,
            // 列数由**页面**算好传进来(用户 2026-09-26 裁决:自适应值就是默认选项,
            // 排序页只高亮它)。此前这里收 `Int?` 并用 `GridCells.Adaptive` 自己算,
            // 于是"当前生效的是几列"只有本组件知道,页面无法高亮 —— 现改为
            // 页面用 [adaptiveColumnCount] 算出具体数,这里只认 [GridCells.Fixed]。
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .testTag(TAG_GRID_CONTAINER),
            contentPadding = PaddingValues(8.dp),
            // 拖动期间交出滚动权(见上方 KDoc):否则 LazyGrid 作为后代先拿到事件,
            // 会把拖拽抢成 cancel。程序化 scrollBy 不受此开关影响。
            userScrollEnabled = draggingIndex == null,
        ) {
            // 头部区块(排序规则编辑器)。
            //
            // ⚠️ 它必须是**独立 item**,绝不能并进下面的 itemsIndexed —— 页面用
            // `onClick(index)` / `slotBounds[index]` 传的是**逻辑索引**,多一个前导项
            // 会让所有格子的索引与拖拽落点整体错位。
            // `span = maxLineSpan` 让它横跨整行(否则只占第 1 列)。
            if (header != null) {
                item(key = DRAG_HEADER_ITEM_KEY, span = { GridItemSpan(maxLineSpan) }) { header() }
            }
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                // 让 slotBounds 只保留**当前已组合**的格:滚出视口的格被 Lazy 销毁后,
                // 它的旧根坐标会与滚上来的新格几乎重合,拖拽起点会命中错误的格。
                val latestIndex by rememberUpdatedState(index)
                DisposableEffect(item.id) {
                    onDispose { slotBounds.remove(latestIndex) }
                }
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
                    )
                }
            }
        }

        // 跟手浮层(HANGOFF §11 第 9 条):拖动中手里的那张图跟着指针走。
        // 只画不参与命中判定,故不与"松手落地"冲突。
        val dragFrom = draggingIndex
        if (dragFrom != null && currentItems.size > dragFrom) {
            DragGhost(
                item = currentItems[dragFrom],
                pointerInRoot = pointerInRoot,
                containerInRoot = containerInRoot,
                sizeInRoot = dragSize,
                style = DragGhostStyle.Card,
            )
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

/** 找出**包含**该点的格下标;不包含任何格时返回 null(拖拽起点判定用,不做兜底)。 */
internal fun findContainedSlotAt(point: Offset, bounds: Map<Int, Rect>): Int? =
    bounds.entries.firstOrNull { (_, r) -> r.contains(point) }?.key

/** 在已知各格 bounds 中找出包含该点的下标;找不到则取**最近格中心**(容错)。 */
internal fun findSlotAt(point: Offset, bounds: Map<Int, Rect>): Int? {
    findContainedSlotAt(point, bounds)?.let { return it }
    if (bounds.isEmpty()) return null
    return bounds.entries.minByOrNull { (_, r) ->
        val dx = point.x - r.center.x
        val dy = point.y - r.center.y
        dx * dx + dy * dy
    }?.key
}
