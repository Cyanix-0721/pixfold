package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.model.SourceItem

/** 拖拽浮层的 tag(拖拽中才会出现在语义树里)。 */
const val TAG_DRAG_GHOST = "drag-ghost"

/** 浮层内容形态 —— 与来源视图一致,让"手里拿的是什么"一眼可辨。 */
enum class DragGhostStyle {
    /** 网格卡片形态:方形缩略图 + 文件名。 */
    Card,

    /** 列表行形态:小缩略图 + 文件名。 */
    Row,
}

/**
 * 拖拽中的**跟手浮层** —— 一张跟随指针的缩略图副本。
 *
 * ## 为什么需要它(HANGOFF §11 第 9 条,2026-09-25 用户裁决)
 *
 * 补缺口前拖动中**没有任何东西跟着手指**:源卡片只是 `.alpha(0.35f)` 留在原地,
 * 目标格高亮又只在指针恰好落在某个格子上时才可见。于是 3 列网格 + 361dp 的真机上,
 * 手指常常已经不在源卡片上方 —— 用户看到的是"拖了没变"。
 *
 * 它与「松手落地」是**两件独立的事**:浮层只画,不参与命中判定,故不引入
 * "指针微动反复换位"(HANGOFF §12.5 结论 2 实测否决的是**实时让位**,不是浮层)。
 *
 * ## 归档原型的可参考之处
 *
 * Flutter 原型用 `childWhenDragging` + `Opacity(0.35)` 表示源位、由外层 `LayoutBuilder`
 * 把 `Size(maxWidth-6, maxHeight-6)` 传给浮层(默认回退 `150×190`)。
 * 这里取同一思路:源卡淡化由调用方负责,浮层尺寸**取被拖格的实际尺寸**
 * (见 [sizeInRoot]),不写死常数。
 *
 * ## 语义
 *
 * 浮层是**纯装饰**:内容被 [clearAndSetSemantics] 屏蔽,故不会让
 * `onNodeWithText(fileName)` 在拖拽中匹配到两个节点;只有本节点带
 * [TAG_DRAG_GHOST],供测试定位。
 *
 * @param pointerInRoot 当前指针的**根坐标**(与 `slotBounds` 同一坐标系)。
 * @param containerInRoot 浮层所在容器的左上角根坐标 —— 两者相减得到容器内局部坐标,
 *   避免把根坐标当成 `Modifier.offset` 使用(那会随容器位置整体偏移)。
 * @param sizeInRoot 浮层尺寸,取**被拖格的实际 bounds 尺寸**(像素)。
 */
@Composable
fun DragGhost(
    item: SourceItem,
    pointerInRoot: Offset,
    containerInRoot: Offset,
    sizeInRoot: Size,
    style: DragGhostStyle,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val w = with(density) { sizeInRoot.width.toDp() }
    val h = with(density) { sizeInRoot.height.toDp() }
    if (sizeInRoot.width <= 0f || sizeInRoot.height <= 0f) return

    Box(
        modifier = modifier
            .size(w, h)
            .graphicsLayer {
                // 以指针为中心(与归档原型一致);`size` 是图层自身的像素尺寸。
                translationX = pointerInRoot.x - containerInRoot.x - size.width / 2f
                translationY = pointerInRoot.y - containerInRoot.y - size.height / 2f
                // 轻微放大 + 投影,读作"抬起来了"
                scaleX = 1.05f
                scaleY = 1.05f
                this.alpha = 0.92f
            },
    ) {
        // ⚠️ tag 必须挂在本层(`graphicsLayer` 的**子**节点)而不是挂在上面的图层节点上:
        // 语义树的 `boundsInRoot` **不计入节点自身**的 `graphicsLayer`/`offset`/`padding`,
        // 但**计入祖先**的图层变换(实测见 `DragGhostBoundsTest`)。
        // 挂错位置会让"浮层是否跟手"在离线断言里恒真 ⇒ 断言无杀伤力。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(TAG_DRAG_GHOST),
        ) {
            Box(modifier = Modifier.clearAndSetSemantics { }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    when (style) {
                        DragGhostStyle.Card -> Column(modifier = Modifier.fillMaxWidth()) {
                            ItemThumb(
                                item = item,
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

                        DragGhostStyle.Row -> Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ItemThumb(
                                item = item,
                                modifier = Modifier.size(44.dp),
                                showIndex = false,
                            )
                            Text(
                                text = item.fileName,
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
