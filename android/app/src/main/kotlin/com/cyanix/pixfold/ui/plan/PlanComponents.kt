package com.cyanix.pixfold.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.plan.ConfirmStep
import com.cyanix.pixfold.domain.plan.DangerAction
import com.cyanix.pixfold.domain.plan.PlanCounts
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.plan.describe
import com.cyanix.pixfold.domain.text.TextKey
import com.cyanix.pixfold.ui.components.PixFoldAlertDialog
import com.cyanix.pixfold.ui.components.PixFoldSectionCard
import com.cyanix.pixfold.ui.components.PixFoldSegmentedItem
import com.cyanix.pixfold.ui.components.PixFoldShapeBadge
import com.cyanix.pixfold.ui.components.PixFoldStatusPill
import com.cyanix.pixfold.ui.text.TAG_WARN_ICON
import com.cyanix.pixfold.ui.text.WarnLine
import com.cyanix.pixfold.ui.text.textOf
import com.cyanix.pixfold.ui.text.textOfOrNull
import com.cyanix.pixfold.ui.text.warnIcon
import com.cyanix.pixfold.ui.theme.dangerActionButtonColors
import com.cyanix.pixfold.ui.theme.pixFoldSurfaceBorder

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
    val summaryText =
        pluralStringResource(R.plurals.plan_summary_title, counts.total, counts.total, counts.runnable) +
            if (counts.skipped > 0) stringResource(R.string.plan_summary_skipped, counts.skipped) else ""

    PixFoldSectionCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = summaryText,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(TAG_PLAN_SUMMARY),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PixFoldStatusPill(
                    text = "${stringResource(R.string.plan_will_run)} ${counts.runnable}",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    iconRes = R.drawable.ic_check,
                )
                PixFoldStatusPill(
                    text = "${stringResource(R.string.domain_op_status_skip)} ${counts.skipped}",
                    containerColor = if (counts.skipped > 0) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (counts.skipped > 0) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    iconRes = if (counts.skipped > 0) R.drawable.ic_forbidden else null,
                )
            }
            if (dangers.isEmpty()) {
                Text(
                    text = stringResource(R.string.plan_no_danger),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = pixFoldSurfaceBorder(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        dangers.forEach { d ->
                            // 警示靠矢量图标承担(2026-10-05 用户要求),文案里不再有 emoji 前缀。
                            // `plan_danger_action` 是纯资源键(不是 `TextKey`),故按资源 id 查表。
                            WarnLine(
                                icon = warnIcon(R.string.plan_danger_action) ?: return@forEach,
                                text = stringResource(R.string.plan_danger_action, textOf(d.labelKey)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
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
    count: Int = 1,
) {
    PixFoldSegmentedItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count.coerceAtLeast(1)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag(planRowTag(index)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = plan.volume.dirPath,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = if (plan.skipped) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                PixFoldStatusPill(
                    text = if (plan.skipped) {
                        stringResource(R.string.domain_op_status_skip)
                    } else {
                        stringResource(R.string.plan_will_run)
                    },
                    containerColor = if (plan.skipped) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (plan.skipped) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    iconRes = if (plan.skipped) R.drawable.ic_forbidden else R.drawable.ic_check,
                )
            }

            plan.describe().forEachIndexed { j, line ->
                Text(
                    text = "· ${textOf(line)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .semantics(mergeDescendants = true) {}
                        .testTag(planLineTag(index, j)),
                )
            }

            if (plan.skipped) {
                // 原 `plan_skip_reason_prefix`(⛔ + 裸占位符)已整键删除 —— 去掉符号前缀后
                // 它不承载任何文案。禁止语义改由 `ic_forbidden` 承担(与 `⚠️` 系区分)。
                WarnLine(
                    icon = R.drawable.ic_forbidden,
                    text = textOfOrNull(plan.skipReason).orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .semantics(mergeDescendants = true) {}
                        .testTag(planSkipTag(index)),
                )
            }
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
            PixFoldAlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.domain_general_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ConfirmStepLabel(stepIndex, totalSteps)
                        step.summaryLines.forEach { line ->
                            // 警示行由图标标注,且**不再叠加 `"· "`**(图标就是它的符号前缀)。
                            // 非警示行保持原样,`PlanUiTest`/`PlanWiringTest` 对 `"· "` 的
                            // 断言正是打在那些行上(如 `SummarySourceDirPrefix`)。
                            val icon = warnIcon(line)
                            if (icon == null) {
                                Text("· ${textOf(line)}", style = MaterialTheme.typography.bodySmall)
                            } else {
                                WarnLine(
                                    icon = icon,
                                    text = textOf(line),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onConfirm,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .testTag(TAG_CONFIRM_OK)
                            .semantics { contentDescription = ok },
                    ) { Text(ok) }
                },
                dismissButton = {
                    TextButton(
                        onClick = onCancel,
                        shapes = ButtonDefaults.shapes(),
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
            PixFoldAlertDialog(
                onDismissRequest = onCancel,
                heroBadge = { DangerConfirmHeroBadge() },
                title = {
                    Text(
                        text = stringResource(R.string.domain_danger_dialog_title),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TAG_CONFIRM_DANGER_TITLE),
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ConfirmStepLabel(stepIndex, totalSteps)
                        // 原 `"⛔ $body"` 里的字形前缀改由 `ic_forbidden` 承担。
                        WarnLine(
                            icon = R.drawable.ic_forbidden,
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onConfirm,
                        shapes = ButtonDefaults.shapes(),
                        colors = dangerActionButtonColors(),
                        modifier = Modifier
                            .testTag(TAG_CONFIRM_OK)
                            .semantics { contentDescription = confirm },
                    ) { Text(confirm) }
                },
                dismissButton = {
                    TextButton(
                        onClick = onCancel,
                        shapes = ButtonDefaults.shapes(),
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DangerConfirmHeroBadge() {
    PixFoldShapeBadge(
        polygon = MaterialShapes.SoftBurst,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        iconRes = warnIcon(R.string.domain_danger_dialog_title) ?: R.drawable.ic_warning_triangle,
        size = 56.dp,
        iconSize = 28.dp,
        modifier = Modifier.testTag(TAG_WARN_ICON),
    )
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
