package com.pixfold.d1.ui.workflowb

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.comic.includedVolumes
import com.pixfold.d1.domain.comic.previewOf
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.LangChoice
import com.pixfold.d1.domain.workflowb.WorkflowBState
import com.pixfold.d1.domain.workflowb.selectVolume

const val TAG_PACK_PAGE = "pack-page"
const val TAG_PACK_LIST = "pack-list"
const val TAG_XML_PREVIEW = "pack-xml-preview"
const val TAG_PAGE_PREVIEW = "pack-page-preview"
const val TAG_PAGE_PREVIEW_COUNT = "pack-page-preview-count"
const val TAG_PACK_VOLUME_SELECTOR = "pack-volume-selector"
const val TAG_PACK_PENDING = "pack-pending-issues"
const val TAG_PACK_EXCLUDED = "pack-excluded"
const val TAG_PACK_INCLUDED_COUNT = "pack-included-count"

/** 页码预览第 i 行。 */
fun packPageRowTag(index: Int) = "pack-page-row-$index"

/** 步骤 3 的卷切换 chip。 */
fun packVolumeChipTag(id: String) = "pack-volume-chip-$id"

/**
 * 工作流 B · 步骤 3:预览(验收第 14 项:**ComicInfo.xml 预览与页码列表预览一致**)。
 *
 * **一致性靠结构保证,不靠 UI 小心同步**:XML 与页码来自**同一次** [previewOf] 求值
 * (页序 → 重编号 → XML),不存在"两处各算一份、结果不一致"的可能。
 * 本页是纯展示 + 切卷,不持有自己的状态副本(切卷把新状态上报上层),
 * 因此也不会出现"预览与当前设置不一致"的陈旧副本。
 *
 * 本阶段(验收 11–14)只做**预览**:执行计划、危险动作二次确认、报告与撤销属 P6(15–17),
 * 故这里**不放**"确认打包"按钮 —— 不放一个点了没反应的假按钮。
 */
@Composable
fun PackPreviewPage(
    libraryState: WorkflowBState,
    onLibraryChange: (WorkflowBState) -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val volume = libraryState.activeVolume
    val preview = previewOf(volume)

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_PACK_PAGE),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(TAG_PACK_VOLUME_SELECTOR),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            libraryState.volumes.forEach { v ->
                FilterChip(
                    selected = v.id == volume.id,
                    onClick = { onLibraryChange(selectVolume(libraryState, v.id)) },
                    // 未勾选的卷在 chip 上直接标出:否则用户在步骤 1 取消勾选后,
                    // 到步骤 3 仍能看到完整预览,会误以为它也会被打包。
                    label = {
                        Text(
                            if (v.included) {
                                v.title.ifEmpty { v.id }
                            } else {
                                "${v.title.ifEmpty { v.id }}（未勾选）"
                            },
                        )
                    },
                    modifier = Modifier
                        .testTag(packVolumeChipTag(v.id))
                        .semantics { contentDescription = "预览卷 ${v.id}" },
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_PACK_LIST),
        ) {
            item(key = "header") {
                PackHeader(
                    volume = volume,
                    includedCount = includedVolumes(libraryState.volumes).size,
                    totalCount = libraryState.volumes.size,
                )
            }

            // ---- ComicInfo.xml 预览 ----
            item(key = "xml") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = "ComicInfo.xml 预览",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = preview.xml,
                        style = MaterialTheme.typography.bodySmall,
                        // 等宽字体:XML 的缩进与对齐才有意义
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag(TAG_XML_PREVIEW),
                    )
                    HorizontalDivider()
                }
            }

            // ---- 页码列表预览 ----
            item(key = "page-header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "页码预览（按当前页序重编号，共 ${preview.pages.size} 项）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag(TAG_PAGE_PREVIEW_COUNT),
                    )
                    Text(
                        text = "（序号从 001 起，扩展名统一小写；标「人工调整」的页被拖拽过）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .testTag(TAG_PAGE_PREVIEW),
                    )
                    if (!preview.consistent) {
                        Text(
                            text = "⚠️ 页码与页序不一致：" + preview.pageIssues.joinToString("；"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }

            items(preview.pages.indices.toList()) { i ->
                val page = preview.pages[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .testTag(packPageRowTag(i)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = page.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "← ${page.item.fileName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (volume.pageOrder.isManual(page.item.id)) {
                        // 人工调整过的页:预览必须能看出来(归档原型用蓝字标注同一意图)
                        Text(
                            text = "人工调整",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackHeader(volume: ComicVolume, includedCount: Int, totalCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = volume.dirPath,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "输出：${volume.outputDir}/${volume.cbzFileName}" +
                if (volume.outputExists) "（已存在同名文件）" else "",
            style = MaterialTheme.typography.labelSmall,
            color = if (volume.outputExists) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = "LanguageISO：${langLabel(volume)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!volume.included) {
            // 未勾选 = 本次不打包。必须显式说明,否则"能看到预览"会被当成"会被打包"。
            Text(
                text = "⚠️ 本卷未勾选，本次不会打包（在步骤 1 或步骤 2 的卷头可重新勾选）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TAG_PACK_EXCLUDED),
            )
        }
        // 用领域层 includedVolumes 求"本次实际会打包几卷"——预览必须回答"这次到底做什么",
        // 而不是只展示当前这一卷(用户勾选状态改了却看不到反馈,是 P3 那类"界面没用上领域规则")。
        Text(
            text = "本次将打包 $includedCount / $totalCount 卷" +
                if (includedCount < totalCount) "（未勾选的卷不会打包）" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TAG_PACK_INCLUDED_COUNT),
        )
        if (volume.pendingIssues.isNotEmpty()) {
            Text(
                text = "待确认：" + volume.pendingIssues.joinToString(" / "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag(TAG_PACK_PENDING),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

/** LanguageISO 的实际取值说明(不写入时给出"为什么")。 */
private fun langLabel(volume: ComicVolume): String = when (volume.language) {
    LangChoice.Zh -> "zh"
    LangChoice.Ja -> "ja"
    LangChoice.Other -> volume.otherLangCode.ifEmpty { "（未填代码，不写入）" }
    LangChoice.Unset -> "不写入（语言未确认）"
    LangChoice.Unknown -> "不写入（未知）"
    LangChoice.Skip -> "不写入"
}
