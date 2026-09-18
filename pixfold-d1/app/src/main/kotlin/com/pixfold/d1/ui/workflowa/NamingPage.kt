package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.naming.buildProposals
import com.pixfold.d1.domain.naming.buildRenamePlan
import com.pixfold.d1.domain.model.effectiveScheme
import com.pixfold.d1.domain.naming.clearOverride
import com.pixfold.d1.domain.naming.setOverride
import com.pixfold.d1.domain.sort.PageOrderState

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
 * **布局:整页只有一个 LazyColumn**(标题/编辑器/汇总为头部 item,建议行为后续 items)。
 * 早先写法是"外层 verticalScroll 放编辑器 + 内层 LazyColumn 放建议表",
 * 结果外层无界高度把建议表挤到近乎 0 高(第 2 行都组合不出来)——
 * 嵌套滚动 + 高度竞争。单容器后同时消除这两个问题。
 *
 * **为什么把 overrides 放在 NamingState 而不是各自 Composable 的 remember 里**:
 * 覆盖必须能在"改结构"后存活(验收第 9 项)。放在**独立于 scheme 的字段**里,
 * 就不存在"UI 忘了同步"的可能 —— 这是规格 §6.3 指定的机制,不是实现细节。
 *
 * @param initialState 初值(上层持有的批次级状态);为 null 时用集合默认结构。
 * @param onStateChange 状态变化上报(上层据此在离开本页后仍保留覆盖)。
 */
@Composable
fun NamingPage(
    collection: ImageCollection,
    order: PageOrderState,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    initialState: NamingState? = null,
    onStateChange: (NamingState) -> Unit = {},
) {
    // 状态模式:**本地状态权威,初值取自 initialState,每次变更上报上层**。
    // 不用 `initialState ?: local` —— 那样一旦上层传值,就地编辑就永不显示(实测踩过)。
    var state by remember {
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_NAMING_PAGE),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_PROPOSAL_LIST),
        ) {
            // ---- 头部:标题 + 结构编辑器(随列表滚动,不吸顶) ----
            item(key = "header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "命名结构 · ${collection.name}（${pageOrder.size} 张）",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag(TAG_NAMING_TITLE),
                    )

                    SchemeEditor(
                        state = state,
                        effective = effective,
                        onChange = { update(it) },
                    )

                    if (skipped > 0) {
                        Text(
                            text = "⚠️ 有 $skipped 项存在冲突，执行时将跳过（见下表红字）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                }
            }

            // ---- 汇总 + 筛选(作为 item 跟随滚动;stickyHeader 仍是实验 API,不用) ----
            item(key = "summary") {
                ProposalSummary(
                    total = proposals.size,
                    conflictCount = conflictCount,
                    warningOnlyCount = warningOnlyCount,
                    conflictOnly = conflictOnly,
                    onConflictOnlyChange = { conflictOnly = it },
                )
            }

            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (conflictOnly) "没有冲突或警告项" else "没有可命名的图片",
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
    }
}
