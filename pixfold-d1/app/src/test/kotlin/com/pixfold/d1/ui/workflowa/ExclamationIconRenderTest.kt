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
 * 圆形感叹号图标(`ic_exclamation_circle`)的**真实可绘制性**守护
 * (2026-09-18 用户要求把"自动清洗"说明改为该图标承载后补)。
 *
 * 与 [DirectionIconRenderTest] 同一教训:语义层断言"节点存在"**不等于**"图标看得见",
 * 更不等于"图标长得对"。矢量资源的以下两类错误都能骗过语义测试:
 *  1. 图形被画到画布外(负坐标 / viewport 不符)-> **零像素**;
 *  2. 挖空规则写错(如漏了 `fillType="evenOdd"`)-> 只剩**实心圆、感叹号消失**。
 *
 * 故本测试**既数像素(挡 1),也专门检查圆内存在"洞"(挡 2)**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExclamationIconRenderTest {

    private companion object {
        const val SIZE = 96
        /** 24x24 视口 -> 96x96 位图的缩放。 */
        const val SCALE = SIZE / 24
    }

    private fun render(resId: Int): Bitmap {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, resId)
            ?: throw AssertionError("资源无法加载: $resId")
        d.setBounds(0, 0, SIZE, SIZE)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        d.draw(canvas)
        return bmp
    }

    private fun opaque(bmp: Bitmap, vx: Int, vy: Int): Boolean =
        Color.alpha(bmp.getPixel(vx * SCALE + SCALE / 2, vy * SCALE + SCALE / 2)) > 0

    @Test
    fun `icon actually paints pixels`() {
        val bmp = render(R.drawable.ic_exclamation_circle)
        var n = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (Color.alpha(bmp.getPixel(x, y)) > 0) n++
            }
        }
        // 直径 20/24 的实心圆在 96x96 下约 5000+ 像素(减去感叹号的洞)。
        // 若图形被画到画布外(负坐标等),这里是 0。
        assertTrue(n > 3000, "圆形感叹号只画了 $n 个非透明像素 —— 过少说明图形不可见")
    }

    @Test
    fun `the exclamation mark is knocked out of the circle`() {
        val bmp = render(R.drawable.ic_exclamation_circle)

        // 圆内、且**不属于**感叹号的位置 -> 应为实心(不透明)
        assertTrue(
            opaque(bmp, 6, 12),
            "圆心左侧(x=6,y=12)应被圆填充 —— 否则整个圆都是空的",
        )

        // 感叹号**竖笔**位置(设计坐标 x∈[11,13], y∈[6,14])-> 应为**透明**
        assertTrue(
            !opaque(bmp, 12, 10),
            "感叹号竖笔处(x=12,y=10)本应是挖空的洞 —— 若为实心," +
                "说明感叹号子路径缺失或与圆同向填充,图标退化成「只有一个实心圆」",
        )

        // 感叹号**圆点**位置(设计坐标 x∈[11,13], y∈[16,18])-> 也应为透明
        assertTrue(
            !opaque(bmp, 12, 17),
            "感叹号圆点处(x=12,y=17)本应是挖空的洞",
        )
    }

    @Test
    fun `the two knocks are separated by a filled gap`() {
        // 竖笔与圆点之间(y=15 附近)应仍是实心 —— 保证感叹号是"一竖 + 一点",
        // 而不是从上到下一条贯通的长缝(那样看起来像被劈开的圆)。
        val bmp = render(R.drawable.ic_exclamation_circle)
        assertTrue(
            opaque(bmp, 12, 15),
            "竖笔与圆点之间(x=12,y=15)应为实心,以区分一竖与一点",
        )
    }
}
