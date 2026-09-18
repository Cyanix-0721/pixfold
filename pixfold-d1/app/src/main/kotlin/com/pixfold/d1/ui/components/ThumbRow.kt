package com.pixfold.d1.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.background
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
    isDragging: Boolean = false,
    isHovered: Boolean = false,
    onDragStart: ((Offset) -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_LIST_ROW)
            .alpha(if (isDragging) 0.35f else 1f)
            .then(
                // 拖拽与点击共存:长按超时才启动拖拽,短按仍走 clickable。
                // 仅在提供回调时挂 pointerInput(纯展示用途不受影响)。
                if (onDragStart != null) {
                    Modifier.pointerInput(item.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> onDragStart(offset) },
                            onDrag = { change, _ ->
                                change.consume()
                                onDrag?.invoke(change.position)
                            },
                            onDragEnd = { onDragEnd?.invoke() },
                            onDragCancel = { onDragCancel?.invoke() },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .heightIn(min = 56.dp)
            .background(
                if (isHovered) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .semantics { contentDescription = item.relPath },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 点击区只覆盖内容,避免吞掉图钉按钮的点击
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 列表行内已用文字显示文件名,故缩略图不再重复画字(避免噪杂)
            ProceduralThumb(
                seed = item.seed,
                fileName = item.fileName,
                modifier = Modifier.size(44.dp),
                showIndex = false,
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
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
        }

        // 图钉入口必须可点(验收第 6 项);图形为自绘图钉(见 PinToggle 注释:不用五角星)
        if (onPin != null) {
            PinToggle(
                isPinned = isPinned,
                itemName = item.fileName,
                onClick = onPin,
                size = 40.dp,
                modifier = Modifier.padding(end = 8.dp),
                tag = pinTag(item.id),
            )
        }
    }
}
