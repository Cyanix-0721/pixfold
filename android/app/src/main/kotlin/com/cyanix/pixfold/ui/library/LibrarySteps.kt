package com.cyanix.pixfold.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cyanix.pixfold.R
import com.cyanix.pixfold.data.task.PackTaskBus
import com.cyanix.pixfold.data.task.PackTaskRequest
import com.cyanix.pixfold.data.task.PackTaskService
import com.cyanix.pixfold.data.task.PackTaskSettled
import com.cyanix.pixfold.data.task.PackTaskState
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
import com.cyanix.pixfold.domain.comic.applyDirMetaSuggestions
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.libraryStateOf
import com.cyanix.pixfold.domain.library.updateVolume
import com.cyanix.pixfold.nav.DestinationSurface
import com.cyanix.pixfold.nav.NavTransitionStyle
import com.cyanix.pixfold.nav.PixFoldNavHost
import com.cyanix.pixfold.nav.LibraryShelfRoute
import com.cyanix.pixfold.nav.LibraryMetadataRoute
import com.cyanix.pixfold.nav.LibraryPackRoute
import com.cyanix.pixfold.nav.goToLibraryStep
import com.cyanix.pixfold.nav.popWorkflowStep
import com.cyanix.pixfold.nav.libraryStepIndex
import com.cyanix.pixfold.ui.components.DirMetaButton
import com.cyanix.pixfold.ui.components.ResetAllButton
import com.cyanix.pixfold.ui.text.WarnLine
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import com.cyanix.pixfold.ui.workspace.handleOrNull
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

const val TAG_B_STEP_TABS = "library-step-tabs"
const val TAG_B_STEP_LIBRARY = "library-step-library"
const val TAG_B_STEP_METADATA = "library-step-metadata"
const val TAG_B_STEP_PACK = "library-step-pack"
const val TAG_B_STEP_BACK = "library-step-back"

/** 工作流 B 的步骤(规格 §10.1:步骤1 漫画库 / 步骤2 元数据 / 步骤3 打包计划)。 */
enum class LibraryStep(val labelRes: Int) {
    Library(R.string.b_step_label_library),
    Metadata(R.string.b_step_label_metadata),
    Pack(R.string.b_step_label_pack),
}

/**
 * 工作流 B 的步骤容器(验收第 11–14 项)。
 *
 * 与 `OrganizeSteps` 同构:状态提在这一层,**三个步骤共享同一个
 * [LibraryState]**(含 `activeVolumeId`)—— 否则"在第 1 步选中的卷"在切到第 2 步时会丢失。
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
fun LibrarySteps(
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
     * 与 `OrganizeSteps` 的同名参数保持完全一致的语义（理由见那里的长注释）：
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
     *
     * ⚠️ **2026-10-19 起真实应用不再走这条路径**，改用前台服务（见 [backgroundPacking]）：
     * 在组合作用域里跑执行器，用户切到别的应用就会中断。这里保留它是因为
     * 「进程内的同步执行」仍是单元测试与将来「只需要打一卷」的轻量场景的正当需求。
     */
    packExecutor: PackExecutorPort? = null,
    /**
     * 把打包交给前台服务（D2b 第 2 项「后台任务与进度 · 切后台恢复」）。
     *
     * `true` 时：
     * - 点「确认打包」→ [PackTaskService.enqueue]，**不**在组合作用域里起协程；
     * - 进度、取消、结果全部来自 [PackTaskBus]，Activity 重建/切后台都不影响任务；
     * - 通知栏有持续进度（Android 16 上是状态栏胶囊 / Live Update）。
     *
     * 与 [packExecutor] 的关系：两者互斥，[backgroundPacking] 优先。做成两颗参数而不是
     * 一个三态枚举，是因为它们回答的是两个不同问题（「谁来执行」与「在哪个生命周期里执行」），
     * 而目前的组合只有三种：模拟（都空）/ 进程内（只给执行器）/ 前台服务（给服务标记）。
     */
    backgroundPacking: Boolean = false,
    /**
     * 上次打包被中断时，交给界面展示的恢复提示（D2b）。`null` = 没有需要提示的中断。
     *
     * 由上层（`PixFoldApp`）在启动时用 `recoverPackTask(快照, 服务是否还活着)` 算出，
     * 提示被用户关掉后由上层清成 `null`。放上层是因为它要读落盘快照 + 问系统服务状态，
     * 两件事都超出本组件的职责。
     */
    interruptedNotice: String? = null,
    /** 用户确认/关闭了恢复提示。 */
    onDismissInterruptedNotice: () -> Unit = {},
    /**
     * 打开后是否直接落到「步骤 3 · 打包计划」。
     *
     * 用于「点打包通知回到进度/结果」（2026-10-20 真机缺陷：点通知落在首页）。
     * 只把人送到能看见进度的那一页，**不另存状态** —— 进度本身来自
     * [PackTaskBus]，Activity 重建也拿得到。默认 false ⇒ 既有测试零改动。
     */
    startAtPackStep: Boolean = false,
    /**
     * 「已把人送到步骤 3」之后回调上层清零。
     *
     * **为什么必须回清**（2026-10-20 真机第二轮）：热启动点通知时 `onNewIntent`
     * 把标志置 true；若不清零，用户从打包页按返回回书架、再点「打包漫画」，
     * 又会立刻被弹回步骤 3。默认空实现 ⇒ 既有测试零改动。
     */
    onStartAtPackStepHandled: () -> Unit = {},
    /**
     * 工作区结构警告（2026-10-20 裁决「Series 三条语义」第 ③ 条：系列下出现
     * 嵌套目录要警告）。由 `PixFoldApp` 从扫描结果算出（`nestedDirWarnings`），
     * 传给书架页显示。警告说「工作区目录层级有问题」，与用户在库里的操作无关。
     * 默认空 ⇒ 既有测试零改动。
     */
    workspaceWarnings: List<Msg> = emptyList(),
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
    // 唯一需要额外保住的是「当前选中的是哪一卷」—— 它在 `LibraryState` 里，
    // 而 `libraryStateOf` 一律把当前卷设成第一卷。用户删了张图、列表刷新一下
    // 就被拽回第一本，是很突兀的体验。
    var lastActiveVolumeId by remember(rootPath) { mutableStateOf("") }
    var state by remember(volumes) {
        val seeded = libraryStateOf(rootPath, volumes)
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
    // 计划页预检(D4b)。进入步骤 3 时算,**不写盘**。
    // 之前预检只在「按下执行」之后那一刻跑 ⇒ 用户在计划页看不到任何结论,
    // 空间临界 / 估算下界 / 需要 Zip64 三类告知项全都没有出口。
    var pagePreflight by remember { mutableStateOf<PackUiPreflight?>(null) }
    val scope = rememberCoroutineScope()
    // 拒绝文案与结果故障要在非 composable 的回调里取 `Context` 解析文案
    val context = androidx.compose.ui.platform.LocalContext.current
    // 这两条文案要**在组合期**取，不能在点击回调里 `context.getString`：
    // 前者不会被记为配置依赖，语言/字号一变还拿着旧 Configuration 的字符串
    // （lint `LocalContextGetResourceValueCall`）。取值时机与展示时机解耦后，
    // 回调里只需把字符串塞进状态。
    val unknownErrorText = androidx.compose.ui.res.stringResource(
        com.cyanix.pixfold.R.string.pack_error_unknown,
    )
    val busyErrorText = androidx.compose.ui.res.stringResource(
        com.cyanix.pixfold.R.string.pack_error_busy,
    )

    // ---- 前台服务执行（D2b 第 2 项）----
    // Service 是唯一执行方，这里只是观察者。`collectAsState` 在 Activity 重建后
    // 直接拿到当前进度（进程还活着的情况下），不需要任何重连逻辑。
    val backgroundState by PackTaskBus.state.collectAsState()
    val backgroundRunning = backgroundState as? PackTaskState.Running

    // 三个派生值，**不写回**上面那组 mutable 状态：
    // 组合期写 `mutableStateOf` 会把「读取」变成「读取+写入」，在同一帧里触发
    // 额外的重组，且让「服务态」与「进程内态」两份真相互相覆盖。
    //
    // ⚠️ **判据是「总线在跑」，不是 `backgroundPacking`**（2026-10-20 真机缺陷）：
    // 从通知进来时，工作区恢复（`restore()` 读 SharedPreferences + 扫目录）比
    // 组合慢一拍 ⇒ 那几十毫秒里 `packTreeUri == null` ⇒ `backgroundPacking` 是
    // false。第一版据此判定「没在执行」，于是先渲染出**未开始的打包页**、
    // 等恢复完成才切成进度条 —— 用户看到的正是「先闪一下未开始的卡片」。
    //
    // `backgroundPacking` 表达的是「能不能走服务」（需要 tree URI），
    // 「现在是否正在执行」是另一个问题，答案只该看总线。
    val effectiveExecuting =
        backgroundRunning != null || (!backgroundPacking && executing)
    val effectiveCancelling =
        backgroundRunning?.cancelling ?: (!backgroundPacking && cancelling)
    val effectiveProgress =
        backgroundRunning?.progress ?: if (backgroundPacking) null else progress
    // 当前卷的页级进度（双层圆环**外环**）。只有服务路径能给出它 —— 进程内
    // 路径与模拟路径都没有页回调来源，此时外环画不确定态而不是假的 0%。
    val effectivePageDone = backgroundRunning?.pageDone
    val effectivePageTotal = backgroundRunning?.pageTotal
    // 正在写的卷名（弹窗底部一行）。取 `processed` 指向的那一卷：
    // 卷是顺序处理的，`processed` 就是「已完成卷数」，正好是当前卷的下标。
    //
    // ⚠️ 用 `dirPath`（目录相对路径）而不是 `title`（用户 `User said (m15457)`
    // 「当前打包的名称要完整显示作者和标题」）。`title` 是**清洗后**的元数据值，
    // 开头 `[作者]` 前缀被剥掉了 —— 同系列多本只剩后半截，弹窗里几乎一样，
    // 分不清卡在哪一本。`dirPath` 与卷列表里显示的名字同源，用户能对上号。
    val effectiveVolumeLabel = remember(backgroundRunning, state.volumes) {
        val running = backgroundRunning ?: return@remember null
        state.volumes.getOrNull(running.progress.processed)?.dirPath?.takeIf { it.isNotEmpty() }
    }

    // 收尾结果只在副作用里消费一次。展示完把总线拨回 Idle，否则下次进打包页
    // 还会看到上一次的报告，用户会以为刚才那次又跑了一遍。
    LaunchedEffect(backgroundState, backgroundPacking) {
        if (!backgroundPacking) return@LaunchedEffect
        val settled = backgroundState as? PackTaskState.Settled ?: return@LaunchedEffect
        when (val outcome = settled.outcome) {
            is PackTaskSettled.Done -> report = outcome.report
            is PackTaskSettled.Cancelled -> report = outcome.report
            is PackTaskSettled.Rejected -> executionError = outcome.message
            is PackTaskSettled.Error -> executionError = outcome.message
        }
        PackTaskBus.consumeResult()
    }


    val navController = rememberNavController()
    // 当前步骤**从导航栈派生**(理由同 OrganizeSteps):手势进行中 entry 不变,序号稳定。
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentIndex = navBackStackEntry?.destination.libraryStepIndex()

    // 点通知进来 ⇒ 直接落到步骤 3。
    //
    // **只跳一次**（`LaunchedEffect(startAtPackStep)` + `once` 容器）：这个参数在
    // Activity 重建后仍是 true，若每次都跳，用户从步骤 3 按返回回步骤 1 会立刻被
    // 弹回去 —— 表现成「退不出打包页」。
    val jumpToPackOnce = remember { mutableStateOf(startAtPackStep) }
    LaunchedEffect(startAtPackStep) {
        if (!jumpToPackOnce.value) return@LaunchedEffect
        jumpToPackOnce.value = false
        // 复用 `goToLibraryStep`：它保证返回栈是**线性链**（往回 pop、往前 push），
        // 直接 `navigate(LibraryPackRoute)` 会让返回栈变成 [Shelf, Pack] ——
        // 少掉元数据这一步，按返回会从打包页直接回书架。
        navController.goToLibraryStep(current = currentIndex ?: 0, target = LibraryStep.Pack.ordinal)
        // 跳完立刻回清上层标志：这个参数在 Activity 重建后仍是 true，若留着，
        // 用户从步骤 3 按返回回书架后会立刻被弹回来（真机第二轮实测）。
        onStartAtPackStepHandled()
    }
    // 执行器的**最新值**，但不作为 `LaunchedEffect` 的 key。
    //
    // 为什么不直接把它当 key（2026-10-20 真机缺陷）：调用方一旦没 `remember`
    // （`PixFoldApp` 当时就是这样），每次重组都会新建一个 `SafPackExecutor`，
    // 对象身份变了 ⇒ 预检无限重启，每次先把结论清空再被取消 ⇒ 界面上永远看不到。
    // 把「对象是否稳定」这个要求推给每个调用方是脆弱契约，改由本层吸收。
    val currentExecutor by rememberUpdatedState(packExecutor)

    // 计划页预检（D4b）：进入步骤 3（或计划本身变化）就算一次,但**不写盘**。
    //
    // 依赖里刻意含 `conflictPolicy` / `sourcePolicy`：它们改变 `buildPackagePlans`
    // 的输出（跳过哪些卷、写到哪个路径），而阻断项之一的「输出名撞车」直接吃
    // `outputPath` —— 用户切一下策略就可能从「撞车」变成「不撞」,界面必须跟着变。
    //
    // `packExecutor == null`（测试/模拟路径）时不跑：那时压根没有平台去问空间,
    // 算出来的结论会是一条假的「空间无法判定」。
    LaunchedEffect(currentIndex, state.volumes, conflictPolicy, sourcePolicy) {
        if (currentIndex != LibraryStep.Pack.ordinal) return@LaunchedEffect
        val executor = currentExecutor ?: return@LaunchedEffect
        pagePreflight = null
        val plans = buildPackagePlans(state.volumes, conflictPolicy, sourcePolicy)
        pagePreflight = runCatching { executor.preflight(plans) }.getOrElse { e ->
            PackUiPreflight.Unavailable(e.message ?: e::class.simpleName ?: "预检失败")
        }
    }

    /**
     * 重置**整个工作流**的调整(用户 2026-10-14 裁决,B 侧与 A 侧同构)。
     *
     * 「全部」= 卷勾选回到初始(全部 included)、当前卷回到第一卷、输出冲突与源文件
     * 策略回到默认(重命名 / 保留)、危险动作开关复位、确认流程与报告复位,
     * 并**回到步骤 1**。
     */
    /**
     * 「按目录结构获取」是否可用:目录推导出的值里**至少有一个非空**。
     *
     * 不逐个字段判:`Title` / `Series` / `Writer` 三个目录来源任一有值就够 ——
     * 全都推不出来时这个按钮点下去什么都不会变,不该让人点。
     */
    fun dirMetaAvailable(volume: ComicVolume): Boolean =
        volume.dirTitleSug.hasValue ||
            volume.dirSeriesSug.hasValue ||
            volume.dirWriterSug.hasValue

    /**
     * 把当前卷全部元数据按目录结构重填(用户 2026-10-22 裁决)。
     *
     * 空工作区时 `activeVolumeOrNull` 为 null ⇒ **什么都不做**(不读 `activeVolume`,
     * 那个属性在空列表上会抛异常)。
     */
    fun applyDirMetaToActiveVolume() {
        val volume = state.activeVolumeOrNull ?: return
        state = updateVolume(state, applyDirMetaSuggestions(volume))
    }

    fun resetAll() {
        // 重置时若有任务在跑,先叫停 —— 否则重置完界面归零,后台还在往磁盘写,
        // 用户看到的是「我明明重置了,文件却还在变多」。
        //
        // 前台服务路径下「叫停」走总线：`requestCancel` 会让执行方在**卷间**停下。
        // 注意**不要**在这里 `consumeResult` 清状态 —— 那会把正在跑的任务从界面上
        // 抹掉而磁盘上它还在写，比不重置更糟。
        //
        // 判据同 `effectiveExecuting`：看总线有没有在跑，而不是 `backgroundPacking`
        // （工作区还没恢复完时后者是 false，会把在跑的服务任务漏掉、只取消进程内令牌）。
        if (backgroundRunning != null) PackTaskBus.requestCancel()
        if (!backgroundPacking) cancelToken?.cancel()
        cancelToken = null
        executing = false
        cancelling = false
        progress = null
        executionError = null
        state = libraryStateOf(rootPath, volumes)
        conflictPolicy = OutputConflictPolicy.Rename
        sourcePolicy = SourcePolicy.Keep
        cleanEmptyDirs = false
        confirmFlow = ConfirmFlowState()
        report = null
        undoMessage = null
        // 回到步骤 1(退回一律用 pop,保持返回链线性)
        navController.goToLibraryStep(currentIndex, 0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets),
    ) {
        // 上次被打断的提示（D2b 第 2 项）。放在**页签行之上**：它讲的是「上一轮
        // 遗留的问题」，与本轮选到哪一步无关；塞进某个步骤里会让只在别的步骤
        // 工作的用户永远看不到它。
        if (interruptedNotice != null) {
            InterruptedNoticeRow(text = interruptedNotice, onDismiss = onDismissInterruptedNotice)
        }

        // 页签行 + 右侧「重置全部」:理由与槽位写法同 OrganizeSteps
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
                LibraryStep.entries.forEachIndexed { i, step ->
                    // 要进 semantics{},故先在组合上下文里解析
                    val stepLabel = stringResource(step.labelRes)
                    Tab(
                        selected = currentIndex == i,
                        // 跳步走 goToLibraryStep,保证返回链始终线性(详见该函数注释)
                        onClick = { navController.goToLibraryStep(currentIndex, i) },
                        text = { Text(stepLabel) },
                        modifier = Modifier
                            .testTag(
                                when (step) {
                                    LibraryStep.Library -> TAG_B_STEP_LIBRARY
                                    LibraryStep.Metadata -> TAG_B_STEP_METADATA
                                    LibraryStep.Pack -> TAG_B_STEP_PACK
                                },
                            )
                            .semantics { contentDescription = stepLabel },
                    )
                }
            }

            // 「按目录结构获取」紧邻重置:两者都是「我想重来一遍」类动作,
            // 放在同一槽位,用户找它们时不必跨页面。作用于**当前卷**(用户 2026-10-22 裁决)。
            DirMetaButton(
                onApply = { applyDirMetaToActiveVolume() },
                enabled = state.activeVolumeOrNull?.let { dirMetaAvailable(it) } == true,
            )
            ResetAllButton(onReset = { resetAll() })
        }

        // 步骤内容不重复叠加 inset(外层已处理)
        val innerInsets = WindowInsets(0, 0, 0, 0)

        // 返回入口已随「底栏化」迁入各页自己的底栏(用户 2026-09-25 指定:
        // 左 = 退回、右 = 推进)。原先常驻在 TabRow 与内容之间的那个返回按钮已删除 ——
        // 它把内容夹在中间,且与底栏的推进按钮分处两端,不符合统一的底栏布局。
        PixFoldNavHost(
            navController = navController,
            startDestination = LibraryShelfRoute,
            predictiveBackAnimation = predictiveBackAnimation,
            // 步骤级 = 只淡出:上级页原样露出(用户规格)
            style = NavTransitionStyle.Step,
            // weight 挂在嵌套 NavHost 自身(ColumnScope 扩展),理由同 OrganizeSteps
            modifier = Modifier.weight(1f),
        ) {
            composable<LibraryShelfRoute> {
                DestinationSurface {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LibraryPage(
                            state = state,
                            onStateChange = { state = it },
                            contentInsets = innerInsets,
                            onNext = { navController.navigate(LibraryMetadataRoute) },
                            // 工作区条只在注入状态时出现(D2b);默认 null ⇒ 既有测试布局不变
                            workspaceState = workspaceState,
                            onPickWorkspace = onPickWorkspace,
                            // 结构警告来自扫描结果,不是库状态 —— 由 PixFoldApp 现算注入
                            workspaceWarnings = workspaceWarnings,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            composable<LibraryMetadataRoute> {
                DestinationSurface {
                    Column(modifier = Modifier.fillMaxSize()) {
                        MetadataPage(
                            libraryState = state,
                            onLibraryChange = { state = it },
                            contentInsets = innerInsets,
                            onNext = { navController.navigate(LibraryPackRoute) },
                            onBack = { navController.popWorkflowStep() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            composable<LibraryPackRoute> {
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
                                    // 同 OrganizeSteps:用 pop 让 [Metadata, Pack] 整体出栈
                                    report = null
                                    undoMessage = null
                                    navController.popBackStack(LibraryShelfRoute, inclusive = false)
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
                                        if (backgroundPacking) {
                                            // ---- 前台服务（D2b 第 2 项）----
                                            // 请求与执行分离：这里只把「要打什么」交给总线，
                                            // 真正的读字节/写 ZIP 在 Service 里跑，故切后台、
                                            // 旋转屏幕、Activity 被回收都不影响它。
                                            //
                                            // 用当前工作区的 tree URI + 显示名。两者都缺失
                                            // （不该发生 —— 没工作区时 `volumes` 是 mock，
                                            // 用户点确认会被预检拦住）时退回 `rootPath`，
                                            // 让失败发生在 `SafPackExecutor` 里并显示成错误，
                                            // 而不是静默什么都不做。
                                            val treeUri = workspaceState?.handleOrNull()?.treeUri
                                            val rootName = workspaceState?.handleOrNull()?.displayName
                                            if (treeUri == null || rootName == null) {
                                                executionError = unknownErrorText
                                            } else {
                                                executionError = null
                                                val accepted = PackTaskService.enqueue(
                                                    context = context,
                                                    request = PackTaskRequest(
                                                        treeUri = treeUri,
                                                        rootDisplayName = rootName,
                                                        plans = plans,
                                                        workspaceName = rootName,
                                                    ),
                                                )
                                                // `false` = 已有任务在跑。并发写同一批输出路径
                                                // 会互相覆盖，故必须让用户知道这次没启动。
                                                if (!accepted) {
                                                    executionError = busyErrorText
                                                }
                                            }
                                        } else if (executor == null) {
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
                                executing = effectiveExecuting,
                                progress = effectiveProgress,
                                pageDone = effectivePageDone,
                                pageTotal = effectivePageTotal,
                                volumeLabel = effectiveVolumeLabel,
                                onCancelExecution = {
                                    if (backgroundPacking) {
                                        // 服务侧取消：令牌在总线里，`requestCancel` 会置
                                        // `cancelling = true` 并让执行方在**卷间**停下。
                                        PackTaskBus.requestCancel()
                                    } else {
                                        // 先让界面进入「取消中」,再发信号:反过来的话
                                        // 信号发出去后界面仍可点,存在连点窗口。
                                        cancelling = true
                                        cancelToken?.cancel()
                                    }
                                },
                                cancelling = effectiveCancelling,
                                executionError = executionError,
                                pagePreflight = pagePreflight,
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

/**
 * 「上次打包被中断」的提示条（D2b 第 2 项）。
 *
 * ### 为什么不是 `Snackbar`
 * Snackbar 会自动消失（默认 4 秒），而这条消息讲的是**磁盘上可能有不完整的文件** ——
 * 用户可能正在看别的地方，错过就再也看不到了。它必须等到用户主动关掉。
 *
 * ### 为什么不用 `AlertDialog`
 * 它不阻塞任何操作：用户可能只是想先看看库里的内容，或者干脆是别人点了别人的
 * 打包。用模态对话框会强制用户处理一件「可能跟我没关系」的事。
 *
 * ### 为什么不自动删掉可疑文件
 * 中断时刻的真实状态不可知 —— 文件可能是完整的（旧文件被原地覆盖到一半），
 * 也可能是零字节（`createDocument` 刚建好就死了）。自动删除会毁掉可能完好的数据。
 * 这里只陈述事实 + 给出确认，删不删由用户决定（[onDismiss] 只关提示，不碰磁盘）。
 */
@Composable
private fun InterruptedNoticeRow(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_B_INTERRUPTED_NOTICE)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WarnLine(
            icon = R.drawable.ic_warning_triangle,
            text = text,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.testTag(TAG_B_INTERRUPTED_DISMISS)) {
            Text(stringResource(R.string.b_interrupted_dismiss))
        }
    }
}

/** 「上次打包被中断」提示条。 */
const val TAG_B_INTERRUPTED_NOTICE = "library-interrupted-notice"

/** 关掉「上次打包被中断」提示。 */
const val TAG_B_INTERRUPTED_DISMISS = "library-interrupted-dismiss"
