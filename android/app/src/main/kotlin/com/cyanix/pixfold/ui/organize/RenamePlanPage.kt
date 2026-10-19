package com.cyanix.pixfold.ui.organize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.naming.RenameOp
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.domain.plan.ConfirmFlowState
import com.cyanix.pixfold.domain.plan.ConfirmStep
import com.cyanix.pixfold.domain.plan.DangerAction
import com.cyanix.pixfold.domain.plan.buildLibraryRenamePlan
import com.cyanix.pixfold.domain.plan.buildConfirmSequence
import com.cyanix.pixfold.domain.plan.renameSummaryLines
import com.cyanix.pixfold.domain.plan.organizeDangerActions
import com.cyanix.pixfold.domain.sort.PageOrderState
import com.cyanix.pixfold.ui.components.IssueFilterRow
import com.cyanix.pixfold.ui.components.PageActionBar
import com.cyanix.pixfold.ui.components.TaskProgressBar
import com.cyanix.pixfold.ui.plan.ConfirmDialog
import com.cyanix.pixfold.ui.text.WarnLine
import com.cyanix.pixfold.ui.plan.PlanHeader
import com.cyanix.pixfold.ui.plan.planLineTag
import com.cyanix.pixfold.ui.plan.planRowTag
import com.cyanix.pixfold.ui.plan.planSkipTag
import com.cyanix.pixfold.ui.text.textOfOrNull

const val TAG_A_PLAN_PAGE = "organize-plan-page"
const val TAG_A_PLAN_LIST = "organize-plan-list"
const val TAG_A_PLAN_CONFIRM = "organize-plan-confirm"
const val TAG_A_PLAN_CLEAN_TOGGLE = "organize-plan-clean-empty-dirs"
const val TAG_A_PLAN_FILTER_ALL = "organize-plan-filter-all"
const val TAG_A_PLAN_FILTER_ISSUES = "organize-plan-filter-issues"
const val TAG_A_PLAN_FILTER_NONE = "organize-plan-filter-none"

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
    onBack: (() -> Unit)? = null,
    /**
     * 执行中(D4b 真实改名)。
     *
     * 为 `true` 时底栏整条换成进度条 —— 与库侧打包页同一形态(`PackPreviewPage`)。
     * 计划已经定稿且正在落地,此时「确认执行」再按一次只会再启一次;
     * 左侧「返回命名结构」也会离开这个目的地,而改名还在跑。
     *
     * 默认 `false` = 模拟执行那条路(既有 UI 测试全走它),行为一行不变。
     */
    executing: Boolean = false,
    /** 执行进度。仅 [executing] 为 true 时渲染。 */
    progress: TaskProgress? = null,
    /** 请求中断。中断只在**项之间**生效(见 `executeRenamePlanReal`)。 */
    onCancelExecution: () -> Unit = {},
) {
    // 计划是纯函数的结果,但本页**现在会因本地筛选状态而重组** —— 不再每次重组都重算。
    val plan = remember(collections, orders, namingState) {
        buildLibraryRenamePlan(collections, orders, namingState)
    }
    val dangers = organizeDangerActions(cleanEmptyDirs)
    // 要进 semantics{} / 作为参数传给子组件,故先在组合上下文里解析
    val cleanToggleCd = stringResource(R.string.a_plan_clean_empty_cd)
    val backCd = stringResource(R.string.a_plan_back_cd)
    val confirmCd = stringResource(R.string.a_plan_confirm_cd)

    // 筛选状态是**本页的**显示偏好,不上报(与 NamingPage 同样的状态提升约定)。
    // 默认「全部」—— 计划页的首要用途是「执行前把整份计划看全」(验收第 15 项)。
    var issuesOnly by remember { mutableStateOf(false) }

    // ⚠️ 过滤后**必须保留原始下标**:`planRowTag(index)` 是「计划第几行」的契约,
    // 报告行序也与计划行序对齐(见 `LibraryRenamePlan` KDoc),两者靠同一个序号核对。
    // 若改用「可见序号」,筛掉前几行后报告第 5 行会对到计划第 3 行。
    val shown = plan.ops.withIndex().filter { !issuesOnly || it.value.isSkipped }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_A_PLAN_PAGE),
    ) {
        // 筛选条**常驻**(用户裁决 2026-09-26):56 行的计划滚到一半想改筛选时,
        // 不该被迫先滚回顶部。它是本页唯一不随列表滚走的控件。
        IssueFilterRow(
            issueCount = plan.skipped,
            issuesOnly = issuesOnly,
            onIssuesOnlyChange = { issuesOnly = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            allTag = TAG_A_PLAN_FILTER_ALL,
            issuesTag = TAG_A_PLAN_FILTER_ISSUES,
        )

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
                            label = {
                                Text(
                                    stringResource(
                                        if (cleanEmptyDirs) {
                                            R.string.a_plan_clean_empty_on
                                        } else {
                                            R.string.a_plan_clean_empty_off
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier
                                .testTag(TAG_A_PLAN_CLEAN_TOGGLE)
                                .semantics { contentDescription = cleanToggleCd },
                        )
                    }
                    Text(
                        text = stringResource(R.string.a_plan_clean_empty_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (issuesOnly && shown.isEmpty()) {
                // 筛选结果的空态:必须说清「不是没有计划,而是筛掉了」,
                // 否则用户会以为计划丢了 —— 这正是「空表 = 重置」那类误判的同源风险。
                // 只在**筛选**造成的空表时出现:计划本身为空是另一回事,不该借用这句话。
                item(key = "filter-none") {
                    Text(
                        text = stringResource(R.string.common_filter_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(16.dp)
                            .testTag(TAG_A_PLAN_FILTER_NONE),
                    )
                }
            }

            items(shown, key = { it.index }) { indexed ->
                RenamePlanRow(
                    indexed.value,
                    indexed.index,
                    plan.collectionNames[indexed.value.proposal.image.collectionId],
                )
            }
        }

        // 终局动作常驻底栏(台账 §2):A 侧整库计划 56 行,原先要连滑 8–12 次才够得到,
        // 且未滚到之前按钮根本没被组合。移出 LazyColumn 后与 [TAG_TO_PLAN] 一致。
        // 左 = 退回(返回命名结构),右 = 终局(确认执行)—— 与全站底栏一致(用户 2026-09-25)。
        // `onBack` 为 null 表示本页被单独使用(无多步骤流程),此时左侧为空槽。
        if (executing) {
            // 执行期只留进度条 + 行内取消。取消按钮挂在进度条行内由组件自己负责
            // (见 TaskProgressBar 的 KDoc「为什么取消按钮在进度条行内」)。
            TaskProgressBar(
                progress = progress ?: TaskProgress(total = 0),
                onCancel = onCancelExecution,
            )
        } else {
            val back = onBack
            PageActionBar(
                leading = {
                    if (back != null) {
                        FilledTonalButton(
                            onClick = back,
                            modifier = Modifier
                                .testTag(TAG_STEP_BACK)
                                .semantics { contentDescription = backCd },
                        ) { Text(stringResource(R.string.a_plan_back)) }
                    }
                },
                trailing = {
                    Button(
                        onClick = onStartConfirm,
                        modifier = Modifier
                            .testTag(TAG_A_PLAN_CONFIRM)
                            .semantics { contentDescription = confirmCd },
                    ) { Text(stringResource(R.string.a_plan_confirm)) }
                },
            )
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
    // `reason` 是领域层的 `Msg`(语言无关),在此现场解析
    val reason = textOfOrNull(op.reason)
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
                text = if (op.isSkipped) {
                    stringResource(R.string.a_plan_status_skip)
                } else {
                    stringResource(R.string.a_plan_status_rename)
                },
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
        if (op.isSkipped && reason != null) {
            // 原 `a_plan_skip_reason`(⛔ + 裸占位符)已整键删除 —— 去前缀后它不承载文案。
            // 禁止语义改由 `ic_forbidden` 承担;tag 留在 `Text` 上(经 `textModifier`),
            // 因为 `PlanUiTest` 只按 tag 断言"显示",而 `Row` 上挂 tag 也可,但 `Text` 上更稳。
            WarnLine(
                icon = R.drawable.ic_forbidden,
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(planSkipTag(index)),
            )
        }
    }
}
