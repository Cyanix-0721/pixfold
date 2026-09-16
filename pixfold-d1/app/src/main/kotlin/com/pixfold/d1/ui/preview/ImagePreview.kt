package com.pixfold.d1.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.ui.components.ProceduralThumb

const val TAG_PREVIEW = "preview-root"
const val TAG_PREVIEW_IMAGE = "preview-image"
const val TAG_PREVIEW_PAGE_LABEL = "preview-page-label"
const val TAG_PREVIEW_NEXT = "preview-next"
const val TAG_PREVIEW_PREV = "preview-prev"
const val TAG_PREVIEW_CLOSE = "preview-close"

/** 用于可测的状态描述:把当前缩放写成可断言的语义值(避免只测内部数据)。 */
fun zoomDescription(scale: Float): String = "缩放 ${"%.2f".format(scale)}"

/** 双击放大的目标倍率。 */
private const val DOUBLE_TAP_SCALE = 2f

/**
 * 大图预览(验收第 2 项):自适应窗口、可缩放、可拖动平移、可翻页。
 *
 * 实现要点:
 *  - 缩放/平移**自研**(`detectTransformGestures` / `detectTapGestures`),不引入三方库;
 *  - 图片占满可用空间(`fillMaxSize`)即"自适应窗口";
 *  - 缩放的当前值通过 `stateDescription` 暴露,使 UI 测试能断言**界面真的变了**;
 *  - 消费系统栏 inset(P1 真机教训)。
 */
@Composable
fun ImagePreview(
    items: List<SourceItem>,
    initialIndex: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("没有可预览的图片", color = Color.White)
        }
        return
    }

    var state by remember { mutableStateOf(previewStateOf(initialIndex, items.size)) }
    val current = items[state.index.coerceIn(0, items.size - 1)]

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_PREVIEW)
            .background(Color.Black),
    ) {
        // 图片区:占满可用空间 = 自适应窗口;手势自研。
        // 预览显示文件名(去掉扩展名),便于确认"当前看的是哪一张"。
        ProceduralThumb(
            seed = current.seed,
            fileName = current.fileName,
            showIndex = true,
            captionMaxChars = 28,
            modifier = Modifier
                .fillMaxSize()
                .testTag(TAG_PREVIEW_IMAGE)
                .graphicsLayer(
                    scaleX = state.scale,
                    scaleY = state.scale,
                    translationX = state.offsetX,
                    translationY = state.offsetY,
                )
                .semantics { stateDescription = zoomDescription(state.scale) }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        state = if (zoom != 1f) state.zoomed(zoom) else state
                        if (state.scale > MIN_SCALE && (pan.x != 0f || pan.y != 0f)) {
                            state = state.panned(pan.x, pan.y)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            state = if (state.scale > MIN_SCALE) {
                                state.resetZoom()
                            } else {
                                state.copy(scale = DOUBLE_TAP_SCALE)
                            }
                        },
                    )
                },
        )

        // 顶部:页码 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.pageLabel,
                modifier = Modifier.testTag(TAG_PREVIEW_PAGE_LABEL),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.testTag(TAG_PREVIEW_CLOSE).semantics { contentDescription = "关闭预览" },
            ) {
                Text("关闭", color = Color.White)
            }
        }

        // 底部:上一页 / 缩放提示 / 下一页
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "双击放大 · 双指拖动平移",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = { state = state.previous() },
                    enabled = state.hasPrevious,
                    modifier = Modifier.testTag(TAG_PREVIEW_PREV).semantics { contentDescription = "上一页" },
                ) { Text("上一页") }
                Button(
                    onClick = { state = state.next() },
                    enabled = state.hasNext,
                    modifier = Modifier.testTag(TAG_PREVIEW_NEXT).semantics { contentDescription = "下一页" },
                ) { Text("下一页") }
            }
        }
    }
}
