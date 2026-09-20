package com.pixfold.d1.ui.theme

import androidx.compose.ui.graphics.Color
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
 * 这些用例是纯 JVM 计算,不需要 Robolectric。
 */
class ColorSchemesTest {

    private val seed = 0xFF3F51B5.toInt()

    private fun primary(style: ColorStyle, spec: ColorSpecVersion = ColorSpecVersion.V2021, dark: Boolean = false) =
        ColorSchemes.fromSeed(seed, dark, style, spec).primary

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
}
