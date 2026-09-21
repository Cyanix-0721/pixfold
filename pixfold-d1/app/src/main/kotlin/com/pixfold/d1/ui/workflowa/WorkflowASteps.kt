package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import com.pixfold.d1.ui.components.PredictiveBackContainer
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
 */
@Composable
fun WorkflowASteps(
    collections: List<ImageCollection>,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onOpenPreview: (Int) -> Unit = {},
    onActiveCollectionChange: (String) -> Unit = {},
    onBack: () -> Unit = {},
    /**
     * 步骤级返回是否使用**预测性返回动画**(来自 `settings.predictiveBackAnimation`)。
     * 默认 true(与设置默认值一致);false 时内部退回普通 `BackHandler`。
     */
    predictiveBackAnimation: Boolean = true,
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    // 跨步骤共享的唯一状态源
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

    // 返回手势/返回键(用户 2026-09-18 反馈:真机上按返回会直接退出应用)。
    //
    // 统一在**一个** handler 内按层级决策,而不是"非首步才启用 + 靠外层兜底":
    //   - 步骤 3 -> 步骤 2 -> 步骤 1(逐级后退);
    //   - 已在步骤 1 -> 回调 [onBack](由上层回首页)。
    // 这样注册始终是 enabled(系统据此知道"返回有去处",预测性返回动画才会
    // 走"应用内后退"而非"退出应用"),且不存在依赖注册顺序的隐式耦合。
    //
    // **2026-09-21 修复**:这里原先直接用 `BackHandler`。它的注册优先级高于根级
    // `PredictiveBackHandler`(OnBackInvokedDispatcher 取最后注册者),于是**工作流内部
    // 的手势跟随动画完全不生效** —— 在步骤 2 拖到一半内容纹丝不动,松手才突然跳回步骤 1。
    // 改用 [PredictiveBackContainer](与根级同一实现)后步骤级返回也跟随手势进度;
    // [predictiveBackAnimation] = false 时它内部自动退回 `BackHandler`。
    PredictiveBackContainer(
        enabled = predictiveBackAnimation,
        onBack = { if (stepIndex > 0) stepIndex -= 1 else onBack() },
    ) {

        Column(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets),
        ) {
            PrimaryTabRow(
                selectedTabIndex = stepIndex,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_STEP_TABS),
            ) {
                WorkflowAStep.entries.forEachIndexed { i, step ->
                    Tab(
                        selected = stepIndex == i,
                        onClick = { stepIndex = i },
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

            when (WorkflowAStep.entries[stepIndex]) {
                WorkflowAStep.Sort -> SortAndPreviewPage(
                    collections = collections,
                    contentInsets = innerInsets,
                    onOpenPreview = onOpenPreview,
                    initialCollectionId = activeId,
                    onActiveCollectionChange = { activeId = it },
                    externalState = state,
                    onStateChange = { state = it },
                    onNext = { stepIndex = 1 },
                    modifier = Modifier.weight(1f),
                )

                WorkflowAStep.Naming -> {
                    val collection = activeCollection
                    val order = collection?.let { state.orders[it.id] }
                    if (collection != null && order != null) {
                        // 返回步骤 1 的显式入口(Tab 也能切,但长表滚到底后 Tab 不在视口内)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = { stepIndex = 0 },
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .testTag(TAG_STEP_BACK)
                                    .semantics { contentDescription = "返回步骤1" },
                            ) {
                                Text("← 返回排序")
                            }
                        }
                        NamingPage(
                            collection = collection,
                            order = order,
                            contentInsets = innerInsets,
                            initialState = namingState,
                            onStateChange = { namingState = it },
                            modifier = Modifier.weight(1f),
                        )
                        // 常驻在底部的下一步:长表滚到底后 Tab 与返回入口都可能不在视口内
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { stepIndex = 2 },
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .testTag(TAG_TO_PLAN)
                                    .semantics { contentDescription = "下一步 执行计划" },
                            ) { Text("下一步：执行计划") }
                        }
                    } else {
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

                WorkflowAStep.Plan -> {
                    val currentReport = report
                    if (currentReport != null) {
                        ExecutionReportPage(
                            report = currentReport,
                            undoMessage = undoMessage,
                            onUndo = { undoMessage = performUndo(undoRecordOf(currentReport)).message },
                            onDone = {
                                // 执行完回到步骤 1:报告是一次执行的终点,不该留在计划页
                                // 让用户误以为还能再执行一次。
                                report = null
                                undoMessage = null
                                stepIndex = 0
                            },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = { stepIndex = 1 },
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .testTag(TAG_STEP_BACK)
                                    .semantics { contentDescription = "返回步骤2" },
                            ) {
                                Text("← 返回命名结构")
                            }
                        }
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
                            onExecute = { stepIndex = 2 },
                            contentInsets = innerInsets,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
