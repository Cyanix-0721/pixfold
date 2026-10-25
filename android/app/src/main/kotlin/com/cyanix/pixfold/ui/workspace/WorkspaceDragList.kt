package com.cyanix.pixfold.ui.workspace

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * 工作区列表的行高。
 *
 * **56dp 不是随便取的**：AGENTS.md §2.7 要求触控目标 ≥48dp，而本列表的整行本身就
 * 是「点一行切换过去」的点击区 —— 行高就是触控高度，不能再靠内层按钮兜底。
 * 留到 56dp 是为了给「目录名 + 使用中」两行文字留出垂直呼吸，同时不小于 48。
 *
 * **不能写成 `const val`**：`Dp` 不是编译期常量，`const` 会直接编译失败
 * （`Const 'val' initializer should be a constant value`）。task-6-brief 的骨架里
 * 写的是 `const val`，那是不可能编译的写法 —— 这里是**有意的偏差**，
 * 第 7 轮起若发现别处照抄了带 `const` 的签名，那是复制错误，不是这里的回归。
 */
val WORKSPACE_ROW_HEIGHT: Dp = 56.dp

/**
 * 极简的「固定行高 + 整行长按拖动」容器。
 *
 * ## 为什么不复用 `ui/components/DragReorderList.kt`
 *
 * 那个组件的签名写死 `List<SourceItem>`（见 `DragReorderList.kt:62`），而工作区条目是
 * `WorkspaceListing`（存储事实 + 运行时授权状态）。把 `WorkspaceListing` 硬塞进
 * `SourceItem` 等于给两个不同的领域概念做假适配，将来任何一边改字段都要动另一边的
 * 界面。工作区这一版只需要「行高固定、整行长按拖动、松手提交一次」，重写比适配便宜。
 *
 * ## 为什么不用 LazyColumn
 *
 * 本组件被放在 `PixFoldDialogShell(contentScrollable = false)` 的正文里，而正文自己
 * 已经是 `verticalScroll`。再嵌一个惰性滚动的容器会撞上
 * `IllegalStateException: Vertically scrollable component was measured with an infinity
 * maximum height constraints`。列表上限只有 8 条，普通 `Column` 足够。
 *
 * ## 手势与状态的两个要点
 *
 * 1. **`dragFrom` / `dragOffset` 必须走 `mutableIntStateOf` / `mutableFloatStateOf`**，
 *    不能是普通局部变量：手势回调跑在 `pointerInput` 的挂起块里，而 Compose 状态才能
 *    驱动让位重组，也才能让「渲染」与「提交」读到同一份当前值。
 * 2. **目标下标必须在 `onDragEnd` 里现算，不能捕获组合期的 `targetIndex`**。
 *    `pointerInput` 的手势协程不会因重组而重启，闭包里捕获的局部 `val` 会停留在
 *    按下那一刻的值 —— 按下时 `dragFrom == -1`，于是松手时永远算不出目标 ⇒
 *    「拖了没反应」。故抽成 `targetIndexFor`，渲染与提交各调一次。
 *
 * @param items 本分区的条目。**本组件不改顺序**，只把 `(from, to)` 这两个
 *   *本分区内* 的下标交给 [onMove]；换算成全局存储下标是调用方的事
 *   （下分区要加 `granted.size` 偏移，少了这一步拖拽会改错条目，而且不会崩，
 *   只是「拖了没反应」）。
 * @param row 单行内容。行高由本组件固定为 [WORKSPACE_ROW_HEIGHT]，`row` 不要再设高度。
 */
@Composable
fun <T> WorkspaceDragList(
    items: List<T>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { WORKSPACE_ROW_HEIGHT.toPx() }
    val targetIndex = targetIndexFor(dragFrom, dragOffset, rowHeightPx, items.lastIndex)

    Column(modifier) {
        items.forEachIndexed { index, item ->
            val dragging = index == dragFrom
            // 让位位移与拖动中的跟随位移都来自纯函数：几何要能离线断言（变异 M10 原本存活，
            // 因为方向反了没有任何测试读得到 translationY）。
            val shift = rowShiftFor(index, dragFrom, targetIndex, rowHeightPx)
            val offset = rowDragOffsetFor(dragging, dragOffset)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WORKSPACE_ROW_HEIGHT)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = shift + offset }
                    .pointerInput(items) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragFrom = index
                                dragOffset = 0f
                            },
                            onDrag = { _, delta -> dragOffset += delta.y },
                            onDragEnd = {
                                val from = dragFrom
                                // 现算，不用组合期的 targetIndex —— 见类 KDoc 要点 2。
                                val to = targetIndexFor(from, dragOffset, rowHeightPx, items.lastIndex)
                                if (from >= 0 && to >= 0 && to != from) onMove(from, to)
                                dragFrom = -1
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                dragFrom = -1
                                dragOffset = 0f
                            },
                        )
                    },
            ) {
                row(item)
            }
        }
    }
}

/**
 * 把「起点 + 位移折算的行数」钳进合法下标区间。
 *
 * 两条守卫各自防的事：
 *
 * 1. **`dragFrom < 0`（未在拖）** —— 没在拖的时候 `dragOffset` 是 0，直接算会得到
 *    `dragFrom` 本身，让整个列表误以为「正在拖第 0 行」。
 *    ⚠️ **删掉这条守卫的真实后果不是「画错位移」，而是堆被耗尽**：`-1` 会流进
 *    `LazyListState.layoutInfo` 的可见项查找与让位计算，那条路径会不断产出新状态帧
 *    并反复重组同一个可见区，几秒内抛 `OutOfMemoryError: Java heap space`（2026-10-26
 *    Task 9 变异 M8 实测）。故它不能按「少一条美术修正」来理解。
 * 2. **`lastIndex < 0`（空列表）** —— `coerceIn(0, -1)` 会抛
 *    `IllegalArgumentException: Cannot coerce value to an empty range`。
 *    本项目在 `TwoLevelChipRows` 的 M5 上踩过同型崩溃。空列表并不罕见：
 *    所有条目都失效、或调用方在数据回来前先渲染一帧。
 */
private fun targetIndexFor(
    dragFrom: Int,
    dragOffset: Float,
    rowHeightPx: Float,
    lastIndex: Int,
): Int = if (dragFrom < 0 || lastIndex < 0) {
    -1
} else {
    (dragFrom + (dragOffset / rowHeightPx).roundToInt()).coerceIn(0, lastIndex)
}

/**
 * 某一行的**让位位移**（px，正负号表示方向）。
 *
 * 拖动中，被拖行越过谁，谁就往反方向挪一格 —— 这是「实时让位」的全部几何。
 *
 * 抽成纯函数的原因：它曾是 forEachIndexed 里的一段内联 when，而内联写法
 * **离线断不到** —— 2026-10-26 Task 9 变异 M10 把 -rowHeightPx 与 +rowHeightPx
 * 对调之后 841 条测试全绿（屏幕外的位移没有任何断言读得到）。方向反了在真机上的
 * 表现是「拖谁谁不动、别人乱窜」，属用户一眼可见的缺陷，不该留给真机兜。
 *
 * @param index 正在计算的是第几行。
 * @param dragFrom 被拖起的行下标；-1 = 没在拖。
 * @param targetIndex 松手会落在哪一行（-1 = 无有效目标）。
 * @param rowHeightPx 单行高度（px）。
 */
internal fun rowShiftFor(
    index: Int,
    dragFrom: Int,
    targetIndex: Int,
    rowHeightPx: Float,
): Float = when {
    // 没在拖 / 无有效目标 / 自己那一行（它跟着手指走，不该再让位）。
    targetIndex < 0 || index == dragFrom -> 0f
    // 向下拖：[dragFrom+1 .. targetIndex] 整体上移一格，补上被抽走的那一行。
    index in (dragFrom + 1)..targetIndex -> -rowHeightPx
    // 向上拖：[targetIndex .. dragFrom-1] 整体下移一格。
    index in targetIndex..(dragFrom - 1) -> rowHeightPx
    else -> 0f
}

/**
 * 被拖行自己的跟随位移：只有它跟着手指走，其余行由 [rowShiftFor] 让位。
 *
 * 抽出来是为了让「拖动中位移 = 手指位移、非拖动行位移 = 0」也变成可断言的纯函数，
 * 而不是嵌在 graphicsLayer 里的三元表达式。
 */
internal fun rowDragOffsetFor(dragging: Boolean, dragOffset: Float): Float =
    if (dragging) dragOffset else 0f
