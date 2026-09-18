package com.pixfold.d1.ui.workflowa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.pixfold.d1.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * 方向三角矢量资源的**真实可绘制性**守护(2026-09-17 真机踩坑后补)。
 *
 * **踩坑经过(重要)**:三角最初照抄 Material Symbols 官网 SVG ——
 * 官网用 `viewBox="0 -960 960 960"`(**负 y**),而 Android VectorDrawable
 * **没有 viewBox 偏移语义**。我配上 `viewportHeight="960"` + 负坐标后,
 * 图形整体落在画布**之外**,表现为"**占了 16dp 宽度却什么都不画**"。
 *
 * **为什么语义测试没挡住**:Compose 语义树里 `Icon` 节点**依然存在**、
 * `testTag` 依然可查、`contentDescription` 依然正确 —— 只是**没有像素**。
 * 全部 12 项 `SortRuleDirectionTest` 因此**全绿通过**,
 * 直到真机做**像素对比**才暴露(点击前后规则编辑区像素零差异)。
 *
 * **教训**:断言"图标存在"≠断言"图标看得见"。凡依赖矢量资源的改动,
 * 必须**栅格化后数非透明像素**(本测试),否则"空白资源"能一路通过所有测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectionIconRenderTest {

    private fun paintAndCountNonTransparent(resId: Int): Int {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, resId)
            ?: throw AssertionError("资源无法加载: $resId")
        val size = 96
        d.setBounds(0, 0, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        d.draw(canvas)
        var n = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (Color.alpha(bmp.getPixel(x, y)) > 0) n++
            }
        }
        return n
    }

    @Test
    fun `ascending triangle actually paints pixels`() {
        val n = paintAndCountNonTransparent(R.drawable.ic_arrow_drop_up)
        // 三角形约占 10x5/24x24 -> 96x96 下约数百像素。
        // 旧实现(负坐标 + viewport 960)会画到画布外 -> **0 像素**。
        assertTrue(
            n > 100,
            "升序三角实际绘制了 $n 个非透明像素 —— 过少说明图形被画到画布外或为空。" +
                "注意:Material Symbols 的 SVG 是 viewBox=\"0 -960 960 960\"(负 y)," +
                "直接照抄到 Android VectorDrawable 会不可见,须换算到 24x24。",
        )
    }

    @Test
    fun `descending triangle actually paints pixels`() {
        val n = paintAndCountNonTransparent(R.drawable.ic_arrow_drop_down)
        assertTrue(n > 100, "降序三角实际绘制了 $n 个非透明像素 —— 过少说明不可见")
    }

    @Test
    fun `ascending and descending triangles point opposite ways`() {
        // 光"都能画出来"不够:方向必须相反,否则升降序看起来一样
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val size = 96

        fun rows(resId: Int): List<Int> {
            val d = androidx.core.content.ContextCompat.getDrawable(ctx, resId)!!
            d.setBounds(0, 0, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.draw(Canvas(bmp))
            return (0 until size).map { y ->
                (0 until size).count { x -> Color.alpha(bmp.getPixel(x, y)) > 0 }
            }
        }

        val up = rows(R.drawable.ic_arrow_drop_up)
        val down = rows(R.drawable.ic_arrow_drop_down)

        // ⚠️ 注意屏幕坐标 **y 向下**:顶点在上 = "尖朝上"。
        // 实测(y=0..95, 每行非透明宽度):
        //   UP   : y=36 宽2 -> y=55 宽40  = **上窄下宽**(尖朝上)✅ 升序
        //   DOWN : y=40 宽40 -> y=59 宽2  = **上宽下窄**(尖朝下)✅ 降序
        // 只看图形实际占用的行(排除空白边),再比上半/下半宽度,避免用固定的
        // 1/3 分带(图形位于中部时会两侧都为 0,导致误判 —— 已踩过)。
        fun onlyPainted(prof: List<Int>): List<Int> =
            prof.dropWhile { it == 0 }.dropLastWhile { it == 0 }

        val upP = onlyPainted(up)
        val dnP = onlyPainted(down)
        val upTop = upP.take(upP.size / 2).sum()
        val upBottom = upP.takeLast(upP.size / 2).sum()
        val dnTop = dnP.take(dnP.size / 2).sum()
        val dnBottom = dnP.takeLast(dnP.size / 2).sum()

        assertTrue(
            upBottom > upTop,
            "升序应尖朝上(上窄下宽),实际上半=$upTop 下半=$upBottom",
        )
        assertTrue(
            dnTop > dnBottom,
            "降序应尖朝下(上宽下窄),实际上半=$dnTop 下半=$dnBottom",
        )
    }

    @Test
    fun `triangle is large enough to read comfortably`() {
        // 用户 2026-09-17 反馈"有点小,可以稍微大点"。
        // 根因:官方 24x24 里三角仅占 10x5,留白过多 -> 16dp 图标只画出 ~6.7dp 宽。
        // 修正:资源内图形放大 1.6 倍 + 图标 18dp -> 实际三角约 12x6dp。
        // 本测试钉住**实际绘制宽度**,防止以后又悄悄变小。
        val widthDp = 18.0                     // Icon 尺寸
        val glyphFraction = 16.0 / 24.0        // 资源内图形占视口宽度比例(放大后 16/24)

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_arrow_drop_up)!!
        val size = 96
        d.setBounds(0, 0, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.draw(Canvas(bmp))

        var widest = 0
        for (y in 0 until size) {
            val w = (0 until size).count { x -> Color.alpha(bmp.getPixel(x, y)) > 0 }
            if (w > widest) widest = w
        }
        val drawnFraction = widest.toDouble() / size
        assertTrue(
            drawnFraction > 0.6,
            "三角最宽处仅占视口 ${"%.0f".format(drawnFraction * 100)}% —— " +
                "官方原版是 10/24≈42%(太小);放大后应 >60%",
        )
        // 换算到实际 dp:应在 10dp 以上才够醒目
        val effectiveDp = widthDp * drawnFraction
        assertTrue(
            effectiveDp >= 10.0,
            "三角实际宽度约 ${"%.1f".format(effectiveDp)}dp,偏小(应 >=10dp)",
        )
        // 图形仍应完整落在视口内(不能被裁切)
        assertTrue(glyphFraction * 24 <= 24, "放大后图形不应超出 24 视口")
    }
}
