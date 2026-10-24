package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R

/**
 * 单选芯片行的**全站唯一实现**:从 N 个具名条目里选 1 个。
 *
 * 当前用户(2026-10-19 用户裁决「只统一集合芯片与卷芯片」):
 * - [VolumeChipRow] —— 工作流 B 步骤 2 元数据页 / 步骤 3 打包预览页的换卷行
 * - 工作流 A 步骤 1 的集合/系列芯片行(两级)
 *
 * ## 为什么必须共用一份实现
 *
 * 三次真机缺陷都出自同一类错误(见下),两处各写一遍必然再犯:
 * 1. **2026-09-25**:原为「Row + horizontalScroll」,5 卷时第 4、5 卷完全落在视口外,
 *    只能靠横滑够到,而界面上没有任何提示说明这里能滑 ⇒ 改自动换行。
 * 2. **2026-10-18**:芯片行作为 LazyColumn 第一个 item 时一滚就整体滚出视口,
 *    换卷必须先回顶;而换卷是高频动作 ⇒ 移到滚动容器**之外**常驻。
 * 3. **2026-10-18(规模回归)**:常驻 + 不设上限时,条目一多就把整块顶高
 *    —— 40 卷实测芯片块 2296px(834.9dp),几乎吃满 835.3dp 视口,**下方内容永久不可达**。
 *
 * ## 收口规则(2026-10-23 改版)
 *
 * 超过 [CHIP_COLLAPSE_LINES] 行时,保留能放进这么多行的前若干块,**并把一块虚线
 * 「全部 N 项」芯片补在最后一行末尾**;点它原地展开(不再开 ModalBottomSheet),
 * 展开后末尾换成「收起」。
 *
 * ### 为什么不用 Compose 原生的 FlowRowOverflow
 *
 * 试过(探针 FlowOverflowProbeTest,已删):两条路都断了 ——
 * - FlowRowOverflow.expandOrCollapseIndicator 的 FlowRowOverflowScope.shownItemCount
 *   **在组合阶段不可访问**,直接抛
 *   「IllegalStateException: Accessing shownItemCount before it is set」;
 * - 整个 FlowRowOverflow 与 overflow 参数都已 **deprecated**
 *   (「FlowLayout overflow is no longer maintained」)。
 *
 * ### 为什么收口芯片必须自己算位置,不能塞进 FlowRow(maxLines)
 *
 * 手写的收口芯片若作为 FlowRow 的第 N+1 个子项,会被 maxLines 直接裁掉 ——
 * 「超出部分的唯一入口」自己先消失。所以这里改用 [ChipFlow]([SubcomposeLayout] 实现)
 * 自己分行:先量出每块的真实宽度,按轨道宽度贪心折行,再决定保留几块、收口芯片放哪。
 *
 * ### 为什么「溢出与否」离线可断
 *
 * [CHIP_MIN_WIDTH] 是**确定性**约束:Robolectric 把文本宽度算成 ~0,但 widthIn 的
 * min 仍把每块撑到 96dp ⇒ 321dp 轨道下一行 3 块、3 行 9 块,第 10 块必然溢出。
 *
 * @param items 全部条目(顺序即展示顺序)。
 * @param activeId 当前项 id。
 * @param idOf 取条目 id。
 * @param onSelect 选中某项。
 * @param selectorTag 芯片块的 testTag(各页不同,避免跨页冲突)。
 * @param chipTag 芯片的 testTag 生成器。
 * @param allEntryTag 「全部 N 项」收起/展开芯片的 testTag。
 * @param chipDescription 芯片的无障碍文案。**是 @Composable 的** —— 调用方可以直接
 *   在里面 `stringResource(...)`,不必在外层先解析好再传。
 * @param chipLabel 芯片标签 —— 各页文案不同(打包页还要标出「未勾选」项)。
 * @param allEntryText 收口芯片的文案(计数由本组件传入)。卷芯片用「全部 N 卷」,
 *   集合芯片用「全部 N 项」—— 统一组件不能把量词写死。
 * @param collapseLines 收起态最多排几行芯片。默认 [CHIP_COLLAPSE_LINES];
 *   传 Int.MAX_VALUE 表示不收口(series 浮层内部用它,用户在浮层里应看到全部)。
 * @param surface 是否给整块芯片垫底托。series 浮层内传 false ——
 *   浮层卡片本身已有底色,再垫一层会读成两层嵌套的灰块。
 */
@Composable
fun <T> SingleSelectChipRow(
    items: List<T>,
    activeId: String,
    idOf: (T) -> String,
    onSelect: (String) -> Unit,
    selectorTag: String,
    chipTag: (String) -> String,
    allEntryTag: String,
    chipDescription: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    collapseLines: Int = CHIP_COLLAPSE_LINES,
    allEntryText: @Composable (Int) -> String = { n -> pluralStringResource(R.plurals.volume_all, n, n) },
    emphasized: Boolean = false,
    chipLabel: @Composable (T) -> String,
    chipSuffix: @Composable (T) -> String = { "" },
    chipTextMaxLines: Int = CHIP_TEXT_MAX_LINES,
    surface: Boolean = true,
    onChipBounds: ((id: String, bounds: Rect) -> Unit)? = null,
) {
    // ## 展开态的重置键是**条目 id 序列**,不是 items 实例
    //
    // 用 `items` 作 key 会在「同一批卷被重扫、元数据回填」时也复位 —— 那些场合
    // 列表内容是同一批 id,只是实例换了。用户在展开态里上下找某一卷,一次后台刷新
    // 就把它收起来,是很莫名的体验。
    // 反过来,换工作区 / 换系列时 id 序列必然变 ⇒ 该复位。用 id 序列两边都对。
    var expanded by remember(items.map(idOf)) { mutableStateOf(false) }
    val total = items.size
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxWidth()
            // **1 级 / 2 级视觉区分**(2026-10-21 用户裁决「加个遮罩」)。
            //
            // 两条由真机反馈修正过的细节:
            // ① **必须留白不顶边** —— 首版把色块铺满整行宽,真机看起来像一条横贯屏幕的
            //    分隔条(用户:「你这个遮罩都顶到边缘了很难看」)。8dp 外边距让色块成为
            //    一块「底托」而不是「横线」。
            // ② **两级都要有底色,只差强度** —— 首版只给 1 级垫、2 级透明,用户反馈
            //    「只有第一层又第二层又没有也很难看」:一层有一层没有读起来像画漏了。
            //    两级都垫,1 级 0.45 / 2 级 0.18,层级关系由深浅表达。
            // 用半透明 surfaceVariant 而不是实色:深浅两套主题下都不需要另配色。
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .then(
                if (surface) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = if (emphasized) 0.45f else 0.18f,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        ChipFlow(
            count = total,
            // **左对齐**(用户 2026-10-21:「不是 chip 本身居中」)。曾按「1 级芯片没有
            // 居中显示」的反馈改成每行居中,但那句话指的是**芯片内的文字**,不是芯片
            // 在行里的位置。瀑布流的本义就是从左上起排、各行左端对齐;居中会让每一行
            // 的起点随该行芯片总宽左右漂,读起来像没对齐。文字居中由
            // SingleSelectChip 的 textAlign 负责。
            // ## 间距(2026-10-24 真机反馈)
            //
            // 用户原话:「chip 内容有换行的情况下,左右间隔大了些,上下又几乎没有间隔」。
            // 旧值横 6 / 纵 4 —— 6dp 是从 Arrangement.spacedBy(6.dp) 抄来的,4dp 纯属顺手取小。
            // 真机上高矮不一的芯片并排时,行高由最高那块决定,单行块的**下方**先空出一截,
            // 再叠加仅 4dp 的行距 ⇒ 视觉上行与行几乎粘死;而一块芯片已经顶到
            // CHIP_MAX_WIDTH,横向 6dp 反而显宽。故横收窄到 4、纵放大到 10,
            // 让「行」成为主要分界。
            horizontalGap = 4.dp,
            verticalGap = 10.dp,
            collapseLines = collapseLines,
            expanded = expanded,
            // ## 收起态写的是**条目总数**,不是被收起的块数
            //
            // 2026-10-24 自查发现的真缺陷:原写法是 `allEntryText(total - hidden)`,
            // 而 `hidden = count - shownCount` ⇒ 算出来的是 **shownCount**(已经显示
            // 出来的块数)。12 块在 321dp 轨道上收口到 7 块时会写「全部 7 项」,
            // 点开却冒出 12 项 —— 入口写着「全部」却少报,比不写数字更误导。
            // 用户 2026-10-23 要的就是「全部 X 项」里的 X = 总数。
            //
            // 文案同时当无障碍描述用(2026-10-24 评审 M2 合并):原先是
            // `indicatorText` / `indicatorDescription` 两个参数各自传同一个值,
            // 两个 lambda 内容逐字相同 —— 多一个参数只多一处能写歪的地方。
            indicatorLabel = {
                if (expanded) stringResource(R.string.chip_collapse) else allEntryText(total)
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // ## 展开态才限高 + 内滚(2026-10-24 评审 C1)
                    //
                    // 收起态本身有 [CHIP_COLLAPSE_LINES] 封顶,不需要滚动容器;给它套
                    // `heightIn` 反而会在极端字体缩放下平白截断。展开态没有上限,
                    // 40 卷 ≈ 1200dp 会把宿主页面的 `weight(1f)` 挤成 0 —— 用户既看不到
                    // 下方内容,**也够不到末尾那枚「收起」芯片**。
                    //
                    // 于是:展开态 318dp 封顶 + 内部竖滚,收起入口永远可达。
                    // 这是「窄屏 + 长列表」的通用取舍,不是给某个页面打的补丁。
                    if (expanded) {
                        Modifier
                            .heightIn(max = CHIP_EXPANDED_MAX_HEIGHT)
                            .verticalScroll(scrollState)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(selectorTag),
            onToggle = {
                expanded = !expanded
                if (!expanded) {
                    // 收起时把内部滚动位置也归零:否则下次展开会停在上次滚到的地方,
                    // 而用户的心智是「刚展开,从头看」。
                    // scrollTo 是挂起函数(要等动画/布局),只能走协程。
                    scope.launch { scrollState.scrollTo(0) }
                }
            },
            indicatorTag = allEntryTag,
            item = { index ->
                // ## 越界守卫不是防御性编程,是实测崩溃
                //
                // SubcomposeLayout 的子组合会被复用:items 从 2 项缩成 1 项时,上一轮
                // 留下的 `ChipSlot.Probe(1)` 仍会被重新组合,而这时闭包里的 items
                // 只有 1 项 ⇒ `items[1]` 抛 IndexOutOfBoundsException(实测栈顶
                // `SingleSelectChipRow.kt:198` 经 `Recomposer.performRecompose`)。
                // 这个槽位反正马上会被丢弃,直接组合成空。
                val item = items.getOrNull(index) ?: return@ChipFlow
                val id = idOf(item)
                SingleSelectChip(
                    item = item,
                    id = id,
                    selected = id == activeId,
                    tag = chipTag(id),
                    chipDescription = chipDescription,
                    chipLabel = chipLabel,
                    chipSuffix = chipSuffix,
                    textMaxLines = chipTextMaxLines,
                    onSelect = onSelect,
                    onPositioned = onChipBounds?.let { callback -> { rect -> callback(id, rect) } },
                )
            },
        )
    }
}

/** [ChipFlow] 的子项槽位标识 —— SubcomposeLayout 在 measure 阶段重进时靠它对齐。 */
private sealed interface ChipSlot {
    /**
     * 只用来量宽度的探测副本。
     *
     * 必须与 [Item] 分开:同 key 的 subcomposition 会被复用,那样「清语义」的
     * 包装会跟着漏到真实显示的那一份上,芯片就没有 testTag/无障碍信息了。
     */
    data class Probe(val index: Int) : ChipSlot

    data class Item(val index: Int) : ChipSlot

    data class Indicator(val expanded: Boolean) : ChipSlot
}

/**
 * 芯片块的**分行 + 收口布局**。
 *
 * 用 [SubcomposeLayout] 而不是 FlowRow 的原因见 [SingleSelectChipRow] 的 KDoc:
 * 收口芯片必须能落在**最后一行末尾**,而 FlowRow(maxLines) 会把它裁掉。
 *
 * 算法分三步:
 * 1. 先量出每块芯片的宽度(不加约束,widthIn 自己会把宽度钳进 [CHIP_MIN_WIDTH]..[CHIP_MAX_WIDTH])。
 * 2. 贪心折行,得到「不收口时」的行数与每行成员。
 * 3. 若收起态下行数超过 [collapseLines],从「前 collapseLines 行能装下的块数」开始
 *    **倒着试**:把前 j 块 + 一块收口芯片重新折行,第一个 ≤ collapseLines 行的 j 就是答案。
 *    收口芯片按**最大宽度**预留,所以真实文案更短时只会更宽松。
 *
 * ## 为什么要「倒着试」而不是「直接砍到第 3 行」
 *
 * 因为收口芯片本身要占位置。若直接把前 3 行的块全留下,再加一块收口芯片,
 * 它有大概率把第 3 行挤爆、把自己推到第 4 行 —— 那正是要防止的「入口自己消失」。
 * 从多到少试,第一个装得下的就是**能显示的最多块数**,视觉上不会莫名空出半行。
 */
@Composable
internal fun ChipFlow(
    count: Int,
    horizontalGap: Dp,
    verticalGap: Dp,
    collapseLines: Int,
    expanded: Boolean,
    modifier: Modifier,
    indicatorTag: String,
    indicatorLabel: @Composable (hiddenCount: Int) -> String,
    onToggle: () -> Unit,
    item: @Composable (index: Int) -> Unit,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val hGap = horizontalGap.roundToPx()
        val vGap = verticalGap.roundToPx()
        val rowWidth = constraints.maxWidth
        // ## 量宽度的约束(2026-10-24 评审 M11)
        //
        // 用 `Constraints(maxWidth = rowWidth)` 而不是全放开的 `Constraints()`:
        // 芯片自带 `widthIn(min = 96, max = 156)`,正常情况下 156 << 321 的轨道,
        // 两者等价。但一旦宿主把芯片行塞进比 156dp 还窄的父级(分屏、外接小窗、
        // 将来某个内层容器),无约束 measure 会量出比轨道更宽的块,而 place 阶段
        // 是**不重测**的 ⇒ 芯片静默横向溢出、压到右边界外。
        // 夹住 maxWidth 后这种情况会退化成「块变窄、文字多折一行」,是有界的。
        val probeConstraints = Constraints(maxWidth = rowWidth)

        // 贪心折行:按给定宽度序列分行,返回每行成员的**下标**。
        fun rowsOf(widths: List<Int>): List<List<Int>> {
            val rows = mutableListOf<List<Int>>()
            var row = mutableListOf<Int>()
            var x = 0
            widths.forEachIndexed { index, width ->
                if (row.isNotEmpty() && x + hGap + width > rowWidth) {
                    rows += row
                    row = mutableListOf()
                    x = 0
                }
                if (row.isNotEmpty()) x += hGap
                x += width
                row += index
            }
            if (row.isNotEmpty()) rows += row
            return rows
        }

        // ## 为什么要先「探测」再「真正组合」
        //
        // 折行与收口都依赖**每一块的宽度**,而 SubcomposeLayout 是先把内容组合出来、
        // 才能 measure 拿宽度的。第一版直接对全部条目 subcompose 一次、然后只用前
        // shownCount 个来放置 —— 结果被收起来的那些芯片虽然没被放置,**仍然留在了
        // 语义树里**:收起态下 `chip-item-12` 能被 `onNodeWithTag` 找到,读屏也会把
        // 看不见的条目念出来(实测 742 条里有一条正是被这个卡住的)。
        //
        // 所以分两趟:
        //   ① 探测副本([ChipSlot.Probe])用 `clearAndSetSemantics` 清掉语义,纯量宽;
        //   ② 决定 shownCount 之后,只为**真正要显示的**那些重新 subcompose([ChipSlot.Item])。
        // 两个槽位必须是不同的 key —— 同 key 会让 subcomposition 复用,清理语义的
        // 包装就会漏到真实那一份上,芯片连 testTag 都没了。
        val probePlaceables = List(count) { index ->
            subcompose(ChipSlot.Probe(index)) {
                Box(Modifier.clearAndSetSemantics {}) { item(index) }
            }.first().measure(probeConstraints)
        }
        val naturalRows = rowsOf(probePlaceables.map { it.width })
        val overLines = collapseLines < Int.MAX_VALUE && naturalRows.size > collapseLines
        val collapsing = overLines && !expanded

        var shownCount = count
        if (collapsing) {
            // 收口芯片宽度按上限预留 —— 文案里的计数会影响宽度,但宽度恒在
            // [CHIP_MIN_WIDTH]..[CHIP_MAX_WIDTH] 内,按上限算是保守且稳定的。
            val reserve = CHIP_MAX_WIDTH.roundToPx()
            val capacity = naturalRows.take(collapseLines).sumOf { it.size }
            shownCount = (capacity downTo 0).firstOrNull { candidate ->
                rowsOf(probePlaceables.take(candidate).map { it.width } + reserve).size <= collapseLines
            } ?: 0
        }

        val shown = List(shownCount) { index ->
            subcompose(ChipSlot.Item(index)) { item(index) }.first().measure(probeConstraints)
        }
        val hidden = count - shownCount
        // 收口芯片出现的条件:
        // - 收起态确实溢出 ⇒ 「全部 N 项」;
        // - 展开态且这一批本来就超行 ⇒ 「收起」(否则展开后没有收起入口)。
        val showIndicator = if (collapsing) {
            true
        } else {
            expanded && overLines
        }
        val indicatorPlaceable = if (showIndicator) {
            subcompose(ChipSlot.Indicator(expanded)) {
                CollapseChip(
                    text = indicatorLabel(hidden),
                    description = indicatorLabel(hidden),
                    tag = indicatorTag,
                    expanded = expanded,
                    onClick = onToggle,
                )
            }.first().measure(Constraints())
        } else {
            null
        }

        val placeables = shown + listOfNotNull(indicatorPlaceable)
        val rows = rowsOf(placeables.map { it.width })
        val rowHeights = rows.map { row -> row.maxOf { placeables[it].height } }
        val totalHeight = rowHeights.sum() + vGap * (rows.size - 1).coerceAtLeast(0)

        layout(rowWidth, totalHeight) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEach { index ->
                    placeables[index].placeRelative(x, y)
                    x += placeables[index].width + hGap
                }
                y += rowHeights[rowIndex] + vGap
            }
        }
    }
}

/**
 * 收口芯片:虚线描边 + 文案 + 上/下箭头。
 *
 * **尺寸必须与普通芯片同区间**([CHIP_MIN_WIDTH] / [CHIP_MAX_WIDTH]),否则它会被当成
 * 「另一类东西」而不是「这一行里的一块」。虚线是「这块不是数据项、是个动作」的唯一
 * 视觉信号 —— 实线边框会和真正可选的芯片撞脸。
 *
 * 高度用 heightIn(min = 32.dp) 而不是与芯片等高:芯片内部文字折 4 行时会到 88dp,
 * 收口芯片的文字恒为一行,强行等高会在中间留一大片空白。
 *
 * ## 触摸区与无障碍(2026-10-24 评审 I3 / I4)
 *
 * ① **必须自己补 48dp 触摸目标**。AGENTS.md §2.7 是硬约束,而 FilterChip 靠 M3 内置的
 *    `minimumInteractiveComponentSize()` 拿到 48dp —— 手写的 Box 没有这层。这块芯片
 *    又是「溢出部分**唯一**的入口」,32dp 高的话在真机上会频繁点空。
 *    做法 = 外层 Box 承担 触摸目标 + 点击 + 语义,内层 Box 只画虚线框与文字;
 *    这样虚线框仍贴着文字(不高),触摸区却是 48dp。
 *
 *    ⚠️ **光调 `minimumInteractiveComponentSize()` 不够**(2026-10-24 实测):Robolectric 下
 *    量到的触摸区只有 **36dp** —— 该扩展受 `LocalMinimumInteractiveComponentEnforcement`
 *    控制,靠不住。所以显式加 `.heightIn(min = 48.dp)` 兜住;两者叠着也不冲突。
 * ② **语义必须合并成一个节点并声明按钮角色**。`.clickable` 不合并后代,原来的写法会让
 *    带 contentDescription 的 Box 与内部 Text 各成一个可聚焦节点,读屏把
 *    「全部 12 项」念两遍,且不报「按钮」。`semantics(mergeDescendants = true)` +
 *    `role = Role.Button` 一并解决。
 */
@Composable
private fun CollapseChip(
    text: String,
    description: String,
    tag: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.outline
    Box(
        // 外层:触摸目标 / 点击 / 语义。**宽度不定**,由内层的 widthIn 决定,
        // 因此二者恒等宽 —— 触摸区与视觉块左右对齐。
        modifier = Modifier
            // 显式 48dp 兜底:M3 的 minimumInteractiveComponentSize 受
            // LocalMinimumInteractiveComponentEnforcement 控制,实测在 Robolectric 下
            // 只给到 36dp。§2.7 是硬约束,不能靠一条会失效的隐式规则。
            .heightIn(min = 48.dp)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // 内层:纯视觉。虚线描边贴文字,不被 48dp 触摸目标撑高。
        Box(
            modifier = Modifier
                .widthIn(min = CHIP_MIN_WIDTH, max = CHIP_MAX_WIDTH)
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(8.dp))
                .drawBehind {
                    drawRoundRect(
                        color = borderColor,
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(5.dp.toPx(), 4.dp.toPx()),
                            ),
                        ),
                    )
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = ImageVector.vectorResource(
                        if (expanded) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down,
                    ),
                    contentDescription = null,
                )
            }
        }
    }
}

/**
 * 芯片本体:常驻行与 series 浮层共用,tag 与文案由调用方给。
 *
 * ## 三条排布规则都在这里,而且只能在这里
 *
 * 1. **宽度在 [CHIP_MIN_WIDTH]..[CHIP_MAX_WIDTH] 之间**:下限保证「一行最多 3 块」,
 *    上限保证「长名不吃掉整行」。用户的裁决是「chip 跟随内容长度变化」——
 *    widthIn 保留了这个跟随,只是把它关进笼子。
 * 2. **内部文字居中**:textAlign = TextAlign.Center。注意它只在**芯片比文字宽**时看得见
 *    —— 单行且宽度正好包住内容时居中不产生任何位移,那是正常的;真正生效的场合是
 *    **文字折行**(第二行比第一行短,不居中就左对齐成锯齿)与统一最小宽度。
 *    刻意**不**给 label 加 fillMaxWidth:那会把芯片撑到父级宽度、破坏「跟随内容」。
 * 3. **内容可换行**:softWrap = true。芯片数量占几行由 [ChipFlow] 管,
 *    与「单块芯片内文字折行」是两个互不冲突的维度 —— 用户 2026-10-21 特意点明过。
 */
@Composable
private fun <T> SingleSelectChip(
    item: T,
    id: String,
    selected: Boolean,
    tag: String,
    chipDescription: @Composable (T) -> String,
    chipLabel: @Composable (T) -> String,
    chipSuffix: @Composable (T) -> String,
    textMaxLines: Int,
    onSelect: (String) -> Unit,
    onPositioned: ((Rect) -> Unit)? = null,
) {
    val description = chipDescription(item)
    // **截断只作用于名称,后缀完整保留**:后缀是「未勾选」这类固定短标记,
    // 长度不随数据增长;把它一起截断会让「这卷被排除」这条关键信息时有时无
    // —— 名字越长越容易被砍掉,正好是最需要看标记的场合(2026-10-21 改判)。
    val label = truncateChipLabel(chipLabel(item)) + chipSuffix(item)
    FilterChip(
        selected = selected,
        onClick = { onSelect(id) },
        label = {
            Text(
                text = label,
                textAlign = TextAlign.Center,
                // 显式给 softWrap:将来有人想改行数时,会先看到这一行注释,
                // 知道它动的是「可换行」这条用户裁决,而不是排版微调。
                softWrap = true,
                // 行数上限是**芯片内文字**的行数(见 [CHIP_TEXT_MAX_LINES]):
                // 传 CHIP_TEXT_MAX_LINES(两级相同)。overflow 用 Ellipsis 兜超出的行,
                // 因为这里已有前置的字符级截断(首尾保留),剩下的溢出只可能是
                // 「4 行塞不下」,再切字符没有意义。
                maxLines = textMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier
            .widthIn(min = CHIP_MIN_WIDTH, max = CHIP_MAX_WIDTH)
            .testTag(tag)
            .semantics { contentDescription = description }
            .then(
                if (onPositioned != null) {
                    Modifier.onGloballyPositioned { onPositioned(it.boundsInWindow()) }
                } else {
                    Modifier
                },
            ),
    )
}

