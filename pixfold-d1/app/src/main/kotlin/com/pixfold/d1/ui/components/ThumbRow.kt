package com.pixfold.d1.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.SourceItem

const val TAG_LIST_ROW = "list-row"

/** 列表行:小缩略图 + 文件名 + 相对路径/大小。信息密度高于网格,便于核对路径。 */
@Composable
fun ThumbRow(
    item: SourceItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    onPin: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_LIST_ROW)
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = item.relPath },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 列表行内已用文字显示文件名,故缩略图不再重复画字(避免噪杂)
        ProceduralThumb(
            seed = item.seed,
            fileName = item.fileName,
            modifier = Modifier.size(44.dp),
            showIndex = false,
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.relPath}  ·  ${item.sizeLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 图钉入口必须可点(验收第 6 项 / 走查反馈④的静默缺口)
        if (onPin != null) {
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .testTag(pinTag(item.id))
                    .clickable(onClick = onPin)
                    .semantics {
                        contentDescription =
                            if (isPinned) "${item.fileName} 已固定" else "${item.fileName} 固定位置"
                    },
                shape = MaterialTheme.shapes.small,
                color = if (isPinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isPinned) "★" else "☆",
                        color = if (isPinned) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
