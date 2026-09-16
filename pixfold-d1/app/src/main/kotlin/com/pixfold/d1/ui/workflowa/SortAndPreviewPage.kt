package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import com.pixfold.d1.ui.components.ThumbCard
import com.pixfold.d1.ui.components.ThumbRow

const val TAG_GRID = "view-grid"
const val TAG_LIST = "view-list"
const val TAG_MODE_TOGGLE = "view-mode-toggle"
const val TAG_MODE_GRID_OPTION = "view-mode-grid"
const val TAG_MODE_LIST_OPTION = "view-mode-list"

/**
 * 工作流 A · 步骤 1:排序与预览(验收第 1 项)。
 *
 * 网格列数按窗口宽度**自适应**(`GridCells.Adaptive`)——这是信息密度的关键:
 * 换栈动因正是"元素过大、一屏看不了多少"(§8.2 证伪条件 ③)。
 */
@Composable
fun SortAndPreviewPage(
    items: List<SourceItem>,
    modifier: Modifier = Modifier,
    initialMode: ViewMode = ViewMode.Grid,
    onOpenPreview: (Int) -> Unit = {},
) {
    var mode by remember { mutableStateOf(initialMode) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${items.size} 张",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            ModeToggle(mode = mode, onChange = { mode = it })
        }

        when (mode) {
            ViewMode.Grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TAG_GRID),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                    ThumbCard(item = item, onClick = { onOpenPreview(index) })
                }
            }

            ViewMode.List -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TAG_LIST),
            ) {
                listItemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                    ThumbRow(item = item, onClick = { onOpenPreview(index) })
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
