package com.pixfold.d1.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

const val TAG_SCROLL_TO_TOP = "scroll-to-top"

/**
 * "回到顶部"按钮(W2,用户 2026-09-17 提出)。
 *
 * 需求:网格或列表**产生下滑**时,在**底部右下角**实时显示;回到顶部后隐藏。
 *
 * 位置取**右下角**而非底部正中:拇指易达,且避开底部系统手势条与页面主操作。
 * 箭头**自绘**(零三方依赖 —— 不引入 `material-icons`;与 `PinToggle`、`ProceduralThumb` 一致)。
 *
 * 由调用方决定可见性(用滚动状态判断),本组件只负责外观与点击;
 * [visible] 用淡入淡出,避免滚动时突兀闪烁。
 */
@Composable
fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier
                    .padding(16.dp)
                    .size(48.dp) // M3 触控下限
                    .testTag(TAG_SCROLL_TO_TOP)
                    .semantics { contentDescription = "回到顶部" },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                val arrowColor = MaterialTheme.colorScheme.onSecondaryContainer
                Canvas(modifier = Modifier.size(20.dp)) {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@Canvas
                    val stroke = Stroke(
                        width = (w * 0.13f).coerceAtLeast(1.5f),
                        cap = StrokeCap.Round,
                    )
                    // 向上箭头:两条斜线 + 一条竖线
                    val tip = Offset(w / 2f, h * 0.16f)
                    drawLine(
                        color = arrowColor,
                        start = Offset(w * 0.16f, h * 0.44f),
                        end = tip,
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = arrowColor,
                        start = Offset(w * 0.84f, h * 0.44f),
                        end = tip,
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = arrowColor,
                        start = Offset(w / 2f, h * 0.30f),
                        end = Offset(w / 2f, h * 0.86f),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
