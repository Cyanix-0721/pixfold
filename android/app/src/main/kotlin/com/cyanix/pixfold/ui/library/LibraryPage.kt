package com.cyanix.pixfold.ui.library

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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.text.TextKey
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.selectVolume
import com.cyanix.pixfold.domain.library.setVolumeIncluded
import com.cyanix.pixfold.ui.components.PageActionBar
import com.cyanix.pixfold.ui.workspace.WorkspaceBar
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import com.cyanix.pixfold.ui.text.textOf

const val TAG_LIBRARY_PAGE = "library-page"

/**
 * 步骤 1 的空工作区提示（工作区扫不出任何卷）。
 *
 * 与步骤 2 / 步骤 3 的空态 tag 分开：三处文案不同，共用一个 tag 时测试无法区分
 * 是不是渲染了错误的那一页 —— 而「空态出现在错误的页上」正是路由缺陷的典型形态。
 */
const val TAG_LIBRARY_EMPTY = "library-page-empty"
const val TAG_LIBRARY_LIST = "library-list"
const val TAG_LIBRARY_SUMMARY = "library-summary"

/** 「下一步 编辑元数据」按钮。 */
const val TAG_LIBRARY_NEXT = "library-next"

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
 *  - 点卡片 = 选中该卷进入后续步骤(选中项由 [LibraryState.activeVolumeId] 承载,
 *    切步骤不会丢)。
 *
 * **为什么"待确认"在列表、卷头、计划页三处都要出现**:只在一处显示时,
 * 用户滚到别处就会忘记还有未确认的卷(HANGOFF §6.2 预览是主工作区)。
 */
@Composable
fun LibraryPage(
    state: LibraryState,
    onStateChange: (LibraryState) -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onNext: (() -> Unit)? = null,
    /**
     * 工作区状态（D2b）。`null` = **不显示**工作区条。
     *
     * 与工作流 A 的同名参数语义完全一致：默认 `null` 让既有测试的布局与断言一行都不用改。
     * 真实应用由 `PixFoldApp` 注入。用户 2026-10-19 裁决「工作流 B 复用工作区
     * （和工作流 A 同一套）」—— 因此这里显示的**就是同一个**工作区，不是第二份选择。
     */
    workspaceState: WorkspaceState? = null,
    /** 用户要求选择/更换工作区目录。仅 [workspaceState] 非 null 时会被调用。 */
    onPickWorkspace: () -> Unit = {},
) {
    // 要进 semantics{},故先在组合上下文里解析
    val nextCd = stringResource(R.string.lib_next_cd)
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_LIBRARY_PAGE),
    ) {
        // 工作区条常驻在标题行**之前**：它决定下面整个列表的来源，
        // 放在列表里会随滚动消失（与工作流 A 的落点一致，见 HANGOFF §4「从属层」）。
        if (workspaceState != null) {
            WorkspaceBar(state = workspaceState, onPick = onPickWorkspace)
        }
        Text(
            text = stringResource(R.string.lib_root, state.rootPath),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            text = pluralStringResource(
                R.plurals.lib_summary,
                state.seriesGroups.size,
                state.seriesGroups.size,
                state.volumeCount,
                state.includedCount,
                state.pendingCount,
            ),
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
            if (!state.hasVolumes) {
                // 空工作区:光有顶部的工作区条与一条「0 卷」摘要,下面一片空白,
                // 用户会以为还在加载。必须给一句明确的话 + 出口(工作区条上的「更换」)。
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.b_library_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp)
                            .testTag(TAG_LIBRARY_EMPTY),
                    )
                }
            }
            state.seriesGroups.forEach { (series, volumes) ->
                // 分组键保持语言无关(空串 = 未命名系列,见 groupVolumesBySeries 的 KDoc);
                // 只有**显示**时才翻译,故 testTag 用原始键、标题用解析结果。
                item(key = "series-$series") {
                    SeriesHeader(series = series, volumes = volumes)
                }
                items(volumes, key = { it.id }) { volume ->
                    VolumeRow(
                        volume = volume,
                        selected = volume.id == state.activeVolumeOrNull?.id,
                        onSelect = { onStateChange(selectVolume(state, volume.id)) },
                        onToggleIncluded = { checked ->
                            onStateChange(setVolumeIncluded(state, volume.id, checked))
                        },
                    )
                }
            }
        }

        // 下一步常驻底栏(台账 §2):向导式推进动作原先在列表末尾,长库要滚到底才够得到;
        // 顶部 Tab 能切目的地,但那是「平级视图索引」,与「按顺序推进」语义不同。
        if (onNext != null) {
            PageActionBar(
                trailing = {
                    Button(
                        onClick = onNext,
                        modifier = Modifier
                            .testTag(TAG_LIBRARY_NEXT)
                            .semantics { contentDescription = nextCd },
                    ) { Text(stringResource(R.string.lib_next)) }
                },
            )
        }
    }
}

/** 系列分组头(含"待确认"聚合,对齐归档 `_seriesHeader`)。 */
@Composable
private fun SeriesHeader(series: String, volumes: List<ComicVolume>) {
    val pending = volumes.count { it.needsConfirmation }
    // 分组键是语言无关的稳定字符串(空串 = 未命名系列)—— 翻译只发生在**显示**这一层。
    // 若把 `TextKey.UnnamedSeries` 存进键,切语言时同一批卷会被分到两个组里。
    val seriesTitle = if (series.isEmpty()) textOf(TextKey.UnnamedSeries) else series
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(seriesHeaderTag(series)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = seriesTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pluralStringResource(R.plurals.lib_series_volume_count, volumes.size, volumes.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pending > 0) {
            // 待确认用 tertiary(主题语义色),不硬编码橙色
            Text(
                text = stringResource(R.string.lib_series_pending, pending),
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
    // 要进 semantics{} / 需要现场解析领域 `Msg`,故先在组合上下文里取到局部变量
    val selectedSuffix = if (selected) stringResource(R.string.lib_volume_cd_selected) else ""
    val volumeCd = stringResource(R.string.lib_volume_cd, volume.dirPath, selectedSuffix)
    val includeCd = stringResource(R.string.lib_include_cd, volume.id)
    val issuesText = volume.pendingIssues.map { textOf(it) }
        .joinToString(stringResource(R.string.lib_issue_separator))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(volumeCardTag(volume.id))
            .semantics {
                contentDescription = volumeCd
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = volume.included,
            onCheckedChange = onToggleIncluded,
            modifier = Modifier
                .testTag(volumeIncludedTag(volume.id))
                .semantics { contentDescription = includeCd },
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
                        text = stringResource(R.string.lib_volume_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 建议 + 来源都必须是**真的可读**:来源被省略号截掉等于没有来源,
            // 故这一行给 2 行余量(验收第 11 项要求来源可见,不是"存在但看不到")。
            Text(
                text = pluralStringResource(
                    R.plurals.lib_volume_suggestion,
                    volume.pageCount,
                    volume.pageCount,
                    volume.titleSug.value.orEmpty(),
                    volume.titleSug.source,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(volumeSuggestionTag(volume.id)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (issuesText.isNotEmpty()) {
                    Text(
                        text = issuesText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (volume.outputExists) {
                    Text(
                        text = stringResource(R.string.lib_output_conflict),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
