package com.cyanix.pixfold.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.cyanix.pixfold.domain.comic.groupVolumes
import com.cyanix.pixfold.domain.model.ComicVolume

/**
 * 卷切换芯片行 —— **两级**(系列 → 卷),用于工作流 B 步骤 2 元数据页。
 *
 * 用户 2026-10-19 要求「打包漫画的元数据步骤也改下支持刚才的双层芯片选取」——
 * 与工作流 A 的集合芯片是**同一套交互**(1 级选系列、2 级选系列下各本),因此状态逻辑
 * 直接复用 [TwoLevelChipRows],这里只做「把卷映射成 1 级条目 + 注入工作流 B 的 tag 与文案」
 * 这层适配。
 *
 * ## 为什么不影响步骤 3 的打包预览页
 *
 * 步骤 3 的卷芯片带**勾选状态**(`setVolumeIncluded`,被排除的卷在芯片上直接标出来)。
 * 两级 + 勾选叠加时,1 级系列芯片无法同时表达「这个系列里有几卷被排除」而不新造一句文案,
 * 而在步骤 3 用户实际是逐卷核对勾选 —— 那里保持单级更省事、信息也更完整。
 *
 * ## tag 兼容
 *
 * **2 级芯片(即各卷本身)沿用调用方的 [ChipRowTags.chipTag]**(元数据页 = `volumeChipTag`),
 * 单本 1 级芯片也沿用它 —— 既有测试按这些 tag 点卷,语义没变;1 级系列芯片才用新 tag。
 *
 * @param volumes 全部卷(顺序即展示顺序,不在此重排)。
 * @param activeId 当前卷 id。
 * @param onSelect 选中某卷。
 * @param topTags 1 级行的 tag 体系。
 * @param childTags 2 级行的 tag 体系。
 * @param singleTopChipTag 单本 1 级芯片的 tag 生成器(通常与 [childTags] 的 `chipTag` 相同)。
 * @param topChipDescription 1 级芯片的无障碍文案(入参是分组本身,系列与单本可给不同文案)。
 * @param topChipLabel 1 级芯片标签(同上,入参是分组)。
 * @param topAllEntryText 1 级「全部 N 项」文案。
 * @param childChipDescription 2 级芯片的无障碍文案。
 * @param childChipLabel 2 级芯片标签。
 * @param childAllEntryText 2 级「全部 N 卷」文案。
 */
@Composable
fun VolumeChipRows(
    volumes: List<ComicVolume>,
    activeId: String,
    onSelect: (String) -> Unit,
    topTags: ChipRowTags,
    childTags: ChipRowTags,
    singleTopChipTag: (String) -> String,
    topChipDescription: @Composable (TwoLevelGroup<ComicVolume>) -> String,
    topChipLabel: @Composable (TwoLevelGroup<ComicVolume>) -> String,
    topAllEntryText: @Composable (Int) -> String,
    childChipDescription: @Composable (ComicVolume) -> String,
    childChipLabel: @Composable (ComicVolume) -> String,
    childAllEntryText: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
    childChipSuffix: @Composable (ComicVolume) -> String = { "" },
) {
    val groups = remember(volumes) {
        groupVolumes(volumes).map { group ->
            TwoLevelGroup(
                key = group.key,
                name = group.name,
                isSeries = group.isSeries,
                members = group.volumes,
            )
        }
    }

    TwoLevelChipRows(
        groups = groups,
        activeItemId = activeId,
        idOf = { it.id },
        onSelect = onSelect,
        topTags = topTags,
        childTags = childTags,
        singleTopChipTag = singleTopChipTag,
        topChipDescription = topChipDescription,
        topChipLabel = topChipLabel,
        topAllEntryText = topAllEntryText,
        childChipDescription = childChipDescription,
        childChipLabel = childChipLabel,
        childChipSuffix = childChipSuffix,
        childAllEntryText = childAllEntryText,
        modifier = modifier,
    )
}
