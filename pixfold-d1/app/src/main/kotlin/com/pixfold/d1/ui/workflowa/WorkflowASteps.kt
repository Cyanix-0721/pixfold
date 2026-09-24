package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.workflowa.WorkflowAState
import com.pixfold.d1.domain.plan.ConfirmFlowState
import com.pixfold.d1.domain.plan.ExecutionReport
import com.pixfold.d1.domain.plan.buildConfirmSequence
import com.pixfold.d1.domain.plan.buildLibraryRenamePlan
import com.pixfold.d1.domain.plan.cancel
import com.pixfold.d1.domain.plan.confirmCurrent
import com.pixfold.d1.domain.plan.executeRenamePlan
import com.pixfold.d1.domain.plan.performUndo
import com.pixfold.d1.domain.plan.renameSummaryLines
import com.pixfold.d1.domain.plan.startConfirmFlow
import com.pixfold.d1.domain.plan.undoRecordOf
import com.pixfold.d1.domain.plan.workflowADangerActions
import com.pixfold.d1.domain.workflowa.workflowAStateOf
import com.pixfold.d1.nav.DestinationSurface
import com.pixfold.d1.nav.NavTransitionStyle
import com.pixfold.d1.nav.PixFoldNavHost
import com.pixfold.d1.nav.WorkflowANamingRoute
import com.pixfold.d1.nav.WorkflowAPlanRoute
import com.pixfold.d1.nav.WorkflowASortRoute
import com.pixfold.d1.nav.goToWorkflowAStep
import com.pixfold.d1.nav.workflowAStepIndex
import com.pixfold.d1.ui.plan.ExecutionReportPage

const val TAG_STEP_TABS = "step-tabs"
const val TAG_STEP_SORT = "step-sort"
const val TAG_STEP_NAMING = "step-naming"
const val TAG_STEP_BACK = "step-back"
const val TAG_STEP_PLAN = "step-plan"

/** 命名结构 -> 执行计划 的下一步入口(与步骤1的「下一步」区分,便于测试精确定位)。 */
const val TAG_TO_PLAN = "step-to-plan"

/** 工作流 A 的步骤(规格 §10.1:步骤1 排序与预览 / 步骤2 命名结构 / 步骤3 计划)。 */
enum class WorkflowAStep(val label: String) {
    Sort("步骤1 排序与预览"),
    Naming("步骤2 命名结构"),
    Plan("步骤3 执行计划"),
}

/**
 * 工作流 A 的步骤容器。
 *
 * **为什么把状态提到这里**:步骤 2 的命名序号取自**步骤 1 的最终页序**(规格 §6.2
 * "position = 当前页序中的下标")。若两页各自持有状态,命名的序号就会与用户排好的
 * 顺序脱节 —— 这是必须由上层持有、两页共享的**唯一**理由。
 *
 * 用 M3 `TabRow` 而非自绘步骤条:直接获得 M3 的选中/未选中语义色、指示器与无障碍支持。
 *
 * ## 步骤切换 = 嵌套 NavHost(2026-09-22 迁移)
 *
 * 迁移前这里的"当前步骤"是 `var stepIndex by remember { mutableIntStateOf(0) }`,
 * 返回栈靠 `stepIndex -= 1` 模拟。现在改用**嵌套 NavHost**托管步骤返回栈:
 * 步骤级返回由 NavHost 自己处理,预测性返回的"目标页停在背后"也就免费拿到了。
 *
 * **为什么步骤序号仍然存在**:`TabRow` 需要一个选中下标,而它是从**导航栈派生**的
 * (见 [workflowAStepIndex]),不再是独立状态 —— 于是不存在"两份状态不同步"的问题。
 *
 * **TabRow 为什么留在 NavHost 外面**:它要常驻(不随步骤内容转场),而且这样
 * `TAG_STEP_*` 这些 testTag 在任意时刻都只有一份被组合,转场期间不会出现重复 tag。
 *
 * 嵌套图的返回由**内层** NavHost 处理;内层栈深为 1 时它自动让位给外层
 * (navigation 的 `enabled = currentBackStack.size > 1`),故"步骤 1 再返回 → 回首页"
 * 无需本组件写任何代码,也不再需要 `onBack` 参数。
 */
@Composable
fun WorkflowASteps(
    collections: List<ImageCollection>,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onOpenPreview: (Int) -> Unit = {},
    onActiveCollectionChange: (String) -> Unit = {},
    /**
     * 步骤级返回是否使用**预测性返回动画**(来自 `settings.predictiveBackAnimation`)。
     * 默认 true(与设置默认值一致);false 时嵌套 NavHost 的手势转场被关掉,
     * 但普通返回动画保留(即"关掉手势跟随,但不硬切")。
     */
    predictiveBackAnimation: Boolean = true,
) {
    // 跨步骤共享的唯一状态源。这些状态**必须留在嵌套 NavHost 之外** ——
    // 它们要跨步骤存活,而 NavHost 里每个 destination 都是独立作用域。
    var state by remember { mutableStateOf(workflowAStateOf(collections)) }
    var activeId by remember { mutableStateOf(collections.firstOrNull()?.id.orEmpty()) }
    // 命名结构与覆盖是**批次级**(规格 §6.3:overrides 全局一份,不属于某个集合),
    // 故在此持有 —— 切集合、来回切 Tab 都不会丢失用户的覆盖。
    // 工作流 A 的危险动作(默认关闭)+ 确认流程 + 执行报告
    var cleanEmptyDirs by remember { mutableStateOf(false) }
    var confirmFlow by remember { mutableStateOf(ConfirmFlowState()) }
    var report by remember { mutableStateOf<ExecutionReport?>(null) }
    var undoMessage by remember { mutableStateOf<String?>(null) }

    var namingState by remember {
        mutableStateOf(
            NamingState(
                NamingScheme(rootDirName = collections.firstOrNull()?.rootDirName.orEmpty()),
            ),
        )
    }

    val activeCollection = collections.firstOrNull { it.id == activeId } ?: collections.firstOrNull()

    val navController = rememberNavController()
    // 当前步骤**从导航栈派生**。返回手势进行中 NavHost 尚未提交 pop,
    // 故 entry 不变 —— 序号在整段手势里稳定,不会抖。
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentIndex = navBackStackEntry?.destination.workflowAStepIndex()

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets),
    ) {
        PrimaryTabRow(
            selectedTabIndex = currentIndex,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_STEP_TABS),
        ) {
            WorkflowAStep.entries.forEachIndexed { i, step ->
                Tab(
                    selected = currentIndex == i,
                    // 跳步必须走 goToWorkflowAStep:往回 pop、往前逐级 push,
                    // 保证返回链始终是线性的一步一退(详见该函数注释)。
                    onClick = { navController.goToWorkflowAStep(currentIndex, i) },
                    text = { Text(step.label) },
                    modifier = Modifier
                        .testTag(
                            when (step) {
                                WorkflowAStep.Sort -> TAG_STEP_SORT
                                WorkflowAStep.Naming -> TAG_STEP_NAMING
                                WorkflowAStep.Plan -> TAG_STEP_PLAN
                            },
                        )
                        .semantics { contentDescription = step.label },
                )
            }
        }

        // 步骤内容不重复叠加 inset(外层已处理)
        val innerInsets = WindowInsets(0, 0, 0, 0)

        PixFoldNavHost(
            navController = navController,
            startDestination = WorkflowASortRoute,
            predictiveBackAnimation = predictiveBackAnimation,
            // 步骤级 = 只淡出:上级页原样露出(用户规格)
            style = NavTransitionStyle.Step,
            // weight 必须挂在这里:它是 ColumnScope 扩展,原先挂在三个子页上,
            // 现在它们各自在独立的 destination 里,外层只剩这一个子节点需要撑满剩余高度。
            modifier = Modifier.weight(1f),
        ) {
            composable<WorkflowASortRoute> {
                DestinationSurface {
                    // 每个 destination 内再包一层 Column,好让子页自己的 weight(1f) 有作用域
                    Column(modifier = Modifier.fillMaxSize()) {
                        SortAndPreviewPage(
                            collections = collections,
                            contentInsets = innerInsets,
                            onOpenPreview = onOpenPreview,
                            initialCollectionId = activeId,
                            onActiveCollectionChange = { activeId = it },
                            externalState = state,
                            onStateChange = { state = it },
                            onNext = { navController.navigate(WorkflowANamingRoute) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            composable<WorkflowANamingRoute> {
                DestinationSurface {
                    val collection = activeCollection
                    val order = collection?.let { state.orders[it.id] }
                    if (collection != null && order != null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 返回与推进都交给页面自己的底栏(用户 2026-09-25 指定:
                            // 左 = 退回、右 = 推进)。原先这里的「返回排序」入口在上、
                            // 「下一步：执行计划」入口在下,把内容夹在中间 —— 已删除。
                            NamingPage(
                                collection = collection,
                                order = order,
                                contentInsets = innerInsets,
                                initialState = namingState,
                                onStateChange = { namingState = it },
                                onBack = { navController.popBackStack() },
                                onNext = { navController.navigate(WorkflowAPlanRoute) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        // `align` 是 ColumnScope 扩展,故这里必须自带一个 Column 作用域
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "无可命名的集合",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(16.dp)
                                    .align(Alignment.CenterHorizontally),
                            )
                        }
                    }
                }
            }

            composable<WorkflowAPlanRoute> {
                DestinationSurface {
                    val currentReport = report
                    if (currentReport != null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ExecutionReportPage(
                                report = currentReport,
                                undoMessage = undoMessage,
                                onUndo = { undoMessage = performUndo(undoRecordOf(currentReport)).message },
                                onDone = {
                                    // 执行完回到步骤 1:报告是一次执行的终点,不该留在计划页
                                    // 让用户误以为还能再执行一次。
                                    // 用 pop 而非 navigate —— 后半段栈([Naming, Plan])必须整体出栈,
                                    // 否则返回会回到刚执行完的计划页。
                                    report = null
                                    undoMessage = null
                                    navController.popBackStack(WorkflowASortRoute, inclusive = false)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 返回入口由 RenamePlanPage 的底栏提供(用户 2026-09-25 指定)
                            RenamePlanPage(
                                collections = collections,
                                orders = state.orders,
                                namingState = namingState,
                                cleanEmptyDirs = cleanEmptyDirs,
                                onCleanEmptyDirsChange = { cleanEmptyDirs = it },
                                flow = confirmFlow,
                                onStartConfirm = {
                                    val plan = buildLibraryRenamePlan(collections, state.orders, namingState)
                                    val dangers = workflowADangerActions(cleanEmptyDirs)
                                    confirmFlow = startConfirmFlow(
                                        buildConfirmSequence(dangers, renameSummaryLines(plan, dangers)),
                                    )
                                },
                                onConfirmStep = {
                                    val (next, done) = confirmFlow.confirmCurrent()
                                    confirmFlow = next
                                    if (done) {
                                        val plan = buildLibraryRenamePlan(collections, state.orders, namingState)
                                        report = executeRenamePlan(plan.ops)
                                    }
                                },
                                onCancelConfirm = { confirmFlow = confirmFlow.cancel() },
                                // 迁移前这里传的是 `stepIndex = 2`,而当前已是步骤 3 —— 属就地 no-op。
                                // 保留空实现以维持等价行为(执行入口由确认流程驱动)。
                                onExecute = {},
                                contentInsets = innerInsets,
                                modifier = Modifier.weight(1f),
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
