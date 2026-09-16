package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.sort.PageOrderState
import com.pixfold.d1.domain.sort.applyRule
import com.pixfold.d1.domain.sort.moveItemTo
import com.pixfold.d1.domain.sort.pageOrderOf
import com.pixfold.d1.domain.sort.resetToAuto
import com.pixfold.d1.domain.sort.togglePin
import com.pixfold.d1.ui.components.DragReorderGrid
import com.pixfold.d1.ui.components.ThumbRow

const val TAG_GRID = "view-grid"
const val TAG_LIST = "view-list"
const val TAG_MODE_TOGGLE = "view-mode-toggle"
const val TAG_MODE_GRID_OPTION = "view-mode-grid"
const val TAG_MODE_LIST_OPTION = "view-mode-list"
const val TAG_RESET_MANUAL = "reset-manual"
const val TAG_SORT_RULE_EDITOR = "sort-rule-editor"

/**
 * 工作流 A · 步骤 1:排序与预览(验收第 1、3、4、5、6、7 项)。
 *
 * 排序状态由领域层 [PageOrderState] 承载(不可变 + 纯函数),
 * UI 只调用 [moveItemTo] / [togglePin] / [resetToAuto] / [applyRule] 四个入口,
 * **不自己改顺序**(归档设计结论 3:统一重排入口)。
 *
 * @param contentInsets 系统栏 inset;可注入以便测试(Robolectric 下系统 inset 恒为 0)。
 * @param initialOrder 初始页序;默认按自动规则生成。
 */
@Composable
fun SortAndPreviewPage(
    items: List<SourceItem>,
    modifier: Modifier = Modifier,
    initialMode: ViewMode = ViewMode.Grid,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onOpenPreview: (Int) -> Unit = {},
    initialOrder: PageOrderState? = null,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var order by remember { mutableStateOf(initialOrder ?: pageOrderOf(items)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    onClick = { order = resetToAuto(order) },
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
        }

        // 排序规则编辑器(验收第 7 项:多级可增删改)
        SortRuleEditor(
            rule = order.rule,
            onApply = { newRule -> order = applyRule(order, newRule) },
        )

        when (mode) {
            ViewMode.Grid -> DragReorderGrid(
                items = order.order,
                onMoveTo = { id, target -> order = moveItemTo(order, id, target) },
                onPin = { id -> order = togglePin(order, id) },
                isPinned = { id -> order.isPinned(id) },
                onClick = { index -> onOpenPreview(index) },
                modifier = Modifier.fillMaxSize(),
            )

            ViewMode.List -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TAG_LIST),
            ) {
                listItemsIndexed(order.order, key = { _, it -> it.id }) { index, item ->
                    ThumbRow(
                        item = item,
                        isPinned = order.isPinned(item.id),
                        onPin = { order = togglePin(order, item.id) },
                        onClick = { onOpenPreview(index) },
                    )
                }
            }
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
