package com.pixfold.d1.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 程序化占位缩略图 —— 由 seed 确定性派生,**不引入任何图片资源或加载库**(零三方依赖)。
 * 语义沿用归档原型的 ProceduralThumb:HSL 取色 + 右下角三角 + 确定性纹样 + 中央序号,
 * 既便于肉眼区分不同图片,也便于测试断言。
 *
 * D1 不连真实文件系统,故无真实缩略图;D2b 起接入 `ContentResolver.loadThumbnail`(§9 D2b)。
 */

/** 色相:seed 取模 360(对负数取绝对值,避免 UI 层 hashCode 为负时出问题)。 */
fun seedHue(seed: Int): Float = (abs(seed) % 360).toFloat()

/** 基础色:HSL(hue, 0.45, 0.55)。 */
fun seedBaseColor(seed: Int): Color = hslToColor(seedHue(seed), 0.45f, 0.55f)

/** 强调色:同色相、明度更低(0.35),用于右下角三角。 */
fun seedAccentColor(seed: Int): Color = hslToColor(seedHue(seed), 0.45f, 0.35f)

/** 纹样线数量:2 + seed % 3。 */
fun seedLineCount(seed: Int): Int = 2 + (abs(seed) % 3)

/** 中央显示的大序号:1 + seed % 99。 */
fun seedDisplayNumber(seed: Int): Int = 1 + (abs(seed) % 99)

/**
 * HSL → Color。自行实现以免依赖不同 Compose 版本对 HSL 转换的可用性差异。
 * @param hue 0..360 度
 * @param saturation 0..1
 * @param lightness 0..1
 */
internal fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val h = (((hue % 360f) + 360f) % 360f) / 60f
    val x = c * (1f - abs(h % 2f - 1f))
    val (r1, g1, b1) = when {
        h < 1f -> Triple(c, x, 0f)
        h < 2f -> Triple(x, c, 0f)
        h < 3f -> Triple(0f, c, x)
        h < 4f -> Triple(0f, x, c)
        h < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = lightness - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

@Composable
fun ProceduralThumb(
    seed: Int,
    modifier: Modifier = Modifier,
    showIndex: Boolean = true,
) {
    val base = seedBaseColor(seed)
    val accent = seedAccentColor(seed)
    val measurer = rememberTextMeasurer()
    val lines = seedLineCount(seed)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        drawRect(color = base, size = Size(w, h))

        // 右下角三角(模拟"照片"占位样式)
        val tri = Path().apply {
            moveTo(w, h * 0.35f)
            lineTo(w, h)
            lineTo(w * 0.45f, h)
            close()
        }
        drawPath(tri, color = accent.copy(alpha = 0.8f))

        // 确定性纹样,便于肉眼区分不同图片
        val lineColor = Color.White.copy(alpha = 0.25f)
        val maxDx = (w * 0.8f).coerceAtLeast(1f)
        for (i in 1..lines) {
            val dx = (((seed shr i) and 0x7fffffff) % maxDx.toInt().coerceAtLeast(1)).toFloat()
            drawLine(
                color = lineColor,
                start = Offset(dx, 0f),
                end = Offset(dx + 10f, h),
                strokeWidth = 1.5f,
            )
        }

        if (showIndex) {
            val layout = measurer.measure(
                text = seedDisplayNumber(seed).toString(),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = (h * 0.38f).coerceAtLeast(10f).sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset((w - layout.size.width) / 2f, (h - layout.size.height) / 2f),
            )
        }
    }
}
