package com.cyanix.pixfold.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.ui.components.PixFoldAlertDialog
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.SourceItem

/** 预览页顶部「重命名」按钮。 */
const val TAG_PREVIEW_RENAME = "preview-rename"

/** 预览页「将重命名为 …」提示 —— 仅在该图**存在覆盖**时出现(改名的可见反馈)。 */
const val TAG_PREVIEW_RENAMED = "preview-renamed"

const val TAG_PREVIEW_RENAME_FIELD = "preview-rename-field"
const val TAG_PREVIEW_RENAME_SAVE = "preview-rename-save"
const val TAG_PREVIEW_RENAME_CANCEL = "preview-rename-cancel"

/**
 * 预览页重命名所需的全部接线,**打包成一个值**,而不是散成三个可空参数。
 *
 * 三个参数各自可空会让"缺其中一个"成为可表达的状态,而它们在语义上是一体的:
 * 有入口就必须能取名、也必须能写回。打包后 `ImagePreview(rename = null)`
 * 就是唯一的"本页不提供改名"表达,调用点无法只传一半。
 *
 * **写回的是命名页同一份 `NamingState.overrides`**(键 = [SourceItem.id],
 * 值 = 完整文件名,空值即撤销覆盖 —— 领域层 `setOverride` 的既定语义),
 * 故两处改名天然同步,不存在两份状态需要对齐。
 *
 * @param overrides 当前覆盖表,仅用于判断"这一张是否已被改过"。
 * @param finalNameOf 取某个条目的**当前最终名**(覆盖优先于建议名):既作输入框初值,
 *   也作提示行文案。点"重命名"时用户想在已有结果上微调,而不是从零重打一遍。
 * @param onRename 提交改名;传空串表示恢复建议名。
 */
@Immutable
class PreviewRename(
    val overrides: Map<String, String>,
    val finalNameOf: (SourceItem) -> String,
    val onRename: (SourceItem, String) -> Unit,
) {
    /** 这一张是否已被改名(有覆盖)。决定要不要显示提示行。 */
    fun isRenamed(item: SourceItem): Boolean = overrides.containsKey(item.id)
}

/**
 * 改名对话框。
 *
 * **`key` 传入当前条目 id**,作用是把输入框的记忆内容与"正在改哪一张"绑定。
 * 今天的调用点是 `if (renameOpen) { … }`,对话框关闭即离开组合,再打开必然重置;
 * 因此 `key` 是**把这条约束写明**,而不是依赖"恰好被卸载"这个副作用 ——
 * 后者一旦改动(例如改成常驻组合或一次预览多张)就会静默失效。
 *
 * 留空即恢复建议名这一约定写在 [R.string.preview_rename_hint] 里 ——
 * 否则"清空后保存"看起来像把名字删没了,而实际行为是撤销覆盖。
 * 该行为由领域层负责解释,本组件不重复实现(不 trim、不兜底)。
 */
@Composable
internal fun PreviewRenameDialog(
    initialName: String,
    /** 换图时用于重置输入框的键(传当前条目 id)。 */
    key: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(key) { mutableStateOf(initialName) }

    // 无障碍/语义文案须在 `semantics {}` 之外取好(`stringResource` 不能在非 composable lambda 里调)。
    val hint = stringResource(R.string.preview_rename_hint)
    val title = stringResource(R.string.preview_rename_title)
    val fieldLabel = stringResource(R.string.preview_rename_field)
    val save = stringResource(R.string.preview_rename_save)
    val cancel = stringResource(R.string.common_cancel)

    PixFoldAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(fieldLabel) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_PREVIEW_RENAME_FIELD),
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // 不加 `heightIn(min = 48.dp)`:设计硬指标「触摸目标 ≥ 48dp」由 M3 的
            // `minimumInteractiveComponentSize` 保证(实测裸 `TextButton` 即 48dp+,
            // 该修饰符加上去前后布局尺寸完全一致 —— 去掉它的变异无测试变红)。
            TextButton(
                onClick = { onConfirm(text) },
                modifier = Modifier.testTag(TAG_PREVIEW_RENAME_SAVE),
            ) { Text(save) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TAG_PREVIEW_RENAME_CANCEL),
            ) { Text(cancel) }
        },
    )
}
