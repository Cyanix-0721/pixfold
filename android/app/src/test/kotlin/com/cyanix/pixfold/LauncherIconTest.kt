package com.cyanix.pixfold

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 启动器图标(`@mipmap/ic_launcher`)的**结构与真实可绘制性**守护(2026-09-22 补, 2026-11-27 随 M3E 正式图标升级)。
 *
 * **为什么值得单独守护**:图标是**唯一一类"坏掉也全绿"的资源** ——
 * 它不出现在任何 Compose 语义树里,没有 `testTag` 可查,单元测试与 UI 测试都不会碰到它;
 * 真机上也只有在桌面翻到该应用时才看得见。历史上本仓库已经踩过一次同类坑:
 * `ic_arrow_drop_up` 因坐标系错误**画到画布外**(见 [com.cyanix.pixfold.ui.organize.DirectionIconRenderTest]),
 * 语义层断言全绿、图标却完全空白。
 *
 * **本测试钉住五件事**:
 *  1. `@mipmap/ic_launcher` 解析出的是**三层自适应图标**
 *     (background / foreground / **monochrome**)—— `monochrome` 正是"主题化图标"
 *     (Android 13+ 跟随壁纸取色)的开关,缺了它这个功能就没有;
 *  2. 前景层已摆脱纯白占位,具备至少 4 种不透明真实配色;
 *  3. 单色层与前景层外包围盒对齐(四边误差 ≤ 2px),且全为纯白蒙版并通过负形镂空体现内部细节(`monoN < fgN`);
 *  4. 图形完整落在**可见遮罩 `[18,90]` 与 `66dp` 圆形安全区(`r <= 33.5`)内**,不会被厂商圆/方遮罩裁边;
 *  5. 底色配置与实际渲染在**亮/暗两档确实不同**,且暗档平均亮度更低。
 *
 * **2026-09-22 变更**:minSdk 由 24 提到 **30**(用户裁决),自适应图标(API 26 引入)
 * 从此是唯一一档 —— 原先 `res/mipmap-anydpi/ic_launcher.xml` 里那个"48dp 普通 vector
 * 回退档"及其源码结构断言已**一并删除**;三层 `<adaptive-icon>` 也从
 * `mipmap-anydpi-v26/` 移入无版本限定符的 `mipmap-anydpi/`
 * (否则 lint 报 `UnnecessaryQualifier` 而本项目 `warningsAsErrors = true` 直接失败)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {

    private companion object {
        /** 自适应图标的规范视口。 */
        const val VIEWPORT = 108

        /** 可见遮罩内边界:108 视口四边各留 18dp,可绘制区为 [18, 90]。 */
        const val MASK_MIN = 18
        const val MASK_MAX = 90

        /** 66dp 圆形安全区半径(含 0.5dp 光栅化取整容差),圆心位于 53.5。 */
        const val SAFE_CIRCLE_RADIUS = 33.5

        const val DAY = 0xFF3F51B5.toInt()   // Indigo 500
        const val NIGHT = 0xFF1A237E.toInt() // Indigo 900
    }

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private fun render(d: Drawable, size: Int = VIEWPORT): Bitmap {
        d.setBounds(0, 0, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        d.draw(canvas)
        return bmp
    }

    private fun load(resId: Int, c: Context = ctx()): Drawable =
        ContextCompat.getDrawable(c, resId) ?: throw AssertionError("资源无法加载: $resId")

    private fun paintedCount(bmp: Bitmap): Int {
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            if (Color.alpha(bmp.getPixel(x, y)) > 0) n++
        }
        return n
    }

    /** 已绘制像素的包围盒,空图返回 null。 */
    private fun paintedBounds(bmp: Bitmap): IntArray? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            if (Color.alpha(bmp.getPixel(x, y)) > 0) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        return if (maxX < minX) null else intArrayOf(minX, minY, maxX, maxY)
    }

    private fun avgLuminance(bmp: Bitmap): Double {
        var total = 0.0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            total += Color.luminance(bmp.getPixel(x, y)).toDouble()
        }
        return total / (bmp.width * bmp.height)
    }

    @Test
    fun `the launcher icon resolves to a three layer adaptive icon`() {
        val d = load(R.mipmap.ic_launcher)
        assertTrue(
            d is AdaptiveIconDrawable,
            "API 26+ 上 @mipmap/ic_launcher 应是 AdaptiveIconDrawable,实际是 ${d.javaClass.name} —— " +
                "若退回普通 vector,说明 mipmap-anydpi-v26/ic_launcher.xml 丢失或未被 aapt2 采纳。",
        )
        assertTrue(d.background != null, "缺少 background 层")
        assertTrue(d.foreground != null, "缺少 foreground 层")
        assertTrue(
            d.monochrome != null,
            "缺少 monochrome 层 —— 这是「主题化图标」(Android 13+ 跟随壁纸取色)的唯一开关;" +
                "只做彩色两层的话,系统取色无从下手。",
        )
    }

    @Test
    fun `the foreground uses multiple colors instead of a monochrome placeholder`() {
        val adaptive = load(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val fg = render(adaptive.foreground!!)
        val distinctColors = buildSet {
            for (y in 0 until fg.height) for (x in 0 until fg.width) {
                val px = fg.getPixel(x, y)
                if (Color.alpha(px) >= 200) {
                    add(px and 0x00FFFFFF)
                }
            }
        }
        assertTrue(
            distinctColors.size >= 4,
            "前景层仅有 ${distinctColors.size} 种不透明配色 —— 应使用多色 M3E 分层图标而非单色占位块",
        )
    }

    @Test
    fun `the monochrome layer stays aligned with the foreground glyph and carves internal detail`() {
        val adaptive = load(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val fg = render(adaptive.foreground!!)
        val mono = render(adaptive.monochrome!!)

        val fgN = paintedCount(fg)
        val monoN = paintedCount(mono)
        assertTrue(fgN > 0, "前景层一个像素都没画 —— 图形可能被画到视口外")
        assertTrue(monoN > 0, "单色层一个像素都没画 —— 图形可能被画到视口外")
        assertTrue(
            monoN < fgN,
            "前景层画了 $fgN 像素、单色层画了 $monoN 像素 —— 单色层应通过内部负形镂空表达细节而非整块实心或单色复制。",
        )

        val fgB = paintedBounds(fg)!!
        val monoB = paintedBounds(mono)!!
        for (i in 0 until 4) {
            assertTrue(
                abs(fgB[i] - monoB[i]) <= 2,
                "前景层包围盒 ${fgB.toList()} 与单色层 ${monoB.toList()} 对齐偏差超过 2px (index=$i)",
            )
        }

        for (y in 0 until mono.height) for (x in 0 until mono.width) {
            val px = mono.getPixel(x, y)
            if (Color.alpha(px) >= 200) {
                assertEquals(
                    0x00FFFFFF,
                    px and 0x00FFFFFF,
                    "单色层像素 ($x,$y) 不是纯白 #FFFFFF: 0x${Integer.toHexString(px)}",
                )
            }
        }
    }

    @Test
    fun `the glyph stays inside the visible mask`() {
        val adaptive = load(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val fg = render(adaptive.foreground!!)
        val b = paintedBounds(fg)
            ?: throw AssertionError("前景层为空,无法判断是否落在遮罩内")

        // 图形必须整体落在 [18,90] 内,否则会被各厂商的圆形/方形遮罩切掉边角。
        assertTrue(
            b[0] >= MASK_MIN && b[1] >= MASK_MIN && b[2] <= MASK_MAX && b[3] <= MASK_MAX,
            "图形包围盒 (l=${b[0]}, t=${b[1]}, r=${b[2]}, b=${b[3]}) 超出了可见遮罩 " +
                "[$MASK_MIN, $MASK_MAX] —— 四边各 18dp 是留给厂商遮罩与视差的,越界会被裁掉。",
        )
        // 但也别太小:自适应图标规范建议 logo 占 48dp 左右。
        assertTrue(
            b[2] - b[0] >= 40,
            "图形宽仅 ${b[2] - b[0]}px(108 视口),偏小 —— 规范建议 logo 约 48dp",
        )
        // 严格守住 66dp 圆形安全区,确保纯圆遮罩桌面不裁猫耳和书签角。
        for (y in 0 until fg.height) for (x in 0 until fg.width) {
            if (Color.alpha(fg.getPixel(x, y)) >= 128) {
                val dist = hypot(x - 53.5, y - 53.5)
                assertTrue(
                    dist <= SAFE_CIRCLE_RADIUS,
                    "前景像素 ($x,$y) 距中心距离 ${"%.2f".format(dist)} 超出 66dp 圆形安全区半径 $SAFE_CIRCLE_RADIUS",
                )
            }
        }
    }

    @Test
    fun `the composite icon paints pixels`() {
        val n = paintedCount(render(load(R.mipmap.ic_launcher)))
        assertTrue(
            n > VIEWPORT * VIEWPORT / 2,
            "合成后的自适应图标只画了 $n 个非透明像素 —— 底色应当铺满整张画布",
        )
    }

    @Test
    fun `the background is lighter in day mode and darker at night`() {
        val day = ctx().getColor(R.color.ic_launcher_background)
        assertEquals(DAY, day, "浅色档底色应为 Indigo 500 (#3F51B5)")

        val base = ctx().resources.configuration
        val nightCfg = Configuration(base).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
        val nightCtx = ctx().createConfigurationContext(nightCfg)
        val night = nightCtx.getColor(R.color.ic_launcher_background)

        assertEquals(NIGHT, night, "深色档底色应为 Indigo 900 (#1A237E)")
        assertNotEquals(day, night, "亮/暗两档底色不应相同 —— values-night/colors.xml 未被采纳")
        assertTrue(
            Color.luminance(night) < Color.luminance(day),
            "深色档底色的亮度应低于浅色档(实际 day=${Color.luminance(day)}, night=${Color.luminance(night)})",
        )

        val dayBg = render((load(R.mipmap.ic_launcher) as AdaptiveIconDrawable).background!!)
        val nightBg = render((load(R.mipmap.ic_launcher, nightCtx) as AdaptiveIconDrawable).background!!)
        val dayAvg = avgLuminance(dayBg)
        val nightAvg = avgLuminance(nightBg)
        assertTrue(
            nightAvg < dayAvg,
            "深色模式渲染底图平均亮度 ($nightAvg) 应低于浅色模式 ($dayAvg)",
        )
    }

    /**
     * 2026-09-22 起新增用例必须注意:minSdk = 30 后**只有一档**资源,
     * 且它就落在无版本限定符的 `mipmap-anydpi/`。
     *
     * 原先这里有一条 `the pre api 26 fallback is a plain opaque vector`,用源码结构断言
     * (而非渲染)守护 `mipmap-anydpi/ic_launcher.xml` 里那个 48dp 普通 vector 回退档 ——
     * 因为 Robolectric 渲染 API 24 需拉取 `android-all` 运行时 jar,而本机缓存只有 SDK 34
     * 那份、Maven 侧 TLS 握手被拦(`SSLHandshakeException`),`@Config(sdk = [24])` 必然报
     * "Failed to fetch maven artifact"。**该回退档已随 minSdk 提到 30 而整体删除,用例一并移除。**
     */
}
