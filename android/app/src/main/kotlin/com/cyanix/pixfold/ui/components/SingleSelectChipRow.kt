package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R

/**
 * 单选芯片行最多常驻几个:超过就限个数并给「全部 N 个」入口。
 *
 * 5 是 2026-10-18 真机实测出来的数(361dp 宽下 5 个芯片正好排 2 行、占 294px);
 * 不按宽度测量动态算 —— Robolectric 把文本宽度算成 ~0,测量式判据离线无法断言。
 */
internal const val VISIBLE_SINGLE_SELECT_CHIPS = 5

/**
 * 单选芯片行的**全站唯一实现**:从 N 个具名条目里选 1 个。
 *
 * 当前用户(2026-10-19 用户裁决「只统一集合芯片与卷芯片」):
 * - [VolumeChipRow] —— 工作流 B 步骤 2 元数据页 / 步骤 3 打包预览页的换卷行
 * - 工作流 A 步骤 1 的集合/系列芯片行(两级)
 *
 * 其余 `FilterChip` 用法(布尔开关、排序字段枚举、冲突策略等)语义不同 —— 它们编辑的是
 * **参数**而不是「切换当前对象」,也没有溢出处理问题,不往这里塞。
 *
 * ## 为什么必须共用一份实现
 *
 * 三次真机缺陷都出自同一类错误(见下),两处各写一遍必然再犯:
 * 1. **2026-09-25**:原为 `Row + horizontalScroll`,5 卷时第 4、5 卷完全落在视口外,
 *    只能靠横滑够到,而界面上没有任何提示说明这里能滑 ⇒ 改 `FlowRow` 自动换行。
 *    横向滚动把可操作控件挤出视口,是本项目反复踩过的坑。
 * 2. **2026-10-18**:芯片行作为 `LazyColumn` 第一个 item 时一滚就整体滚出视口,
 *    换卷必须先回顶;而换卷是高频动作 ⇒ 移到滚动容器**之外**常驻。
 * 3. **2026-10-18(规模回归)**:常驻 + `FlowRow` 不设个数上限时,条目一多就把整块顶高
 *    —— 40 卷实测芯片块 2296px(834.9dp),几乎吃满 835.3dp 视口,**下方内容永久不可达**。
 *    ⇒ 超过 [VISIBLE_SINGLE_SELECT_CHIPS] 时只常驻 5 个(前 4 个 + 当前项,见
 *    [visibleSingleSelectItems]),另给「全部 N 个」入口,点开用 `ModalBottomSheet` 列全量。
 *
 * **为什么不额外加 `FlowRow(maxLines = 2)` 兜底**(试过,变异存活):常驻芯片已被
 * [visibleSingleSelectItems] 限到 5 个,`maxLines = 2` 永远不触发 ⇒ 它不承重;而在更窄的
 * 屏幕上 5 个芯片可能排到第 3 行,`maxLines = 2` 反而会把第 5 个(可能正是当前项)裁掉
 * 且不给任何提示。**承重的是条数上限,不是行数上限。**
 *
 * @param items 全部条目(顺序即展示顺序)。
 * @param activeId 当前项 id —— 必须留在常驻行内,否则用户看不出自己在看哪个。
 * @param idOf 取条目 id。
 * @param onSelect 选中某项。
 * @param selectorTag 常驻行的 testTag(各页不同,避免跨页冲突)。
 * @param chipTag 常驻行芯片的 testTag 生成器。
 * @param allEntryTag 「全部 N 个」入口的 testTag。
 * @param sheetTag sheet 容器的 testTag。
 * @param sheetListTag sheet 内列表的 testTag。
 * @param sheetChipTag sheet 内芯片的 testTag 生成器(与常驻行区分)。
 * @param chipDescription 芯片的无障碍文案(`semantics {}` 的 lambda 不是 @Composable,
 *   故由调用方在外层解析好再传进来)。
 * @param chipLabel 芯片标签 —— 各页文案不同(打包页还要标出「未勾选」项)。
 * @param allEntryText 「全部 N 个」入口与 sheet 标题的文案(计数由本组件传入)。
 *   卷芯片用「全部 N 卷」,集合芯片用「全部 N 项」—— 统一组件不能把量词写死。
 * @param maxLines 常驻行的最大行数。默认不限(自动换行到底)。
 *   两级芯片的行长会传 1:「选中系列后把这行压缩到一行」,把高度让给第 2 级行。
 *   **一旦限制行数,溢出入口的判定就放宽为「条目多于 1 个」**(见下方理由),
 *   否则被裁掉的那部分条目彻底不可达。
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
    sheetTag: String,
    sheetListTag: String,
    sheetChipTag: (String) -> String,
    chipDescription: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    allEntryText: @Composable (Int) -> String = { n -> pluralStringResource(R.plurals.volume_all, n, n) },
    chipLabel: @Composable (T) -> Unit,
) {
    val total = items.size
    // 为什么 `maxLines` 受限时要放宽到「多于 1 个」:限行之后**看不见的条目不能再靠换行够到**,
    // 而一行的容量离线测不出来(Robolectric 把文本宽度算成 ~0)。宁可多给一个入口,
    // 也不能让某个条目既看不见、又没有任何入口可达 —— 那正是 2026-09-25 缺陷的形态。
    val rowCramped = maxLines != Int.MAX_VALUE
    val overflowing = total > VISIBLE_SINGLE_SELECT_CHIPS || (rowCramped && total > 1)
    val visible = if (total > VISIBLE_SINGLE_SELECT_CHIPS) {
        visibleSingleSelectItems(items, activeId, idOf, VISIBLE_SINGLE_SELECT_CHIPS)
    } else {
        items
    }
    var sheetOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(selectorTag),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = maxLines,
        ) {
            visible.forEach { item ->
                SingleSelectChip(
                    item = item,
                    id = idOf(item),
                    selected = idOf(item) == activeId,
                    tag = chipTag(idOf(item)),
                    chipDescription = chipDescription,
                    chipLabel = chipLabel,
                    onSelect = onSelect,
                )
            }
        }
        if (overflowing) {
            TextButton(
                onClick = { sheetOpen = true },
                modifier = Modifier
                    .padding(start = 4.dp)
                    .heightIn(min = 48.dp)
                    .testTag(allEntryTag),
            ) {
                Text(allEntryText(total))
            }
        }
    }

    if (sheetOpen) {
        AllItemsSheet(
            items = items,
            activeId = activeId,
            idOf = idOf,
            onSelect = onSelect,
            onDismiss = { sheetOpen = false },
            sheetTag = sheetTag,
            sheetListTag = sheetListTag,
            sheetChipTag = sheetChipTag,
            chipDescription = chipDescription,
            chipLabel = chipLabel,
            allEntryText = allEntryText,
        )
    }
}

/**
 * 溢出时页面上仍常驻哪几项:前 [maxVisible] - 1 项 + 当前项。
 *
 * 当前项必须留在行内(否则用户看不出"我在改哪个");它若已在前几项里,
 * 就顺延补足到 [maxVisible] 个。纯函数,便于离线断言。
 */
internal fun <T> visibleSingleSelectItems(
    items: List<T>,
    activeId: String,
    idOf: (T) -> String,
    maxVisible: Int,
): List<T> {
    if (maxVisible <= 0) return emptyList()
    val head = items.take(maxVisible - 1)
    val active = items.firstOrNull { idOf(it) == activeId }
    return when {
        active == null -> items.take(maxVisible)
        head.any { idOf(it) == activeId } -> items.take(maxVisible)
        else -> head + active
    }
}

/** 芯片本体:常驻行与「全部 N 个」sheet 共用,tag 与文案由调用方给。 */
@Composable
private fun <T> SingleSelectChip(
    item: T,
    id: String,
    selected: Boolean,
    tag: String,
    chipDescription: @Composable (T) -> String,
    chipLabel: @Composable (T) -> Unit,
    onSelect: (String) -> Unit,
) {
    val description = chipDescription(item)
    FilterChip(
        selected = selected,
        onClick = { onSelect(id) },
        label = { chipLabel(item) },
        modifier = Modifier
            .testTag(tag)
            .semantics { contentDescription = description },
    )
}

/**
 * 「全部 N 个」sheet:列出所有条目供切换(只在条目数超 [VISIBLE_SINGLE_SELECT_CHIPS] 时可达)。
 *
 * 用 `ModalBottomSheet` 而不是新页面:切换是**轻量且可取消**的动作,不需要页面级
 * 导航状态(也就不会把工作流的返回栈搅乱);也不引新依赖 —— material3 已带。
 * 选中后立即关闭:页面内容已经随 `onSelect` 更新,再播一段 hide 动画只是延迟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> AllItemsSheet(
    items: List<T>,
    activeId: String,
    idOf: (T) -> String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    sheetTag: String,
    sheetListTag: String,
    sheetChipTag: (String) -> String,
    chipDescription: @Composable (T) -> String,
    chipLabel: @Composable (T) -> Unit,
    allEntryText: @Composable (Int) -> String,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        modifier = Modifier.testTag(sheetTag),
    ) {
        Text(
            text = allEntryText(items.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(bottom = 24.dp)
                .testTag(sheetListTag),
        ) {
            items(items, key = { idOf(it) }) { item ->
                SingleSelectChip(
                    item = item,
                    id = idOf(item),
                    selected = idOf(item) == activeId,
                    tag = sheetChipTag(idOf(item)),
                    chipDescription = chipDescription,
                    chipLabel = chipLabel,
                    onSelect = { id ->
                        onSelect(id)
                        onDismiss()
                    },
                )
            }
        }
    }
}
