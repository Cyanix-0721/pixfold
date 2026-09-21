package com.pixfold.d1.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pixfold.d1.domain.settings.ColorSpecVersion
import com.pixfold.d1.domain.settings.ColorStyle
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 配色生成的**非空转**验证。
 *
 * 只说"能生成配色"是不够的 —— 必须证明**参数真的在起作用**:
 * 换色彩风格/色彩标准真的会改变输出。否则开关就是假的(界面能点、结果不变)。
 *
 * **2026-09-21 补**:此前"色彩标准"这一路其实**没有被证明接上过**(详见
 * `color spec version really re-tones the scheme` 的说明);同时实测发现该开关
 * 对 9 个色彩风格中的 5 个是无操作的。
 *
 * 这些用例是纯 JVM 计算,不需要 Robolectric。
 */
class ColorSchemesTest {

    private val seed = 0xFF3F51B5.toInt()

    private fun primary(style: ColorStyle, spec: ColorSpecVersion = ColorSpecVersion.V2021, dark: Boolean = false) =
        ColorSchemes.fromSeed(seed, dark, style, spec).primary

    /**
     * 把配色摊平成可逐项比较的 ARGB 序列(48 个角色)。
     *
     * ⚠️ **必须用 `toArgb()` 而不是 `Color.value`**:`value` 是 `ULong`,低 32 位是
     * **色彩空间 id** 而不是 ARGB,`value.toInt()` 会把任何两个 sRGB 颜色都算成相等。
     * 2026-09-21 写本组用例时第一版探针正是栽在这里 —— 它报告"2021 与 2025 逐字节相同",
     * 与事实相反。凡按颜色值做断言的测试都须用 `toArgb()`。
     */
    private fun argbRoles(s: ColorScheme): List<Int> = listOf(
        s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.inversePrimary,
        s.secondary, s.onSecondary, s.secondaryContainer, s.onSecondaryContainer,
        s.tertiary, s.onTertiary, s.tertiaryContainer, s.onTertiaryContainer,
        s.background, s.onBackground, s.surface, s.onSurface, s.surfaceVariant, s.onSurfaceVariant,
        s.surfaceTint, s.inverseSurface, s.inverseOnSurface,
        s.error, s.onError, s.errorContainer, s.onErrorContainer,
        s.outline, s.outlineVariant, s.scrim,
        s.surfaceBright, s.surfaceDim, s.surfaceContainer, s.surfaceContainerHigh,
        s.surfaceContainerHighest, s.surfaceContainerLow, s.surfaceContainerLowest,
        s.primaryFixed, s.primaryFixedDim, s.onPrimaryFixed, s.onPrimaryFixedVariant,
        s.secondaryFixed, s.secondaryFixedDim, s.onSecondaryFixed, s.onSecondaryFixedVariant,
        s.tertiaryFixed, s.tertiaryFixedDim, s.onTertiaryFixed, s.onTertiaryFixedVariant,
    ).map { it.toArgb() }

    /** ARGB -> `FF515B92` 形式(便于失败信息阅读)。 */
    private fun hex(c: Color): String = "%08X".format(c.toArgb())

    private fun diffCount(seedArgb: Int, dark: Boolean, style: ColorStyle): Int {
        val a = argbRoles(ColorSchemes.fromSeed(seedArgb, dark, style, ColorSpecVersion.V2021))
        val b = argbRoles(ColorSchemes.fromSeed(seedArgb, dark, style, ColorSpecVersion.V2025))
        return a.indices.count { a[it] != b[it] }
    }

    @Test
    fun `different color styles produce different primaries`() {
        // 抽三种差别明显的风格比对。若这里相等,说明"色彩风格"开关没有真实效果。
        val tonal = primary(ColorStyle.TonalSpot)
        val vibrant = primary(ColorStyle.Vibrant)
        val mono = primary(ColorStyle.Monochrome)

        assertNotEquals(tonal, vibrant, "鲜艳风格应与标准风格给出不同主色")
        assertNotEquals(tonal, mono, "单色风格应与标准风格不同")
    }

    @Test
    fun `light and dark produce different schemes`() {
        val light = primary(ColorStyle.TonalSpot, dark = false)
        val dark = primary(ColorStyle.TonalSpot, dark = true)
        assertNotEquals(light, dark, "深浅色必须产出不同配色")
    }

    @Test
    fun `all styles and both specs produce complete schemes`() {
        // 覆盖全部组合:不得抛异常,且关键角色都不是 Unspecified(漏映射的表现)。
        //
        // ⚠️ 本用例**证明不了"色彩标准真的接上了算法"** —— 把 `toAlgorithm()` 改成恒返回
        // `SPEC_2021`,它照样通过。真正的接线验证在下面的
        // `color spec version really re-tones the scheme`。
        for (style in ColorStyle.entries) {
            for (spec in ColorSpecVersion.entries) {
                for (dark in listOf(false, true)) {
                    val scheme = ColorSchemes.fromSeed(seed, dark, style, spec)
                    assertTrue(
                        scheme.primary != Color.Unspecified,
                        "$style / $spec / dark=$dark 的 primary 未映射",
                    )
                    assertTrue(
                        scheme.onPrimary != Color.Unspecified,
                        "$style / $spec / dark=$dark 的 onPrimary 未映射",
                    )
                    assertTrue(
                        scheme.background != Color.Unspecified,
                        "$style / $spec / dark=$dark 的 background 未映射",
                    )
                }
            }
        }
    }

    @Test
    fun `error colors stay at material defaults regardless of accent`() {
        // 产品约束:破坏性动作必须醒目。错误色若随主题漂移,警示强度会不稳定。
        val a = ColorSchemes.fromSeed(seed, false, ColorStyle.TonalSpot, ColorSpecVersion.V2021)
        val b = ColorSchemes.fromSeed(0xFF00696D.toInt(), false, ColorStyle.Vibrant, ColorSpecVersion.V2025)
        assertEquals(a.error, b.error, "error 角色不应随强调色/风格变化")
    }

    @Test
    fun `pure black turns every surface container fully black`() {
        val dark = ColorSchemes.fromSeed(seed, true, ColorStyle.TonalSpot, ColorSpecVersion.V2021)
        val black = dark.withPureBlack()

        // 关键:不只是 background —— 容器层级也要压黑,否则会出现"黑底灰卡"的割裂感。
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertEquals(Color.Black, black.surfaceContainer)
        assertEquals(Color.Black, black.surfaceContainerHigh)
        assertEquals(Color.Black, black.surfaceContainerHighest)
        assertEquals(Color.Black, black.surfaceContainerLow)
        assertEquals(Color.Black, black.surfaceContainerLowest)

        // on 系角色不动:保证文字对比度不退化。
        assertEquals(dark.onSurface, black.onSurface, "纯黑不应改动 onSurface")
        assertEquals(dark.onBackground, black.onBackground, "纯黑不应改动 onBackground")
    }

    /**
     * 「色彩标准」开关的**接线验证**(HANGOFF §9.1「后续三步」第 ① 步,2026-09-21)。
     *
     * ## 为什么非补不可
     *
     * 该开关是设置页 4 个外观开关之一,但在此之前**没有任何用例能证明它接上了算法**:
     * 唯一涉及两套规范的 `all styles and both specs produce complete schemes` 只断言
     * "不空转",把 [ColorSchemes] 里的 `toAlgorithm()` 改成恒返回 `SPEC_2021` 它照样全绿
     * —— 即"界面能点、存储能写、结果不变"的假开关也能通过验收。
     *
     * ## 判据来自实测,不是猜的
     *
     * 先跑探针量化(6 个种子 × 深浅两模式 × 9 风格):
     * **TonalSpot / Vibrant / Expressive / Neutral 四个变体最少也有 21/48 个角色变值**
     * (最多 24/48);Monochrome / Fidelity / Content / Rainbow / FruitSalad 恒为 0
     * (原因见下一个用例)。
     *
     * 下界取 **10**:远低于实测最小值 21,留出算法微调余量,但"没接上"(恒为 0)必被抓住。
     *
     * ## 为什么还要钉两个具体色值
     *
     * 计数断言能抓"没接上",**抓不到"接反了"** —— 把 V2021/V2025 的映射对调,
     * 差值仍是 21+。因此再钉一组实测色值(见下方,标注了测量版本):
     * 接反时计数断言通过而本断言失败,失败信息可直接指向接反。
     */
    @Test
    fun `color spec version really re-tones the scheme`() {
        // 12 组组合:6 个种子(含黄/红/灰等不同色相,触发算法里的 isYellow 分支)× 深浅。
        val seeds = listOf(
            0xFF3F51B5.toInt(), // indigo:内置默认强调色
            0xFF1A73E8.toInt(), // blue
            0xFFF9A825.toInt(), // yellow
            0xFFE53935.toInt(), // red
            0xFF00696D.toInt(), // teal
            0xFF808080.toInt(), // gray
        )
        for (style in listOf(
            ColorStyle.TonalSpot,
            ColorStyle.Vibrant,
            ColorStyle.Expressive,
            ColorStyle.Neutral,
        )) {
            for (seedArgb in seeds) {
                for (dark in listOf(false, true)) {
                    val diff = diffCount(seedArgb, dark, style)
                    assertTrue(
                        diff >= 10,
                        "「色彩标准」应实质改变 $style/%08X/dark=%s 的配色,实测仅 %d/48 个角色不同".format(
                            seedArgb,
                            dark,
                            diff,
                        ),
                    )
                }
            }
        }

        // 方向钉死:这两个色值在 materialkolor 5.0.1 上实测得到。
        // 用十六进制字符串比较而不用 Int —— 失败信息里 `#FF515B92 vs #FF545C8C` 一眼可读,
        // 而十进制 `-11445358 vs -11248500` 需要心算才能看出接反。
        // 库升级后若本断言失败,先重新测量色值 —— 并顺便看一眼设置页的观感是否需要复核。
        val light2021 = ColorSchemes.fromSeed(seed, false, ColorStyle.TonalSpot, ColorSpecVersion.V2021)
        val light2025 = ColorSchemes.fromSeed(seed, false, ColorStyle.TonalSpot, ColorSpecVersion.V2025)
        assertEquals("FF515B92", hex(light2021.primary), "2021 的主色实测值变了(可能是 V2021/V2025 接反)")
        assertEquals("FF545C8C", hex(light2025.primary), "2025 的主色实测值变了(可能是 V2021/V2025 接反)")
    }

    /**
     * 「色彩标准」的**覆盖面边界**(实测记录 + 越界预警)。
     *
     * 实测事实(materialkolor 5.0.1,6 种子 × 深浅,48 个角色):SPEC_2025 只重写了
     * Neutral / TonalSpot / Expressive / Vibrant 四个变体的调色板与若干角色定义
     * (`ColorSpec2025 : ColorSpec2021()`,其余分支 `else -> super`),
     * 所以 **Monochrome / Fidelity / Content / Rainbow / FruitSalad 五个风格下,
     * 切换 2021↔2025 完全不改变任何颜色**。
     *
     * 这条断言有两个用处:
     *  1. **产品层面的事实记录** —— 设置页把「色彩标准」做成了全局开关,
     *     而上述 5 个风格下它是无操作的;当前文案未对此作说明(留待用户裁决是否补)。
     *  2. **升级预警** —— 若某天算法库改变覆盖面,这里会失败,提醒重新核对
     *     设置页该开关的说明文字是否仍然成立。
     */
    @Test
    fun `color spec version is a no-op for the five styles it does not re-tone`() {
        for (style in listOf(
            ColorStyle.Monochrome,
            ColorStyle.Fidelity,
            ColorStyle.Content,
            ColorStyle.Rainbow,
            ColorStyle.FruitSalad,
        )) {
            for (seedArgb in listOf(0xFF3F51B5.toInt(), 0xFFF9A825.toInt(), 0xFF1A73E8.toInt())) {
                for (dark in listOf(false, true)) {
                    assertEquals(
                        0,
                        diffCount(seedArgb, dark, style),
                        "$style 实测两套规范应无差异;若失败说明算法库覆盖面变了," +
                            "需复核设置页「色彩标准」的说明文案",
                    )
                }
            }
        }
    }
}
