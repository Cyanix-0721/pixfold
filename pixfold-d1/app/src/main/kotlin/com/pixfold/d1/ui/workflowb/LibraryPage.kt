package com.pixfold.d1.ui.workflowb

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.workflowb.WorkflowBState
import com.pixfold.d1.domain.workflowb.selectVolume
import com.pixfold.d1.domain.workflowb.setVolumeIncluded

const val TAG_LIBRARY_PAGE = "library-page"
const val TAG_LIBRARY_LIST = "library-list"
const val TAG_LIBRARY_SUMMARY = "library-summary"

/** 卷卡片(整卡可选)。 */
fun volumeCardTag(id: String) = "library-volume-$id"

/** 卷的 included 勾选框。 */
fun volumeIncludedTag(id: String) = "library-included-$id"

/** 系列分组标题。 */
fun seriesHeaderTag(series: String) = "library-series-$series"

/**
 * 某卷的"建议值 + 来源"文本节点。
 *
 * 单独给 testTag 而不是靠 `hasAnyDescendant(hasText(...))`:后者在有多个同系列卷时
 * 会命中多个行,断言无从下手;挂上 tag 后可以精确断言"**这一卷**的建议与来源都显示了"。
 */
fun volumeSuggestionTag(id: String) = "library-suggestion-$id"

/**
 * 工作流 B · 步骤 1:漫画库(验收第 11 项的"建议值带来源"在列表层先可见)。
 *
 * 结构对齐归档 `library_page.dart` 的语义:
 *  - 按**当前 series 值**分组(用户改了系列名,分组随之变化);
 *  - 系列头显示卷数 + `N 卷待确认`(语言未确认 / 卷号缺失);
 *  - 每卷一行:`included` 勾选 + 目录路径 + 页数 + 建议 Title 与来源 + 待确认项 + 输出冲突标记;
 *  - 点卡片 = 选中该卷进入后续步骤(选中项由 [WorkflowBState.activeVolumeId] 承载,
 *    切步骤不会丢)。
 *
 * **为什么"待确认"在列表、卷头、计划页三处都要出现**:只在一处显示时,
 * 用户滚到别处就会忘记还有未确认的卷(HANGOFF §6.2 预览是主工作区)。
 */
@Composable
fun LibraryPage(
    state: WorkflowBState,
    onStateChange: (WorkflowBState) -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onNext: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_LIBRARY_PAGE),
    ) {
        Text(
            text = "漫画库：${state.rootPath}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            text = "识别到 ${state.seriesGroups.size} 个系列 / ${state.volumeCount} 卷 · " +
                "已勾选 ${state.includedCount} 卷 · 待确认 ${state.pendingCount} 卷",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .testTag(TAG_LIBRARY_SUMMARY),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_LIBRARY_LIST),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.seriesGroups.forEach { (series, volumes) ->
                item(key = "series-$series") {
                    SeriesHeader(series = series, volumes = volumes)
                }
                items(volumes, key = { it.id }) { volume ->
                    VolumeRow(
                        volume = volume,
                        selected = volume.id == state.activeVolume.id,
                        onSelect = { onStateChange(selectVolume(state, volume.id)) },
                        onToggleIncluded = { checked ->
                            onStateChange(setVolumeIncluded(state, volume.id, checked))
                        },
                    )
                }
            }

            if (onNext != null) {
                item(key = "next") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = onNext,
                            label = { Text("下一步 编辑元数据") },
                            modifier = Modifier
                                .testTag(TAG_LIBRARY_NEXT)
                                .semantics { contentDescription = "下一步 编辑元数据" },
                        )
                    }
                }
            }
        }
    }
}

const val TAG_LIBRARY_NEXT = "library-next"

/** 系列分组头(含"待确认"聚合,对齐归档 `_seriesHeader`)。 */
@Composable
private fun SeriesHeader(series: String, volumes: List<ComicVolume>) {
    val pending = volumes.count { it.needsConfirmation }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(seriesHeaderTag(series)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = series,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${volumes.size} 卷",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pending > 0) {
            // 待确认用 tertiary(主题语义色),不硬编码橙色
            Text(
                text = "$pending 卷待确认",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
    HorizontalDivider()
}

/**
 * 单卷一行。
 *
 * 建议 Title 与**来源**成对显示(验收第 11 项:建议值带来源)——
 * 只给值不给来源,用户无法判断该不该信它。
 */
@Composable
private fun VolumeRow(
    volume: ComicVolume,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleIncluded: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(volumeCardTag(volume.id))
            .semantics {
                contentDescription = "卷 ${volume.dirPath}${if (selected) "（当前选中）" else ""}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = volume.included,
            onCheckedChange = onToggleIncluded,
            modifier = Modifier
                .testTag(volumeIncludedTag(volume.id))
                .semantics { contentDescription = "勾选卷 ${volume.id}" },
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = volume.dirPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Text(
                        text = " · 当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 建议 + 来源都必须是**真的可读**:来源被省略号截掉等于没有来源,
            // 故这一行给 2 行余量(验收第 11 项要求来源可见,不是"存在但看不到")。
            Text(
                text = "${volume.pageCount} 页 · 建议 Title「${volume.titleSug.value}」" +
                    "（${volume.titleSug.source}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(volumeSuggestionTag(volume.id)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (volume.pendingIssues.isNotEmpty()) {
                    Text(
                        text = volume.pendingIssues.joinToString(" / "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (volume.outputExists) {
                    Text(
                        text = "输出冲突",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
