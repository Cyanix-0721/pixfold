package com.pixfold.d1.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

    /**
     * 跨色相的测试种子集:含黄 / 红 / 灰,以命中算法里的 `isYellow()` 等分支,
     * 避免"只测一个种子恰好通过"。
     */
    private val seeds = listOf(
        0xFF3F51B5.toInt(), // indigo:内置默认强调色
        0xFF1A73E8.toInt(), // blue
        0xFFF9A825.toInt(), // yellow
        0xFFE53935.toInt(), // red
        0xFF00696D.toInt(), // teal
        0xFF808080.toInt(), // gray
    )

    private fun diffCount(seedArgb: Int, dark: Boolean, style: ColorStyle): Int {
        val a = argbRoles(ColorSchemes.fromSeed(seedArgb, dark, style, ColorSpecVersion.V2021))
        val b = argbRoles(ColorSchemes.fromSeed(seedArgb, dark, style, ColorSpecVersion.V2025))
        return a.indices.count { a[it] != b[it] }
    }

    /**
     * 全部 19 个**扩展角色**的取值读取器(2026-09-21 补全映射的那批)。
     *
     * 这些角色此前不在 `ColorSchemes.toColorScheme()` 的映射里,恒为
     * `light/darkColorScheme()` 的基线常量 —— 见
     * `surface and fixed roles follow the seed instead of material3 baseline`。
     */
    private val extendedRoleReaders: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "surfaceBright" to { it.surfaceBright },
        "surfaceDim" to { it.surfaceDim },
        "surfaceContainerLowest" to { it.surfaceContainerLowest },
        "surfaceContainerLow" to { it.surfaceContainerLow },
        "surfaceContainer" to { it.surfaceContainer },
        "surfaceContainerHigh" to { it.surfaceContainerHigh },
        "surfaceContainerHighest" to { it.surfaceContainerHighest },
        "primaryFixed" to { it.primaryFixed },
        "primaryFixedDim" to { it.primaryFixedDim },
        "onPrimaryFixed" to { it.onPrimaryFixed },
        "onPrimaryFixedVariant" to { it.onPrimaryFixedVariant },
        "secondaryFixed" to { it.secondaryFixed },
        "secondaryFixedDim" to { it.secondaryFixedDim },
        "onSecondaryFixed" to { it.onSecondaryFixed },
        "onSecondaryFixedVariant" to { it.onSecondaryFixedVariant },
        "tertiaryFixed" to { it.tertiaryFixed },
        "tertiaryFixedDim" to { it.tertiaryFixedDim },
        "onTertiaryFixed" to { it.onTertiaryFixed },
        "onTertiaryFixedVariant" to { it.onTertiaryFixedVariant },
    )

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
        // 2026-09-21:surfaceBright/surfaceDim 在补全映射后也会随种子色变化,
        // 必须一并压黑,否则深色纯黑模式下这两档又会冒出非黑的浅色表面。
        assertEquals(Color.Black, black.surfaceBright)
        assertEquals(Color.Black, black.surfaceDim)
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
     * 先跑探针量化(6 个种子 × 深浅两模式 × 9 风格,48 个角色):
     * **TonalSpot / Vibrant / Expressive / Neutral 四个变体改变 36–43 / 48 个角色**;
     * Monochrome / Fidelity / Content / Rainbow / FruitSalad 恒为 0(原因见下一个用例)。
     *
     * > 2026-09-21 补记:上面 36–43 是**扩展角色映射补齐后**的数字。
     * > 补齐前那 19 个角色恒为基线常量,差异面只有 21–24 —— 差异变小本身就是
     * > "角色没接上算法"的征兆。**测得的数字变了,不是断言松了,是缺陷少了。**
     *
     * 下界取 **10**:远低于实测最小值 36,留出算法微调余量,但"没接上"(恒为 0)必被抓住。
     *
     * ## 为什么还要钉两个具体色值
     *
     * 计数断言能抓"没接上",**抓不到"接反了"** —— 把 V2021/V2025 的映射对调,
     * 差值仍是 36+。因此再钉一组实测色值(见下方,标注了测量版本):
     * 接反时计数断言通过而本断言失败,失败信息可直接指向接反。
     */
    @Test
    fun `color spec version really re-tones the scheme`() {
        // 只测**声称受影响**的风格 —— 该名单是单一事实源(设置页据此置灰),
        // 若把某个不受影响的风格错列进来,这里必然失败(实测 0 < 下界 10)。
        val affected = ColorStyle.entries.filter { it.isAffectedByColorSpec() }
        assertTrue(affected.isNotEmpty(), "敏感风格名单不得为空,否则本用例空转")
        for (style in affected) {
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
     * 「色彩标准」的**覆盖面边界**(实测记录 + 越界预警 + 置灰依据)。
     *
     * 实测事实(materialkolor 5.0.1,6 种子 × 深浅,48 个角色):SPEC_2025 只重写了
     * Neutral / TonalSpot / Expressive / Vibrant 四个变体的调色板与若干角色定义
     * (`ColorSpec2025 : ColorSpec2021()`,其余分支 `else -> super`),
     * 所以 **Monochrome / Fidelity / Content / Rainbow / FruitSalad 五个风格下,
     * 切换 2021↔2025 完全不改变任何颜色**。
     *
     * 这条断言的三个用处:
     *  1. **验证置灰依据** —— 设置页据 [COLOR_SPEC_SENSITIVE_STYLES] 置灰该行;
     *     本用例证明该名单与算法实际行为一致(名单错列 → 两处断言之一必失败)。
     *  2. **升级预警** —— 若算法库改变覆盖面,这里先失败,提醒复核置灰逻辑。
     *  3. **事实记录** —— 该开关对这 5 个风格确为无操作。
     */
    @Test
    fun `color spec version is a no-op for the styles it does not re-tone`() {
        val unaffected = ColorStyle.entries.filterNot { it.isAffectedByColorSpec() }
        assertTrue(unaffected.isNotEmpty(), "不受影响的风格名单不得为空,否则本用例空转")
        for (style in unaffected) {
            for (seedArgb in seeds) {
                for (dark in listOf(false, true)) {
                    assertEquals(
                        0,
                        diffCount(seedArgb, dark, style),
                        "$style 实测两套规范应无差异;若失败说明算法库覆盖面变了," +
                            "需复核设置页「色彩标准」行的置灰逻辑",
                    )
                }
            }
        }
    }

    /**
     * **扩展角色必须由算法派生**(2026-09-21 修掉的缺陷的回归护栏)。
     *
     * 缺陷:此前 `ColorSchemes.toColorScheme()` 只映射 24 个角色,下列 19 个
     * (`surfaceBright` / `surfaceDim` / `surfaceContainer*` 七档 / `*Fixed*` 十二个)
     * 不在其中,于是**逐字节等于** `light/darkColorScheme()` 的基线常量 ——
     * 无论强调色怎么换都不变。
     *
     * 后果:非靛蓝强调色下 `surface` 与 `surfaceContainer*` **色相错配**
     * (背景走了种子,卡片还是 M3 基线紫灰),深色下最明显;
     * `*Fixed*` 则任何强调色都拿到基线淡紫 `#EADDFF`。
     *
     * ## 判据为什么是"与基线比对"而不是"随种子变化"
     *
     * 本用例第一版用"6 个种子下取值集合 > 1"作判据,**被 `surfaceContainerLowest`
     * 浅色判失败** —— 它恒为 `#FFFFFFFF`。但这是**算法本来的正确行为**:
     * 该角色取中性色板 tone 100(纯白),本就与种子无关。
     * 把"与种子无关"一律当缺陷会误伤。
     *
     * 真正的判据是"**该角色是否还等于你从没配过的 material3 基线色**":
     * 只要在 6 种子 × 深浅的任一组合下与基线不同,就证明它已接上算法。
     * 未映射的角色在**所有**组合下都恰好等于基线 —— 一抓一个准。
     */
    @Test
    fun `surface and fixed roles come from the algorithm, not the material3 baseline`() {
        val baselineLight = lightColorScheme()
        val baselineDark = darkColorScheme()

        for ((name, read) in extendedRoleReaders) {
            val derivedFromAlgorithm = listOf(false, true).any { dark ->
                val baseline = hex(read(if (dark) baselineDark else baselineLight))
                seeds.any { seedArgb ->
                    val actual = ColorSchemes.fromSeed(seedArgb, dark, ColorStyle.TonalSpot, ColorSpecVersion.V2021)
                    hex(read(actual)) != baseline
                }
            }
            assertTrue(
                derivedFromAlgorithm,
                "$name 在 6 个种子 × 深浅全部组合下都等于 material3 基线值" +
                    "(${hex(read(baselineLight))}) —— 说明该角色未映射到算法输出",
            )
        }
    }
}
