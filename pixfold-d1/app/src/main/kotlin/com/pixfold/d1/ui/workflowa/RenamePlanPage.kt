package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.naming.RenameOp
import com.pixfold.d1.domain.plan.ConfirmFlowState
import com.pixfold.d1.domain.plan.ConfirmStep
import com.pixfold.d1.domain.plan.DangerAction
import com.pixfold.d1.domain.plan.buildLibraryRenamePlan
import com.pixfold.d1.domain.plan.buildConfirmSequence
import com.pixfold.d1.domain.plan.renameSummaryLines
import com.pixfold.d1.domain.plan.workflowADangerActions
import com.pixfold.d1.domain.sort.PageOrderState
import com.pixfold.d1.ui.plan.ConfirmDialog
import com.pixfold.d1.ui.plan.PlanHeader
import com.pixfold.d1.ui.plan.planLineTag
import com.pixfold.d1.ui.plan.planRowTag
import com.pixfold.d1.ui.plan.planSkipTag

const val TAG_A_PLAN_PAGE = "a-plan-page"
const val TAG_A_PLAN_LIST = "a-plan-list"
const val TAG_A_PLAN_CONFIRM = "a-plan-confirm"
const val TAG_A_PLAN_CLEAN_TOGGLE = "a-plan-clean-empty-dirs"

/**
 * 工作流 A · 步骤 3:执行计划(验收第 15、16 项)。
 *
 * **计划遍历所有集合**(规格 §6.4 末条):命名的根目录名按集合各自生效,
 * 但重名冲突可能跨集合出现,只看当前集合会让用户在执行后才发现被跳过。
 *
 * 危险动作只有「清理空目录」一项(默认关闭);勾选后需**独立二次确认**(§9.2)。
 * 确认流程由领域层的 [ConfirmFlowState] 驱动 —— UI 只负责按当前步渲染对话框,
 * 不自作判断"该弹几个框"。
 */
@Composable
fun RenamePlanPage(
    collections: List<ImageCollection>,
    orders: Map<String, PageOrderState>,
    namingState: NamingState,
    cleanEmptyDirs: Boolean,
    onCleanEmptyDirsChange: (Boolean) -> Unit,
    flow: ConfirmFlowState,
    onStartConfirm: () -> Unit,
    onConfirmStep: () -> Unit,
    onCancelConfirm: () -> Unit,
    onExecute: () -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val plan = buildLibraryRenamePlan(collections, orders, namingState)
    val dangers = workflowADangerActions(cleanEmptyDirs)

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_A_PLAN_PAGE),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_A_PLAN_LIST),
        ) {
            item(key = "header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PlanHeader(counts = plan.counts, dangers = dangers.toList())

                    // ---- 危险动作开关(默认关闭) ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = cleanEmptyDirs,
                            onClick = { onCleanEmptyDirsChange(!cleanEmptyDirs) },
                            label = { Text(if (cleanEmptyDirs) "清理空目录：开（危险）" else "清理空目录：关（默认）") },
                            modifier = Modifier
                                .testTag(TAG_A_PLAN_CLEAN_TOGGLE)
                                .semantics { contentDescription = "清理空目录开关" },
                        )
                    }
                    Text(
                        text = "默认关闭；勾选后仍需在确认对话框中再次确认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                }
            }

            items(plan.ops.size) { i ->
                RenamePlanRow(plan.ops[i], i, plan.collectionNames[plan.ops[i].proposal.image.collectionId])
            }

            item(key = "execute") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                    Button(
                        onClick = onStartConfirm,
                        modifier = Modifier
                            .testTag(TAG_A_PLAN_CONFIRM)
                            .semantics { contentDescription = "确认执行" },
                    ) { Text("确认执行") }
                }
            }
        }
    }

    // 确认对话框:严格按领域层给出的序列推进(通用 → 危险)
    val step: ConfirmStep? = flow.currentStep
    if (step != null) {
        ConfirmDialog(
            step = step,
            stepIndex = flow.stepIndex,
            totalSteps = flow.totalSteps,
            onConfirm = onConfirmStep,
            onCancel = onCancelConfirm,
        )
    }
}

@Composable
private fun RenamePlanRow(op: RenameOp, index: Int, collectionName: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(planRowTag(index)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "${collectionName ?: ""} · ${op.proposal.image.relPath}",
                style = MaterialTheme.typography.bodySmall,
                color = if (op.isSkipped) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (op.isSkipped) "将跳过" else "将重命名",
                style = MaterialTheme.typography.labelSmall,
                color = if (op.isSkipped) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Text(
            text = "→ ${op.finalName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag(planLineTag(index, 0)),
        )
        if (op.isSkipped) {
            Text(
                text = "⛔ ${op.reason.orEmpty()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(planSkipTag(index)),
            )
        }
    }
}
