package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
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
import com.cyanix.pixfold.domain.model.SourceItem

const val TAG_LIST_ROW = "list-row"

/** 列表行:小缩略图 + 文件名 + 相对路径/大小。信息密度高于网格,便于核对路径。
 *
 * 拖动中的让位由**邻居实时重排**表达(见 [DragReorderGrid] 的 KDoc),
 * 故不再需要"目标行高亮"—— 被拖的这一行自己就在新位置上(半透明,见 [isDragging])。 */
@Composable
fun ThumbRow(
    item: SourceItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    onPin: (() -> Unit)? = null,
    isDragging: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_LIST_ROW)
            .alpha(if (isDragging) 0.35f else 1f)
            .heightIn(min = 56.dp)
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
            ItemThumb(
                item = item,
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
        //
        // 尺寸:吃 PinToggle 的默认 32dp,**不要**再传 size(用户 2026-09-26 指定
        // 「PinToggle48太大了,32差不多了」)。此处曾写 `size = 40.dp`,导致网格 32dp /
        // 列表 40dp 两个尺寸并存 —— 同一控件在两处视图里长得不一样。
        // 触控目标不受影响:视觉 32dp 之外仍由 `Modifier.clickable` 撑到 48dp
        // (真机实测语义框 168px = 48dp,视觉 112px = 32dp),理由与取证见台账 §16。
        if (onPin != null) {
            PinToggle(
                isPinned = isPinned,
                itemName = item.fileName,
                onClick = onPin,
                modifier = Modifier.padding(end = 8.dp),
                tag = pinTag(item.id),
            )
        }
    }
}
