package com.cyanix.pixfold.ui.organize

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SortRule
import com.cyanix.pixfold.domain.sort.moveItemTo
import com.cyanix.pixfold.domain.sort.resetToAuto
import com.cyanix.pixfold.domain.sort.togglePin
import com.cyanix.pixfold.domain.organize.OrganizeState
import com.cyanix.pixfold.domain.organize.applyRuleToCollection
import com.cyanix.pixfold.domain.organize.toggleCustomRule
import com.cyanix.pixfold.domain.organize.updatePageOrder
import com.cyanix.pixfold.domain.organize.organizeStateOf
import com.cyanix.pixfold.ui.components.DragReorderGrid
import com.cyanix.pixfold.ui.components.DragReorderList
import com.cyanix.pixfold.ui.components.LANDSCAPE_MAX_COLUMNS
import com.cyanix.pixfold.ui.components.PORTRAIT_MAX_COLUMNS
import com.cyanix.pixfold.ui.components.PageActionBar
import com.cyanix.pixfold.ui.components.adaptiveColumnCount
import com.cyanix.pixfold.ui.components.columnOptions
import com.cyanix.pixfold.ui.components.isPortraitOrientation
import com.cyanix.pixfold.ui.components.orientationWidth
import com.cyanix.pixfold.ui.text.textOf

const val TAG_GRID = "view-grid"
const val TAG_LIST = "view-list"
const val TAG_MODE_TOGGLE = "view-mode-toggle"
const val TAG_MODE_GRID_OPTION = "view-mode-grid"
const val TAG_MODE_LIST_OPTION = "view-mode-list"
const val TAG_RESET_MANUAL = "reset-manual"
const val TAG_SORT_RULE_EDITOR = "sort-rule-editor"
const val TAG_CUSTOM_RULE_TOGGLE = "custom-rule-toggle"
const val TAG_COLLECTION_ROW = "collection-row"

/**
 * 状态错配时的显式空态 testTag(2026-10-19)。
 *
 * 正常情况下不该出现 —— 出现即意味着本地 `state.orders` 缺当前集合的键。
 * 之所以仍给它 tag:让「白屏」这种**不可断言**的失败模式变成**可断言**的显式文案。
 */
const val TAG_SORT_EMPTY = "sort-empty"

const val TAG_NEXT_STEP = "next-step"
const val TAG_RULE_SUMMARY_BAR = "rule-summary-bar"
const val TAG_RULE_SUMMARY_TOGGLE = "rule-summary-toggle"

/**
 * 网格列数控件的 testTag(用户 2026-09-26 第二轮裁决:
 * 「纯图标，但是当前值只在下拉列表显示，长按图标是显示说明这是列数」)。
 *
 * 该按钮**放在网格/列表切换按钮右边、同一行内**,不单独占一行 ——
 * 第一轮曾单独占一行(当时是 4 个数字按钮,361dp 下切换行塞不下);
 * 改成纯图标后只占 48dp,与切换按钮同行的宽度账已重新算过(见 [ColumnCountButton])。
 * 该修订是用户当回合指令「因为只有一个图标所以可以放在网格/列表的切换按钮右边
 * 而不用单独占用一行」。
 */
const val TAG_COLUMN_BUTTON = "column-button"

/** 列数下拉菜单本身的 testTag(仅展开时存在)。 */
const val TAG_COLUMN_MENU = "column-menu"

/**
 * 列数下拉菜单的宽度 = 48dp,与列数按钮([TAG_COLUMN_BUTTON],实测 168px = 48.0dp)同宽。
 *
 * 用户 2026-09-27 第三次订正:「列数下拉列表只显示个数字,选项却特别宽,不好看」,
 * 并裁决定稿 **48dp**。
 *
 * ## 为什么在容器这一处给宽就够(不必逐条目重复 `Modifier.width()`)
 *
 * 菜单默认宽 112dp 来自 M3:`Menu.kt:527` 的
 * `DropdownMenuItemDefaultMinWidth = 112.dp` 是**库内 `private val`,外部无法下调**,
 * 由 `Menu.kt:419 DropdownMenuItemContent` 的
 * `modifier.clickable(...).fillMaxWidth().sizeIn(minWidth = 112.dp, maxWidth = 280.dp, ...)`
 * 兜在条目上 —— 只有一位数字也被撑成 112dp。
 *
 * 收窄之所以能"从上面压下来",是因为链上每一环都 `enforceIncoming = true`(服从上游约束):
 *  1. 该 `sizeIn` 默认 `enforceIncoming = true`(`Size.kt:226-238`)⇒ 服从上游;
 *  2. 调用方的 `modifier` 正是**最上游** —— `Menu.kt:359-415 DropdownMenuContent` 把它
 *     放在内层 `Column` 链首:
 *     `modifier.padding(vertical = 8.dp).width(IntrinsicSize.Max).verticalScroll(...)`;
 *     androidMain `AndroidMenu.android.kt:74-85` 的 `DropdownMenu` 逐字透传该 `modifier`;
 *  3. 紧随其后的 `width(IntrinsicSize.Max)` 也 `enforceIncoming = true`
 *     (`Intrinsic.kt:51` + `Intrinsic.kt:236-300 IntrinsicWidthNode`)⇒ 同样服从 48dp 上限。
 *
 * ⇒ 在 `DropdownMenu(modifier = ...)` 上单点设宽即可收窄**整个菜单**,
 * 条目侧的 112dp 兜底被自动夹掉。这比逐条目 `Modifier.width(48.dp)` 少 4 处重复,
 * 也不会出现"容器与条目两处宽度各自演化而失配"的隐患。
 */
private val COLUMN_MENU_WIDTH = 48.dp

/** 下拉菜单里某一档列数的 testTag。 */
fun columnTag(n: Int) = "column-$n"

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
 * 排序状态由领域层 `OrganizeState` 承载(**批次默认 + 本组独立**,规格 §5.4);
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
 * @param portraitDefaultColumns 竖屏下的**默认列数**(来自设置页);`null` = 跟随自适应。
 * @param landscapeDefaultColumns 横屏下的默认列数;`null` = 跟随自适应。
 *
 * ## 列数三层的来源(用户 2026-09-26 裁决 + 第二轮补充)
 *
 * 1. **自适应算式**([com.cyanix.pixfold.ui.components.adaptiveColumnCount])—— 兜底,
 *    也是"跟随自适应"的含义;
 * 2. **设置页的默认值**(上面两个参数,按屏幕方向实时二选一)—— 用户没在排序页动过时的起点;
 * 3. **本页临时覆盖**(工具栏图标按钮选的值)—— 只在本次进入有效,**离开页面即失效**,
 *    不回写设置(用户裁决:「设置 = 默认值;工具栏 = 本页临时覆盖,离开页面即失效」)。
 *
 * 三者优先级 3 > 2 > 1。工具栏下拉列表**只有数字,没有「自适应」项**(用户裁决 6):
 * "当前是哪一档"由页面算出具体数字再高亮,而不是列一个抽象的"自适应"选项。
 *
 * ## 头部可伸缩(用户 2026-09-25 指定)
 *
 * 「步骤1上面部分也做个伸缩吧,让网格/列表的空间可以大点显示。」
 * 折叠的**只有排序规则编辑器** —— 集合切换行 / 「共 N 张」行 / 网格-列表切换行**全部常驻**
 * (它们是"看什么、看多少、怎么看"的导航信息,折叠了用户就不知道自己看的是哪一批图);
 * 默认**折叠**,进页面就把高度让给网格。
 *
 * **编辑器并进滚动容器的头部**(2026-09-26 修正):排序级数上限
 * `SortRule.MAX_LEVELS` = 4 曾被当作"高度有界"的理由,据此把它留作固定区块。
 * 该前提**已被真机证伪** —— 4 级时编辑器约 2900px,而屏高只有 2780px;更早一步,
 * 它作为非加权子项会把 `weight(1f)` 的网格/列表挤到 0 高,而它自身不在任何滚动容器内,
 * 于是"展开后整页滑不动"(用户 2026-09-26 报告)。现改为把编辑器交给网格/列表**唯一**
 * 的滚动容器(见 [DragReorderGrid]/[DragReorderList] 的 `header` 参数),展开后可一路滑到底。
 * 摘要栏仍在容器外常驻,滚到深处也能一键折叠。
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
    externalState: OrganizeState? = null,
    onStateChange: (OrganizeState) -> Unit = {},
    onNext: (() -> Unit)? = null,
    portraitDefaultColumns: Int? = null,
    landscapeDefaultColumns: Int? = null,
) {
    var mode by remember { mutableStateOf(initialMode) }
    // 本地状态权威,初值取自 externalState,每次变更上报上层(理由同 NamingPage:
    // `externalState ?: local` 会让上层的值冻结就地编辑)。
    //
    // ⚠️ `remember(collections)` 的 key **不是**可选的(2026-10-19 真机缺陷):
    // 上层 `PixFoldApp` 用 `remember(collections)` 播种整个工作流状态,换工作区时
    // 会重建并传下**新的** `externalState`;但本页若无 key,组合实例不销毁 ⇒ 仍持有
    // 旧工作区的 state,其 `orders` 里只有旧集合 id,查不到新集合 ⇒ 整页渲染成空白
    // (真机实测:工作流内点「更换」换目录后全黑,冷启动却正常 —— 冷启首帧就播种正确)。
    // 加 key 后:collections 一变(换工作区/重扫)就重新播种,与上层同一步调。
    var state by remember(collections) {
        mutableStateOf(externalState ?: organizeStateOf(collections))
    }
    fun updateState(next: OrganizeState) {
        state = next
        onStateChange(next)
    }
    // ⚠️ `remember(initialCollectionId)`:这个 key **不是**多余的。
    // 「重置全部」(用户 2026-10-14)要把工作流复位到初值,其中包含**当前集合回到第一个**;
    // 而重置发生在 `OrganizeSteps` 那一层,本页不会重新组合 —— 没有这个 key 时
    // 上层的 `activeId` 改了、这里的 `activeId` 纹丝不动,重置看起来「只清了一半」。
    // 用 key 后:上层改值 ⇒ 本页重新播种。用户自己切集合时上层会收到同一个值,
    // 重新播种到同一值是无操作,故不影响正常交互(已由 `ResetAllTest` 的
    // `reset all returns to the first collection` 守住)。
    var activeId by remember(initialCollectionId) {
        mutableStateOf(initialCollectionId ?: collections.firstOrNull()?.id.orEmpty())
    }
    // W4:每次排序规则变更自增 -> 通知网格/列表回顶(用户 2026-09-17 指定)
    var scrollSignal by remember { mutableIntStateOf(0) }
    // 排序规则编辑器的展开态(用户 2026-09-25 指定:默认折叠,把高度让给网格)。
    // 状态 hoist 在本页而不是编辑器内部 —— 编辑器若自持该状态,`SortRuleDirectionTest`
    // (直接把 SortRuleEditor 当 root 组合)就得先展开才能点字段,徒增一处耦合。
    // `rememberSaveable` 让它扛住进程重建(与 NamingPage 的 editorExpanded 同一模式)。
    var ruleExpanded by rememberSaveable { mutableStateOf(false) }
    // ---- 列数:三级来源 + 本页临时覆盖(用户 2026-09-26 裁决 + 第二轮补充)----
    //
    //   优先级:本页临时覆盖 > 设置页的默认值(按屏幕方向二选一) > 自适应算式
    //
    // 为什么必须在这里算出**具体数字**、而不是把 `Int?` 交给网格让 `GridCells.Adaptive`
    // 自己算:下拉列表要**高亮当前生效的那一档**(用户裁决:「点击图标展开下拉列表就
    // 高亮那个选项」),而"当前生效的是几列"只有先算出来才可能被高亮。
    // 算式本身仍是唯一事实来源([adaptiveColumnCount]),设置页预览/本页默认/测试三处共用。
    //
    // 本页覆盖用 `remember`(非 `rememberSaveable`),与紧邻的 `mode` 同层同性质:
    // 旋转重建 Activity ⇒ 覆盖清零、回到新方向自己的默认值,正是用户裁决
    // 「工具栏 = 本页临时覆盖,离开页面即失效」的延伸;若扛过旋转,横屏会沿用
    // 竖屏选的 4 列(横屏下很难看)。
    var columnOverride by remember { mutableStateOf<Int?>(null) }
    val isPortrait = isPortraitOrientation()
    val adaptiveColumns = adaptiveColumnCount(
        orientationWidth(LocalConfiguration.current, isPortrait),
    )
    val settingColumns = if (isPortrait) portraitDefaultColumns else landscapeDefaultColumns
    val columns = columnOverride ?: settingColumns ?: adaptiveColumns
    // 初始集合也要通知上层,避免预览与当前集合不一致
    androidx.compose.runtime.LaunchedEffect(activeId) { onActiveCollectionChange(activeId) }

    val activeCollection = collections.firstOrNull { it.id == activeId } ?: collections.firstOrNull()
    val order = activeCollection?.let { state.orders[it.id] }
    // ⚠️ 这里**不能**写成 `?: return`(2026-10-19 真机缺陷的直接原因)。
    // 曾经是静默 `return` —— 一旦状态错配(本地 state 的 orders 里没有当前集合 id,
    // 典型场景:热切换工作区后旧 state 残留),整页渲染成**白屏且无任何提示**,
    // 用户只能看到一片黑,既不知道出了什么事也没法自救。
    // 现在改为显式空态:任何状态错配都**可见**,白屏不再是可能的结果。
    // 注:集合列表为空这种情况由上层 `OrganizeSteps` 的 `EmptyWorkspace` 处理,
    // 走不到这里 —— 这里专门覆盖「有集合、但它的排序状态缺失」这种内部错配。
    if (order == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets),
        ) {
            Text(
                text = stringResource(R.string.a_sort_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .testTag(TAG_SORT_EMPTY)
                    .align(Alignment.CenterHorizontally),
            )
        }
        return
    }
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
        //
        // 2026-09-25 真机实测缺陷:原实现是 Row + horizontalScroll,集合一多就溢出行外。
        // 3 个集合时 `杂图` 的右边界恰好 = 屏宽 1264px,第 4 个集合**完全落在视口外**,
        // 只能靠横向滑动才够得着(且界面上没有任何提示说明这里能滑)。
        // 这违反 docs/notes/d1-ui-friction-inventory.md 已固化的布局约定
        // 「361dp 下不给可操作控件加横向滚动」,也与 HANGOFF §9 第 1 条
        // (组件卡片横向滚动 -> 靠后按钮落在视口外 -> 改两行卡片)的既有裁决同源。
        // 改用 FlowRow 自动换行:集合再多也只是增高,不会有控件不可达。
        //
        // 2026-10-19:芯片行改为**两级**(series / 单本),逻辑整体搬到
        // [CollectionChipRows];本页只负责「选中哪个集合」与切集合回顶。
        //
        // 注:Robolectric 不做真实文本测量(芯片标签宽度都算成 ~0),单测**证明不了**
        // 换行是否真的发生,故此处没有对应的 UI 断言,只能靠真机坐标核对。
        CollectionChipRows(
            collections = collections,
            activeId = activeId,
            onSelect = { id ->
                // W1(2026-09-17 实测缺陷):切集合必须**回到顶部**。
                // 原因:切换集合的语义是"换一批图看",沿用上一个集合的滚动偏移
                // 会让用户看到"中段"而非第一张,需手动滑回顶部才知道切换成功;
                // 该残留状态还会**误导测试**(语义树只含可见项,读到的是视口中段)。
                //
                // 复用 W4 已建立的同一条通道(signal 自增 -> requestScrollToItem(0)):
                // 两处需求本质相同 —— 视口位置失去意义时归位。
                // 只有真的换了集合才自增,重复点同一个 chip 不该把用户拽回顶部。
                if (id != activeId) {
                    activeId = id
                    scrollSignal++
                }
            },
        )

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
            // 左侧:视图切换 + 列数按钮**同一行**(用户 2026-09-26 第二轮指令:
            // 「因为只有一个图标所以可以放在网格/列表的切换按钮右边而不用单独占用一行」)。
            // 第一轮它是 4 个数字按钮、361dp 下切换行塞不下才单独占一行;改成纯图标后
            // 只占 48dp(IconButton 触控下限),宽度账已重新算:切换按钮约 128dp + 4dp
            // 间距 + 48dp ≈ 180dp,而右侧「本组独立 + 开关」约 108dp,361dp − 24dp(左右
            // padding) = 337dp 足够。⚠️ 这仍是**估算**:Robolectric 不做真实文本测量,
            // "是否真的放得下"属于 §7.4 表里只能真机核对的一类。
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeToggle(mode = mode, onChange = { mode = it })
                // 仅网格模式显示:列表模式没有"列数"这个概念。
                if (mode == ViewMode.Grid) {
                    ColumnCountButton(
                        columns = columns,
                        onSelect = { columnOverride = it },
                    )
                }
            }

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
            onToggle = {
                val expanding = !ruleExpanded
                ruleExpanded = expanding
                // 前插锚定陷阱(P0 命名页 KDoc 已记录同一坑):展开是在视口**上方**插入内容,
                // LazyList 会锚定"当前首个可见项" ⇒ 画面看起来毫无反应。
                // 只在**展开**时回顶;折叠时不回 —— 用户很可能滚到深处才折叠,
                // 此时把他拽回顶部比"没反应"更糟(且折叠后可见项本就由 key 锚定保持不变)。
                if (expanding) scrollSignal++
            },
        )

        // 排序规则编辑器**并进网格/列表这个唯一滚动容器**的头部(用户 2026-09-26 裁决 A)。
        //
        // 此前它是 Column 里的固定(非加权)子项,高度无上限:展开后把 `weight(1f)` 的
        // 网格/列表挤到 0 高,而编辑器自身不在任何滚动容器内 ⇒ 内容超出屏高后既不能由
        // 列表滚、也不能自己滚(4 级时编辑器约 2900px > 屏高 2780px)。
        // 并进滚动容器后,展开态可以一路滑到底;代价是上滑时编辑器会滚出视口
        // (用户已知悉,同 P0 命名页形态)。
        //
        // 摘要栏**留在容器外**:滚到深处仍能一键折叠,且不参与滚动。
        val ruleEditorHeader: (@Composable () -> Unit)? = if (ruleExpanded) {
            {
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
        } else {
            null
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
                columns = columns,
                scrollToTopSignal = scrollSignal,
                header = ruleEditorHeader,
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
                header = ruleEditorHeader,
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
                // 去掉选中态的对勾(用户 2026-09-26:「网格/列表切换按钮选中有高亮所以不需要"✔"」)。
                //
                // `SegmentedButton` 的 `icon` 默认是 `SegmentedButtonDefaults.Icon(selected)`,
                // 选中时画 `Icons.Filled.Check`。传空 lambda 后:
                //  - 对勾消失(material3 1.4.0 `SegmentedButton.kt` 的测量里 icon 宽度算成 0);
                //  - **总宽度不变** —— 那个 26dp(IconSize 18 + IconSpacing 8)槽位是硬编码的,
                //    任何参数都去不掉(只有 contentPadding 可调);
                //  - 标签因此被 `offsetX = -13dp` 重新居中(此前有图标时它被右推 26dp)。
                // 选中态仍有容器高亮色,故"选中/未选中"依旧一眼可辨。
                icon = {},
                modifier = Modifier
                    .testTag(if (isGrid) TAG_MODE_GRID_OPTION else TAG_MODE_LIST_OPTION)
                    .semantics { contentDescription = if (isGrid) gridCd else listCd },
            ) {
                Text(text = if (isGrid) gridLabel else listLabel)
            }
        }
    }
}

/**
 * 网格列数按钮 —— **纯图标**,点击展开下拉列表,长按显示说明
 * (用户 2026-09-26 第二轮裁决:「纯图标，但是当前值只在下拉列表显示，
 * 长按图标是显示说明这是列数」)。
 *
 * ## 为什么按钮上**不**显示当前列数
 *
 * 用户明确要求纯图标。理由也成立:按钮只有 48dp,`4 列` 这样的文字会把它撑宽或换行,
 * 而列数是低频查看的信息 —— 需要时展开列表即可。代价是"当前几列"不再一眼可见,
 * 由 `contentDescription`(`a_cols_cd`,如「4 列」)向读屏用户补齐。
 *
 * ## 长按与点击为什么不冲突
 *
 * 两者都挂在同一个 `IconButton` 上,但 M3 的 `TooltipBox` 内部
 * (`androidx/compose/material3/internal/BasicTooltip.kt` 的 `Modifier.handleGestures`)
 * 在长按触发后**会消费随后的抬手事件**,于是子节点的 `onClick` 不再触发;
 * 它同时只在 `PointerEventPass.Initial` 观察、不消费,普通短按照样穿到 `IconButton`。
 * 换句话说"长按出说明、轻点出列表"是 API 保证的,不需要自己写手势判别。
 *
 * ## 下拉列表的内容
 *
 * 只有数字,没有「自适应」项(用户裁决 6:「设置里没有自适应选项,而是计算出的
 * 自适应值就是默认的选项,排序页里点击图标展开下拉列表就高亮那个选项」)。
 * 列表长度按屏幕方向取上限(竖屏 5 / 横屏 9),并把当前值临时并入,保证永远可见可选。
 *
 * ## 当前档位怎么"高亮"(2026-09-27 用户第二次订正)
 *
 * 第一版用 `leadingIcon` 打对勾。用户否掉了:「列数下拉要的效果是高亮而不是打勾」
 * ⇒ 改为**整行背景填充**。
 *
 * 三个 M3 事实决定了只能这么写:
 *  1. `DropdownMenuItem` **不设** selected 语义(`Menu.kt` 的 `DropdownMenuItemContent`
 *     只挂 `clickable`),故 `selected` 必须自己补,否则读屏用户与离线断言都看不出选中项;
 *  2. `MenuItemColors` **没有** selected 变体(只有 textColor / leadingIconColor /
 *     trailingIconColor 及其 disabled 版),故"选中底色"不会由 `colors` 参数给出,
 *     只能自己在 `modifier` 上填;
 *  3. 填在 `modifier` 上是安全的:`DropdownMenuItemContent` 把调用方的 `modifier`
 *     放在链首(`modifier.clickable(...).fillMaxWidth().sizeIn(...).padding(...)`),
 *     所以背景铺满整行、连内部 12dp 横向内边距一起 —— 正是"高亮条"该有的样子;
 *     而 `DropdownMenu` 的 `Column` 有 8dp 纵向内边距,首尾条目够不着圆角,
 *     不必自己描圆角。
 *
 * 色角色取 M3 官方的菜单选中项配色(`tokens/MenuTokens.kt`):
 * `ListItemSelectedContainerColor = secondaryContainer` +
 * `ListItemSelectedLabelTextColor = onSecondaryContainer`。用官方角色而非硬编码,
 * 既符合约束 7,也与仓内既有"选中"语义(FilterChip、底栏)同源。
 *
 * ## 菜单为什么只有 48dp(2026-09-27 用户第三次订正)
 *
 * 用户指出「列数下拉列表只显示个数字,选项却特别宽,不好看」,并裁决收窄到
 * **48dp(与列数按钮同宽)**。根因不在本页代码:M3 的
 * `DropdownMenuItemDefaultMinWidth = 112.dp`(`Menu.kt:527`,库内 `private val`,
 * 外部无法下调)被 `DropdownMenuItemContent` 的
 * `sizeIn(minWidth = DropdownMenuItemDefaultMinWidth, ...)` 兜住,
 * 于是只有一位数字的条目也被撑成 112dp。
 *
 * 收窄的写法**只在 [COLUMN_MENU_WIDTH] 一处给宽**,不逐条目重复 `Modifier.width()`。
 * 依据是该 `sizeIn` 的 `enforceIncoming = true`(`Size.kt:226-238`)⇒ 它服从**上游**
 * 约束;而调用方的 `modifier` 正是上游 —— `DropdownMenuContent` 把它放在内层
 * `Column` 链首(`Menu.kt:359-415`):
 * `modifier.padding(vertical = 8.dp).width(IntrinsicSize.Max).verticalScroll(...)`,
 * 链上紧随其后的 `width(IntrinsicSize.Max)` 同样 `enforceIncoming = true`
 * (`Intrinsic.kt:51` + `Intrinsic.kt:174-205`)⇒ 也服从这个上限。
 * 因此容器单点设宽即可收窄整个菜单,条目侧的 112dp 兜底会被自动夹掉。
 *
 * @param columns 当前**生效**的列数(页面已按"覆盖 > 设置默认 > 自适应"算好)。
 * @param onSelect 选中某一档时的回调;页面把它存为本次进入的临时覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnCountButton(columns: Int, onSelect: (Int) -> Unit) {
    // 先解析文案:tooltip/菜单项的 lambda 非 @Composable 上下文
    val label = stringResource(R.string.a_cols_label)
    val columnsCd = stringResource(R.string.a_cols_cd, columns)
    val portrait = isPortraitOrientation()
    val maxColumns = if (portrait) PORTRAIT_MAX_COLUMNS else LANDSCAPE_MAX_COLUMNS
    val options = columnOptions(maxColumns, columns)
    // 每个数字档的无障碍描述(如「4 列」);lambda 里调不了 stringResource,先算好。
    val optionCds = options.map { stringResource(R.string.a_cols_cd, it) }

    var expanded by remember { mutableStateOf(false) }

    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Above,
            ),
            tooltip = {
                PlainTooltip { Text(text = label) }
            },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .testTag(TAG_COLUMN_BUTTON)
                    // 纯图标 => 无障碍描述必须自己给:读屏用户看不到"几列",只能听。
                    .semantics { contentDescription = columnsCd },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_view_column),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // 宽度只在**这一处**给:容器上的固定宽会沿约束链压掉条目侧的 112dp 兜底,
            // 故不必逐条目重复 `Modifier.width()`(依据见 [COLUMN_MENU_WIDTH] 的说明)。
            modifier = Modifier
                .testTag(TAG_COLUMN_MENU)
                .width(COLUMN_MENU_WIDTH),
        ) {
            options.forEachIndexed { index, option ->
                val isCurrent = option == columns
                // 当前生效那一档 = 整行背景填充高亮(用户 2026-09-27 裁决定稿)。
                // 背景填在 `modifier` 上是安全的:`DropdownMenuItemContent` 把调用方
                // 的 modifier 放在链首,故会铺满整行(含内部 12dp 内边距);
                // 首尾条目够不着圆角,不必自己描。
                // 非当前档用 `Modifier`(而不是填透明色),免得白draw一层。
                val highlight = if (isCurrent) {
                    // 颜色用 M3 官方菜单选中项角色(MenuTokens.ListItemSelectedContainerColor)。
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                }
                DropdownMenuItem(
                    // 数字要**水平居中**:M3 把 text 槽位放在一个 `Box(Modifier.weight(1f))` 里
                    // (`Menu.kt:461-479`),Box 默认把子项贴着起始边放,于是数字被行内的
                    // 12dp `contentPadding` 顶到左边(真机实测 x=529 = 行左缘 487 + 42px/12dp)。
                    // 槽位自身没有对齐参数可传 ⇒ 只能让 Text 铺满该槽位再用 `TextAlign.Center`
                    // 把文字摆到槽位中线;槽位左右各有等宽内边距,故这也是整行的中线。
                    text = {
                        Text(
                            text = option.toString(),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = Modifier
                        .testTag(columnTag(option))
                        .then(highlight)
                        // M3 的 DropdownMenuItem 不设 selected 语义,只能自己补:
                        // 读屏用户与离线断言都靠它认出"当前是哪一档"。
                        .semantics {
                            selected = isCurrent
                            contentDescription = optionCds[index]
                        },
                )
            }
        }
    }
}
