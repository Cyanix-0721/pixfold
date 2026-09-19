package com.pixfold.d1.ui.workflowb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.plan.ConfirmFlowState
import com.pixfold.d1.domain.plan.ExecutionReport
import com.pixfold.d1.domain.plan.OutputConflictPolicy
import com.pixfold.d1.domain.plan.SourcePolicy
import com.pixfold.d1.domain.plan.buildConfirmSequence
import com.pixfold.d1.domain.plan.buildPackagePlans
import com.pixfold.d1.domain.plan.cancel
import com.pixfold.d1.domain.plan.confirmCurrent
import com.pixfold.d1.domain.plan.effectiveDangerActions
import com.pixfold.d1.domain.plan.executePackagePlans
import com.pixfold.d1.domain.plan.generalSummaryLines
import com.pixfold.d1.domain.plan.performUndo
import com.pixfold.d1.domain.plan.startConfirmFlow
import com.pixfold.d1.domain.plan.undoRecordOf
import com.pixfold.d1.ui.plan.ExecutionReportPage
import com.pixfold.d1.domain.workflowb.WorkflowBState
import com.pixfold.d1.domain.workflowb.workflowBStateOf

const val TAG_B_STEP_TABS = "b-step-tabs"
const val TAG_B_STEP_LIBRARY = "b-step-library"
const val TAG_B_STEP_METADATA = "b-step-metadata"
const val TAG_B_STEP_PACK = "b-step-pack"
const val TAG_B_STEP_BACK = "b-step-back"

/** 工作流 B 的步骤(规格 §10.1:步骤1 漫画库 / 步骤2 元数据 / 步骤3 打包计划)。 */
enum class WorkflowBStep(val label: String) {
    Library("步骤1 漫画库"),
    Metadata("步骤2 元数据"),
    Pack("步骤3 打包计划"),
}

/**
 * 工作流 B 的步骤容器(验收第 11–14 项)。
 *
 * 与 `WorkflowASteps` 同构:状态提在这一层,**三个步骤共享同一个
 * [WorkflowBState]**(含 `activeVolumeId`)—— 否则"在第 1 步选中的卷"在切到第 2 步时会丢失。
 *
 * 返回手势按层级决策(与工作流 A 同一约定,用户 2026-09-18 反馈后固化):
 * 步骤 3 → 2 → 1 → 首页;`BackHandler` 始终注册(系统据此走"应用内后退"而非退出应用)。
 *
 * @param volumes 库内容;默认由上层注入(便于测试换数据)。
 * @param onBack 步骤 1 再返回时的动作(由上层回首页)。
 */
@Composable
fun WorkflowBSteps(
    rootPath: String,
    volumes: List<ComicVolume>,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onBack: () -> Unit = {},
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(workflowBStateOf(rootPath, volumes)) }

    // P6:策略(默认 Rename / Keep,与归档语义一致)、危险动作、确认流程、执行报告。
    // 全部提到这一层 —— 在步骤 3 内执行完再看报告,切回来时不该丢掉策略选择。
    var conflictPolicy by remember { mutableStateOf(OutputConflictPolicy.Rename) }
    var sourcePolicy by remember { mutableStateOf(SourcePolicy.Keep) }
    var cleanEmptyDirs by remember { mutableStateOf(false) }
    var confirmFlow by remember { mutableStateOf(ConfirmFlowState()) }
    var report by remember { mutableStateOf<ExecutionReport?>(null) }
    var undoMessage by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (stepIndex > 0) stepIndex -= 1 else onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets),
    ) {
        PrimaryTabRow(
            selectedTabIndex = stepIndex,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_B_STEP_TABS),
        ) {
            WorkflowBStep.entries.forEachIndexed { i, step ->
                Tab(
                    selected = stepIndex == i,
                    onClick = { stepIndex = i },
                    text = { Text(step.label) },
                    modifier = Modifier
                        .testTag(
                            when (step) {
                                WorkflowBStep.Library -> TAG_B_STEP_LIBRARY
                                WorkflowBStep.Metadata -> TAG_B_STEP_METADATA
                                WorkflowBStep.Pack -> TAG_B_STEP_PACK
                            },
                        )
                        .semantics { contentDescription = step.label },
                )
            }
        }

        // 步骤内容不重复叠加 inset(外层已处理)
        val innerInsets = WindowInsets(0, 0, 0, 0)

        // 非首步给一个显式返回入口(Tab 也能切,但长列表滚到底后 Tab 不在视口内)
        if (stepIndex > 0) {
            TextButton(
                onClick = { stepIndex -= 1 },
                modifier = Modifier
                    .padding(start = 4.dp)
                    .testTag(TAG_B_STEP_BACK)
                    .semantics { contentDescription = "返回上一步" },
            ) { Text("← 返回上一步") }
        }

        when (WorkflowBStep.entries[stepIndex]) {
            WorkflowBStep.Library -> LibraryPage(
                state = state,
                onStateChange = { state = it },
                contentInsets = innerInsets,
                onNext = { stepIndex = 1 },
                modifier = Modifier.weight(1f),
            )

            WorkflowBStep.Metadata -> MetadataPage(
                libraryState = state,
                onLibraryChange = { state = it },
                contentInsets = innerInsets,
                onNext = { stepIndex = 2 },
                modifier = Modifier.weight(1f),
            )

            WorkflowBStep.Pack -> {
                val currentReport = report
                if (currentReport != null) {
                    ExecutionReportPage(
                        report = currentReport,
                        undoMessage = undoMessage,
                        onUndo = { undoMessage = performUndo(undoRecordOf(currentReport)).message },
                        onDone = {
                            report = null
                            undoMessage = null
                            stepIndex = 0
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    PackPreviewPage(
                        libraryState = state,
                        onLibraryChange = { state = it },
                        contentInsets = innerInsets,
                        conflictPolicy = conflictPolicy,
                        sourcePolicy = sourcePolicy,
                        cleanEmptyDirs = cleanEmptyDirs,
                        onConflictPolicyChange = { conflictPolicy = it },
                        onSourcePolicyChange = { sourcePolicy = it },
                        onCleanEmptyDirsChange = { cleanEmptyDirs = it },
                        confirmFlow = confirmFlow,
                        onStartConfirm = {
                            val plans = buildPackagePlans(state.volumes, conflictPolicy, sourcePolicy)
                            val dangers = effectiveDangerActions(plans, cleanEmptyDirs)
                            confirmFlow = startConfirmFlow(
                                buildConfirmSequence(dangers, generalSummaryLines(plans, dangers)),
                            )
                        },
                        onConfirmStep = {
                            val (next, done) = confirmFlow.confirmCurrent()
                            confirmFlow = next
                            if (done) {
                                report = executePackagePlans(
                                    buildPackagePlans(state.volumes, conflictPolicy, sourcePolicy),
                                )
                            }
                        },
                        onCancelConfirm = { confirmFlow = confirmFlow.cancel() },
                        onExecute = {},
                        showPlanSection = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
