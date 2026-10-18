package com.pixfold.d1.ui.components

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
import com.pixfold.d1.R
import com.pixfold.d1.domain.model.ComicVolume

/**
 * 卷芯片行最多常驻几个卷:超过就限个数并给「全部 N 卷」入口。
 *
 * 5 是 2026-10-18 真机实测出来的数(361dp 宽下 5 个芯片正好排 2 行、占 294px);
 * 不按宽度测量动态算 —— Robolectric 把文本宽度算成 ~0,测量式判据离线无法断言。
 */
internal const val VISIBLE_VOLUME_CHIPS = 5

/**
 * 卷切换芯片行 —— **全站唯一实现**(工作流 B 步骤 2 元数据页 / 步骤 3 打包预览页共用)。
 *
 * **为什么抽成共享组件**:两页原本各写一份「`FlowRow` 遍历全部卷」,2026-09-25 那轮
 * 同源缺陷(第 4、5 卷落在视口外)也是两处各修一遍 —— 与 `IssueFilterRow` 的教训相同:
 * 同一种交互在多处各写一遍,必然漂移。现在规模上限、当前卷保留、sheet 全量列表
 * 只有这一份实现。
 *
 * ## 三段历史(缺一段就会把已修的问题再犯一遍)
 *
 * 1. **2026-09-25 真机实测**:原为 `Row + horizontalScroll`。5 卷时第 4、5 卷
 *    完全落在视口外,只能靠横向滑动才够得着,而界面上没有任何提示说明这里能滑
 *    ⇒ 改 `FlowRow` 自动换行(横向滚动把可操作控件挤出视口,是本项目反复踩过的坑)。
 * 2. **2026-10-18 真机实测(P2)**:芯片行作为 `LazyColumn` 的第一个 item 时,
 *    一旦滚动就整体滚出视口,换卷必须先回顶;而换卷是高频动作
 *    ⇒ 移到 `LazyColumn` **之外**常驻。
 * 3. **2026-10-18 真机实测(规模回归)**:常驻 + `FlowRow` 不设个数上限时,卷一多就把
 *    整块顶高 —— 40 卷实测芯片块 2296px(834.9dp),几乎吃满 835.3dp 的列表视口,
 *    **下方内容永久不可达**(连续 11 次全屏 swipe 后仍不可见)。同一份数据把该行放回
 *    `LazyColumn` 内时下方内容可达 ⇒ 这是「个数无上限」引入的回归,不是既有缺陷。
 *    ⇒ 超过 [VISIBLE_VOLUME_CHIPS] 时**只常驻 5 个芯片**(前 4 卷 + 当前卷,见
 *    [visibleVolumeChips]),另给「全部 N 卷」入口,点开用 `ModalBottomSheet` 列全量。
 *    卷数不超限时**不出入口** —— 5 卷是今天的真机形态,不许降级。
 *
 * **为什么不额外加 `FlowRow(maxLines = 2)` 兜底**(试过,变异存活):常驻芯片已被
 * [visibleVolumeChips] 限到 5 个,`maxLines = 2` 永远不触发 ⇒ 它不承重;而在更窄的
 * 屏幕上 5 个芯片可能排到第 3 行,`maxLines = 2` 反而会把第 5 个(可能正是当前卷)
 * 裁掉且不给任何提示。**承重的是芯片个数上限,不是行数上限。**
 *
 * @param volumes 全部卷(顺序即展示顺序)。
 * @param activeId 当前卷 id —— 必须留在常驻行内,否则用户看不出自己在看哪一卷。
 * @param onSelect 选中某卷。
 * @param selectorTag 常驻行的 testTag(各页不同,避免跨页冲突)。
 * @param chipTag 常驻行芯片的 testTag 生成器。
 * @param allEntryTag 「全部 N 卷」入口的 testTag。
 * @param sheetTag sheet 容器的 testTag。
 * @param sheetListTag sheet 内列表的 testTag。
 * @param sheetChipTag sheet 内芯片的 testTag 生成器(与常驻行区分)。
 * @param chipDescription 芯片的无障碍文案(`semantics {}` 的 lambda 不是 @Composable,
 *   故由调用方在外层解析好再传进来)。
 * @param chipLabel 芯片标签 —— 各页文案不同(步骤 3 还要标出「未勾选」卷)。
 */
@Composable
fun VolumeChipRow(
    volumes: List<ComicVolume>,
    activeId: String,
    onSelect: (String) -> Unit,
    selectorTag: String,
    chipTag: (String) -> String,
    allEntryTag: String,
    sheetTag: String,
    sheetListTag: String,
    sheetChipTag: (String) -> String,
    chipDescription: @Composable (String) -> String,
    modifier: Modifier = Modifier,
    chipLabel: @Composable (ComicVolume) -> Unit,
) {
    val total = volumes.size
    val overflowing = total > VISIBLE_VOLUME_CHIPS
    val visible = if (overflowing) visibleVolumeChips(volumes, activeId) else volumes
    var sheetOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(selectorTag),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            visible.forEach { v ->
                VolumeChip(
                    volume = v,
                    selected = v.id == activeId,
                    tag = chipTag(v.id),
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
                Text(pluralStringResource(R.plurals.volume_all, total, total))
            }
        }
    }

    if (sheetOpen) {
        AllVolumesSheet(
            volumes = volumes,
            activeId = activeId,
            onSelect = onSelect,
            onDismiss = { sheetOpen = false },
            sheetTag = sheetTag,
            sheetListTag = sheetListTag,
            sheetChipTag = sheetChipTag,
            chipDescription = chipDescription,
            chipLabel = chipLabel,
        )
    }
}

/**
 * 溢出时页面上仍常驻哪几卷:前 [VISIBLE_VOLUME_CHIPS] - 1 卷 + 当前卷。
 *
 * 当前卷必须留在行内(否则用户看不出"我在改哪一卷");它若已在前几卷里,
 * 就顺延补足到 [VISIBLE_VOLUME_CHIPS] 个。纯函数,便于离线断言。
 */
internal fun visibleVolumeChips(volumes: List<ComicVolume>, activeId: String): List<ComicVolume> {
    val head = volumes.take(VISIBLE_VOLUME_CHIPS - 1)
    val active = volumes.firstOrNull { it.id == activeId }
    return when {
        active == null -> volumes.take(VISIBLE_VOLUME_CHIPS)
        head.any { it.id == active.id } -> volumes.take(VISIBLE_VOLUME_CHIPS)
        else -> head + active
    }
}

/** 卷芯片本体:页面常驻行与「全部 N 卷」sheet 共用,tag 与文案由调用方给。 */
@Composable
private fun VolumeChip(
    volume: ComicVolume,
    selected: Boolean,
    tag: String,
    chipDescription: @Composable (String) -> String,
    chipLabel: @Composable (ComicVolume) -> Unit,
    onSelect: (String) -> Unit,
) {
    val description = chipDescription(volume.id)
    FilterChip(
        selected = selected,
        onClick = { onSelect(volume.id) },
        label = { chipLabel(volume) },
        modifier = Modifier
            .testTag(tag)
            .semantics { contentDescription = description },
    )
}

/**
 * 「全部 N 卷」sheet:列出所有卷供切换(只在卷数超 [VISIBLE_VOLUME_CHIPS] 时可达)。
 *
 * 用 `ModalBottomSheet` 而不是新页面:换卷是**轻量且可取消**的动作,不需要页面级
 * 导航状态(也就不会把工作流的返回栈搅乱);也不引新依赖 —— material3 已带。
 * 选中后立即关闭:页面内容已经随 `onSelect` 更新,再播一段 hide 动画只是延迟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllVolumesSheet(
    volumes: List<ComicVolume>,
    activeId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    sheetTag: String,
    sheetListTag: String,
    sheetChipTag: (String) -> String,
    chipDescription: @Composable (String) -> String,
    chipLabel: @Composable (ComicVolume) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        modifier = Modifier.testTag(sheetTag),
    ) {
        Text(
            text = pluralStringResource(R.plurals.volume_all, volumes.size, volumes.size),
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
            items(volumes, key = { it.id }) { v ->
                VolumeChip(
                    volume = v,
                    selected = v.id == activeId,
                    tag = sheetChipTag(v.id),
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
