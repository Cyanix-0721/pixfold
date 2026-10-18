package com.cyanix.pixfold.ui.workflowb

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.plan.ConfirmFlowState
import com.cyanix.pixfold.domain.plan.ExecutionReport
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.SourcePolicy
import com.cyanix.pixfold.domain.plan.buildConfirmSequence
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.plan.cancel
import com.cyanix.pixfold.domain.plan.confirmCurrent
import com.cyanix.pixfold.domain.plan.effectiveDangerActions
import com.cyanix.pixfold.domain.plan.executePackagePlans
import com.cyanix.pixfold.domain.plan.generalSummaryLines
import com.cyanix.pixfold.domain.plan.performUndo
import com.cyanix.pixfold.domain.plan.startConfirmFlow
import com.cyanix.pixfold.domain.plan.UndoScope
import com.cyanix.pixfold.domain.plan.undoRecordOf
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.workflowb.WorkflowBState
import com.cyanix.pixfold.domain.workflowb.workflowBStateOf
import com.cyanix.pixfold.nav.DestinationSurface
import com.cyanix.pixfold.nav.NavTransitionStyle
import com.cyanix.pixfold.nav.PixFoldNavHost
import com.cyanix.pixfold.nav.WorkflowBLibraryRoute
import com.cyanix.pixfold.nav.WorkflowBMetadataRoute
import com.cyanix.pixfold.nav.WorkflowBPackRoute
import com.cyanix.pixfold.nav.goToWorkflowBStep
import com.cyanix.pixfold.nav.popWorkflowStep
import com.cyanix.pixfold.nav.workflowBStepIndex
import com.cyanix.pixfold.ui.components.ResetAllButton
import com.cyanix.pixfold.ui.plan.ExecutionReportPage

const val TAG_B_STEP_TABS = "b-step-tabs"
const val TAG_B_STEP_LIBRARY = "b-step-library"
const val TAG_B_STEP_METADATA = "b-step-metadata"
const val TAG_B_STEP_PACK = "b-step-pack"
const val TAG_B_STEP_BACK = "b-step-back"

/** 工作流 B 的步骤(规格 §10.1:步骤1 漫画库 / 步骤2 元数据 / 步骤3 打包计划)。 */
enum class WorkflowBStep(val labelRes: Int) {
    Library(R.string.b_step_label_library),
    Metadata(R.string.b_step_label_metadata),
    Pack(R.string.b_step_label_pack),
}

/**
 * 工作流 B 的步骤容器(验收第 11–14 项)。
 *
 * 与 `WorkflowASteps` 同构:状态提在这一层,**三个步骤共享同一个
 * [WorkflowBState]**(含 `activeVolumeId`)—— 否则"在第 1 步选中的卷"在切到第 2 步时会丢失。
 *
 * ## 步骤切换 = 嵌套 NavHost(2026-09-22 迁移)
 *
 * 迁移前"当前步骤"是 `var stepIndex by remember { mutableIntStateOf(0) }`,返回靠
 * `stepIndex -= 1` 模拟。现在由嵌套 NavHost 托管步骤返回栈:步骤 3 → 2 → 1 → 首页
 * 逐级后退,且步骤级手势跟随的"目标页停在背后"由 NavHost 免费提供。
 *
 * 内层栈深为 1 时嵌套 NavHost 的返回处理器会**自动让位**给外层,故"步骤 1 再返回 →
 * 回首页"无需本组件写代码,`onBack` 参数因此被删除。
 *
 * @param volumes 库内容;默认由上层注入(便于测试换数据)。
 */
@Composable
fun WorkflowBSteps(
    rootPath: String,
    volumes: List<ComicVolume>,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    /**
     * 步骤级返回是否使用**预测性返回动画**(来自 `settings.predictiveBackAnimation`)。
     * 默认 true(与设置默认值一致);false 时嵌套 NavHost 的手势转场被关掉,
     * 但普通返回动画保留(即"关掉手势跟随,但不硬切")。
     */
    predictiveBackAnimation: Boolean = true,
) {
    // 跨步骤共享的唯一状态源。这些状态**必须留在嵌套 NavHost 之外** ——
    // 它们要跨步骤存活,而 NavHost 里每个 destination 都是独立作用域。
    var state by remember { mutableStateOf(workflowBStateOf(rootPath, volumes)) }

    // P6:策略(默认 Rename / Keep,与归档语义一致)、危险动作、确认流程、执行报告。
    // 全部提到这一层 —— 在步骤 3 内执行完再看报告,切回来时不该丢掉策略选择。
    var conflictPolicy by remember { mutableStateOf(OutputConflictPolicy.Rename) }
    var sourcePolicy by remember { mutableStateOf(SourcePolicy.Keep) }
    var cleanEmptyDirs by remember { mutableStateOf(false) }
    var confirmFlow by remember { mutableStateOf(ConfirmFlowState()) }
    var report by remember { mutableStateOf<ExecutionReport?>(null) }
    // 领域层的 `Msg`(语言无关);成品文案由 ExecutionReportPage 现场解析
    var undoMessage by remember { mutableStateOf<Msg?>(null) }

    val navController = rememberNavController()
    // 当前步骤**从导航栈派生**(理由同 WorkflowASteps):手势进行中 entry 不变,序号稳定。
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentIndex = navBackStackEntry?.destination.workflowBStepIndex()

    /**
     * 重置**整个工作流**的调整(用户 2026-10-14 裁决,B 侧与 A 侧同构)。
     *
     * 「全部」= 卷勾选回到初始(全部 included)、当前卷回到第一卷、输出冲突与源文件
     * 策略回到默认(重命名 / 保留)、危险动作开关复位、确认流程与报告复位,
     * 并**回到步骤 1**。
     */
    fun resetAll() {
        state = workflowBStateOf(rootPath, volumes)
        conflictPolicy = OutputConflictPolicy.Rename
        sourcePolicy = SourcePolicy.Keep
        cleanEmptyDirs = false
        confirmFlow = ConfirmFlowState()
        report = null
        undoMessage = null
        // 回到步骤 1(退回一律用 pop,保持返回链线性)
        navController.goToWorkflowBStep(currentIndex, 0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets),
    ) {
        // 页签行 + 右侧「重置全部」:理由与槽位写法同 WorkflowASteps
        //(`PrimaryTabRow` 无 trailing 槽位,故外层 Row 分槽)。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryTabRow(
                selectedTabIndex = currentIndex,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TAG_B_STEP_TABS),
            ) {
                WorkflowBStep.entries.forEachIndexed { i, step ->
                    // 要进 semantics{},故先在组合上下文里解析
                    val stepLabel = stringResource(step.labelRes)
                    Tab(
                        selected = currentIndex == i,
                        // 跳步走 goToWorkflowBStep,保证返回链始终线性(详见该函数注释)
                        onClick = { navController.goToWorkflowBStep(currentIndex, i) },
                        text = { Text(stepLabel) },
                        modifier = Modifier
                            .testTag(
                                when (step) {
                                    WorkflowBStep.Library -> TAG_B_STEP_LIBRARY
                                    WorkflowBStep.Metadata -> TAG_B_STEP_METADATA
                                    WorkflowBStep.Pack -> TAG_B_STEP_PACK
                                },
                            )
                            .semantics { contentDescription = stepLabel },
                    )
                }
            }

            ResetAllButton(onReset = { resetAll() })
        }

        // 步骤内容不重复叠加 inset(外层已处理)
        val innerInsets = WindowInsets(0, 0, 0, 0)

        // 返回入口已随「底栏化」迁入各页自己的底栏(用户 2026-09-25 指定:
        // 左 = 退回、右 = 推进)。原先常驻在 TabRow 与内容之间的那个返回按钮已删除 ——
        // 它把内容夹在中间,且与底栏的推进按钮分处两端,不符合统一的底栏布局。
        PixFoldNavHost(
            navController = navController,
            startDestination = WorkflowBLibraryRoute,
            predictiveBackAnimation = predictiveBackAnimation,
            // 步骤级 = 只淡出:上级页原样露出(用户规格)
            style = NavTransitionStyle.Step,
            // weight 挂在嵌套 NavHost 自身(ColumnScope 扩展),理由同 WorkflowASteps
            modifier = Modifier.weight(1f),
        ) {
            composable<WorkflowBLibraryRoute> {
                DestinationSurface {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LibraryPage(
                            state = state,
                            onStateChange = { state = it },
                            contentInsets = innerInsets,
                            onNext = { navController.navigate(WorkflowBMetadataRoute) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            composable<WorkflowBMetadataRoute> {
                DestinationSurface {
                    Column(modifier = Modifier.fillMaxSize()) {
                        MetadataPage(
                            libraryState = state,
                            onLibraryChange = { state = it },
                            contentInsets = innerInsets,
                            onNext = { navController.navigate(WorkflowBPackRoute) },
                            onBack = { navController.popWorkflowStep() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            composable<WorkflowBPackRoute> {
                DestinationSurface {
                    val currentReport = report
                    if (currentReport != null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ExecutionReportPage(
                                report = currentReport,
                                undoMessage = undoMessage,
                                onUndoScope = { scope ->
                                    // 用户选的档位必须传到领域层:危险档多删的就是
                                    // 「被本次覆盖掉的那些位置」(见 UndoScope)。
                                    undoMessage = performUndo(undoRecordOf(currentReport, scope)).message
                                },
                                onDone = {
                                    // 同 WorkflowASteps:用 pop 让 [Metadata, Pack] 整体出栈
                                    report = null
                                    undoMessage = null
                                    navController.popBackStack(WorkflowBLibraryRoute, inclusive = false)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
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
                                onBack = { navController.popWorkflowStep() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
