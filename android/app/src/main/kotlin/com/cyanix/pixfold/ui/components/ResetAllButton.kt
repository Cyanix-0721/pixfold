package com.cyanix.pixfold.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cyanix.pixfold.R

const val TAG_RESET_ALL = "reset-all"
const val TAG_RESET_ALL_DIALOG = "reset-all-dialog"
const val TAG_RESET_ALL_CONFIRM = "reset-all-confirm"

/**
 * 「重置全部调整」:步骤页签行右侧的纯图标按钮 + 长按说明 + 二次确认。
 *
 * 工作流 A/B 共用(两侧状态类型不同,故只共享交互外壳,重置逻辑由调用方通过
 * [onReset] 提供)。
 *
 * ## 为什么是纯图标 + 长按浮字
 *
 * 用户 2026-10-14 裁决:「只有图标,长按时悬浮显示文字说明」。文字版按钮会与
 * 页签行抢宽度,而重置是**低频破坏性动作**,不该常驻占用标题区。
 * 长按出说明、轻点走确认,这套手势共存由 M3 的 `TooltipBox` 保证 ——
 * 它在长按触发后消费随后的抬手事件,故子节点 `onClick` 不会跟着触发;
 * 它只在 `PointerEventPass.Initial` 观察、不消费,普通短按照样穿到 `IconButton`
 * (依据与实读结论同 `SortAndPreviewPage.ColumnCountButton`)。
 *
 * ## 为什么要二次确认(而不是直接重置)
 *
 * 纯图标按钮**没有文字提示它有多危险** —— 误触的代价是整条工作流的调整全丢。
 * 产品约束 5 也要求破坏性动作可解释,故轻点先弹 `AlertDialog` 列明清除范围。
 *
 * @param onReset 用户**确认后**调用;纯图标按钮自身不碰任何状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetAllButton(
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 文案先在组合上下文解析:tooltip / 对话框 lambda 都不是 @Composable
    val cd = stringResource(R.string.common_reset_all_cd)
    val hint = stringResource(R.string.common_reset_all_hint)
    val title = stringResource(R.string.common_reset_all_title)
    val body = stringResource(R.string.common_reset_all_body)
    val confirm = stringResource(R.string.common_reset_all_confirm)

    var showDialog by remember { mutableStateOf(false) }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Below,
        ),
        tooltip = {
            PlainTooltip { Text(text = hint) }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = { showDialog = true },
            modifier = modifier
                .testTag(TAG_RESET_ALL)
                // 纯图标 => 无障碍描述必须自己给(约束 7)
                .semantics { contentDescription = cd },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_restart_alt),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        onReset()
                    },
                    modifier = Modifier.testTag(TAG_RESET_ALL_CONFIRM),
                ) {
                    Text(confirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            modifier = Modifier.testTag(TAG_RESET_ALL_DIALOG),
        )
    }
}
