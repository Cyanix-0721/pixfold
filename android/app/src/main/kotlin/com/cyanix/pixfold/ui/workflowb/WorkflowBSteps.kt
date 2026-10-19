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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress
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
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import kotlinx.coroutines.launch
import com.cyanix.pixfold.ui.plan.ExecutionReportPage

/**
 * 把「预检拒绝」翻成一句能操作的话（D2b）。
 *
 * ### 为什么不是直接显示 `blockers.toString()`
 * 领域层的 `PackBlocker.SpaceInsufficient` 带的是字节数（`shortfallBytes`），
 * 直接打印是 `SpaceInsufficient(space=Insufficient(availableBytes=..., shortfallBytes=...))`。
 * 用户需要的是「还差 1.2 GB」这种可行动的读数。
 *
 * ### 为什么放在组合之外（非 @Composable）
 * 它是纯字符串拼装，但要拿 `Context` 解析文案 —— 而 `Context` 在调用点的闭包里
 * 已经能通过 `LocalContext` 拿到。做成普通函数才能在协程回调里调用。
 *
 * 只报**第一个**阻断理由：目前的阻断项里，空间不足与输出名冲突都是「改完就能
 * 立刻重试」的单点问题，列两条会让用户在本就拥挤的错误行里读两遍。
 * 将来若出现「一次必须全部处理」的类型，再改成列全部。
 */
private fun rejectionText(context: android.content.Context, outcome: PackUiOutcome.Rejected): String {
    val result = outcome.preflight
    val blocker = result.blockers.firstOrNull()
    return when (blocker) {
        is com.cyanix.pixfold.domain.pack.PackBlocker.SpaceInsufficient ->
            context.getString(
                com.cyanix.pixfold.R.string.pack_error_space,
                humanBytes(blocker.shortfallBytes),
                humanBytes(result.availableBytes),
            )
        // 输出名撞车:这是 2026-10-19 真机发现的真实缺陷(两卷写同一路径 ⇒
        // 报告说「成功 2」而磁盘只剩 1 个)。文案给路径 + 下一步动作,
        // 因为用户必须回到步骤 2 改 CBZ 文件名才能继续。
        is com.cyanix.pixfold.domain.pack.PackBlocker.OutputNameCollision ->
            context.resources.getQuantityString(
                com.cyanix.pixfold.R.plurals.pack_error_collision,
                blocker.collidingCount,
                blocker.collidingCount,
                blocker.outputPath,
            ) + "\n" + context.getString(com.cyanix.pixfold.R.string.pack_error_collision_hint)
        // 没有阻断却走到「拒绝」是自相矛盾的（`canProceed` = blockers.isEmpty()）。
        // 与其静默什么都不显示，不如说清楚：让用户知道该报 bug 而不是反复重试。
        null -> context.getString(com.cyanix.pixfold.R.string.pack_error_unknown)
    }
}

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
    /**
     * 工作区状态（D2b）。`null` = **不显示**工作区条、且执行走模拟。
     *
     * 与 `WorkflowASteps` 的同名参数保持完全一致的语义（理由见那里的长注释）：
     * 默认 `null` 让既有 UI 测试的布局与断言一行都不用改 —— 它们是在「没有工作区
     * 概念」的前提下写的。真实应用由 `PixFoldApp` 注入实际状态。
     */
    workspaceState: WorkspaceState? = null,
    /** 用户要求选择/更换工作区目录。仅 [workspaceState] 非 null 时会被调用。 */
    onPickWorkspace: () -> Unit = {},
    /**
     * 真实执行器（D2b）。`null` = **走模拟执行**（`executePackagePlans`）。
     *
     * 这个参数是「D1 原型」与「真实写入」之间唯一的开关，刻意做成可空而不做成
     * `Boolean`：执行器自身携带工作区 URI 与平台上下文，测试里根本无法构造真实现，
     * 而 `false` 那种布尔开关会让「忘了传执行器」与「故意不传」在类型上无法区分 ——
     * 前者是缺陷（用户点确认却什么都没写），后者是测试的正当需求。
     */
    packExecutor: PackExecutorPort? = null,
) {
    // 跨步骤共享的唯一状态源。这些状态**必须留在嵌套 NavHost 之外** ——
    // 它们要跨步骤存活,而 NavHost 里每个 destination 都是独立作用域。
    //
    // ⚠️ key 用 `volumes`（2026-10-19，与工作流 A 的热切换白屏同源）：
    // 工作流 B 的数据源接上真实工作区后，重扫/换工作区都会传下**新的** `volumes`。
    // 若无 key，本组件组合实例不销毁 ⇒ 仍持旧库，用户删除的卷阴魂不散、
    // 新增的卷永远不出现。带 key 后每次来源变化都重新播种。
    //
    // ⚠️ 重建 state 会不会丢掉用户在步骤 2 填的元数据？**不会**，前提是上层做了
    // 对账：`PixFoldApp` 在组合期调 `reconcileLibrary(旧, 新扫描)`，传下来的
    // `volumes` 已经是「扫描事实 + 用户调整」的合并结果。这里的重新播种只是
    // 换了 state 的外壳（activeVolumeId 等容器字段），卷内容本身不丢。
    // 换言之：**对账的责任在上层，本组件只负责不缓存**。
    //
    // 唯一需要额外保住的是「当前选中的是哪一卷」—— 它在 `WorkflowBState` 里，
    // 而 `workflowBStateOf` 一律把当前卷设成第一卷。用户删了张图、列表刷新一下
    // 就被拽回第一本，是很突兀的体验。
    var lastActiveVolumeId by remember(rootPath) { mutableStateOf("") }
    var state by remember(volumes) {
        val seeded = workflowBStateOf(rootPath, volumes)
        // 上一次选的那卷还在就用它；已消失则退回扫描给出的默认（第一本）
        val keep = seeded.volumes.firstOrNull { it.id == lastActiveVolumeId }?.id
            ?: seeded.activeVolumeId
        mutableStateOf(seeded.copy(activeVolumeId = keep))
    }
    // 把当前卷的变化记到跨刷新存活的容器里。写成 SideEffect 而不是在
    // `onStateChange` 各处埋点：卷的切换路径有 4 条（点卡片、批次设置、重置、
    // 对账重建），逐条埋点必漏，而这里只需要「state 变了就同步一次」。
    SideEffect { lastActiveVolumeId = state.activeVolumeId }

    // P6:策略(默认 Rename / Keep,与归档语义一致)、危险动作、确认流程、执行报告。
    // 全部提到这一层 —— 在步骤 3 内执行完再看报告,切回来时不该丢掉策略选择。
    var conflictPolicy by remember { mutableStateOf(OutputConflictPolicy.Rename) }
    var sourcePolicy by remember { mutableStateOf(SourcePolicy.Keep) }
    var cleanEmptyDirs by remember { mutableStateOf(false) }
    var confirmFlow by remember { mutableStateOf(ConfirmFlowState()) }
    var report by remember { mutableStateOf<ExecutionReport?>(null) }
    // 领域层的 `Msg`(语言无关);成品文案由 ExecutionReportPage 现场解析
    var undoMessage by remember { mutableStateOf<Msg?>(null) }

    // ---- 真实执行(D2b)----
    // 正在执行时,计划区与底栏都换形态(见 PackPreviewPage 的 executing 参数)。
    var executing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<TaskProgress?>(null) }
    // 执行器故障(拿不到授权 / 输出目录不可写)。与「报告里的失败项」分开显示:
    // 后者是部分成功,前者是压根没跑起来。
    var executionError by remember { mutableStateOf<String?>(null) }
    // 取消令牌。每次执行新建一个 —— 复用会让「上次取消过」污染下一次。
    var cancelToken by remember { mutableStateOf<CancellationToken?>(null) }
    // 取消已发出、任务正在收尾。必须在**执行结束时**复位 —— 否则下一次执行
    // 一进来「取消」按钮就是禁用态,用户再也停不下来。
    var cancelling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 拒绝文案与结果故障要在非 composable 的回调里取 `Context` 解析文案
    val context = androidx.compose.ui.platform.LocalContext.current

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
        // 重置时若有任务在跑,先叫停 —— 否则重置完界面归零,后台还在往磁盘写,
        // 用户看到的是「我明明重置了,文件却还在变多」。
        cancelToken?.cancel()
        cancelToken = null
        executing = false
        cancelling = false
        progress = null
        executionError = null
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
                            // 工作区条只在注入状态时出现(D2b);默认 null ⇒ 既有测试布局不变
                            workspaceState = workspaceState,
                            onPickWorkspace = onPickWorkspace,
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
                                        val plans = buildPackagePlans(
                                            state.volumes,
                                            conflictPolicy,
                                            sourcePolicy,
                                        )
                                        val executor = packExecutor
                                        if (executor == null) {
                                            // 没有真实执行器 ⇒ D1 原型的模拟路径。
                                            // 这条分支存在的意义是让既有 UI 测试不必构造
                                            // 平台对象;真实应用永远会注入执行器。
                                            report = executePackagePlans(plans)
                                        } else {
                                            // ---- 真实执行(D2b)----
                                            // 令牌每次新建:复用会让上次的取消污染这一次。
                                            val token = CancellationToken()
                                            cancelToken = token
                                            executing = true
                                            cancelling = false
                                            progress = null
                                            executionError = null
                                            scope.launch {
                                                val outcome = try {
                                                    executor.execute(
                                                        plans = plans,
                                                        token = token,
                                                        // 回调在 IO 线程上;快照状态本身线程安全,
                                                        // 但为了让 Compose 观察到变化,写回主线程。
                                                        onProgress = { p -> progress = p },
                                                    )
                                                } finally {
                                                    // 无论成功、被拒、异常还是取消,都必须复位 ——
                                                    // 留在 executing 会让底栏永远停在「取消」,
                                                    // 用户再也点不到「确认打包」。
                                                    executing = false
                                                    cancelling = false
                                                    cancelToken = null
                                                }
                                                when (outcome) {
                                                    is PackUiOutcome.Done -> report = outcome.report
                                                    is PackUiOutcome.Rejected ->
                                                        executionError = rejectionText(context, outcome)
                                                    is PackUiOutcome.Error ->
                                                        executionError = outcome.message
                                                }
                                            }
                                        }
                                    }
                                },
                                onCancelConfirm = { confirmFlow = confirmFlow.cancel() },
                                onExecute = {},
                                showPlanSection = true,
                                // 执行态:底栏换「取消」+ 上方进度条(见 PackPreviewPage)
                                executing = executing,
                                progress = progress,
                                onCancelExecution = {
                                    // 先让界面进入「取消中」,再发信号:反过来的话
                                    // 信号发出去后界面仍可点,存在连点窗口。
                                    cancelling = true
                                    cancelToken?.cancel()
                                },
                                cancelling = cancelling,
                                executionError = executionError,
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
