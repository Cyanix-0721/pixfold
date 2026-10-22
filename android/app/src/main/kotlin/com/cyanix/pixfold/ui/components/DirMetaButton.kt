package com.cyanix.pixfold.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
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

const val TAG_DIR_META = "dir-meta"
const val TAG_DIR_META_DIALOG = "dir-meta-dialog"
const val TAG_DIR_META_CONFIRM = "dir-meta-confirm"

/**
 * 「按目录结构获取」:步骤页签行右侧、紧挨「重置全部调整」的纯图标按钮。
 *
 * 把当前卷**全部字段**重新填成「仅由文件夹结构推导」的值 —— 这是绕开
 * `ComicInfo.xml` 的那条退路(见 `applyDirMetaSuggestions` 的 KDoc):
 * 「恢复建议值」回到的是**建议值**,而建议值在有 ComicInfo 时已被文件值顶掉。
 *
 * ## 与「重置全部调整」的分工
 *
 * - 重置全部:**整个工作流**的调整全丢,并回到步骤 1(低频、破坏面最大);
 * - 本按钮:**当前卷**的元数据按文件夹重来,不影响其他卷、不动 Steps 与报告。
 *
 * 两者并列放在页签行右侧,是因为它们都是「我想重来一遍」类动作,用户找它们时
 * 不该跨页面找。用户 2026-10-22 裁决原话:「右上角重置旁边加个根据目录结构
 * 自动获取填写到输入框的按钮」。
 *
 * ## 为什么也要二次确认
 *
 * 它一次覆盖 7 个字段,并且会**打上逐项例外标记**(用户的意图是「就要目录里这一套」)。
 * 误触的代价是刚手改完的元数据全被换掉,而纯图标按钮没有任何文字提示它有多危险。
 * 产品约束 5 要求破坏性动作可解释,故与重置同一套外壳:轻点先列明覆盖范围。
 *
 * @param onApply 用户**确认后**调用;按钮自身不碰任何状态。
 * @param enabled 目录推导不出任何值时置 false(例如目录名既无作者也无标题可清洗)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirMetaButton(
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 文案先在组合上下文解析:tooltip / 对话框 lambda 都不是 @Composable
    val cd = stringResource(R.string.common_dir_meta_cd)
    val hint = stringResource(R.string.common_dir_meta_hint)
    val title = stringResource(R.string.common_dir_meta_title)
    val body = stringResource(R.string.common_dir_meta_body)
    val confirm = stringResource(R.string.common_dir_meta_confirm)

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
            enabled = enabled,
            modifier = modifier
                .testTag(TAG_DIR_META)
                // 纯图标 => 无障碍描述必须自己给(约束 7)
                .semantics { contentDescription = cd },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_account_tree),
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    // 与 M3 IconButton 的禁用色一致:保持元素可见但不诱导点击
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }
    }

    if (showDialog) {
        PixFoldConfirmDialog(
            onDismissRequest = { showDialog = false },
            title = title,
            body = body,
            confirmText = confirm,
            onConfirm = {
                showDialog = false
                onApply()
            },
            dismissText = stringResource(R.string.common_cancel),
            confirmTag = TAG_DIR_META_CONFIRM,
            modifier = Modifier.testTag(TAG_DIR_META_DIALOG),
        )
    }
}
