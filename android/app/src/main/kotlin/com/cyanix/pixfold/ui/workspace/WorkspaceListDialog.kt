package com.cyanix.pixfold.ui.workspace

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.source.WorkspaceListing
import com.cyanix.pixfold.ui.components.PinToggle
import com.cyanix.pixfold.ui.components.PixFoldDialogShell
import com.cyanix.pixfold.ui.components.pinTag

/** 弹窗容器 tag。 */
const val TAG_WORKSPACE_DIALOG = "workspace-list-dialog"

/** 「添加文件夹…」按钮 tag。 */
const val TAG_WORKSPACE_ADD = "workspace-list-add"

/** 「移除时同时撤销系统授权」开关 tag。 */
const val TAG_WORKSPACE_RELEASE_SWITCH = "workspace-list-release"

/**
 * 列表正文的高度上限。
 *
 * 取 300dp ≈ 视口 38%（361×794dp 屏）。不设上限时，8 条 × 56dp 行高 + 两个分区
 * 标题会把「添加文件夹…」和撤销开关挤出屏幕，而它们下面再没有别的落点。
 */
private val WORKSPACE_LIST_MAX_HEIGHT = 300.dp

/** 单行 tag（按 treeUri 区分，便于测试点中具体某一条）。 */
fun workspaceRowTag(treeUri: String): String = "workspace-row-$treeUri"

/** 单行 ✕ 按钮 tag。 */
fun workspaceRemoveTag(treeUri: String): String = "workspace-remove-$treeUri"

/**
 * 行内 [PinToggle] 自身的 tag。
 *
 * 为什么不用 `PinToggle` 的默认 TAG_PIN_TOGGLE：列表里同时有多行，默认值会在同一棵
 * 语义树里重复出现多次，任何 `onNodeWithTag(TAG_PIN_TOGGLE)` 都会变成 ambiguous。
 * 对外可寻址的入口是 [pinTag]（挂在 48dp 触控容器上，见 [PinHitTarget]）。
 */
private fun pinInnerTag(treeUri: String): String = "pin-inner-$treeUri"

/**
 * 全钉住时给调用方的提示文案。
 *
 * 存在的第二个理由是 lint：`workspace_reject_all_pinned` 若在本模块里没有任何引用，
 * `UnusedResources` 会在 `warningsAsErrors = true` 下硬失败。调用方（Task 7）只需写
 * `rejectHint = rejectAllPinnedHint()`，不必自己拼文案。
 */
@Composable
fun rejectAllPinnedHint(): String = stringResource(R.string.workspace_reject_all_pinned)

/**
 * 工作区列表弹窗：两分区 + 图钉 + 拖拽排序 + 「移除时同时撤销授权」开关。
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
 *   **本组件不再排序、也不截断**：上限 8 条是存储层的事，UI 侧截断会让
 *   「列表里看不到的那条」变成无法解释的黑洞。
 * @param currentTreeUri 当前正在打开的工作区 treeUri；匹配的那条标「使用中」。
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
    // 持久化会让下次手滑删掉一条就永久失去授权，而那是不可逆的
    // （用户要重新走一次系统目录选择器）。
    var releaseGrant by remember { mutableStateOf(false) }
    val granted = listings.filter { it.granted }
    val stale = listings.filterNot { it.granted }

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
                )
            }
            if (granted.isNotEmpty()) {
                SectionLabel(R.string.workspace_section_granted)
                WorkspaceDragList(granted, onMove = { f, t -> onMove(f, t) }) { entry ->
                    WorkspaceRow(entry, currentTreeUri, releaseGrant, onOpen, onTogglePin, onRemove)
                }
            }
            if (stale.isNotEmpty()) {
                SectionLabel(R.string.workspace_section_stale)
                Text(
                    text = stringResource(R.string.workspace_section_stale_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 分区内下标 → 整表下标：下分区要加 granted.size 偏移。
                // 少了这一步拖拽会改错条目，而且不会崩，只是「拖了没反应」。
                WorkspaceDragList(
                    stale,
                    onMove = { f, t -> onMove(granted.size + f, granted.size + t) },
                ) { entry ->
                    WorkspaceRow(entry, currentTreeUri, releaseGrant, onOpen, onTogglePin, onRemove)
                }
            }
            TextButton(onClick = onAdd, modifier = Modifier.testTag(TAG_WORKSPACE_ADD)) {
                Text(stringResource(R.string.workspace_list_add))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workspace_release_switch), Modifier.weight(1f))
                Switch(
                    checked = releaseGrant,
                    onCheckedChange = { releaseGrant = it },
                    modifier = Modifier.testTag(TAG_WORKSPACE_RELEASE_SWITCH),
                )
            }
        }
    }
}

/** 分区标题。 */
@Composable
private fun SectionLabel(@StringRes id: Int) {
    Text(
        text = stringResource(id),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 单行：目录名（+ 使用中）· 图钉 · ✕。
 *
 * 整行 `clickable` ⇒ 点击行本体就是「切换到这个工作区」，这是列表里最高频的动作，
 * 不该要求用户瞄准某颗按钮。
 */
@Composable
private fun WorkspaceRow(
    entry: WorkspaceListing,
    currentTreeUri: String?,
    releaseGrant: Boolean,
    onOpen: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onRemove: (treeUri: String, alsoRelease: Boolean) -> Unit,
) {
    val treeUri = entry.entry.handle.treeUri
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(workspaceRowTag(treeUri))
            .clickable { onOpen(treeUri) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.entry.handle.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (treeUri == currentTreeUri) {
                Text(
                    text = stringResource(R.string.workspace_in_use),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        PinHitTarget(entry, onTogglePin)
        IconButton(
            onClick = { onRemove(treeUri, releaseGrant) },
            modifier = Modifier
                .testTag(workspaceRemoveTag(treeUri))
                // 显式 48dp：`IconButton` 的 `minimumInteractiveComponentSize()` 不可依赖
                // （chip v4 的教训：Robolectric 下量到 36dp）。
                .size(48.dp),
        ) {
            Icon(
                // 图标来源见 `res/drawable/ic_close.xml` 的注释：官方矢量几何 +
                // painterResource；本项目没有 material-icons 依赖，
                // 故不能写 `Icons.Default.Close`（编译不过）。
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(
                    R.string.workspace_remove_cd,
                    entry.entry.handle.displayName,
                ),
            )
        }
    }
}

/**
 * 图钉的**触控容器**：视觉 32dp，触控 48dp。
 *
 * ## 为什么是两层而不是一层
 *
 * `PinToggle` 的 `size = 32.dp` 是**视觉**尺寸，且用户 2026-09-26 明确要求保持
 * （它被 6 处复用，改默认值会静默改变那些界面的密度）。但 AGENTS.md §2.7 要求
 * 触控目标 ≥48dp，而 `PinToggle` 内部是 `Surface`、**没有**
 * `minimumInteractiveComponentSize()` 兜底 ⇒ 只包一层 32dp 时真实可点区只有 32dp。
 *
 * ## 两层为什么不会双触发
 *
 * 内层 `PinToggle` 的 `clickable` 会消费 down 事件，外层的 `clickable` 走
 * `detectTapGestures` 的 `awaitFirstDown(requireUnconsumed = true)` ⇒ 收到已消费的
 * down 时不响应。于是：点中心 = 内层处理一次；点 32~48dp 的环带 = 外层处理一次。
 * 两个回调都指向同一个 `onTogglePin`，语义一致。
 *
 * 这条「不双触发」不是推理就算数：`WorkspaceListDialogTest` 的
 * `点图钉回调 togglePin` 断言了回调计数恰为 1。
 */
@Composable
private fun PinHitTarget(entry: WorkspaceListing, onTogglePin: (String) -> Unit) {
    val treeUri = entry.entry.handle.treeUri
    Box(
        Modifier
            .size(48.dp)
            // 对外可寻址的 tag 挂在 48dp 容器上：这样 `getUnclippedBoundsInRoot()`
            // 量到的就是真实触控区，而不是 32dp 的视觉尺寸。
            .testTag(pinTag(treeUri))
            .clickable { onTogglePin(treeUri) },
        contentAlignment = Alignment.Center,
    ) {
        PinToggle(
            isPinned = entry.entry.pinned,
            itemName = entry.entry.handle.displayName,
            onClick = { onTogglePin(treeUri) },
            tag = pinInnerTag(treeUri),
        )
    }
}
