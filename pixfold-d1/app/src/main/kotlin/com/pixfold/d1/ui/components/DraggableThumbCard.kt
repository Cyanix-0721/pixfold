package com.pixfold.d1.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.SourceItem

/**
 * 网格卡片 testTag。
 *
 * 曾定义在已删除的 `ThumbCard.kt`(早期静态卡片实现,被本文件取代后成了死代码)。
 * 常量本身仍被生产代码与本文件的 `testTag` 及 6 个测试文件共用,故随实现一起搬到这里。
 */
const val TAG_GRID_CARD = "grid-card"

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
 * **手势不在这里**:长按拖拽的 `pointerInput` 挂在**不滚动的容器**上
 * (见 [DragReorderGrid] 的 KDoc —— LazyGrid 会在卡片滚出视口时销毁它,
 * 挂在卡片上的手势会随之被取消,跨视口拖拽永远失败)。
 * 本组件只负责**呈现**拖拽态([isDragging] / [isHovered])。
 */
@Composable
fun DraggableThumbCard(
    item: SourceItem,
    isPinned: Boolean,
    isHovered: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_GRID_CARD)
                .alpha(if (isDragging) 0.35f else 1f)
                .semantics { contentDescription = item.fileName },
            colors = if (isHovered) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            // clickable 放在内容上:长按拖拽由外层 pointerInput 处理,静止点按仍是点击
            // (两者可共存:长按超时后 drag 才启动,短按走 click)
            Column(modifier = Modifier.clickable(onClick = onClick)) {
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

        PinToggle(
            isPinned = isPinned,
            itemName = item.fileName,
            onClick = onPin,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
            tag = pinTag(item.id),
        )
    }
}
