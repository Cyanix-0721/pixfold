package com.cyanix.pixfold.ui.organize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cyanix.pixfold.domain.naming.namingSchemeSeedOf
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.organize.OrganizeState
import com.cyanix.pixfold.domain.plan.ConfirmFlowState
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.domain.plan.ExecutionReport
import com.cyanix.pixfold.domain.plan.buildConfirmSequence
import com.cyanix.pixfold.domain.plan.buildLibraryRenamePlan
import com.cyanix.pixfold.domain.plan.cancel
import com.cyanix.pixfold.domain.plan.confirmCurrent
import com.cyanix.pixfold.domain.plan.executeRenamePlan
import com.cyanix.pixfold.domain.plan.executeRenamePlanReal
import com.cyanix.pixfold.domain.plan.performUndo
import com.cyanix.pixfold.domain.plan.performUndoReal
import com.cyanix.pixfold.domain.plan.RenameActions
import com.cyanix.pixfold.domain.plan.UndoActions
import com.cyanix.pixfold.domain.plan.renameSummaryLines
import com.cyanix.pixfold.domain.plan.startConfirmFlow
import com.cyanix.pixfold.domain.plan.UndoScope
import com.cyanix.pixfold.domain.plan.undoRecordOf
import com.cyanix.pixfold.domain.plan.organizeDangerActions
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.organize.organizeStateOf
import com.cyanix.pixfold.nav.DestinationSurface
import com.cyanix.pixfold.nav.NavTransitionStyle
import com.cyanix.pixfold.nav.PixFoldNavHost
import com.cyanix.pixfold.nav.OrganizeNamingRoute
import com.cyanix.pixfold.nav.OrganizePlanRoute
import com.cyanix.pixfold.nav.OrganizeSortRoute
import com.cyanix.pixfold.nav.goToOrganizeStep
import com.cyanix.pixfold.nav.popWorkflowStep
import com.cyanix.pixfold.nav.organizeStepIndex
import com.cyanix.pixfold.ui.components.ResetAllButton
import com.cyanix.pixfold.ui.workspace.WorkspaceBar
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import com.cyanix.pixfold.ui.workspace.showsWorkspaceBar
import com.cyanix.pixfold.ui.plan.ExecutionReportPage

const val TAG_STEP_TABS = "step-tabs"

/** 空工作区空态（D2a）：选中的目录里没有图片。 */
const val TAG_EMPTY_WORKSPACE = "empty-workspace"

/** 空工作区空态里的「选择文件夹」按钮。 */
const val TAG_EMPTY_WORKSPACE_PICK = "empty-workspace-pick"
const val TAG_STEP_SORT = "step-sort"
const val TAG_STEP_NAMING = "step-naming"
const val TAG_STEP_BACK = "step-back"
const val TAG_STEP_PLAN = "step-plan"

/** 命名结构 -> 执行计划 的下一步入口(与步骤1的「下一步」区分,便于测试精确定位)。 */
const val TAG_TO_PLAN = "step-to-plan"

/**
 * 工作流 A 的步骤(规格 §10.1:步骤1 排序与预览 / 步骤2 命名结构 / 步骤3 计划)。
 *
 * 存**字符串资源 id 而非成品文案**:枚举是静态常量,而文案要随界面语言变化 ——
 * 若在这里存 `String`,切语言后已构造的枚举成员仍是旧语言。解析放在组合里。
 */
enum class OrganizeStep(val labelRes: Int) {
    Sort(R.string.a_step_label_sort),
    Naming(R.string.a_step_label_naming),
    Plan(R.string.a_step_label_plan),
}

/**
 * 工作流 A 的步骤容器。
 *
 * **为什么把状态提到这里**:步骤 2 的命名序号取自**步骤 1 的最终页序**(规格 §6.2
 * "position = 当前页序中的下标")。若两页各自持有状态,命名的序号就会与用户排好的
 * 顺序脱节 —— 这是必须由上层持有、两页共享的**唯一**理由。
 *
 * **状态可再上提一层**(2026-09-25):页序与命名状态经 [externalState] /
 * [initialNamingState] 支持由**应用根**注入,[onStateChange] / [onNamingStateChange]
 * 回传。原因是预览页是**根导航的兄弟目的地**,不在本组件的子树里 ——
 * 它既要按**当前页序**取图(点击回传的是页序下标),又要写回同一份命名覆盖。
 * 不传这两个参数时行为与从前完全一致(测试与单独使用场景不受影响)。
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
 * (见 [organizeStepIndex]),不再是独立状态 —— 于是不存在"两份状态不同步"的问题。
 *
 * **TabRow 为什么留在 NavHost 外面**:它要常驻(不随步骤内容转场),而且这样
 * `TAG_STEP_*` 这些 testTag 在任意时刻都只有一份被组合,转场期间不会出现重复 tag。
 *
 * 嵌套图的返回由**内层** NavHost 处理;内层栈深为 1 时它自动让位给外层
 * (navigation 的 `enabled = currentBackStack.size > 1`),故"步骤 1 再返回 → 回首页"
 * 无需本组件写任何代码,也不再需要 `onBack` 参数。
 */
@Composable
fun OrganizeSteps(
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
    /** 上层持有的批次状态(页序等);为 null 时本组件自持(单独使用/测试场景)。 */
    externalState: OrganizeState? = null,
    /**
     * 上层持有的「当前集合」;为 null 时本组件自持。
     *
     * **为什么这个种子也必须由上层给**:预览页是**根导航的兄弟目的地**,
     * 打开预览会让 `OrganizeRoute` 这个目的地离开组合,它内部的
     * `remember`(含 `activeId`)**随之销毁**;关闭预览回来是**新的组合实例**,
     * 于是 `activeId` 会退回 `collections.first()` —— 表现就是
     * 「在排序页切到 cn → 点开一张图 → 关闭后排序页又变回 trip」。
     * 由上层传入当前值即可让新实例恢复到正确集合
     * (2026-09-25 由端到端测试发现并修复)。
     */
    initialActiveCollectionId: String? = null,
    /** 批次状态变化上报(仅上层持有状态时有意义)。 */
    onStateChange: (OrganizeState) -> Unit = {},
    /** 上层持有的命名状态(结构 + 覆盖);为 null 时自持。 */
    initialNamingState: NamingState? = null,
    /** 命名状态变化上报(仅上层持有状态时有意义)。 */
    onNamingStateChange: (NamingState) -> Unit = {},
    /**
     * 网格默认列数(来自设置页)—— 竖屏 / 横屏各一个,`null` = 跟随自适应。
     *
     * 只做**透传**:真正按方向二选一发生在 `SortAndPreviewPage` 内部(那里才读得到
     * `LocalConfiguration` 并会随旋转重组)。本组件刻意不在这里选,免得"哪个方向"
     * 在两个地方各判一次、日后改一处漏一处。
     */
    portraitDefaultColumns: Int? = null,
    landscapeDefaultColumns: Int? = null,
    /**
     * 工作区状态（D2a）。`null` = **不显示**工作区条。
     *
     * 默认 `null` 而不是 `WorkspaceState.None` 是刻意的：`None` 会渲染出
     * 「当前显示演示数据…」+「选择文件夹」一行，而既有 491 个 UI 测试都是
     * 在「没有工作区概念」的前提下写的（它们只测步骤内容与导航）。默认 null
     * 让这些测试的布局与断言**一行都不用改**；真实应用由 `PixFoldApp` 注入实际状态。
     *
     * 语义上这也说得通：单独使用本组件（例如某个只关心排序的宿主）时，
     * 「工作区」是宿主的事，组件不该替它凭空画一条选择入口。
     */
    workspaceState: WorkspaceState? = null,
    /** 用户要求选择/更换工作区目录。仅 [workspaceState] 非 null 时会被调用。 */
    onPickWorkspace: () -> Unit = {},
    /**
     * 用户点整条工作区条要求打开**工作区列表弹窗**（2026-10-25 规格 §7.1）。
     *
     * 默认 `{}` = 与本次接线前完全一致，既有 30 余处 `OrganizeSteps(…)` 测试一行都不用改。
     */
    onOpenWorkspaceList: () -> Unit = {},
    /**
     * 任务完成通知的出口（用户 m09108：「让整理图片和打包完成结束后都触发下」）。
     *
     * 默认 `null` = **不发通知**，与既有 491 个 UI 测试的行为完全一致
     * （它们不关心系统通知，注入真实现只会让它们要求通知权限）。
     *
     * **为什么用可空端口而不是 Boolean 开关**：与 `PackExecutorPort` 同一理由 ——
     * 执行/通知这类会碰系统资源的东西，测试里构造不出真实现；用 Boolean 会让
     * 「忘了传」与「故意不传」在代码里长得一模一样，而这个功能恰恰是「忘了接」
     * 就静默失效的那一类（没有异常、没有日志，只是永远不响）。
     */
    onTaskCompleted: ((ExecutionReport) -> Unit)? = null,
    /**
     * 真实改名的平台端口（D4b）。`null` = **走 D1 的确定性模拟**。
     *
     * 注意它与 [onTaskCompleted] 的 `null` 含义**不同**：那个是"不发通知"（可选增强），
     * 这个是不碰文件系统（整条执行换实现）。
     *
     * **为什么是可空端口而不是 Boolean 开关**：与 `PackExecutorPort` 同一理由。
     * `SafRenameActions` 携带 `Context`，测试里构造不出真实现；用 Boolean 会让
     * 「忘了传」与「故意走模拟」在代码里长得一样，而这个功能的失效形态恰恰是
     * **静默的**：界面报「成功 56 项」而磁盘上一个文件都没动。
     */
    renameActions: RenameActions? = null,
    /**
     * 真实撤销的平台端口（D4b）。`null` = 沿用 `performUndo`（D1 mock，明说"未真正回滚"）。
     *
     * 与 [renameActions] 独立传，但**实际必须成对**：模拟报告没有 uri，真实撤销会
     * 逐项报失败；反过来真实执行配 mock 撤销，则界面明明动了文件却说"正式版才回滚"。
     * 两者的错配都**看得见**（撤销结果文案不同），故不做编译期强制 ——
     * 在 `PixFoldApp` 一处同时构造即不会配错。
     */
    undoActions: UndoActions? = null,
) {
    // 跨步骤共享的唯一状态源。这些状态**必须留在嵌套 NavHost 之外** ——
    // 它们要跨步骤存活,而 NavHost 里每个 destination 都是独立作用域。
    //
    // 模式与 `SortAndPreviewPage` / `NamingPage` 一致:**本地状态权威,初值取自上层,
    // 每次变更上报**。刻意不用 `externalState ?: local` —— 那样上层一旦传值,
    // 就地编辑就永不显示(该踩坑记录见 `NamingPage` 的同名注释)。
    //
    // ⚠️ `remember(collections)` 的 key **不是**可选的(2026-10-19 真机缺陷):
    // 上层 `PixFoldApp` 用 `remember(collections)` 播种,换工作区时会传下**新的**
    // `externalState`(新集合 id 的 orders);本组件若无 key,组合实例不销毁 ⇒ 仍持
    // 旧工作区的 state,下层 `SortAndPreviewPage` 查不到新集合的 order ⇒ 白屏
    // (真机实测:冷启动正常、工作流内「更换」后全黑)。加 key 后与上层同一步调。
    var state by remember(collections) {
        mutableStateOf(externalState ?: organizeStateOf(collections))
    }
    fun updateState(next: OrganizeState) {
        state = next
        onStateChange(next)
    }
    // 初值取上层给的值(若给):预览往返会让本目的地整体重建,靠它恢复所选集合。
    // 同样带 `collections` key:换工作区后旧的 activeId 可能指向已不存在的集合
    // (虽然下游有 `?: firstOrNull()` 兜底,但那样会"跳"到第一个集合却不通知上层)。
    var activeId by remember(collections) {
        mutableStateOf(initialActiveCollectionId ?: collections.firstOrNull()?.id.orEmpty())
    }
    // 命名结构与覆盖是**批次级**(规格 §6.3:overrides 全局一份,不属于某个集合),
    // 故在此持有 —— 切集合、来回切 Tab 都不会丢失用户的覆盖。
    // 工作流 A 的危险动作(默认关闭)+ 确认流程 + 执行报告
    var cleanEmptyDirs by remember { mutableStateOf(false) }
    var confirmFlow by remember { mutableStateOf(ConfirmFlowState()) }
    var report by remember { mutableStateOf<ExecutionReport?>(null) }
    // 领域层的 `Msg`(语言无关);成品文案由 ExecutionReportPage 现场解析
    var undoMessage by remember { mutableStateOf<Msg?>(null) }

    // 真实改名的执行状态(D4b)。
    //
    // ⚠️ 改名**必须离开主线程**:一次 renameDocument 是一次同步 Binder 往返,
    // 120 项串行就是 120 次。2026-10-19 真机实测 —— 主线程卡在
    // `DocumentsContract.renameDocument` 的 `transact` 上超过 5 秒,系统判 ANR,
    // 进程在前台被系统杀掉(dropbox 的 data_app_anr 里主线程栈顶就是它),
    // 而磁盘上已经改了 86/120 个名字。**改成协程 + IO 之前,这个功能是
    // 「一点就崩」的**,只是本地测试全在 Robolectric 里跑、没有真实 provider
    // 往返才没暴露。
    var executing by remember { mutableStateOf(false) }
    // 撤销是独立的执行期：它发生在报告页（executing 已复位），两者互斥但不是同一个状态。
    var undoing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<TaskProgress?>(null) }
    var cancelToken by remember { mutableStateOf<CancellationToken?>(null) }
    val scope = rememberCoroutineScope()

    var namingState by remember(collections) {
        mutableStateOf(
            initialNamingState ?: NamingState(
                namingSchemeSeedOf(collections),
            ),
        )
    }
    fun updateNamingState(next: NamingState) {
        namingState = next
        onNamingStateChange(next)
    }

    val activeCollection = collections.firstOrNull { it.id == activeId } ?: collections.firstOrNull()

    val navController = rememberNavController()
    // 当前步骤**从导航栈派生**。返回手势进行中 NavHost 尚未提交 pop,
    // 故 entry 不变 —— 序号在整段手势里稳定,不会抖。
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentIndex = navBackStackEntry?.destination.organizeStepIndex()

    /**
     * 重置**整个工作流**的调整(用户 2026-10-14 裁决)。
     *
     * 「全部」= 排序规则回到批次默认、人工调整清空、命名结构回到初值、覆盖清空、
     * 危险动作开关复位、确认流程与报告复位,并**回到步骤 1**。
     *
     * ⚠️ 必须走 [updateState] / [updateNamingState] 而不是直接改本地 `state`:
     * 这两个状态**上层(`PixFoldApp`)也持有**,预览页读的是上层那份。只改本地会让
     * 「重置后打开预览仍按旧页序取图」—— 属于 2026-09-25 已踩过的同类缺陷。
     */
    fun resetAll() {
        updateState(organizeStateOf(collections))
        updateNamingState(
            NamingState(namingSchemeSeedOf(collections)),
        )
        cleanEmptyDirs = false
        confirmFlow = ConfirmFlowState()
        report = null
        undoMessage = null
        // 执行中重置 = 先叫停。不叫停就复位界面，会让「界面归零、磁盘还在改名」
        // 同时存在（与库侧 resetAll 同一取舍）。
        cancelToken?.cancel()
        cancelToken = null
        executing = false
        undoing = false
        progress = null
        // 当前集合也复位(重置后停在第一个集合,与初值一致)
        val firstId = collections.firstOrNull()?.id.orEmpty()
        activeId = firstId
        onActiveCollectionChange(firstId)
        // 回到步骤 1:往回跳一律用 pop(见 goToOrganizeStep 的注释),保持返回链线性
        navController.goToOrganizeStep(currentIndex, 0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets),
    ) {
        // 工作区条（D2a）—— 页签行**上方**的从属信息层。
        //
        // **为什么不是第 4 个页签**：HANGOFF §4（2026-10-15 用户裁决）把工作区定为
        // 「范围」而非「任务」，与三条工作流步骤不同层；做成第 4 个 Tab 会打破 3 步
        // 线性流程，并牵动 organizeStepIndex()/goToOrganizeStep()/popWorkflowStep()
        // 与约 30 条导航测试。放在页签行外层的顶部即可获得「工作流内部」的位置。
        //
        // **2026-10-24 用户裁决(乙)：常态不再显示** —— 选目录的入口搬到首页了，
        // 工作流里再常驻一条是同一功能摆两处。只保留「正忙」与「出问题」两种，
        // 判据收在 WorkspaceState.showsWorkspaceBar（理由特别是 Denied 那条见那儿）。
        if (workspaceState != null && workspaceState.showsWorkspaceBar) {
            WorkspaceBar(
                state = workspaceState,
                onPick = onPickWorkspace,
                onOpenList = onOpenWorkspaceList,
            )
        }

        // 页签行 + 右侧「重置全部」。M3 的 `PrimaryTabRow` **没有 trailing 槽位**
        // (API 只有 selectedTabIndex/modifier/colors/indicator/content),
        // 故只能在外面套一层 Row 分槽:页签 weight(1f) 吃掉剩余宽度,按钮靠右。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryTabRow(
                selectedTabIndex = currentIndex,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TAG_STEP_TABS),
            ) {
                OrganizeStep.entries.forEachIndexed { i, step ->
                    // 要进 semantics{},故先在组合上下文里解析
                    val stepLabel = stringResource(step.labelRes)
                    Tab(
                        selected = currentIndex == i,
                        // 跳步必须走 goToOrganizeStep:往回 pop、往前逐级 push,
                        // 保证返回链始终是线性的一步一退(详见该函数注释)。
                        onClick = { navController.goToOrganizeStep(currentIndex, i) },
                        text = { Text(stepLabel) },
                        modifier = Modifier
                            .testTag(
                                when (step) {
                                    OrganizeStep.Sort -> TAG_STEP_SORT
                                    OrganizeStep.Naming -> TAG_STEP_NAMING
                                    OrganizeStep.Plan -> TAG_STEP_PLAN
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

        // 空工作区（D2a）：用户选了一个**没有图片**的文件夹。
        //
        // 这是合法状态而不是错误，但三个步骤页在空列表下都会渲染成一片空白
        // （`SortAndPreviewPage` 里 `activeCollection` 为 null 时直接 return）。
        // 空白界面上没有任何可操作入口，用户会以为应用卡住了 —— 必须显式说明，
        // 并给一个「重新选择」按钮。
        //
        // 判据用 `collections.isEmpty()` 而**不是** `workspaceState is Ready`：
        // 宿主也可以直接传空集合（不接工作区），那时同样需要这条空态。
        if (collections.isEmpty()) {
            EmptyWorkspace(
                onPick = onPickWorkspace,
                showPick = workspaceState != null,
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        PixFoldNavHost(
            navController = navController,
            startDestination = OrganizeSortRoute,
            predictiveBackAnimation = predictiveBackAnimation,
            // 步骤级 = 只淡出:上级页原样露出(用户规格)
            style = NavTransitionStyle.Step,
            // weight 必须挂在这里:它是 ColumnScope 扩展,原先挂在三个子页上,
            // 现在它们各自在独立的 destination 里,外层只剩这一个子节点需要撑满剩余高度。
            modifier = Modifier.weight(1f),
        ) {
            composable<OrganizeSortRoute> {
                DestinationSurface {
                    // 每个 destination 内再包一层 Column,好让子页自己的 weight(1f) 有作用域
                    Column(modifier = Modifier.fillMaxSize()) {
                        SortAndPreviewPage(
                            collections = collections,
                            contentInsets = innerInsets,
                            onOpenPreview = onOpenPreview,
                            initialCollectionId = activeId,
                            // ⚠️ 必须**同时**更新本地 activeId **并转发**给外层。
                            // 2026-09-25 修复的真实缺陷:此处原先只写 `{ activeId = it }`,
                            // 于是本组件的 `onActiveCollectionChange` 参数**从未被调用** ——
                            // 应用根的 `activeCollectionId` 永远停在首个集合,
                            // 用户在排序页切到别的集合后预览仍显示旧集合的图
                            // (与"预览用声明序取图"叠加,表现为既点错图又数错页码)。
                            onActiveCollectionChange = {
                                activeId = it
                                onActiveCollectionChange(it)
                            },
                            externalState = state,
                            onStateChange = { updateState(it) },
                            onNext = { navController.navigate(OrganizeNamingRoute) },
                            portraitDefaultColumns = portraitDefaultColumns,
                            landscapeDefaultColumns = landscapeDefaultColumns,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            composable<OrganizeNamingRoute> {
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
                                onStateChange = { updateNamingState(it) },
                                onBack = { navController.popWorkflowStep() },
                                onNext = { navController.navigate(OrganizePlanRoute) },
                                // 步骤 2 的集合芯片**与步骤 1 共用同一个 `activeId`**
                                // (用户 2026-10-19 裁决「共用同一个选中项」)。
                                // 这里只上报,`activeId` 的唯一持有者仍是本组件 ——
                                // 于是步骤 2 切到的集合,回步骤 1 也是同一个,不需要同步逻辑。
                                collections = collections,
                                onSelectCollection = { id ->
                                    if (id != activeId) {
                                        activeId = id
                                        onActiveCollectionChange(id)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        // `align` 是 ColumnScope 扩展,故这里必须自带一个 Column 作用域
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = stringResource(R.string.a_step_empty),
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

            composable<OrganizePlanRoute> {
                DestinationSurface {
                    val currentReport = report
                    if (currentReport != null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ExecutionReportPage(
                                report = currentReport,
                                undoMessage = undoMessage,
                                undoing = undoing,
                                onUndoScope = { undoScope ->
                                    // 用户选的档位必须传到领域层:危险档多删的就是
                                    // 「被本次覆盖掉的那些位置」(见 UndoScope)。
                                    val record = undoRecordOf(currentReport, undoScope)
                                    // 撤销同样是一次文件操作序列（改名 / 删除都是 Binder
                                    // 往返），与执行同源风险：**不能在主线程上跑**。
                                    val platformUndo = undoActions
                                    if (platformUndo != null) {
                                        undoing = true
                                        scope.launch {
                                            try {
                                                val applied = withContext(Dispatchers.IO) {
                                                    performUndoReal(record, platformUndo)
                                                }
                                                undoMessage = applied.message
                                            } finally {
                                                // 与执行路径同一理由：留在 true 会让按钮永远停在
                                                // 「撤销中…」且不可点，用户再也撤不了第二次。
                                                undoing = false
                                            }
                                        }
                                    } else {
                                        undoMessage = performUndo(record).message
                                    }
                                },
                                onDone = {
                                    // 执行完回到步骤 1:报告是一次执行的终点,不该留在计划页
                                    // 让用户误以为还能再执行一次。
                                    // 用 pop 而非 navigate —— 后半段栈([Naming, Plan])必须整体出栈,
                                    // 否则返回会回到刚执行完的计划页。
                                    report = null
                                    undoMessage = null
                                    navController.popBackStack(OrganizeSortRoute, inclusive = false)
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
                                    val dangers = organizeDangerActions(cleanEmptyDirs)
                                    confirmFlow = startConfirmFlow(
                                        buildConfirmSequence(dangers, renameSummaryLines(plan, dangers)),
                                    )
                                },
                                onConfirmStep = {
                                    val (next, done) = confirmFlow.confirmCurrent()
                                    confirmFlow = next
                                    if (done) {
                                        val plan = buildLibraryRenamePlan(collections, state.orders, namingState)
                                        // 执行器二选一：注入了平台端口就走真实改名，
                                        // 否则沿用 D1 的确定性模拟（既有 UI 测试全走这条）。
                                        //
                                        // 真实路径**必须**在 IO 线程上跑（见 `executing` 的注释：
                                        // 主线程上 120 次 Binder 往返 = ANR）。模拟路径是纯计算，
                                        // 留在原地即可 —— 它没有阻塞调用，也不需要进度。
                                        val platformActions = renameActions
                                        if (platformActions != null) {
                                            val token = CancellationToken()
                                            cancelToken = token
                                            executing = true
                                            progress = TaskProgress(total = plan.ops.size)
                                            scope.launch {
                                                val finished = try {
                                                    withContext(Dispatchers.IO) {
                                                        executeRenamePlanReal(
                                                            plan.ops,
                                                            platformActions,
                                                            token = token,
                                                            // 回调在 IO 线程上。`mutableStateOf` 的写入
                                                            // 本身线程安全（写快照 + 通知全局版本），
                                                            // 与库侧 `LibrarySteps` 的进度回调同一种做法 ——
                                                            // 那里已在真机上验证进度条会走字。
                                                            onProgress = { p -> progress = p },
                                                        )
                                                    }
                                                } finally {
                                                    // 无论成功、取消还是异常都必须复位 ——
                                                    // 留在 executing 会让底栏永远停在进度条上，
                                                    // 用户再也点不到「确认执行」。
                                                    executing = false
                                                    cancelToken = null
                                                }
                                                report = finished
                                                progress = null
                                                // 完成通知在这里发，**不**在成功分支里发：
                                                // 用户要的是「任务结束了」这个信号，全失败也
                                                // 同样结束了 —— 把通知挂在 succeededCount > 0
                                                // 上会让「全失败」变成一声不吭，而那正是最需要
                                                // 让用户知道的场合。
                                                onTaskCompleted?.invoke(finished)
                                            }
                                        } else {
                                            val finished = executeRenamePlan(plan.ops)
                                            report = finished
                                            onTaskCompleted?.invoke(finished)
                                        }
                                    }
                                },
                                onCancelConfirm = { confirmFlow = confirmFlow.cancel() },
                                // 迁移前这里传的是 `stepIndex = 2`,而当前已是步骤 3 —— 属就地 no-op。
                                // 保留空实现以维持等价行为(执行入口由确认流程驱动)。
                                onExecute = {},
                                contentInsets = innerInsets,
                                modifier = Modifier.weight(1f),
                                onBack = { navController.popWorkflowStep() },
                                executing = executing,
                                progress = progress,
                                onCancelExecution = { cancelToken?.cancel() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 空工作区空态（D2a）。
 *
 * 只做「说明 + 重新选择」两件事。刻意不放「用演示数据继续」的按钮 ——
 * 用户刚明确选了这个目录，再给他一个「其实用的是假数据」的出口只会造成混淆；
 * 想看演示数据，取消授权（或换个目录）即可。
 *
 * @param showPick 是否显示选择按钮。宿主没接工作区（`workspaceState == null`）
 *   时无从选起，只显示说明文字。
 */
@Composable
private fun EmptyWorkspace(
    onPick: () -> Unit,
    showPick: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TAG_EMPTY_WORKSPACE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.workspace_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.workspace_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showPick) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onPick,
                modifier = Modifier.testTag(TAG_EMPTY_WORKSPACE_PICK),
            ) {
                Text(text = stringResource(R.string.workspace_pick))
            }
        }
    }
}
