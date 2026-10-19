package com.cyanix.pixfold.ui.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * 三角形警示图标(`ic_warning_triangle`)的**真实可绘制性**守护
 * (2026-10-05 用户要求把 `⚠️` emoji 换成矢量图后补)。
 *
 * 与 [com.cyanix.pixfold.ui.workflowa.ExclamationIconRenderTest] 同一教训:
 * 语义层断言「节点存在」**不等于**「图标看得见」,更不等于「图标长得对」。
 * 矢量资源的以下两类错误都能骗过语义测试:
 *  1. 图形被画到画布外(负坐标 / `viewportWidth` 不符)-> **零像素**;
 *  2. 挖空规则写错(如漏了 `fillType="evenOdd"`)-> 只剩**实心三角、感叹号消失**。
 *
 * 故本测试**既数像素(挡 1),也专门检查三角内存在「洞」(挡 2)**。
 *
 * 取样纪律:坐标取 24x24 设计视口,取每格中心像素;且**只挑 alpha 稳定为 0/255 的点**。
 * 本图标的实测 ASCII 图(探针,已删):
 * ```
 * y=2  ...........+............   <- (12,2) 是反锯齿 + ,不可断言
 * y=3  ...........##...........
 * y=12 ......#####..#####......   <- (11,12)(12,12) 是竖笔挖空
 * y=15 ....################....   <- 竖笔与圆点之间的实心窄条
 * y=17 ...########..########...   <- (11,17)(12,17) 是圆点挖空
 * y=20 .######################.   <- 底边整条实心
 * ```
 *
 * pathData = `M1,21h22L12,2 1,21z`(三角) + `M13,14h-2v-4h2v4z`(竖笔 x∈[11,13] y∈[10,14])
 * + `M13,18h-2v-2h2v2z`(圆点 x∈[11,13] y∈[16,18]),靠 `fillType="evenOdd"` 挖空。
 * 探针实测非透明像素 3260/9216。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WarningIconRenderTest {

    private companion object {
        const val SIZE = 96

        /** 24x24 视口 -> 96x96 位图的缩放。 */
        const val SCALE = SIZE / 24
    }

    private fun render(resId: Int): Bitmap {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d = ContextCompat.getDrawable(ctx, resId)
            ?: throw AssertionError("资源无法加载: $resId")
        d.setBounds(0, 0, SIZE, SIZE)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        d.draw(canvas)
        return bmp
    }

    /** 取 24x24 设计坐标 (vx, vy) 所在格子的中心像素是否非透明。 */
    private fun opaque(bmp: Bitmap, vx: Int, vy: Int): Boolean =
        Color.alpha(bmp.getPixel(vx * SCALE + SCALE / 2, vy * SCALE + SCALE / 2)) > 0

    @Test
    fun `icon actually paints pixels`() {
        val bmp = render(R.drawable.ic_warning_triangle)
        var n = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (Color.alpha(bmp.getPixel(x, y)) > 0) n++
            }
        }
        // 实测 3260/9216。若图形被画到画布外(负坐标 / viewport 不符),这里是 0。
        assertTrue(n > 1500, "三角形警示图标只画了 $n 个非透明像素 —— 过少说明图形不可见")
    }

    @Test
    fun `the triangle is filled inside and empty outside`() {
        val bmp = render(R.drawable.ic_warning_triangle)

        // 三角**体内**:顶点下方、底边中点、左右两腰内侧 —— 挡住「整个三角没画出来」
        assertTrue(opaque(bmp, 12, 3), "三角顶点下方 (12,3) 应为实心")
        assertTrue(opaque(bmp, 12, 4), "三角顶点下方 (12,4) 应为实心")
        assertTrue(opaque(bmp, 6, 12), "三角内左侧 (6,12) 应为实心")
        assertTrue(opaque(bmp, 18, 18), "三角内右侧 (18,18) 应为实心")
        assertTrue(opaque(bmp, 12, 20), "三角底边中点 (12,20) 应为实心")

        // 三角**体外**:顶点上方、左右斜边之外 —— 挡住「画成矩形 / 整块填满」
        assertTrue(!opaque(bmp, 12, 1), "顶点之上 (12,1) 应为透明")
        assertTrue(!opaque(bmp, 5, 5), "左上斜边之外 (5,5) 应为透明")
        assertTrue(!opaque(bmp, 2, 12), "三角左侧外部 (2,12) 应为透明")
        assertTrue(!opaque(bmp, 22, 12), "三角右侧外部 (22,12) 应为透明")
    }

    @Test
    fun `the exclamation mark is knocked out of the triangle`() {
        val bmp = render(R.drawable.ic_warning_triangle)

        // 感叹号**竖笔**中段(x∈[11,13] y∈[10,14])-> 透明
        assertTrue(
            !opaque(bmp, 12, 12),
            "感叹号竖笔处 (12,12) 本应是挖空的洞 —— 若为实心,说明感叹号子路径缺失" +
                "或与三角同向填充,图标退化成「只有一个实心三角」",
        )

        // 感叹号**圆点**中段(x∈[11,13] y∈[16,18])-> 透明
        assertTrue(
            !opaque(bmp, 12, 17),
            "感叹号圆点处 (12,17) 本应是挖空的洞",
        )

        // 竖笔与圆点**之间**的实心窄条(y=15)-> 实心,
        // 否则两个子路径连成一根长条,图标不成感叹号。
        assertTrue(
            opaque(bmp, 12, 15),
            "感叹号竖笔与圆点之间 (12,15) 应为实心 —— 二者不该连成一条",
        )
    }
}
