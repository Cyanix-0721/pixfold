package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R
import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.SortRule
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
import com.pixfold.d1.ui.components.PageActionBar
import com.pixfold.d1.ui.text.textOf

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
const val TAG_RULE_SUMMARY_BAR = "rule-summary-bar"
const val TAG_RULE_SUMMARY_TOGGLE = "rule-summary-toggle"

/** 集合选择按钮的 testTag。 */
fun collectionTag(id: String) = "collection-$id"

/**
 * 当前集合的规则作用范围文案的资源 id(批次默认 / 本组独立),供界面与测试共用(§5.4)。
 *
 * ⚠️ 返回**资源 id 而非 String**:本函数会被测试在**非组合上下文**里调用(用来算出
 * 期望文案),故不能是 `@Composable`。调用方负责解析 —— 界面用 `stringResource(...)`,
 * 测试用 `context.getString(...)`;两边都以资源为唯一事实来源,不再各自拼字面量。
 */
fun batchLabelRes(isCustom: Boolean): Int =
    if (isCustom) R.string.a_rule_scope_custom else R.string.a_rule_scope_default

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
 *
 * ## 头部可伸缩(用户 2026-09-25 指定)
 *
 * 「步骤1上面部分也做个伸缩吧,让网格/列表的空间可以大点显示。」
 * 折叠的**只有排序规则编辑器** —— 集合切换行 / 「共 N 张」行 / 网格-列表切换行**全部常驻**
 * (它们是"看什么、看多少、怎么看"的导航信息,折叠了用户就不知道自己看的是哪一批图);
 * 默认**折叠**,进页面就把高度让给网格。
 *
 * **为什么编辑器是固定区块而不是插进滚动容器**(与 `NamingPage` 不同):排序级数上限
 * `SortRule.MAX_LEVELS` = 4,编辑器高度**有界**(约 140dp → 最多约 220dp),
 * 不会像命名结构那样随组件数无限增长;而本页的网格/列表**自带 Lazy**、
 * 没有页面级滚动容器,把编辑器塞进去反而要再引入一层滚动。
 *
 * **`scopeLabel` 只在折叠态显示**:展开后 [SortRuleEditor] 自己的标题行已经显示同一个
 * 作用范围文案,再渲染一遍会让 `onNodeWithText(batchLabelRes(...) 的解析结果)` 精确匹配命中两个节点。
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
    // 排序规则编辑器的展开态(用户 2026-09-25 指定:默认折叠,把高度让给网格)。
    // 状态 hoist 在本页而不是编辑器内部 —— 编辑器若自持该状态,`SortRuleDirectionTest`
    // (直接把 SortRuleEditor 当 root 组合)就得先展开才能点字段,徒增一处耦合。
    // `rememberSaveable` 让它扛住进程重建(与 NamingPage 的 editorExpanded 同一模式)。
    var ruleExpanded by rememberSaveable { mutableStateOf(false) }
    // 初始集合也要通知上层,避免预览与当前集合不一致
    androidx.compose.runtime.LaunchedEffect(activeId) { onActiveCollectionChange(activeId) }

    val activeCollection = collections.firstOrNull { it.id == activeId } ?: collections.firstOrNull()
    val order = activeCollection?.let { state.orders[it.id] } ?: return
    val isCustom = activeId in state.customRuleCollections

    // 先进局部变量:多处要进 semantics{} 或作为 String 参数
    val nextCd = stringResource(R.string.a_next_cd)
    val resetManualCd = stringResource(R.string.a_reset_manual_cd)
    val customRuleLabel = stringResource(R.string.a_custom_rule)
    val customRuleCd = stringResource(R.string.a_custom_rule_cd)
    val scopeText = stringResource(batchLabelRes(isCustom))

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
                    // stringResource 不能进 semantics{} 的 lambda,先取出来
                    val chipCd = stringResource(R.string.a_collection_cd, c.name)
                    FilterChip(
                        selected = c.id == activeId,
                        onClick = {
                            // W1(2026-09-17 实测缺陷):切集合必须**回到顶部**。
                            // 原因:切换集合的语义是"换一批图看",沿用上一个集合的滚动偏移
                            // 会让用户看到"中段"而非第一张,需手动滑回顶部才知道切换成功;
                            // 该残留状态还会**误导测试**(语义树只含可见项,读到的是视口中段)。
                            //
                            // 复用 W4 已建立的同一条通道(signal 自增 -> requestScrollToItem(0)):
                            // 两处需求本质相同 —— 视口位置失去意义时归位。
                            // 只有真的换了集合才自增,重复点同一个 chip 不该把用户拽回顶部。
                            if (c.id != activeId) {
                                activeId = c.id
                                scrollSignal++
                            }
                        },
                        label = {
                            Text(stringResource(R.string.a_collection_chip, c.name, c.images.size))
                        },
                        modifier = Modifier
                            .testTag(collectionTag(c.id))
                            .semantics { contentDescription = chipCd },
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
                text = pluralStringResource(R.plurals.a_image_count, order.order.size, order.order.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 人工调整标记 + 一键重置(验收第 5 项)
                if (order.hasManual) {
                    Text(
                        text = pluralStringResource(R.plurals.a_manual_count, order.manualCount, order.manualCount),
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
                        .semantics { contentDescription = resetManualCd },
                ) {
                    Text(stringResource(R.string.a_reset_manual, order.manualCount))
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
                    text = customRuleLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = isCustom,
                    onCheckedChange = { updateState(toggleCustomRule(state, activeId)) },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .testTag(TAG_CUSTOM_RULE_TOGGLE)
                        .semantics { contentDescription = customRuleCd },
                )
            }
        }

        // ---- 排序规则:摘要栏常驻,编辑器默认折叠(用户 2026-09-25 指定)----
        RuleSummaryBar(
            summary = ruleSummaryLine(state.effectiveRule(activeId)),
            scopeLabel = if (ruleExpanded) null else scopeText,
            expanded = ruleExpanded,
            onToggle = { ruleExpanded = !ruleExpanded },
        )
        if (ruleExpanded) {
            // 作用范围随"本组独立"切换(验收第 7 项)
            SortRuleEditor(
                rule = state.effectiveRule(activeId),
                scopeLabel = scopeText,
                onRuleChange = { newRule ->
                    updateState(applyRuleToCollection(state, activeId, newRule))
                    // W4(用户 2026-09-17 指定):规则变更后**自动回顶**。
                    // 因为重排会让"当前位置"失去意义(用户看到的是中段而非新首项)。
                    scrollSignal++
                },
            )
        }

        when (mode) {
            ViewMode.Grid -> DragReorderGrid(
                items = order.order,
                onMoveTo = { id, target ->
                    updateState(updatePageOrder(state, activeId, moveItemTo(order, id, target)))
                },
                onPin = { id -> updateState(updatePageOrder(state, activeId, togglePin(order, id))) },
                isPinned = { id -> order.isPinned(id) },
                onClick = { index -> onOpenPreview(index) },
                // ⚠️ 必须是 `weight(1f)` 而非 `fillMaxSize()`:Column 先量非加权子项,
                // 剩余的才分给加权项。底栏是非加权的后置子项,若网格仍用 `fillMaxSize()`
                // 就会把全部剩余高度吃光,底栏被挤成 0 高(折叠省下的空间也让不出去)。
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                scrollToTopSignal = scrollSignal,
            )
        }

        // 步骤 2 入口(验收第 8/9/10 项)常驻底栏右侧。仅在上层接入多步骤流程时出现。
        // 用户裁决(2026-09-25):推进类动作统一放底栏右侧,与返回类(左侧)分列。
        if (onNext != null) {
            val next = onNext
            PageActionBar(
                trailing = {
                    Button(
                        onClick = next,
                        modifier = Modifier
                            .testTag(TAG_NEXT_STEP)
                            .semantics { contentDescription = nextCd },
                    ) { Text(stringResource(R.string.a_next)) }
                },
            )
        }
    }
}

/**
 * 排序规则的一行摘要(折叠态显示)。
 *
 * 形如「排序规则：目录名 ↑ · 文件名(自然) ↑」—— 折叠的是**编辑入口**,
 * 不是事实本身(约束 2「建议 ≠ 事实」:当前规则必须始终可见可核对)。
 * 完全无键时不可能发生(`SortRule` 的 `init` 强制至少 1 级),故不处理空列表。
 *
 * `@Composable`:字段名要从 `TextKey` 现场解析(locale 可变),`map` 是 inline 故其
 * lambda 内可调 `textOf`;`joinToString` 的 transform 是普通 lambda,故连接放在外面。
 */
@Composable
private fun ruleSummaryLine(rule: SortRule): String {
    val body = rule.keys.map { key ->
        val arrow = if (key.ascending) "↑" else "↓"
        "${textOf(key.field.textKey)} $arrow"
    }.joinToString(" · ")
    return stringResource(R.string.a_rule_summary, body)
}

/**
 * 折叠摘要栏。结构与 [SchemeSummaryBar] 同构(**刻意不抽公共组件**:
 * `SchemeEditor.kt` 已被 P0 真机核对过,不为复用去动它),差异是这里多一行作用范围。
 *
 * @param scopeLabel 作用范围文案;**展开态传 null** —— 展开后编辑器自己的标题行
 *   已显示同一文案,再渲染一次会让精确文本匹配命中两个节点。
 */
@Composable
private fun RuleSummaryBar(
    summary: String,
    scopeLabel: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val toggleCd = stringResource(
        if (expanded) R.string.a_rule_summary_collapse_cd else R.string.a_rule_summary_expand_cd,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_RULE_SUMMARY_BAR),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 触控目标 ≥48dp(约束 7);内容只有一行文字,靠最小高度撑开
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp)
                .testTag(TAG_RULE_SUMMARY_TOGGLE)
                .semantics {
                    contentDescription = toggleCd
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (scopeLabel != null) {
                    Text(
                        text = scopeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 复用既有箭头资源:展开时朝上(提示"点它收起"),折叠时朝下
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down,
                ),
                contentDescription = null, // 语义由整行的 contentDescription 统一播报
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun ModeToggle(mode: ViewMode, onChange: (ViewMode) -> Unit) {
    val options = listOf(ViewMode.Grid, ViewMode.List)
    // 两个视图名与各自的无障碍描述。**必须先解析成局部变量**:下面 `forEachIndexed`
    // 的 lambda 不是 @Composable 上下文,里面调不了 stringResource。
    // 显式写两套(而不是遍历 options 现算)是为了让新增 ViewMode 时在此处编译失败。
    val gridLabel = stringResource(R.string.a_view_grid)
    val listLabel = stringResource(R.string.a_view_list)
    val gridCd = stringResource(R.string.a_view_cd, gridLabel)
    val listCd = stringResource(R.string.a_view_cd, listLabel)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.testTag(TAG_MODE_TOGGLE)) {
        options.forEachIndexed { index, option ->
            val isGrid = option == ViewMode.Grid
            SegmentedButton(
                selected = mode == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier
                    .testTag(if (isGrid) TAG_MODE_GRID_OPTION else TAG_MODE_LIST_OPTION)
                    .semantics { contentDescription = if (isGrid) gridCd else listCd },
            ) {
                Text(text = if (isGrid) gridLabel else listLabel)
            }
        }
    }
}
