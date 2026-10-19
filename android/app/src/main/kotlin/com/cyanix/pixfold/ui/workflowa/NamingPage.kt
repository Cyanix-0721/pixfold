package com.cyanix.pixfold.ui.workflowa

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.naming.buildProposals
import com.cyanix.pixfold.domain.naming.buildRenamePlan
import com.cyanix.pixfold.domain.model.effectiveScheme
import com.cyanix.pixfold.domain.naming.clearOverride
import com.cyanix.pixfold.domain.naming.namingSummaryLine
import com.cyanix.pixfold.domain.naming.setOverride
import com.cyanix.pixfold.domain.sort.PageOrderState
import com.cyanix.pixfold.ui.components.PageActionBar
import com.cyanix.pixfold.ui.text.WarnLine
import com.cyanix.pixfold.ui.text.textOf
import com.cyanix.pixfold.ui.text.warnIcon

const val TAG_NAMING_PAGE = "naming-page"
const val TAG_NAMING_TITLE = "naming-title"

/**
 * 工作流 A · 步骤 2:命名结构(验收第 8、9、10 项)。
 *
 * 数据流严格单向(规格 §11):**UI 只调用领域纯函数,不自己算名字**。
 *  - 输入:当前集合的页序 [PageOrderState](含人工调整 —— 命名序号跟随页序,不是原始顺序);
 *  - 状态:[NamingState](scheme + overrides),每次改动产生新实例 -> Compose 自然重组;
 *  - 求值:[buildProposals] 一次算出全部建议 + 警告;[buildRenamePlan] 得出"将跳过"的项。
 *
 * **布局(P0 重构,2026-09-24):固定区 + 唯一滚动容器**。
 * 真机(361dp × 794dp)实测:展开态的命名结构编辑器高达 692dp = 视口的 **87%**,
 * 首屏连一条建议行都放不下 —— 直接违反约束 3「预览是主工作区,不是日志」。
 * 故把页面切成两段:
 *  - **固定区**(永不被挤走):标题 -> 折叠摘要栏 -> 冲突提示 -> 汇总/筛选 -> 分隔线;
 *  - **滚动区**:唯一 [LazyColumn],首项是**展开态**的编辑器,其后是建议行。
 *
 * 三个关键取舍:
 *  1. **编辑器默认折叠**成一行摘要(真实建议名 + 组件数),点击展开、展开态记忆;
 *  2. 展开态的编辑器放进滚动容器**而不是**固定区:用户加组件时它会增长,
 *     留在固定区就会重演 P0(主工作区被挤到 0 高);
 *  3. 汇总/筛选**移出** LazyColumn 固定住(「摘要行吸顶」)—— 它高度恒定,
 *     固定住才谈得上"滚到第 20 行时还知道总共几条有问题",且 `stickyHeader`
 *     在 foundation 1.12.1 已不是实验 API,这里用固定槽位而非 sticky 是为了
 *     与折叠栏连成一条完整的头部带。
 *
 * **⚠️ 一个必须知道的行为陷阱(折叠后实测踩到)**:编辑器是**前插**到 LazyColumn 首项的,
 * 而 LazyList 默认锚定"当前第一个可见项"不使其跳动 —— 于是点"展开"后编辑器被插到
 * 视口**上方**,用户看不到任何变化(离线表现为 14 项既有测试全部失败)。故展开时
 * 用 [LaunchedEffect] 主动 `scrollToItem(0)` 把列表拉回顶部。
 *
 * 效果(Robolectric 实测,`w400dp-h1000dp`;固定区 154dp、建议行高 113dp):
 * 展开态编辑器 692dp → 折叠后固定区 **154dp**,真机 794dp 视口首屏可见 **5 行以上**建议。
 *
 * **为什么把 overrides 放在 NamingState 而不是各自 Composable 的 remember 里**:
 * 覆盖必须能在"改结构"后存活(验收第 9 项)。放在**独立于 scheme 的字段**里,
 * 就不存在"UI 忘了同步"的可能 —— 这是规格 §6.3 指定的机制,不是实现细节。
 *
 * @param initialState 初值(上层持有的批次级状态);为 null 时用集合默认结构。
 * @param onStateChange 状态变化上报(上层据此在离开本页后仍保留覆盖)。
 * @param onBack 返回步骤 1 的回调;**为 null 时不显示返回入口**(单独使用/测试场景)。
 * @param onNext 进入步骤 3 的回调;为 null 时不显示推进按钮(单独使用/测试场景)。
 *
 * ## 底栏(用户 2026-09-25 指定)
 *
 * 「下一步、完成之类的按钮都放到底栏的**右侧**,返回、上一步的按钮改到**左侧**。」
 * 左 = [onBack](返回排序),右 = [onNext](下一步:执行计划)。两处都用 `if (… != null)` 守卫,
 * 因为测试直接组合本页时不传回调 —— 不守卫的话底栏会凭空出现并改变既有断言的可视区域。
 */
@Composable
fun NamingPage(
    collection: ImageCollection,
    order: PageOrderState,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    initialState: NamingState? = null,
    onStateChange: (NamingState) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    // ---- 集合芯片行(2026-10-19 用户要求「整理图片的步骤2也加上芯片选取」)----
    //
    // 三个参数都给默认值,让既有调用点(38 个测试用例的 `show()`、`ReplacementFieldTest`)
    // 零改动兼容 —— 不传就没有芯片行,行为与改前完全一致。
    //
    // **选中项与步骤 1 共用同一个 `activeId`**(用户 2026-10-19 裁决「共用同一个选中项」):
    // 步骤 2 只有 `onSelectCollection` 上报,由上层改写那个唯一的 activeId,
    // 于是「步骤 2 切了集合 → 回步骤 1 也是新选的」自然成立,不需要任何同步逻辑。
    collections: List<ImageCollection> = emptyList(),
    onSelectCollection: (String) -> Unit = {},
) {
    // 状态模式:**本地状态权威,初值取自 initialState,每次变更上报上层**。
    // 不用 `initialState ?: local` —— 那样一旦上层传值,就地编辑就永不显示(实测踩过)。
    //
    // `remember(collection)` 的 key(2026-10-19,与 `SortAndPreviewPage` 同源缺陷):
    // 换工作区 / 刷新后集合变了,旧命名状态的 `overrides` 键是旧集合项的 id,
    // 与新集合完全不匹配 —— 不重播种就会带着一堆指向不存在项的覆盖继续算建议。
    // `ImageCollection` 是 data class,内容相同的重扫结果 equals 相等 ⇒ 不会误重置。
    var state by remember(collection) {
        mutableStateOf(initialState ?: NamingState(NamingScheme(rootDirName = collection.rootDirName)))
    }
    fun update(next: NamingState) {
        state = next
        onStateChange(next)
    }

    // 唯一求值入口:页序 + **生效结构** + 覆盖 -> 建议(含警告)。
    // 生效结构 = 批次级 scheme,但根目录名默认**跟随当前集合**
    // (用户 2026-09-18 指定);用户改过则以用户的为准(effectiveScheme 内聚该判定)。
    val pageOrder: List<SourceItem> = order.order
    val effective = effectiveScheme(state, collection)
    val proposals = buildProposals(pageOrder, effective, overrides = state.overrides)
    val plan = buildRenamePlan(proposals)
    val skipped = plan.count { it.isSkipped }
    val conflictCount = proposals.count { it.hasConflict }
    val warningOnlyCount = proposals.count { !it.hasConflict && it.hasWarningOnly }

    var conflictOnly by remember { mutableStateOf(false) }
    val shown = if (conflictOnly) proposals.filter { it.warnings.isNotEmpty() } else proposals

    // 展开态**hoist 到本页**(而非编辑器内部):编辑器整体是 LazyColumn 的一个 item,
    // 状态放在 item 里会随回收一起丢失。`rememberSaveable` 让它还能扛住进程重建。
    var editorExpanded by rememberSaveable { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // 展开后必须**主动滚回列表顶部**:编辑器是**前插**到 LazyColumn 首项的,
    // 而 LazyList 会锚定"当前第一个可见项"不使其跳动 —— 结果是编辑器被插到视口**上方**,
    // 用户点了展开却看不到编辑器(实测:14 项既有测试全部因此失败)。
    LaunchedEffect(editorExpanded) {
        if (editorExpanded) listState.scrollToItem(0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_NAMING_PAGE),
    ) {
        // ---- 固定区(不随列表滚动):集合芯片 + 标题 + 折叠摘要栏 + 冲突提示 + 汇总 ----

        // 集合芯片行放最上方(与步骤 1 同一位置语义):先选在哪一本上命名,再看这一本的标题。
        // **不在外层判 `collections.size > 1`**:该判据在 [CollectionChipRows] 内部已有,外面再判
        // 一层就是冗余守卫 —— 变异实测把外层改成 `if (true)` 后没有任何断言能红(2026-10-19 M13),
        // 留着只会让后来者以为两层都在承重。
        CollectionChipRows(
            collections = collections,
            activeId = collection.id,
            onSelect = onSelectCollection,
        )

        Text(
            text = pluralStringResource(R.plurals.a_naming_title, pageOrder.size, collection.name, pageOrder.size),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag(TAG_NAMING_TITLE),
        )

        SchemeSummaryBar(
            // 摘要样本取**首个最终名**(含逐项覆盖),与建议表同源,结构上排除不一致。
            // `namingSummaryLine` 返回领域层的 `List<Msg>`(语言无关),在此现场解析并连接;
            // 手工调整段**自带段首分隔符**,故连接符用空串(见 `domain_naming_summary_manual`)。
            // `map` 必须内联:`joinToString` 的 transform 是普通 lambda,里面调不了 @Composable。
            summary = namingSummaryLine(effective, proposals.firstOrNull()?.finalName, order.manualCount)
                .map { textOf(it) }
                .joinToString(""),
            expanded = editorExpanded,
            onToggle = { editorExpanded = !editorExpanded },
        )

        if (skipped > 0) {
            // 警示由矢量图标承担(2026-10-05);`padding` 留在外层 `Row` 上,
            // 使排版位置与改前一致(原来 padding 挂在 `Text` 上)。
            WarnLine(
                icon = warnIcon(R.plurals.a_naming_conflicts) ?: R.drawable.ic_warning_triangle,
                text = pluralStringResource(R.plurals.a_naming_conflicts, skipped, skipped),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        ProposalSummary(
            total = proposals.size,
            conflictCount = conflictCount,
            warningOnlyCount = warningOnlyCount,
            conflictOnly = conflictOnly,
            onConflictOnlyChange = { conflictOnly = it },
        )

        HorizontalDivider()

        // ---- 唯一滚动容器:展开态的编辑器(可达) + 建议行 ----
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_PROPOSAL_LIST),
        ) {
            if (editorExpanded) {
                item(key = "editor") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SchemeEditor(
                            state = state,
                            effective = effective,
                            onChange = { update(it) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(
                            if (conflictOnly) {
                                R.string.a_naming_empty_issues
                            } else {
                                R.string.a_naming_empty_images
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }

            items(shown, key = { it.image.id }) { proposal ->
                ProposalRow(
                    proposal = proposal,
                    overrideText = state.overrides[proposal.image.id],
                    onOverride = { id, name -> update(setOverride(state, id, name)) },
                    onResetOverride = { id -> update(clearOverride(state, id)) },
                )
            }
        }

        // 底栏:左 = 返回排序,右 = 下一步:执行计划(用户 2026-09-25 指定)。
        // 任一回调为 null 时不渲染底栏 —— 测试直接组合本页,底栏凭空出现会改变可视区域。
        if (onBack != null || onNext != null) {
            val back = onBack
            val next = onNext
            // 要进 semantics{},故先在组合上下文里解析
            val backCd = stringResource(R.string.a_naming_back_cd)
            val nextCd = stringResource(R.string.a_naming_next_cd)
            PageActionBar(
                leading = {
                    if (back != null) {
                        FilledTonalButton(
                            onClick = back,
                            modifier = Modifier
                                .testTag(TAG_STEP_BACK)
                                .semantics { contentDescription = backCd },
                        ) { Text(stringResource(R.string.a_naming_back)) }
                    }
                },
                trailing = {
                    if (next != null) {
                        Button(
                            onClick = next,
                            modifier = Modifier
                                .testTag(TAG_TO_PLAN)
                                .semantics { contentDescription = nextCd },
                        ) { Text(stringResource(R.string.a_naming_next)) }
                    }
                },
            )
        }
    }
}
