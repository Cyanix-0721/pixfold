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
 * 空心禁止图标(`ic_forbidden`)的**真实可绘制性**守护
 * (2026-10-05 用户要求把 `⛔` emoji 换成矢量图后补)。
 *
 * 与 [WarningIconRenderTest] 同一教训:语义层断言「节点存在」不等于「图标看得见」,
 * 更不等于「图标长得对」。本图标最容易被写坏的两种方式:
 *  1. 图形画到画布外 -> **零像素**;
 *  2. 挖空规则写错(漏 `fillType="evenOdd"`)或斜杠子路径缺失 ->
 *     **退化成实心圆盘 / 空心圈**,而「节点存在」的语义断言完全看不出来。
 *
 * 故本测试检查三件事:有像素、**圈内是空的**、**斜杠在**。
 *
 * 取样纪律:坐标取 24x24 设计视口,取每格中心像素;只挑 alpha 稳定为 0/255 的点。
 * 实测 ASCII 图(探针,已删;`#`=实心 `.`=空 `+`=反锯齿):
 * ```
 * y=2  ........+######+........   顶边实心
 * y=4  .....###+......###+.....   圈内空
 * y=8  ..+#+.........##+..##...   上面这一段右侧两个 # 是斜杠
 * y=12 ..##......##+.......##..   左边 ## 是圈,中间 ## 是斜杠
 * y=18 ....+##+........+##+....   左下这一段 ## 是斜杠汇入圈
 * y=20 .......##########.......   底边实心
 * ```
 * 结论:圆环 + 一条自左下到右上的斜杠;`(12,12)` 落在斜杠边缘(反锯齿 `+`),
 * **不可**用作断言点 —— 故「圈内空」改用 `(12,5)`/`(5,12)`/`(18,12)`/`(12,18)`。
 *
 * pathData 见 `res/drawable/ic_forbidden.xml`(Material Symbols `block`,Apache-2.0,
 * 已在 [com.cyanix.pixfold.ui.settings.LicensesScreen] 声明)。探针实测非透明像素 2598/9216。
 *
 * 与 [WarningIconRenderTest] 的分工:**实心警告 vs 空心禁止** ——
 * 二者形状不同,而非只靠颜色区分(色盲用户同样能分辨;与 `PinToggle` 的实心/空心同判)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForbiddenIconRenderTest {

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
        val bmp = render(R.drawable.ic_forbidden)
        var n = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (Color.alpha(bmp.getPixel(x, y)) > 0) n++
            }
        }
        // 实测 2598/9216。若图形被画到画布外(负坐标 / viewport 不符),这里是 0。
        assertTrue(n > 1200, "禁止图标只画了 $n 个非透明像素 —— 过少说明图形不可见")
    }

    @Test
    fun `the ring is solid at the four cardinal points and empty at the corners`() {
        val bmp = render(R.drawable.ic_forbidden)

        // 圆环四端:上、下、左、右 —— 挡住「整个圆没画出来」
        assertTrue(opaque(bmp, 12, 2), "圆环顶边 (12,2) 应为实心")
        assertTrue(opaque(bmp, 12, 20), "圆环底边 (12,20) 应为实心")
        assertTrue(opaque(bmp, 2, 12), "圆环左边 (2,12) 应为实心")
        assertTrue(opaque(bmp, 21, 12), "圆环右边 (21,12) 应为实心")

        // 四角:圆外 —— 挡住「画成矩形 / 整块填满」
        assertTrue(!opaque(bmp, 1, 1), "圆外左上角 (1,1) 应为透明")
        assertTrue(!opaque(bmp, 22, 22), "圆外右下角 (22,22) 应为透明")
        assertTrue(!opaque(bmp, 12, 0), "圆外顶部 (12,0) 应为透明")
    }

    @Test
    fun `the inside of the ring is hollow, not a filled disc`() {
        val bmp = render(R.drawable.ic_forbidden)

        // 圈内四象限各取一点(避开斜杠经过的位置)-> 全应为透明。
        // 漏写 fillType 时圆盘会整体填满,这四点立刻变实心。
        assertTrue(!opaque(bmp, 12, 5), "圈内上部 (12,5) 应为透明 —— 否则是实心圆盘")
        assertTrue(!opaque(bmp, 5, 12), "圈内左部 (5,12) 应为透明")
        assertTrue(!opaque(bmp, 18, 12), "圈内右部 (18,12) 应为透明")
        assertTrue(!opaque(bmp, 12, 18), "圈内下部 (12,18) 应为透明")
    }

    @Test
    fun `the diagonal slash crosses the ring`() {
        val bmp = render(R.drawable.ic_forbidden)

        // 斜杠自左下到右上穿过圈内:取三点,分别落在右上、中、左下。
        // 若斜杠子路径缺失,这三点会变透明 —— 图标退化成普通的「空心圈」。
        assertTrue(
            opaque(bmp, 14, 8),
            "斜杠右上段 (14,8) 应为实心 —— 若为透明说明斜杠没画出来,图标退化成空心圈",
        )
        assertTrue(opaque(bmp, 11, 12), "斜杠中段 (11,12) 应为实心")
        assertTrue(opaque(bmp, 9, 14), "斜杠左下段 (9,14) 应为实心")
    }
}
