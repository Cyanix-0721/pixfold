package com.cyanix.pixfold.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.pack.CbzMetaUpdateOutcome
import com.cyanix.pixfold.domain.pack.UpdateFailure
import com.cyanix.pixfold.domain.text.TextKey
import com.cyanix.pixfold.ui.components.PixFoldAlertDialog
import com.cyanix.pixfold.ui.text.textOf

/** 确认框（工作流 C 唯一不可逆告知点）。 */
const val TAG_UPDATE_CONFIRM_DIALOG = "update-confirm-dialog"

/** 确认框里的体积行（独立 tag：断言「大小未知也不许省掉这一行」）。 */
const val TAG_UPDATE_CONFIRM_SIZE = "update-confirm-size"

/** 进度弹窗。 */
const val TAG_UPDATE_PROGRESS_DIALOG = "update-progress-dialog"

/** 进度弹窗里的取消按钮。 */
const val TAG_UPDATE_CANCEL = "update-cancel"

/** 取消二次确认。 */
const val TAG_UPDATE_CANCEL_CONFIRM = "update-cancel-confirm"

/** 结果弹框。 */
const val TAG_UPDATE_RESULT_DIALOG = "update-result-dialog"

/** 结果弹框正文（断言具体失败原因）。 */
const val TAG_UPDATE_RESULT_BODY = "update-result-body"

/** 结果弹框的关闭按钮（断言「完成」/「关闭」用词）。 */
const val TAG_UPDATE_RESULT_CONFIRM = "update-result-confirm"

/**
 * [UpdateFailure] -> 文案键。
 *
 * **返回可空**：`Cancelled` 不给文案 —— 取消是用户自己按的，弹一个「更新失败：已取消」
 * 的框等于把用户的决定说成一次故障。调用方拿到 `null` 就不展示结果框。
 *
 * 这也让「七个枚举值全覆盖」这件事在类型上可见：漏掉一个分支编译不过，
 * 而 `when` 里写 `else` 会被 Kotlin 的穷尽检查放过去 —— 所以这里刻意不写 `else`。
 */
fun updateFailureKey(reason: UpdateFailure): TextKey? = when (reason) {
    UpdateFailure.TargetMissing -> TextKey.UpdateFailTargetMissing
    UpdateFailure.PermissionLost -> TextKey.UpdateFailPermissionLost
    UpdateFailure.NoSpace -> TextKey.UpdateFailNoSpace
    UpdateFailure.NotAZip -> TextKey.UpdateFailNotAZip
    UpdateFailure.ReadFailed -> TextKey.UpdateFailReadFailed
    UpdateFailure.ReplaceFailed -> TextKey.UpdateFailReplaceFailed
    UpdateFailure.Cancelled -> null
}

/**
 * 就地更新元数据的确认框。
 *
 * ## 为什么体积这一行不许省
 *
 * CBZ 是 ZIP，**改一个条目也必须整包重写**（中央目录记录了每个条目的偏移与 CRC）。
 * 所以「更新一下元数据」的实际代价是「读写整个包」—— 4.7 GiB 的包就是 4.7 GiB 的读写。
 * 这是本操作唯一的真实代价，也是用户在这个弹框里最想知道的一件事。
 * 取不到体积时显示「未知」，**而不是把这一行删掉**：「未知」也是信息，删掉会让用户
 * 以为体积无关紧要。
 *
 * ## 为什么第二行可以没有
 *
 * 条目数在扫描阶段不一定拿得到（要打开包才知道）。**不知道就别编数字** ——
 * 显示「全部 ? 个条目原样保留」比不显示更糟。
 *
 * @param volumeName 包名（`cbzFileName`），用户用来确认「改的是不是这一个」
 * @param entryCount 包内条目总数；`null` = 未知 => 不显示第二行
 * @param sizeBytes 包体积；`null` = 未知 => 显示「未知」
 */
@Composable
fun CbzUpdateConfirmDialog(
    volumeName: String,
    entryCount: Int?,
    sizeBytes: Long?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizeText =
        if (sizeBytes != null) humanBytes(sizeBytes) else stringResource(R.string.update_meta_unknown_size)
    PixFoldAlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier.testTag(TAG_UPDATE_CONFIRM_DIALOG),
        title = { Text(textOf(TextKey.UpdateMetaTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = volumeName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = textOf(TextKey.UpdateMetaSizeWarning, sizeText),
                    modifier = Modifier.testTag(TAG_UPDATE_CONFIRM_SIZE),
                )
                if (entryCount != null) {
                    Text(textOf(TextKey.UpdateMetaEntrySummary, entryCount))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.update_meta_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * 更新进行中的模态进度弹窗：**单环**（条目进度）+ 取消二次确认。
 *
 * ## 为什么不复用 `PackProgressDialog` 的双层圆环
 *
 * 双环表达的是打包链特有的**两级**结构：内环 = 一批里第几卷、外环 = 当前卷里第几页。
 * 工作流 C 只有一级（「这个包里第几个条目」），套上双环会把同一份进度画两遍，
 * 且内环恒为 1/1 一直停在满格 —— 一个永远不动的圆环比没有圆环更让人怀疑卡死。
 *
 * 取消语义也不同：打包取消会留下**不完整的新包**（所以文案是「需要重新打包」），
 * 而更新取消是**丢弃临时包、原包零改动**（三段式替换还没开始）。故这里自带确认框文案。
 *
 * @param done 已写出的条目数
 * @param total 条目总数；`<= 0` = 未知（执行前不知道，执行完才知道）=> 不确定态转圈
 */
@Composable
fun CbzUpdateProgressDialog(
    done: Int,
    total: Int,
    cancelling: Boolean,
    volumeName: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        PixFoldAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.update_meta_cancel_title)) },
            text = { Text(stringResource(R.string.update_meta_cancel_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onCancel()
                    },
                    modifier = Modifier.testTag(TAG_UPDATE_CANCEL_CONFIRM),
                ) { Text(stringResource(R.string.update_meta_cancel_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.update_meta_cancel_dismiss))
                }
            },
        )
        return
    }

    PixFoldAlertDialog(
        // 模态：点外面不关。中途关掉的话用户看不见还剩多少，而任务还在写。
        onDismissRequest = {},
        modifier = modifier.testTag(TAG_UPDATE_PROGRESS_DIALOG),
        dismissOnOutside = false,
        title = {
            Text(
                text = stringResource(
                    if (cancelling) R.string.update_meta_cancelling else R.string.update_meta_progress_title,
                ),
            )
        },
        text = {
            Column(
                // fillMaxWidth 是居中生效的前提（与 PackProgressDialog 同一个坑）。
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (total > 0) {
                        CircularProgressIndicator(
                            progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.size(120.dp),
                            strokeWidth = 6.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        // 条目数未知：不确定态。写一个假的 0% 会让用户以为卡住了。
                        CircularProgressIndicator(
                            modifier = Modifier.size(120.dp),
                            strokeWidth = 6.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                    Text(
                        text = if (total > 0) {
                            stringResource(R.string.pack_progress_volumes, done, total)
                        } else {
                            done.toString()
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = volumeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirming = true },
                modifier = Modifier.testTag(TAG_UPDATE_CANCEL),
                enabled = !cancelling,
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * 结果弹框：成功一条、失败按 [UpdateFailure] 映射（`Cancelled` 不展示）。
 *
 * `detail` 是平台层给的补充（如「备份已保留：xxx.cbz.bak」）。**不翻译、直接附在第二行** ——
 * 它是文件名与具体错误，翻译它反而让人找不到磁盘上那个文件。
 */
@Composable
fun CbzUpdateResultDialog(
    outcome: CbzMetaUpdateOutcome,
    volumeName: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ok = outcome is CbzMetaUpdateOutcome.Updated
    val body: String = when (outcome) {
        is CbzMetaUpdateOutcome.Updated ->
            textOf(TextKey.UpdateDoneSummary, volumeName, outcome.entryCount)
        is CbzMetaUpdateOutcome.Failed -> {
            val key = updateFailureKey(outcome.reason)
            val base = if (key != null) textOf(key) else ""
            val detail = outcome.detail
            if (!detail.isNullOrEmpty()) {
                if (base.isEmpty()) detail else base + "\n" + detail
            } else {
                base
            }
        }
    }
    PixFoldAlertDialog(
        onDismissRequest = onDone,
        modifier = modifier.testTag(TAG_UPDATE_RESULT_DIALOG),
        title = {
            Text(
                stringResource(
                    if (ok) R.string.update_meta_done_title else R.string.update_meta_fail_title,
                ),
            )
        },
        text = { Text(body, modifier = Modifier.testTag(TAG_UPDATE_RESULT_BODY)) },
        confirmButton = {
            // 成功说「完成」、失败说「关闭」—— 两者都不是「执行」：框里已经没有待执行的动作，
            // 再写「执行」会让人以为要点它才生效（真机实测被误读）。
            TextButton(
                onClick = onDone,
                modifier = Modifier.testTag(TAG_UPDATE_RESULT_CONFIRM),
            ) {
                Text(textOf(if (ok) TextKey.GeneralDone else TextKey.GeneralClose))
            }
        },
    )
}
