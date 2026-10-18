package com.cyanix.pixfold.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R

/** 工作区状态条的根节点。 */
const val TAG_WORKSPACE_BAR = "workspace-bar"

/** 条上的动作按钮（选择 / 更换）。 */
const val TAG_WORKSPACE_ACTION = "workspace-action"

/**
 * 工作区状态条（D2a）—— 常驻在工作流 A 的页签行**上方**。
 *
 * ## 为什么长在这里，而不是首页加第三张卡
 *
 * HANGOFF §4「工作区选取与入口分层」（2026-10-15 用户裁决）把三条渠道分了三层落点：
 * 应用内选目录 → **工作流内部**；外部分享 → Activity 级入口；恢复上次授权 → 首页**从属层**。
 * 理由原文是「Workspace 是**范围**，两个工作流是**任务**，层级不同」，摆成三张等权卡会误导用户。
 *
 * 所以这里**不是**第 4 个步骤页签（那会打破 3 步线性流程，并牵动 `workflowAStepIndex()` /
 * `goToWorkflowAStep()` / `popWorkflowStep()` 与 ~30 条导航测试）；它是一行**从属信息**，
 * 和页签行同属容器层，不参与步骤推进。
 *
 * ## 状态到文案的映射
 *
 * | 状态 | 左侧文字 | 右侧按钮 |
 * | --- | --- | --- |
 * | [WorkspaceState.None] | 未选择工作区 | 选择文件夹 |
 * | [WorkspaceState.Loading] | 正在扫描文件夹… | （无，扫描中不给动作） |
 * | [WorkspaceState.Ready] | 当前工作区：{名字} | 更换 |
 * | [WorkspaceState.Denied] | 授权已失效，请重新选择 | 重新选择 |
 * | [WorkspaceState.Failed] | 无法读取该文件夹 | 重新选择 |
 *
 * **无授权时也显示「未选择工作区」而不是整条隐藏**：用户需要知道当前看的是演示数据，
 * 否则会以为 D1 的 mock 数据是真扫出来的。文案见 `workspace_demo_note`。
 *
 * 状态由调用方持有（本组件无状态），符合本项目「本地状态权威 + 初值取自上层 +
 * 每次变更上报」的约定。
 *
 * @param state 当前工作区状态。
 * @param onPick 用户要求选择/更换目录。**所有状态共用这一个回调** ——
 *   更换与重选在 SAF 层面是同一个动作（再走一次 `ACTION_OPEN_DOCUMENT_TREE`），
 *   拆成两个回调只会让调用方多写一个无意义的转接。
 */
@Composable
fun WorkspaceBar(
    state: WorkspaceState,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 文案在组合上下文里解析：semantics{} 的 lambda 不是 @Composable。
    val actionLabel: String
    val actionCd: String
    val statusText: String
    val statusCd: String?
    when (state) {
        WorkspaceState.None -> {
            actionLabel = stringResource(R.string.workspace_pick)
            actionCd = stringResource(R.string.workspace_pick_cd)
            statusText = stringResource(R.string.workspace_demo_note)
            statusCd = null
        }
        WorkspaceState.Loading -> {
            actionLabel = ""
            actionCd = ""
            statusText = stringResource(R.string.workspace_loading)
            statusCd = null
        }
        is WorkspaceState.Ready -> {
            actionLabel = stringResource(R.string.workspace_change)
            actionCd = stringResource(R.string.workspace_change_cd)
            statusText = stringResource(R.string.workspace_current, state.handle.displayName)
            statusCd = stringResource(R.string.workspace_current_cd, state.handle.displayName)
        }
        is WorkspaceState.Denied -> {
            actionLabel = stringResource(R.string.workspace_pick)
            actionCd = stringResource(R.string.workspace_pick_cd)
            // 带上目录名：「哪个目录失效了」对用户是有用信息，否则不知道该重选哪个。
            statusText = state.handle
                ?.takeIf { it.displayName.isNotEmpty() }
                ?.let { stringResource(R.string.workspace_denied, it.displayName) }
                ?: stringResource(R.string.workspace_denied_generic)
            statusCd = null
        }
        is WorkspaceState.Failed -> {
            actionLabel = stringResource(R.string.workspace_pick)
            actionCd = stringResource(R.string.workspace_pick_cd)
            statusText = stringResource(R.string.workspace_failed)
            statusCd = null
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_WORKSPACE_BAR)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = when (state) {
                is WorkspaceState.Denied, is WorkspaceState.Failed ->
                    MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            // 长目录名不换行：这一行是**从属信息**，撑高会把页签行推走。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (statusCd != null) {
                        Modifier.semantics { contentDescription = statusCd }
                    } else {
                        Modifier
                    },
                ),
        )
        // 扫描中不给按钮：此时再点一次会并发两个扫描，且用户没有可做的决定。
        if (actionLabel.isNotEmpty()) {
            TextButton(
                onClick = onPick,
                modifier = Modifier
                    .testTag(TAG_WORKSPACE_ACTION)
                    .semantics { contentDescription = actionCd },
            ) {
                Text(actionLabel)
            }
        }
    }
}
