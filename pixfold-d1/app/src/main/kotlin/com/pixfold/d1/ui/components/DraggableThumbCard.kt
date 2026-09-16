package com.pixfold.d1.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.SourceItem

/** 图钉按钮的 testTag;测试与"可点入口"断言共用。 */
fun pinTag(id: String): String = "pin-$id"

/** 目标格高亮描边(拖动中)。 */
private val HoverBorder = Color(0xCC3F51B5)

/**
 * 可拖拽的网格卡片(**拖拽自研**,零三方依赖 —— HANGOFF §12.5 用户决策)。
 *
 * 三条设计结论(与实现栈无关,继续有效):
 * 1. **整卡可拖,不必抢点击**:长按后启动拖拽,静止点按仍是点击 → 无需拖拽把手。
 * 2. **不做悬停实时重排**:拖动期间指针微动会反复触发重排 → 顺序来回换位、看起来"拖了没变"。
 *    **正确语义 = 松手落地**:拖动中只**高亮目标格**,`onMoveTo` 仅在**抬起时**调用一次。
 * 3. **统一重排入口**:UI 不自己改顺序,一律回调 `onMoveTo` 交给领域层 `moveItemTo`。
 *
 * 图钉入口必须**可点**(走查反馈④的静默缺口:只有状态角标、没有可点入口)。
 *
 * @param dragPositionInRoot 当前拖拽指针的**根坐标**(由父级解析目标格并回传高亮态);
 *   为 null 表示未在拖拽。
 * @param hoveredTarget 父级解析出的目标下标(用于高亮);null 表示无目标。
 */
@Composable
fun DraggableThumbCard(
    item: SourceItem,
    isPinned: Boolean,
    isHovered: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_GRID_CARD)
                .alpha(if (isDragging) 0.35f else 1f)
                .pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset -> onDragStart(offset) },
                        onDrag = { change, _ ->
                            change.consume()
                            onDrag(change.position)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() },
                    )
                }
                .semantics { contentDescription = item.fileName },
            colors = if (isHovered) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Column {
                ProceduralThumb(
                    seed = item.seed,
                    fileName = item.fileName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Text(
                    text = item.fileName,
                    modifier = Modifier.padding(6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isHovered) {
            // 目标格半透明主色 + 边框(归档设计意图:拖拽视觉要有包络与占位)
            Surface(
                modifier = Modifier
                    .matchParentSize()
                    .padding(2.dp),
                color = HoverBorder.copy(alpha = 0.18f),
                shape = MaterialTheme.shapes.medium,
                border = androidx.compose.foundation.BorderStroke(2.5.dp, HoverBorder),
                content = {},
            )
        }

        PinButton(
            isPinned = isPinned,
            itemName = item.fileName,
            id = item.id,
            onPin = onPin,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** 图钉按钮:未固定=空心样式,已固定=实心主色。必须可点(走查反馈④)。 */
@Composable
private fun PinButton(
    isPinned: Boolean,
    itemName: String,
    id: String,
    onPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(4.dp)
            .size(32.dp)
            .testTag(pinTag(id))
            .clickable(onClick = onPin)
            .semantics {
                contentDescription = if (isPinned) "$itemName 已固定" else "$itemName 固定位置"
            },
        shape = MaterialTheme.shapes.small,
        color = if (isPinned) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (isPinned) "★" else "☆",
                style = MaterialTheme.typography.labelMedium,
                color = if (isPinned) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
