package com.pixfold.d1.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

const val TAG_PIN_TOGGLE = "pin-toggle"

/** 图钉按钮的 testTag(按条目区分,便于测试点中具体某张)。 */
fun pinTag(id: String): String = "pin-$id"

/**
 * 图钉开关(验收第 6 项)。
 *
 * **为什么自绘图钉而不是用 ★/emoji**(2026-09-17 用户指出):
 * - 五角星(★)在通用语义里是**收藏/评分**,而本功能是**固定位置**;
 *   用户会带着"这是收藏"的错误预期去点 —— 属语义错误,不是审美偏好。
 * - emoji 图钉(📌/📍)虽然语义正确,但 **emoji 不接受 `color` 染色**,
 *   无法用 M3 语义色表达"已固定 / 未固定"两态。
 * - 工程约束"零三方依赖"⇒ 不用 `material-icons`。
 * 所以自绘一个**可染色的图钉**,同时满足语义正确与 M3 角色配色
 * (与 `ProceduralThumb` 的自绘思路一致)。
 */
@Composable
fun PinToggle(
    isPinned: Boolean,
    itemName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    tag: String = TAG_PIN_TOGGLE,
) {
    val container = if (isPinned) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    }
    // 未固定:用 onSurfaceVariant(描边风格);已固定:白色实心(在 primary 底上对比清晰)
    val pinColor = if (isPinned) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .size(size)
            .testTag(tag)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (isPinned) "$itemName 已固定" else "$itemName 固定位置"
            },
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(size * 0.55f)) {
                drawPin(color = pinColor, filled = isPinned)
            }
        }
    }
}

/**
 * 画一个图钉:圆形钉帽 + 下方收窄的钉身 + 尖端。
 *
 * 参数化 `filled`:未固定画**描边轮廓**(意图"还没钉"),已固定画**实心**(意图"已钉住"),
 * 这样两态在单色下也能区分(不仅靠底色)。
 */
internal fun DrawScope.drawPin(color: Color, filled: Boolean) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // 钉帽:占上方约 55%,略微横向压扁以贴近图钉观感
    val headW = w * 0.92f
    val headH = h * 0.52f
    val headRect = androidx.compose.ui.geometry.Rect(
        left = (w - headW) / 2f,
        top = 0f,
        right = (w + headW) / 2f,
        bottom = headH,
    )

    // 钉身:从钉帽下缘收窄到尖端
    val bodyTop = headH * 0.92f
    val tipY = h
    val bodyHalfTop = w * 0.30f
    val body = Path().apply {
        moveTo(w / 2f - bodyHalfTop, bodyTop)
        lineTo(w / 2f + bodyHalfTop, bodyTop)
        lineTo(w / 2f, tipY)
        close()
    }

    if (filled) {
        drawOval(color = color, topLeft = headRect.topLeft, size = headRect.size)
        drawPath(body, color = color)
    } else {
        val stroke = Stroke(width = (w * 0.14f).coerceAtLeast(1.5f))
        drawOval(
            color = color,
            topLeft = headRect.topLeft,
            size = headRect.size,
            style = stroke,
        )
        // 钉身**只画两条斜边、不画横边** —— 否则 closed path 的顶边会在钉帽下缘
        // 拉出一条横线,观感不像图钉(实心态无此问题)。
        drawLine(
            color = color,
            start = Offset(w / 2f - bodyHalfTop, bodyTop),
            end = Offset(w / 2f, tipY),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = color,
            start = Offset(w / 2f + bodyHalfTop, bodyTop),
            end = Offset(w / 2f, tipY),
            strokeWidth = stroke.width,
        )
    }
}
