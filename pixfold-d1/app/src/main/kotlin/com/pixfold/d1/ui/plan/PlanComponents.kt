package com.pixfold.d1.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.plan.ConfirmStep
import com.pixfold.d1.domain.plan.DANGER_DIALOG_TITLE
import com.pixfold.d1.domain.plan.DangerAction
import com.pixfold.d1.domain.plan.GENERAL_CONFIRM_CANCEL
import com.pixfold.d1.domain.plan.GENERAL_CONFIRM_OK
import com.pixfold.d1.domain.plan.GENERAL_CONFIRM_TITLE
import com.pixfold.d1.domain.plan.PlanCounts
import com.pixfold.d1.domain.plan.VolumePackPlan
import com.pixfold.d1.domain.plan.describe

const val TAG_PLAN_LIST = "plan-list"
const val TAG_PLAN_SUMMARY = "plan-summary"
const val TAG_PLAN_CONFIRM = "plan-confirm"
const val TAG_CONFIRM_GENERAL = "plan-confirm-general"
const val TAG_CONFIRM_DANGER = "plan-confirm-danger"
const val TAG_CONFIRM_DANGER_TITLE = "plan-confirm-danger-title"
const val TAG_CONFIRM_OK = "plan-confirm-ok"
const val TAG_CONFIRM_CANCEL = "plan-confirm-cancel"
const val TAG_CONFIRM_STEP_LABEL = "plan-confirm-step-label"

/** 计划里第 i 行的 testTag。 */
fun planRowTag(index: Int) = "plan-row-$index"

/** 计划行内第 j 条文案的 testTag。 */
fun planLineTag(index: Int, line: Int) = "plan-row-$index-line-$line"

/** 第 i 行的状态标签(跳过原因)。 */
fun planSkipTag(index: Int) = "plan-row-$index-skip"

/**
 * 计划预览头部:卷数 / 跳过数 + 危险动作清单(验收第 15 项)。
 *
 * **为什么把危险动作也列在这里**:验收第 16 项要求"默认关闭"并且用户能看出
 * 自己选了什么危险动作。若只在确认框里才出现,用户在计划页就看不出
 * "这次会不会删源目录" —— 那正是归档原型被记为薄弱的地方。
 */
@Composable
fun PlanHeader(
    counts: PlanCounts,
    dangers: List<DangerAction>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "共 ${counts.total} 卷 · 将执行 ${counts.runnable} 卷" +
                if (counts.skipped > 0) " · 跳过 ${counts.skipped} 卷" else "",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag(TAG_PLAN_SUMMARY),
        )
        if (dangers.isEmpty()) {
            Text(
                text = "无危险动作：源文件保留，不删除任何文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            dangers.forEach { d ->
                Text(
                    text = "⚠️ 危险动作：${d.label}（执行前需独立二次确认）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * 计划里的一行:卷标题 + 操作清单 + (若跳过)原因。
 *
 * 操作清单来自领域层 [VolumePackPlan.describe] 的**文案契约** —— UI 不自己拼句子,
 * 否则文案契约就无法被测试钉住。
 */
@Composable
fun PlanRow(
    plan: VolumePackPlan,
    index: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(planRowTag(index)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = plan.volume.dirPath,
                style = MaterialTheme.typography.bodyMedium,
                color = if (plan.skipped) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (plan.skipped) "将跳过" else "将执行",
                style = MaterialTheme.typography.labelSmall,
                color = if (plan.skipped) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        plan.describe().forEachIndexed { j, line ->
            Text(
                text = "· $line",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(planLineTag(index, j)),
            )
        }

        if (plan.skipped) {
            Text(
                text = "⛔ ${plan.skipReason.orEmpty()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(planSkipTag(index)),
            )
        }
    }
}

/**
 * 确认对话框:按 [step] 渲染**通用确认**或**独立危险确认**(验收第 16 项)。
 *
 * **一个对话框只处理一步**:由调用方按确认序列推进。这样"两次确认"是
 * 序列驱动的结构结果,而不是散落在 `if` 嵌套里的易错逻辑。
 *
 * 危险确认的标题固定为 [DANGER_DIALOG_TITLE],并在正文上方再次标出
 * 「第 N 步 / 共 M 步」,让用户知道自己身处多步确认的哪一步 ——
 * 否则连点两次"确认"会让用户以为只确认了一次。
 */
@Composable
fun ConfirmDialog(
    step: ConfirmStep,
    stepIndex: Int,
    totalSteps: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    when (step) {
        is ConfirmStep.General -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(GENERAL_CONFIRM_TITLE) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ConfirmStepLabel(stepIndex, totalSteps)
                    step.summaryLines.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier
                        .testTag(TAG_CONFIRM_OK)
                        .semantics { contentDescription = GENERAL_CONFIRM_OK },
                ) { Text(GENERAL_CONFIRM_OK) }
            },
            dismissButton = {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .testTag(TAG_CONFIRM_CANCEL)
                        .semantics { contentDescription = GENERAL_CONFIRM_CANCEL },
                ) { Text(GENERAL_CONFIRM_CANCEL) }
            },
            modifier = Modifier.testTag(TAG_CONFIRM_GENERAL),
        )

        is ConfirmStep.Danger -> AlertDialog(
            onDismissRequest = onCancel,
            title = {
                Text(
                    text = DANGER_DIALOG_TITLE,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TAG_CONFIRM_DANGER_TITLE),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ConfirmStepLabel(stepIndex, totalSteps)
                    Text(
                        text = "⛔ " + step.action.confirmBody,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier
                        .testTag(TAG_CONFIRM_OK)
                        .semantics { contentDescription = step.action.confirmButton },
                ) { Text(step.action.confirmButton) }
            },
            dismissButton = {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .testTag(TAG_CONFIRM_CANCEL)
                        .semantics { contentDescription = "取消" },
                ) { Text("取消") }
            },
            modifier = Modifier.testTag(TAG_CONFIRM_DANGER),
        )
    }
}

@Composable
private fun ConfirmStepLabel(stepIndex: Int, totalSteps: Int) {
    if (totalSteps > 1) {
        Text(
            text = "第 ${stepIndex + 1} / $totalSteps 步确认",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .testTag(TAG_CONFIRM_STEP_LABEL)
                .semantics { contentDescription = "第 ${stepIndex + 1} 步，共 $totalSteps 步" },
        )
    }
}
