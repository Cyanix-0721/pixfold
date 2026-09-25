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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R
import com.pixfold.d1.domain.plan.ConfirmStep
import com.pixfold.d1.domain.plan.DangerAction
import com.pixfold.d1.domain.plan.PlanCounts
import com.pixfold.d1.domain.plan.VolumePackPlan
import com.pixfold.d1.domain.plan.describe
import com.pixfold.d1.domain.text.TextKey
import com.pixfold.d1.ui.text.textOf
import com.pixfold.d1.ui.text.textOfOrNull

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
            text = pluralStringResource(R.plurals.plan_summary_title, counts.total, counts.total, counts.runnable) +
                if (counts.skipped > 0) stringResource(R.string.plan_summary_skipped, counts.skipped) else "",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag(TAG_PLAN_SUMMARY),
        )
        if (dangers.isEmpty()) {
            Text(
                text = stringResource(R.string.plan_no_danger),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            dangers.forEach { d ->
                Text(
                    text = stringResource(R.string.plan_danger_action, textOf(d.labelKey)),
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
                text = if (plan.skipped) stringResource(R.string.domain_op_status_skip) else stringResource(R.string.plan_will_run),
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
                text = "· ${textOf(line)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(planLineTag(index, j)),
            )
        }

        if (plan.skipped) {
            Text(
                text = stringResource(R.string.plan_skip_reason_prefix, textOfOrNull(plan.skipReason).orEmpty()),
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
 * 危险确认的标题固定为 [TextKey.DangerDialogTitle],并在正文上方再次标出
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
        is ConfirmStep.General -> {
            // ⚠️ 文案必须先提到 `semantics {}` **外面**解析:`semantics` 的 lambda
            // 是普通 lambda,不是 `@Composable`,里面调 `stringResource` 编译失败。
            val ok = stringResource(R.string.domain_general_confirm_ok)
            val cancel = stringResource(R.string.common_cancel)
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.domain_general_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ConfirmStepLabel(stepIndex, totalSteps)
                        step.summaryLines.forEach { Text("· ${textOf(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = onConfirm,
                        modifier = Modifier
                            .testTag(TAG_CONFIRM_OK)
                            .semantics { contentDescription = ok },
                    ) { Text(ok) }
                },
                dismissButton = {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .testTag(TAG_CONFIRM_CANCEL)
                            .semantics { contentDescription = cancel },
                    ) { Text(cancel) }
                },
                modifier = Modifier.testTag(TAG_CONFIRM_GENERAL),
            )
        }

        is ConfirmStep.Danger -> {
            val body = textOf(step.action.bodyKey)
            val confirm = textOf(step.action.buttonKey)
            val cancel = stringResource(R.string.common_cancel)
            AlertDialog(
                onDismissRequest = onCancel,
                title = {
                    Text(
                        text = stringResource(R.string.domain_danger_dialog_title),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(TAG_CONFIRM_DANGER_TITLE),
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ConfirmStepLabel(stepIndex, totalSteps)
                        Text(
                            text = "⛔ $body",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = onConfirm,
                        modifier = Modifier
                            .testTag(TAG_CONFIRM_OK)
                            .semantics { contentDescription = confirm },
                    ) { Text(confirm) }
                },
                dismissButton = {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .testTag(TAG_CONFIRM_CANCEL)
                            .semantics { contentDescription = cancel },
                    ) { Text(cancel) }
                },
                modifier = Modifier.testTag(TAG_CONFIRM_DANGER),
            )
        }
    }
}

@Composable
private fun ConfirmStepLabel(stepIndex: Int, totalSteps: Int) {
    if (totalSteps > 1) {
        // 键 + 参数在渲染时才解析,故这里直接构造 Msg(与 `ConfirmFlowState.progressLabel`
        // 同一个键,避免 UI 自己拼"第 N 步"这类句子而与领域层漂移)。
        val label = textOf(TextKey.ConfirmProgress, stepIndex + 1, totalSteps)
        // ⚠️ `semantics` 的 lambda 不是 `@Composable`,故无障碍文案必须在外面取好。
        val labelCd = stringResource(R.string.plan_confirm_progress_cd, stepIndex + 1, totalSteps)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .testTag(TAG_CONFIRM_STEP_LABEL)
                .semantics { contentDescription = labelCd },
        )
    }
}
