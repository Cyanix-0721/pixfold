package com.cyanix.pixfold.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.ComicVolume

/**
 * 卷切换芯片行 —— 工作流 B 步骤 2 元数据页 / 步骤 3 打包预览页共用。
 *
 * 2026-10-19 起**本体已抽到 [SingleSelectChipRow]**:集合芯片与卷芯片是同一类交互
 * (从 N 个具名条目选 1 个,带溢出处理),两处各写一遍必然漂移 —— 三次真机缺陷
 * (横向滚动挤出视口 / 滚出视口 / 规模回归吃满视口)都出自同一处逻辑。
 * 本文件现在只剩「把卷映射成通用条目」这层薄适配:**对外签名、tag 体系、文案全部不变**,
 * 调用方(MetadataPage / PackPreviewPage)与既有测试无需改动。
 *
 * @param volumes 全部卷(顺序即展示顺序)。
 * @param activeId 当前卷 id —— 必须留在常驻行内,否则用户看不出自己在看哪一卷。
 * @param onSelect 选中某卷。
 * @param selectorTag 常驻行的 testTag(各页不同,避免跨页冲突)。
 * @param chipTag 常驻行芯片的 testTag 生成器。
 * @param allEntryTag 「全部 N 卷」入口的 testTag。
 * @param chipDescription 芯片的无障碍文案(传卷 id)。
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
    chipDescription: @Composable (String) -> String,
    modifier: Modifier = Modifier,
    chipLabel: @Composable (ComicVolume) -> String,
    chipSuffix: @Composable (ComicVolume) -> String = { "" },
) {
    SingleSelectChipRow(
        items = volumes,
        activeId = activeId,
        idOf = { it.id },
        onSelect = onSelect,
        selectorTag = selectorTag,
        chipTag = chipTag,
        allEntryTag = allEntryTag,
        // 卷芯片的无障碍文案按 id 生成(调用方传入的是 (String) -> String),
        // 通用底层要的是按条目生成,故在这里把卷映射回 id。
        chipDescription = { volume -> chipDescription(volume.id) },
        modifier = modifier,
        allEntryText = { n -> pluralStringResource(R.plurals.volume_all, n, n) },
        chipLabel = chipLabel,
        chipSuffix = chipSuffix,
    )
}

