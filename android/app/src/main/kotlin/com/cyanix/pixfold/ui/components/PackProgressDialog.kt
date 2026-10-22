package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
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
import com.cyanix.pixfold.ui.components.PixFoldAlertDialog
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.task.TaskProgress

/** 打包进度弹窗本体。 */
const val TAG_PACK_PROGRESS_DIALOG = "pack-progress-dialog"

/** 外层圆环（大圈）：**当前这一卷的页进度**。 */
const val TAG_PACK_PROGRESS_OUTER = "pack-progress-outer"

/** 内层圆环（小圈）：**整批的卷进度**。 */
const val TAG_PACK_PROGRESS_INNER = "pack-progress-inner"

/** 弹窗底部那行「正在写哪一卷」的卷名。 */
const val TAG_PACK_PROGRESS_VOLUME = "pack-progress-volume"

/** 圆环中心那行百分比读数（**两层合并**，见 `TaskProgress.percentWithPage`）。 */
const val TAG_PACK_PROGRESS_PERCENT = "pack-progress-percent"



/** 二次确认里的「仍然取消」。 */
const val TAG_PACK_PROGRESS_CANCEL_CONFIRM = "pack-progress-cancel-confirm"

/**
 * 打包进行中的模态进度弹窗：**双层圆环** + 背景高斯模糊。
 *
 * ## 轴向（用户裁决 `User said (m13400)` 原话）
 *
 * 「内层打包数量进度，外层当前打包漫画图片打包进度」⇒
 * **内环（小圈）= 卷数**、**外环（大圈）= 当前这一卷的页数**。
 *
 * 首版实现恰好写反了（外环卷、内环页），真机上完全看不出来：两环都在转、都随
 * 任务推进、界面不报错 ⇒ 肉眼只能确认「环在动」，确认不了「动的是哪一级」。
 * 现由 `PackProgressDialogTest` 用**两个不同数值**（卷 1/4、页 50/100）钉住：
 * 一旦对调，两条断言的期望值必然互换。
 *
 * 为什么是这一侧大：页进度变化细密（1600 页），放在外圈周长更长、读数更平滑；
 * 卷数通常只有几卷，小圈够用。
 *
 * ## 为什么是双层
 *
 * 打包是「一卷里很多页、一批里很多卷」的两级结构。单环只能表达其中一级：
 *  · 只画卷进度 ⇒ 一卷 1600 页时圆环从 0 直接跳到 100%，看起来像卡死；
 *  · 只画页进度 ⇒ 不知道整批还剩多少卷。
 *
 * ## 为什么模态
 *
 * 打包期间改元数据会与正在读的页列表打架（`plans` 是点确认那一刻的快照，
 * 而界面上还能改的话，用户以为改动了、实际写出去的是旧的）。盖住整个页面
 * 是这个约束最直接的表达。
 *
 * ## 背景模糊
 *
 * 由调用方在页面容器上施加 `Modifier.blur(...)`，见 [PackProgressScrim]。
 * 弹窗自身不模糊 —— 进度文字糊掉就没法看了。
 *
 * @param pageDone 当前卷已写页数；为 null 表示还不知道（刚切到这一卷、或
 *   当前卷是跳过/缺页的），此时外环显示为不确定态而不是假的 0%
 * @param pageTotal 当前卷总页数
 * @param volumeLabel 正在写的那一卷的完整名字。**要显示作者前缀**（用户
 *   `User said (m15457)`），否则同系列多本同名时用户分不清卡在哪一本。
 */
@Composable
fun PackProgressDialog(
    progress: TaskProgress,
    pageDone: Int?,
    pageTotal: Int?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelling: Boolean = false,
    volumeLabel: String? = null,
) {
    // 二次确认：打包中途取消会留下不完整 CBZ（用户 2026-10-20 裁决要求确认）。
    // 状态放组件内部 —— 调用方只需要知道「用户最终决定取消」，中间那一步是
    // 弹窗自己的事，暴露出去只会让每个调用点重复实现一遍。
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        PixFoldAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.pack_progress_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.pack_progress_cancel_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onCancel()
                    },
                    modifier = Modifier.testTag(TAG_PACK_PROGRESS_CANCEL_CONFIRM),
                ) {
                    Text(stringResource(R.string.pack_progress_cancel_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.pack_progress_cancel_confirm_dismiss))
                }
            },
        )
        return
    }

    PixFoldAlertDialog(
        onDismissRequest = { /* 模态：点外部不关，只能等结束或主动取消 */ },
        modifier = modifier.testTag(TAG_PACK_PROGRESS_DIALOG),
        title = {
            Text(
                text = stringResource(
                    if (cancelling) R.string.pack_progress_cancelling else R.string.pack_progress_title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                // ⚠️ `fillMaxWidth` 不是排版偏好，是**居中生效的前提**。
                // `PixFoldDialogShell` 的正文 `Column` 是 wrap-content 宽度，
                // 只写 `horizontalAlignment = CenterHorizontally` 时整个 Column
                // 仍然贴左，只是在它自己那点宽度内把内容居中 —— 真机上表现为
                // 「圆圈和名称没有居中显示」（`User said (m15456)`）。
                // 检验方式见 `PackProgressDialogTest`：比较圆环中心与对话框中心。
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    // 沿用 `TAG_TASK_PROGRESS`：既有测试用它表达「进度区出现了」，
                    // 弹窗换形态不该让那批断言的语义失效。
                    modifier = Modifier.testTag(TAG_TASK_PROGRESS),
                ) {
                    // 外环（大圈）：当前这一卷的**页**进度。
                    // 页进度变化细密（一卷可上千页），放在周长更长的外圈读数更平滑。
                    CircularProgressIndicator(
                        progress = {
                            if (pageDone == null || pageTotal == null || pageTotal <= 0) {
                                // 不确定态：不知道就别画假的百分比。
                                0f
                            } else {
                                (pageDone.toFloat() / pageTotal).coerceIn(0f, 1f)
                            }
                        },
                        modifier = Modifier
                            .size(120.dp)
                            .testTag(TAG_PACK_PROGRESS_OUTER),
                        strokeWidth = 6.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    // 内环（小圈）：整批的**卷**进度。比外圈小、更细，视觉上明确是
                    // 「里面这一层」—— 用户原话「内层打包数量进度」。
                    CircularProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier
                            .size(88.dp)
                            .testTag(TAG_PACK_PROGRESS_INNER),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    // 中心读数：**两层合并**的百分比，不是只按卷算。
                    // 只按卷算的话，一卷 1599 页的整个过程中数字一直是 0%，
                    // 直到整卷写完才跳到 25% —— 环在涨、数字不动，用户以为卡死
                    // （`User said (m17028)`：「内圈的百分比根据双层共同计算取值吧，
                    // 不如外圈没转完进度一直 0」）。
                    Text(
                        text = "${progress.percentWithPage(pageDone, pageTotal)}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag(TAG_PACK_PROGRESS_PERCENT),
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        R.string.pack_progress_volumes,
                        progress.processed,
                        progress.total,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (pageDone != null && pageTotal != null && pageTotal > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pack_progress_pages, pageDone, pageTotal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!volumeLabel.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = volumeLabel,
                        modifier = Modifier.testTag(TAG_PACK_PROGRESS_VOLUME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (progress.failed > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pack_progress_failed, progress.failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirming = true },
                enabled = !cancelling,
                modifier = Modifier.testTag(TAG_TASK_CANCEL),
            ) {
                Text(stringResource(R.string.pack_progress_cancel))
            }
        },
        // 模态：点外部 / 返回键都不关，只能等任务结束或主动取消。
        dismissOnOutside = false,
    )
}
