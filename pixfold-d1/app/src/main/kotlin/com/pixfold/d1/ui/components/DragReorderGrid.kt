package com.pixfold.d1.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.platform.LocalLayoutDirection
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
 * 网格的 `contentPadding`。**必须只有这一处** —— 它同时喂给
 * [LazyVerticalGrid] 与命中几何换算 [layoutSlotBounds],两处若各写一遍就会漂移,
 * 而漂移的直接后果是「指针明明在格子里却命不中」(见该函数 KDoc)。
 */
private val GridContentPadding = PaddingValues(8.dp)

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
 * **实时让位**(用户 2026-10-14 裁决,推翻此前的「松手落地」):拖动中其他卡片**立刻**
 * 让出位置,被拖的卡片就落在新位置上(半透明占位),而不是浮在别处、松手才突然跳。
 *
 * 关键分工 —— **界面实时变,数据只提交一次**:
 *  - 拖动中只重排本地展示顺序([dragItems]),`onMoveTo` 一次都不调;
 *  - 松手时按**最终索引**提交一次。
 * 为什么不能"拖动中每次移动都提交":那会让 `PageOrderState.manualIds` 被灌满、
 * 撤销记录被污染,并且指针在格边界抖动时顺序来回换位 —— 旧裁决(HANGOFF §12.5
 * 结论 2)担心的其实正是这个,而不是"重排"本身。连续多次本地重排的复合结果恒等于
 * 「从原位直接移到末位」(其余项相对顺序不变),故提交最终索引与领域层
 * `moveItemTo` 的 remove+insert 语义逐字一致。
 *
 * 格边界抖动的收敛性:目标格由**格矩形包含判定**得出(不是"离哪个卡片近"),
 * 重排后被拖卡片已占据指针所在格 ⇒ 下一次事件算出的目标 == 当前索引 ⇒ 立即稳定,
 * 不会来回换位。
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
    // 命中判定用的格子几何**每次由 layoutInfo 现算**(见 [layoutSlotBounds]),
    // 不再由 item 节点的 `onGloballyPositioned` 累积 —— 后者会被 `animateItem`
    // 的飞行中位移污染,导致边缘抽搐(用户 2026-10-15 真机反馈)。
    // 拖动中按 **id** 记住"手里是哪一项",不记下标 —— 实时重排会不断改变下标,
    // 记下标就得在每个重排点同步改写,漏一处就变成"浮层里是别人"。
    var draggingId by remember { mutableStateOf<String?>(null) }
    // 起拖时该项在**原始列表**里的下标:松手时只需比较"最终下标是否已变",
    // 变了才提交(拖回原位 -> 不提交,与领域层 moveItemTo 的 from==target 早退一致)。
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    // 拖动期间的**本地展示顺序**(null = 未拖动,直接用入参 items)。
    // 界面实时按它渲染,数据一次都不提交(见上方 KDoc 的"界面实时变、数据提交一次")。
    var dragItems by remember { mutableStateOf<List<SourceItem>?>(null) }
    var pointerInRoot by remember { mutableStateOf(Offset.Zero) }
    // 浮层的锚点:容器自身的左上角根坐标,用于把指针的根坐标换算成容器内局部坐标。
    var containerInRoot by remember { mutableStateOf(Offset.Zero) }
    // 边缘自动滚动需要的容器高度(px)与连续触边事件数。
    // burst 每有一个"仍在边缘带内"的拖拽事件就 +1,用于加速;离开边缘带清零。
    var containerHeight by remember { mutableFloatStateOf(0f) }
    // 容器宽度:与高度一起用来判断"指针是否已拖出容器"。
    // 拖出去时没有格子包含它,严格包含判定会一直返回 null ⇒ 实时让位停摆(见下方 onDrag)。
    var containerWidth by remember { mutableFloatStateOf(0f) }
    var edgeBurst by remember { mutableIntStateOf(0) }
    // 浮层尺寸在**起拖瞬间**定格:源卡一旦滚出视口就被销毁,
    // 再去 layoutInfo 里取它的尺寸会拿到空值导致浮层变形或消失。
    var dragSize by remember { mutableStateOf(Size.Zero) }

    // 命中几何的换算参数与取值函数。必须放在 `containerInRoot` 等状态**声明之后** ——
    // Kotlin 局部函数只能引用在它之前声明的局部变量。
    val gridDensity = LocalDensity.current
    val gridLayoutDirection = LocalLayoutDirection.current
    val gridPadLeftPx = with(gridDensity) { GridContentPadding.calculateLeftPadding(gridLayoutDirection).toPx() }
    val gridPadTopPx = with(gridDensity) { GridContentPadding.calculateTopPadding().toPx() }
    val gridHeaderOffset = if (header != null) 1 else 0
    fun currentSlotBounds(): Map<Int, Rect> = layoutSlotBounds(
        visible = gridSlotInfos(gridState.layoutInfo.visibleItemsInfo),
        containerTopLeft = containerInRoot,
        padLeft = gridPadLeftPx,
        padTop = gridPadTopPx,
        headerOffset = gridHeaderOffset,
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
                        // 起点按**包含**判定(不许兜底到最近格):长按空白处不该起拖,
                        // 否则"点空白/长按空白"会莫名抓住一张卡片。
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
                            // change.position 已是**容器局部**坐标(手势挂在容器上),
                            // 比原先"卡片左上角 + 卡片内偏移"的换算少一层易错源。
                            pointerInRoot = containerInRoot + change.position
                            // 实时让位:容器**内部**用严格包含判定(不是"最近的格中心")。
                            // 这是收敛性的关键 —— 重排后被拖卡片已占住指针所在格,
                            // 下一次事件算出的目标 == 它自己的格 ⇒ 立刻稳定,不会来回换位。
                            // 指针落在格子之间的缝隙时返回 null,此时**保持原目标不动**。
                            //
                            // 但指针拖到容器**外**时(真机上手指很容易越过网格下沿滑进
                            // 底部动作栏),没有任何格子包含它 ⇒ 严格包含会一直 null ⇒
                            // 贴边自动滚动还在滚、让位却完全停摆。故容器外兜底到"最近的格":
                            // 容器内仍严格,容器外退化为最近邻,两处语义各有理由。
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
                        val id = draggingId
                        val finalIndex = dragItems?.indexOfFirst { it.id == id } ?: -1
                        draggingId = null
                        dragItems = null
                        edgeBurst = 0
                        // **数据只在松手时提交一次**:按最终索引提交。拖动中一次都没提交过,
                        // 故领域层的 manualIds 只增长一次、撤销记录里也只有一个动作。
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
            contentPadding = GridContentPadding,
            // 拖动期间交出滚动权(见上方 KDoc):否则 LazyGrid 作为后代先拿到事件,
            // 会把拖拽抢成 cancel。程序化 scrollBy 不受此开关影响。
            userScrollEnabled = draggingId == null,
        ) {
            // 头部区块(排序规则编辑器)。
            //
            // ⚠️ 它必须是**独立 item**,绝不能并进下面的 itemsIndexed —— 页面用
            // `onClick(index)` / 命中几何的索引传的是**逻辑索引**,多一个前导项
            // 会让所有格子的索引与拖拽落点整体错位。
            // `span = maxLineSpan` 让它横跨整行(否则只占第 1 列)。
            if (header != null) {
                item(key = DRAG_HEADER_ITEM_KEY, span = { GridItemSpan(maxLineSpan) }) { header() }
            }
            // 渲染源 = 拖动中的本地顺序(实时让位),否则入参顺序。
            val displayItems = dragItems ?: items
            // ⚠️ 过渡动画**只在拖动期间挂载**,这不是省事,而是必要:
            // 拖动中项目集合恒定(只有顺序变),故不会有项进出视线;而在**非拖动**的重排里
            // (如"改排序规则 ⇒ 整表倒序")`animateItem` 会把正在移出视线的项**留在组合树里
            // 并量成 0 尺寸**,语义树里凭空多出一批 `top=bottom=0` 的卡片节点 ⇒ 任何取
            // `min()` / 最小 top 当判据的既有断言(滚动锚定、编辑器滚出视口)都会被误拉低。
            // 实测:常挂 `animateItem` 即便把三档 spec 全设成 `snap` 也让 2 条**不涉及拖拽**的
            // 既有测试变红;本文件与 [DragReorderList] 的拖动期功能测试则不受影响。
            val reflowAnimating = draggingId != null
            itemsIndexed(displayItems, key = { _, it -> it.id }) { index, item ->
                Box(
                    modifier = Modifier
                        // 实时让位的**过渡**:让位靠逐帧改本地展示顺序实现,没有它邻居是在
                        // 一帧内瞬移的 —— 用户 2026-10-15 反馈的"缺少动效"正指此处。
                        // 规格与选型理由见 [DragReorderPlacementSpec]。前提是 **key 稳定**。
                        // 淡入淡出**不设**:拖动中每次贴边自动滚动都会带入新项,给它们淡入会
                        // 让"让位"这件事被淡入噪声盖住;置 null 也顺带避免上面那种 0 尺寸残留节点。
                        //
                        // ⚠️ `animateItem` **必须挂在 item 根节点**(就是这里):实测把它挪到
                        // 内层嵌套 Box 会让动画**静默失效**(不报错、就是不动)。正因如此,
                        // 命中几何**不能**再从这个节点读 —— 该节点的 `onGloballyPositioned`
                        // 读到的是图层**飞行中**的矩形(实测被重排的项用 14 帧才爬到位),
                        // 用它做命中判定会让指针停在卡片边缘时反复换位 = 用户反馈的"抽搐"。
                        // 现改由 [layoutSlotBounds] 从 `gridState.layoutInfo` 现算逻辑几何。
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
                        .padding(4.dp),
                ) {
                    DraggableThumbCard(
                        item = item,
                        isPinned = isPinned(item.id),
                        isDragging = draggingId == item.id,
                        onClick = { onClick(index) },
                        onPin = { onPin(item.id) },
                    )
                }
            }
        }

        // 跟手浮层:拖动中手里的那张图跟着指针走(用户 2026-10-14 保留此视觉,
        // 只把"松手才重排"改成"实时让位")。只画不参与命中判定。
        val ghostId = draggingId
        if (ghostId != null) {
            (dragItems ?: items).firstOrNull { it.id == ghostId }?.let { ghostItem ->
                DragGhost(
                    item = ghostItem,
                    pointerInRoot = pointerInRoot,
                    containerInRoot = containerInRoot,
                    sizeInRoot = dragSize,
                    style = DragGhostStyle.Card,
                )
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

/** 由 Lazy 布局信息换算出**逻辑索引 → 格子矩形**的映射(命中判定的几何来源)。
 *
 * ## 为什么不能用 item 节点的 `onGloballyPositioned`
 *
 * 这是本文件最容易踩的坑,已用冻结时钟逐帧探针实测(2026-10-15):
 *
 * | 几何来源 | `animateItem` 动画**进行中**读到的是 |
 * | --- | --- |
 * | item 节点的 `onGloballyPositioned { boundsInRoot() }` | **飞行中**位置(被重排的项用 14 帧才从 `(0,0)` 爬到 `(180,100)`) |
 * | `LazyGridState.layoutInfo.visibleItemsInfo` 的 `offset`/`size` | **逻辑**位置(一帧到位) |
 *
 * 用户真机反馈的「拖动到其他卡片的边缘,被挤压的卡片和预计新位置的虚影抽搐」正是前者造成的:
 * 命中判定读到的矩形在飞行途中,指针明明已落在新占位的格子内却按旧矩形算,于是反复换位、
 * 动画反复重启,自我维持成抖动。改用 `layoutInfo` 后几何与动画解耦。
 *
 * ⚠️ **不能靠「把 `animateItem` 挪到内层」或「外层再包一个 Box」来回避** —— 实测前者让
 * 动画**静默失效**(不报错、就是不动),后者读数依旧(动画图层挂在 item 根上)。
 * 故 `animateItem` 必须留在 item 根节点,命中几何另找来源。
 *
 * ## 换算公式(已实测校验)
 *
 * `rect = containerTopLeft + contentPaddingTopLeft + info.offset`,`size = info.size`。
 * 实测例:容器根坐标 `(0,0)`、`contentPadding = 8dp`、header(高 40px)之后第一张卡
 * 的 `info.offset = (0, 40)` ⇒ `onGloballyPositioned` 落定读数 `Rect(8,48,180,148)`,
 * 与算式逐位吻合。
 *
 * ⚠️ 本函数收 [SlotInfo] 而**不是** `LazyGridItemInfo` —— 网格与列表的 Lazy 信息接口
 * 是两个互不相关的类型,故各自先映射成这个中立值类型再共用同一份换算逻辑(见
 * `gridSlotInfos` / `listSlotInfos`),避免"两份换算各写一遍"日后漂移。
 *
 * @param visible 可见项信息(含前导 header 项)。
 * @param containerTopLeft 容器的根坐标左上角(容器自身的 `onGloballyPositioned`)。
 * @param padLeft / @param padTop `contentPadding` 换算成 px 后的左/上内边距。
 * @param headerOffset 前导项个数(有 header 时为 1),用于把 **lazy 下标**换成**逻辑下标**。
 */
internal fun layoutSlotBounds(
    visible: List<SlotInfo>,
    containerTopLeft: Offset,
    padLeft: Float,
    padTop: Float,
    headerOffset: Int,
): Map<Int, Rect> {
    if (visible.isEmpty()) return emptyMap()
    val out = LinkedHashMap<Int, Rect>(visible.size)
    for (info in visible) {
        // 前导项(header)不参与命中判定,且会把逻辑下标整体错位,必须跳过并减去。
        if (info.index < headerOffset) continue
        val left = containerTopLeft.x + padLeft + info.offset.x
        val top = containerTopLeft.y + padTop + info.offset.y
        out[info.index - headerOffset] = Rect(
            left = left,
            top = top,
            right = left + info.size.width,
            bottom = top + info.size.height,
        )
    }
    return out
}

/** 网格可见项的**中立几何**形式(见 [layoutSlotBounds] 为何不直接收 `LazyGridItemInfo`)。 */
internal data class SlotInfo(val index: Int, val offset: Offset, val size: Size)

/** 把 `LazyVerticalGrid` 的可见项映射成 [SlotInfo]。 */
internal fun gridSlotInfos(visible: List<LazyGridItemInfo>): List<SlotInfo> =
    visible.map { SlotInfo(it.index, Offset(it.offset.x.toFloat(), it.offset.y.toFloat()), Size(it.size.width.toFloat(), it.size.height.toFloat())) }


/** 在已知各格 bounds 中找出包含该点的下标;找不到则取**最近格中心**(容错)。
 *
 * 只用于「指针拖出容器」的兜底(见 [isOutsideContainer])与既有的滚动后起拖判定;
 * 容器**内**的格间缝隙刻意不兜底 —— 那里是实时让位收敛性依赖的死区。 */
internal fun findSlotAt(point: Offset, bounds: Map<Int, Rect>): Int? {
    findContainedSlotAt(point, bounds)?.let { return it }
    if (bounds.isEmpty()) return null
    return bounds.entries.minByOrNull { (_, r) ->
        val dx = point.x - r.center.x
        val dy = point.y - r.center.y
        dx * dx + dy * dy
    }?.key
}

/** 找出**包含**该点的格下标;不包含任何格时返回 null。
 *
 * 拖拽起点与实时让位都用它。刻意**不做**"兜底到最近格中心" —— 实时让位要靠
 * "指针在格内才换位"来收敛:重排后被拖卡片已占住指针所在格 ⇒ 下一次事件算出的
 * 目标 == 它自己的格 ⇒ 立即稳定;若在缝隙里也兜底到最近格,指针在边界附近微动
 * 就会来回换位(即旧裁决担心的现象)。
 *
 * ⚠️ 指针拖到**容器外**是另一回事:那里没有任何格包含它,若不兜底则贴边滚动期间
 * 让位完全停摆。故调用方在容器外改用 [findSlotAt] 兜底 —— 见 [isOutsideContainer]。 */
internal fun findContainedSlotAt(point: Offset, bounds: Map<Int, Rect>): Int? =
    bounds.entries.firstOrNull { (_, r) -> r.contains(point) }?.key

/** 实时让位的**目标下标**判定(纯函数,便于离线断言)。
 *
 * 三段语义,各有理由,缺一不可 —— 每条都有变异守护(见 `artifacts/mutate6.py`)：
 *  1. **容器内**且落在某个格/行里 ⇒ 该格。这是收敛性的关键:重排后被拖项已占住
 *     指针所在格,下一次事件算出的目标 == 它自己的格 ⇒ 立即稳定。
 *  2. **容器内**但落在格/行之间的缝隙 ⇒ **null**(保持原目标不动)。刻意不兜底到
 *     "最近的格中心":那样指针在边界附近微动就会来回换位 —— 正是旧裁决担心的现象。
 *  3. **容器外**(手指滑出网格下沿、进到底部动作栏) ⇒ 退化为最近的格。容器外没有
 *     任何格包含该点,不兜底则贴边自动滚动期间让位完全停摆(滚是滚了、卡片不跟)。
 */
internal fun resolveReflowTarget(
    point: Offset,
    containerTopLeft: Offset,
    containerWidth: Float,
    containerHeight: Float,
    bounds: Map<Int, Rect>,
): Int? {
    findContainedSlotAt(point, bounds)?.let { return it }
    return if (isOutsideContainer(point, containerTopLeft, containerWidth, containerHeight)) {
        findSlotAt(point, bounds)
    } else {
        null
    }
}

/** 指针是否已拖出容器矩形(上/下/左/右任一方向)。
 *
 * 专供实时让位的兜底:容器**外**的点不被任何格包含,若不兜底,贴边自动滚动期间
 * 让位会完全停摆(滚是滚了,卡片却不跟着走)。容器**内**的格间缝隙刻意不兜底 —— 
 * 那是收敛性依赖的"死区"。 */
internal fun isOutsideContainer(
    point: Offset,
    containerTopLeft: Offset,
    containerWidth: Float,
    containerHeight: Float,
): Boolean = point.x < containerTopLeft.x ||
    point.x > containerTopLeft.x + containerWidth ||
    point.y < containerTopLeft.y ||
    point.y > containerTopLeft.y + containerHeight
