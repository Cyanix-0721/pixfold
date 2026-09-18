package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import com.pixfold.d1.domain.sort.moveItemTo
import com.pixfold.d1.domain.sort.resetToAuto
import com.pixfold.d1.domain.sort.togglePin
import com.pixfold.d1.domain.workflowa.WorkflowAState
import com.pixfold.d1.domain.workflowa.applyRuleToCollection
import com.pixfold.d1.domain.workflowa.toggleCustomRule
import com.pixfold.d1.domain.workflowa.updatePageOrder
import com.pixfold.d1.domain.workflowa.workflowAStateOf
import com.pixfold.d1.ui.components.DragReorderGrid
import com.pixfold.d1.ui.components.DragReorderList

const val TAG_GRID = "view-grid"
const val TAG_LIST = "view-list"
const val TAG_MODE_TOGGLE = "view-mode-toggle"
const val TAG_MODE_GRID_OPTION = "view-mode-grid"
const val TAG_MODE_LIST_OPTION = "view-mode-list"
const val TAG_RESET_MANUAL = "reset-manual"
const val TAG_SORT_RULE_EDITOR = "sort-rule-editor"
const val TAG_CUSTOM_RULE_TOGGLE = "custom-rule-toggle"
const val TAG_COLLECTION_ROW = "collection-row"
const val TAG_NEXT_STEP = "next-step"

/** 集合选择按钮的 testTag。 */
fun collectionTag(id: String) = "collection-$id"

/** 当前集合的规则作用范围文案(批次默认 / 本组独立),供界面与测试共用(§5.4)。 */
fun batchLabel(collectionId: String, isCustom: Boolean): String =
    if (isCustom) "本组独立规则" else "批次默认规则"

/**
 * 工作流 A · 步骤 1:排序与预览(验收第 1、3、4、5、6、7 项)。
 *
 * 排序状态由领域层 `WorkflowAState` 承载(**批次默认 + 本组独立**,规格 §5.4);
 * UI 只调用领域入口,**不自己改顺序**(归档设计结论 3:统一重排入口):
 *  - [moveItemTo] / [togglePin] / [resetToAuto] 只作用于**当前集合**;
 *  - [applyRuleToCollection] 按当前集合是否独立决定影响范围(独立 → 只重排自己);
 *  - [toggleCustomRule] 切换"本组独立"(加入保留当前顺序,移除立刻按批次规则重排)。
 *
 * @param collections 批次内的集合列表;多集合时显示切换行。
 * @param contentInsets 系统栏 inset;可注入以便测试(Robolectric 下系统 inset 恒为 0)。
 * @param initialCollectionId 初始集合;默认第一个。
 * @param onActiveCollectionChange 当前集合变化回调(供上层让预览跟随当前集合)。
 * @param externalState 由上层持有的批次状态。**步骤 2(命名)必须看到步骤 1 的最终页序**
 *   (命名序号跟随页序,规格 §6.2),故进入多步骤流程时由上层持有、两页共享;
 *   传 null 时本页自持状态(单独使用/测试场景,行为不变)。
 * @param onStateChange 状态变化上报(仅上层持有状态时有意义)。
 * @param onNext 进入步骤 2 的回调;为 null 时不显示"下一步"按钮(单独使用场景)。
 */
@Composable
fun SortAndPreviewPage(
    collections: List<ImageCollection>,
    modifier: Modifier = Modifier,
    initialMode: ViewMode = ViewMode.Grid,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onOpenPreview: (Int) -> Unit = {},
    initialCollectionId: String? = null,
    onActiveCollectionChange: (String) -> Unit = {},
    externalState: WorkflowAState? = null,
    onStateChange: (WorkflowAState) -> Unit = {},
    onNext: (() -> Unit)? = null,
) {
    var mode by remember { mutableStateOf(initialMode) }
    // 本地状态权威,初值取自 externalState,每次变更上报上层(理由同 NamingPage:
    // `externalState ?: local` 会让上层的值冻结就地编辑)。
    var state by remember { mutableStateOf(externalState ?: workflowAStateOf(collections)) }
    fun updateState(next: WorkflowAState) {
        state = next
        onStateChange(next)
    }
    var activeId by remember {
        mutableStateOf(initialCollectionId ?: collections.firstOrNull()?.id.orEmpty())
    }
    // W4:每次排序规则变更自增 -> 通知网格/列表回顶(用户 2026-09-17 指定)
    var scrollSignal by remember { mutableIntStateOf(0) }
    // 初始集合也要通知上层,避免预览与当前集合不一致
    androidx.compose.runtime.LaunchedEffect(activeId) { onActiveCollectionChange(activeId) }

    val activeCollection = collections.firstOrNull { it.id == activeId } ?: collections.firstOrNull()
    val order = activeCollection?.let { state.orders[it.id] } ?: return
    val isCustom = activeId in state.customRuleCollections

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets),
    ) {
        // 集合切换:让"批次统一 + 逐项例外"在界面上可操作(验收第 7 项)
        if (collections.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag(TAG_COLLECTION_ROW),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                collections.forEach { c ->
                    FilterChip(
                        selected = c.id == activeId,
                        onClick = { activeId = c.id },
                        label = { Text("${c.name}（${c.images.size}）") },
                        modifier = Modifier
                            .testTag(collectionTag(c.id))
                            .semantics { contentDescription = "集合 ${c.name}" },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${order.order.size} 张",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 人工调整标记 + 一键重置(验收第 5 项)
                if (order.hasManual) {
                    Text(
                        text = "已手动调整 ${order.manualCount} 处",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                TextButton(
                    onClick = {
                        updateState(updatePageOrder(state, activeId, resetToAuto(order)))
                    },
                    enabled = order.hasManual,
                    modifier = Modifier
                        .testTag(TAG_RESET_MANUAL)
                        .semantics { contentDescription = "重置人工调整" },
                ) {
                    Text("重置人工调整（${order.manualCount} 处）")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeToggle(mode = mode, onChange = { mode = it })

            Row(verticalAlignment = Alignment.CenterVertically) {
                // "本组独立"开关(验收第 7 项后半:此前该能力在界面上不存在)
                Text(
                    text = "本组独立",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = isCustom,
                    onCheckedChange = { updateState(toggleCustomRule(state, activeId)) },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .testTag(TAG_CUSTOM_RULE_TOGGLE)
                        .semantics { contentDescription = "本组独立规则" },
                )

                // 步骤 2 入口(验收第 8/9/10 项)。仅在上层接入多步骤流程时出现。
                if (onNext != null) {
                    TextButton(
                        onClick = onNext,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .testTag(TAG_NEXT_STEP)
                            .semantics { contentDescription = "下一步 命名结构" },
                    ) { Text("下一步") }
                }
            }
        }

        // 排序规则编辑器:作用范围随"本组独立"切换(验收第 7 项)
        SortRuleEditor(
            rule = state.effectiveRule(activeId),
            scopeLabel = batchLabel(activeId, isCustom),
            onRuleChange = { newRule ->
                updateState(applyRuleToCollection(state, activeId, newRule))
                // W4(用户 2026-09-17 指定):规则变更后**自动回顶**。
                // 因为重排会让"当前位置"失去意义(用户看到的是中段而非新首项)。
                scrollSignal++
            },
        )

        when (mode) {
            ViewMode.Grid -> DragReorderGrid(
                items = order.order,
                onMoveTo = { id, target ->
                    updateState(updatePageOrder(state, activeId, moveItemTo(order, id, target)))
                },
                onPin = { id -> updateState(updatePageOrder(state, activeId, togglePin(order, id))) },
                isPinned = { id -> order.isPinned(id) },
                onClick = { index -> onOpenPreview(index) },
                modifier = Modifier.fillMaxSize(),
                scrollToTopSignal = scrollSignal,
            )

            ViewMode.List -> DragReorderList(
                items = order.order,
                onMoveTo = { id, target ->
                    updateState(updatePageOrder(state, activeId, moveItemTo(order, id, target)))
                },
                onPin = { id -> updateState(updatePageOrder(state, activeId, togglePin(order, id))) },
                isPinned = { id -> order.isPinned(id) },
                onClick = { index -> onOpenPreview(index) },
                modifier = Modifier.fillMaxSize(),
                scrollToTopSignal = scrollSignal,
            )
        }
    }
}

@Composable
private fun ModeToggle(mode: ViewMode, onChange: (ViewMode) -> Unit) {
    val options = listOf(ViewMode.Grid, ViewMode.List)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.testTag(TAG_MODE_TOGGLE)) {
        options.forEachIndexed { index, option ->
            val label = if (option == ViewMode.Grid) "网格" else "列表"
            SegmentedButton(
                selected = mode == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier
                    .testTag(if (option == ViewMode.Grid) TAG_MODE_GRID_OPTION else TAG_MODE_LIST_OPTION)
                    .semantics { contentDescription = "$label 视图" },
            ) {
                Text(text = label)
            }
        }
    }
}
