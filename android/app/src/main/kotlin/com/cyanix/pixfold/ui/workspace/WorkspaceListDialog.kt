package com.cyanix.pixfold.ui.workspace

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.source.WorkspaceListing
import com.cyanix.pixfold.ui.components.PixFoldDialogShell
import com.cyanix.pixfold.ui.components.PixFoldSegmentedItem
import com.cyanix.pixfold.ui.components.pinTag
import com.cyanix.pixfold.ui.theme.pixFoldSegmentedColors

/** 弹窗容器 tag。 */
const val TAG_WORKSPACE_DIALOG = "workspace-list-dialog"

/** 「添加文件夹…」按钮 tag。 */
const val TAG_WORKSPACE_ADD = "workspace-list-add"

/** 「移除时同时撤销系统授权」开关 tag。 */
const val TAG_WORKSPACE_RELEASE_SWITCH = "workspace-list-release"

/**
 * 列表正文的高度上限。
 *
 * 取 380dp ≈ 视口 48%（361×794dp 屏），让常见 3~4 条目录与底部动作在无需滚动时
 * 完整呈现，同时 8 条上限不会把标题或按钮挤出屏幕。
 */
private val WORKSPACE_LIST_MAX_HEIGHT = 440.dp

/** 单行 tag（按 treeUri 区分，便于测试点中具体某一条）。 */
fun workspaceRowTag(treeUri: String): String = "workspace-row-$treeUri"

/** 单行 ✕ 按钮 tag。 */
fun workspaceRemoveTag(treeUri: String): String = "workspace-remove-$treeUri"

/**
 * 全钉住时给调用方的提示文案。
 *
 * 存在的第二个理由是 lint：`workspace_reject_all_pinned` 若在本模块里没有任何引用，
 * `UnusedResources` 会在 `warningsAsErrors = true` 下硬失败。调用方只需写
 * `rejectHint = rejectAllPinnedHint()`，不必自己拼文案。
 */
@Composable
fun rejectAllPinnedHint(): String = stringResource(R.string.workspace_reject_all_pinned)

/**
 * 工作区列表弹窗：M3E 分段两分区 + 形变图钉按钮 + 长按拖拽排序 + 「移除时同时撤销授权」开关。
 *
 * ## 两个分区为什么按 `granted` 分，而不是按 `pinned`
 *
 * `granted` 是**运行时事实**（系统里这条 tree 授权此刻还有效吗），它决定了这一行
 * 点下去能不能真的读文件。`pinned` 只是**显示顺序**（钉住的排本区最前），两者正交。
 * 混在一起会让用户在「已持久授权」区里看到一条点不动、也不解释为什么的条目。
 *
 * ## 拖拽提交什么
 *
 * 存储顺序 = 显示顺序，`pinned` 只在渲染时稳定排序（规格 §4.3）。拖拽收到的
 * `(from, to)` 是**渲染顺序**里的下标，交给 `WorkspaceController` 后要按 treeUri
 * 换算回存储下标（规格 §4.4）—— 本组件不做换算，只把整表下标交出去。
 *
 * ## 为什么 `contentScrollable = false`
 *
 * `PixFoldDialogShell` 默认会给正文套 `verticalScroll`；本弹窗的正文自己带一个带
 * 高度上限的滚动容器（列表要自己滚，标题与底部开关留在滚动区里一起滚）。两层竖滚
 * 会撞 `IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints`，故这里显式关掉外面那层。
 *
 * ## 为什么没有底部按钮区
 *
 * 本弹窗无常驻按钮：关闭走系统返回键与点外部，按钮区留空是为了不额外造一个
 * 「关闭」按钮与每行的 ✕ 混淆。
 *
 * @param listings 存储层给出的完整列表（已按 granted 分两部分、区内已稳定排序）。
 * @param currentTreeUri 当前正在打开的工作区 treeUri；匹配的那条标「使用中」并高亮。
 * @param onRemove 移除一条；[alsoRelease] 是底部开关的当前值（默认 false）。
 * @param onMove `(from, to)` 是**整表存储下标**（下分区已由本组件加上偏移），
 *   调用方直接交给 `WorkspaceController.move`。
 * @param rejectHint 上一次添加被拒时的提示文本；`null` 表示没有提示。
 */
@Composable
fun WorkspaceListDialog(
    listings: List<WorkspaceListing>,
    currentTreeUri: String?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (treeUri: String, alsoRelease: Boolean) -> Unit,
    onTogglePin: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onAdd: () -> Unit,
    rejectHint: String? = null,
) {
    // 开关**不持久化**：它表达「这一次移除顺便撤销授权」的临时意图。
    // ⚠️ **调用方必须以 `if (show) { WorkspaceListDialog(...) }` 包裹整个组件**。
    var releaseGrant by remember { mutableStateOf(false) }
    val granted = listings.filter { it.granted }
    val stale = listings.filterNot { it.granted }
    val releaseLabel = stringResource(R.string.workspace_release_switch)

    PixFoldDialogShell(
        onDismissRequest = onDismiss,
        buttons = {},
        tag = TAG_WORKSPACE_DIALOG,
        contentScrollable = false,
        title = { Text(stringResource(R.string.workspace_list_title)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = WORKSPACE_LIST_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            if (rejectHint != null) {
                Text(
                    text = rejectHint,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (granted.isNotEmpty()) {
                SectionLabel(R.string.workspace_section_granted, first = true)
                // 上分区直接透传 f/t，**不加偏移**：与 WorkspaceController 拼表顺序一致。
                WorkspaceDragList(granted, onMove = { f, t -> onMove(f, t) }) { index, entry ->
                    WorkspaceRow(
                        entry = entry,
                        index = index,
                        count = granted.size,
                        currentTreeUri = currentTreeUri,
                        releaseGrant = releaseGrant,
                        onOpen = onOpen,
                        onTogglePin = onTogglePin,
                        onRemove = onRemove,
                    )
                }
            }
            if (stale.isNotEmpty()) {
                SectionLabel(R.string.workspace_section_stale, first = granted.isEmpty())
                Text(
                    text = stringResource(R.string.workspace_section_stale_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                // 分区内下标 → 整表下标：下分区要加 granted.size 偏移。
                WorkspaceDragList(
                    stale,
                    onMove = { f, t -> onMove(granted.size + f, granted.size + t) },
                ) { index, entry ->
                    WorkspaceRow(
                        entry = entry,
                        index = index,
                        count = stale.size,
                        currentTreeUri = currentTreeUri,
                        releaseGrant = releaseGrant,
                        onOpen = onOpen,
                        onTogglePin = onTogglePin,
                        onRemove = onRemove,
                    )
                }
            }
            FilledTonalButton(
                onClick = onAdd,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .testTag(TAG_WORKSPACE_ADD),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder_open),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.workspace_list_add),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
            PixFoldSegmentedItem(
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                trailingContent = {
                    Switch(
                        checked = releaseGrant,
                        onCheckedChange = { releaseGrant = it },
                        modifier = Modifier
                            .testTag(TAG_WORKSPACE_RELEASE_SWITCH)
                            .semantics { contentDescription = releaseLabel },
                    )
                },
            ) {
                Text(
                    text = releaseLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** 分区标题。 */
@Composable
private fun SectionLabel(@StringRes id: Int, first: Boolean) {
    Text(
        text = stringResource(id),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = if (first) 0.dp else 12.dp, bottom = 6.dp),
    )
}

/**
 * 单行：M3E 分段列表项承载「目录名（+ 选中勾与使用中）· 形变图钉 · 移除按钮」。
 */
@Composable
private fun WorkspaceRow(
    entry: WorkspaceListing,
    index: Int,
    count: Int,
    currentTreeUri: String?,
    releaseGrant: Boolean,
    onOpen: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onRemove: (treeUri: String, alsoRelease: Boolean) -> Unit,
) {
    val treeUri = entry.entry.handle.treeUri
    val isCurrent = treeUri == currentTreeUri
    val colors = if (isCurrent) {
        ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            supportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            trailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedSupportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTrailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else {
        pixFoldSegmentedColors()
    }

    PixFoldSegmentedItem(
        selected = isCurrent,
        onClick = { onOpen(treeUri) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = colors,
        contentPadding = PaddingValues(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag(workspaceRowTag(treeUri)),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PinToggleButton(entry = entry, onTogglePin = onTogglePin)
                IconButton(
                    onClick = { onRemove(treeUri, releaseGrant) },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier
                        .testTag(workspaceRemoveTag(treeUri))
                        // 显式 48dp 触控尺寸（AGENTS.md §2.7）。
                        .size(48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(
                            R.string.workspace_remove_cd,
                            entry.entry.handle.displayName,
                        ),
                    )
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.entry.handle.displayName,
                style = if (isCurrent) {
                    MaterialTheme.typography.titleSmallEmphasized
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrent) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.workspace_in_use),
                        style = MaterialTheme.typography.labelSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * M3E 形变图钉按钮：使用 [FilledTonalIconToggleButton] + [IconButtonDefaults.toggleableShapes]，
 * 外层保留 48dp 触控容器并通过 4dp 内缩呈现 40dp 视觉容器，单回调无双触发风险。
 */
@Composable
private fun PinToggleButton(entry: WorkspaceListing, onTogglePin: (String) -> Unit) {
    val treeUri = entry.entry.handle.treeUri
    val isPinned = entry.entry.pinned
    val pinCd = if (isPinned) {
        stringResource(R.string.pin_toggle_pinned, entry.entry.handle.displayName)
    } else {
        stringResource(R.string.pin_toggle_unpinned, entry.entry.handle.displayName)
    }
    FilledTonalIconToggleButton(
        checked = isPinned,
        onCheckedChange = { onTogglePin(treeUri) },
        shapes = IconButtonDefaults.toggleableShapes(),
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
            .testTag(pinTag(treeUri))
            .size(48.dp)
            .semantics { contentDescription = pinCd },
    ) {
        Icon(
            painter = painterResource(
                if (isPinned) R.drawable.ic_push_pin_filled else R.drawable.ic_push_pin_outlined,
            ),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}
