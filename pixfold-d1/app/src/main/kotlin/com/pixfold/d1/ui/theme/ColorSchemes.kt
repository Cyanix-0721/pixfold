package com.pixfold.d1.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.pixfold.d1.domain.settings.ColorSpecVersion
import com.pixfold.d1.domain.settings.ColorStyle

/**
 * 配色生成:从**种子色** + **色彩风格** + **色彩标准** 算出完整的 M3 配色。
 *
 * ## 为什么用算法库而不是 `material-kolor` 的一行式包装
 *
 * `com.materialkolor:material-kolor`(Compose 包装)会把 `material3` 从
 * **stable 1.4.0 强拉到 `1.5.0-alpha22`**(实测依赖解析确认),
 * 违背本项目"只用 stable"的版本策略(见 `docs/notes/d1-toolchain-evidence.md`)。
 *
 * 因此这里只用它的**算法子库** `com.materialkolor:material-color-utilities`
 * (纯 Kotlin,只依赖 kotlin-stdlib),自己把结果映射到 stable material3 的
 * [ColorScheme]。**代价**是多一层映射;**收益**是工具链保持 stable。
 *
 * > 算法本身不自己实现:HCT 色彩空间与两套色彩规范(2021/2025)是相当重的数学,
 * > 自研既无必要也易出错 —— 用官方算法的成熟实现,与"不自造轮子"的一贯取向一致。
 */
object ColorSchemes {

    /**
     * 由种子色生成配色。
     *
     * @param seedArgb 种子色(ARGB)。可由预设给出,也可来自系统动态取色。
     * @param dark 是否深色。
     * @param style 色彩风格(决定用哪个 `Scheme*` 变体)。
     * @param spec 色彩标准(2021 / 2025)。
     */
    fun fromSeed(
        seedArgb: Int,
        dark: Boolean,
        style: ColorStyle,
        spec: ColorSpecVersion,
    ): ColorScheme {
        val scheme = dynamicSchemeOf(seedArgb, dark, style, spec.toAlgorithm())
        return scheme.toColorScheme()
    }

    /** 色彩风格 -> 算法库的 Scheme 变体。 */
    private fun dynamicSchemeOf(
        seedArgb: Int,
        dark: Boolean,
        style: ColorStyle,
        spec: ColorSpec.SpecVersion,
    ): DynamicScheme {
        val hct = Hct.fromInt(seedArgb)
        // 中性对比度(0.0):不额外增减对比,保持规范默认观感。
        val contrast = 0.0
        return when (style) {
            ColorStyle.TonalSpot -> SchemeTonalSpot(hct, dark, contrast, spec)
            ColorStyle.Vibrant -> SchemeVibrant(hct, dark, contrast, spec)
            ColorStyle.Expressive -> SchemeExpressive(hct, dark, contrast, spec)
            ColorStyle.Neutral -> SchemeNeutral(hct, dark, contrast, spec)
            ColorStyle.Monochrome -> SchemeMonochrome(hct, dark, contrast, spec)
            ColorStyle.Fidelity -> SchemeFidelity(hct, dark, contrast, spec)
            ColorStyle.Content -> SchemeContent(hct, dark, contrast, spec)
            ColorStyle.Rainbow -> SchemeRainbow(hct, dark, contrast, spec)
            ColorStyle.FruitSalad -> SchemeFruitSalad(hct, dark, contrast, spec)
        }
    }

    /**
     * 算法结果 -> material3 [ColorScheme]。
     *
     * **只映射语义角色**,不碰 `error` 族(none) —— 见下方注释。
     */
    private fun DynamicScheme.toColorScheme(): ColorScheme {
        val role = { argb: Int -> Color(argb) }
        val base = if (isDark) darkColorScheme() else lightColorScheme()

        return base.copy(
            primary = role(primary),
            onPrimary = role(onPrimary),
            primaryContainer = role(primaryContainer),
            onPrimaryContainer = role(onPrimaryContainer),
            secondary = role(secondary),
            onSecondary = role(onSecondary),
            secondaryContainer = role(secondaryContainer),
            onSecondaryContainer = role(onSecondaryContainer),
            tertiary = role(tertiary),
            onTertiary = role(onTertiary),
            tertiaryContainer = role(tertiaryContainer),
            onTertiaryContainer = role(onTertiaryContainer),
            background = role(background),
            onBackground = role(onBackground),
            surface = role(surface),
            onSurface = role(onSurface),
            surfaceVariant = role(surfaceVariant),
            onSurfaceVariant = role(onSurfaceVariant),
            outline = role(outline),
            outlineVariant = role(outlineVariant),
            inverseSurface = role(inverseSurface),
            inverseOnSurface = role(inverseOnSurface),
            inversePrimary = role(inversePrimary),
            surfaceTint = role(primary),
            // ---- 扩展角色(2026-09-21 补) ----
            // 此前这里**只映射 24 个角色**,下列角色不在其中,于是恒为 `light/darkColorScheme()`
            // 的**基线常量**:无论用户选什么强调色 / 色彩风格 / 色彩标准都不变。
            //
            // 后果(实测,见 docs/notes/d1-ui-friction-inventory.md 第 10 节):
            // 非靛蓝强调色下 `surface`(经种子派生)与 `surfaceContainer*`(基线紫灰)
            // **色相错配** —— "背景对了、卡片还是 M3 基线紫",深色下尤其明显;
            // `*Fixed*` 族则任何强调色都拿到基线淡紫 `#EADDFF`。
            surfaceBright = role(surfaceBright),
            surfaceDim = role(surfaceDim),
            surfaceContainerLowest = role(surfaceContainerLowest),
            surfaceContainerLow = role(surfaceContainerLow),
            surfaceContainer = role(surfaceContainer),
            surfaceContainerHigh = role(surfaceContainerHigh),
            surfaceContainerHighest = role(surfaceContainerHighest),
            // Fixed 族:按 M3 规范跨深浅保持同一值的强调色板。
            // 注意"跨深浅不变"指**同一套配色内**不随 light/dark 切换,
            // **不等于**"不随种子色变" —— 它仍须由种子色派生。
            primaryFixed = role(primaryFixed),
            primaryFixedDim = role(primaryFixedDim),
            onPrimaryFixed = role(onPrimaryFixed),
            onPrimaryFixedVariant = role(onPrimaryFixedVariant),
            secondaryFixed = role(secondaryFixed),
            secondaryFixedDim = role(secondaryFixedDim),
            onSecondaryFixed = role(onSecondaryFixed),
            onSecondaryFixedVariant = role(onSecondaryFixedVariant),
            tertiaryFixed = role(tertiaryFixed),
            tertiaryFixedDim = role(tertiaryFixedDim),
            onTertiaryFixed = role(onTertiaryFixed),
            onTertiaryFixedVariant = role(onTertiaryFixedVariant),
            // error 族**刻意保持 material3 默认**:
            // 错误色若随主题漂移,危险提示的"红色"强度会被削弱甚至变得不像警示,
            // 而"破坏性动作必须醒目"是本项目的产品约束(§2 约束 5)。
            error = base.error,
            onError = base.onError,
            errorContainer = base.errorContainer,
            onErrorContainer = base.onErrorContainer,
            scrim = base.scrim,
        )
    }

    private fun ColorSpecVersion.toAlgorithm(): ColorSpec.SpecVersion = when (this) {
        ColorSpecVersion.V2021 -> ColorSpec.SpecVersion.SPEC_2021
        ColorSpecVersion.V2025 -> ColorSpec.SpecVersion.SPEC_2025
    }
}

/**
 * 纯黑背景:OLED 场景下的"真黑"。
 *
 * **不只是一层**:`surfaceContainer*` 系列也要压到纯黑,
 * 否则深色下会出现"黑底灰卡"的割裂感,与"要纯黑"的诉求相矛盾。
 *
 * **2026-09-21 补**:`surfaceBright` / `surfaceDim` 也一并压黑 ——
 * 二者本是"最亮 / 最暗的表面"两档,扩展角色补全映射后它们会随种子色变化,
 * 若不在纯黑时一并压掉,深色纯黑模式下这两档又会冒出**非黑**的浅色表面,
 * 把刚修好的观感重新打回"黑底灰卡"。
 *
 * **代价与配套**:容器与背景同色后,层级只能靠**边框/分隔线**表达
 * (调用方需保证可交互卡片有可见边界);`surfaceVariant` 保留一档浅灰,
 * 供需要"轻微填充"的位置使用。
 *
 * 只在深色时应用;浅色模式下纯黑没有意义(调用方负责判断)。
 *
 * > 定义为**顶层函数**而非 `ColorSchemes` 的成员扩展:成员扩展无法从其它文件
 * > 直接调用(需要接收者作用域),而主题入口在另一个文件。
 */
fun ColorScheme.withPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceBright = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    surfaceVariant = Color(0xFF1A1A1A),
    // on* 角色不动:保持对比度不退化。
)

/**
 * 会被「色彩标准」(2021 / 2025)真正改变输出的色彩风格。
 *
 * ## 为什么需要这个常量
 *
 * 算法库的 `ColorSpec2025 : ColorSpec2021()`,其六个调色板方法
 * (`getPrimaryPalette` / `getSecondaryPalette` / `getTertiaryPalette` /
 * `getNeutralPalette` / `getNeutralVariantPalette` / `getErrorPalette`)
 * **只重写 `NEUTRAL` / `TONAL_SPOT` / `EXPRESSIVE` / `VIBRANT` 四个变体**,
 * 其余分支一律 `else -> super`。
 *
 * 实测(6 个种子 × 深浅,逐项比较 48 个角色):上列 4 个风格下两套规范改变
 * **21–24 个角色**;其余 5 个风格下**恒为 0 —— 逐个角色逐字节相同**。
 *
 * 即:选中 Monochrome / Fidelity / Content / Rainbow / FruitSalad 时,
 * 设置页的「色彩标准」是**无操作开关**(能点、能存,颜色一点不变)。
 * 故设置页据此**置灰该行**(用户 2026-09-21 裁决),如实反映能力边界。
 *
 * ## 单一事实源
 *
 * 置灰(UI)与"应无差异"断言(测试)共用此常量,避免两处各自维护一份知识而漂移;
 * 测试会验证该常量与算法实际行为一致,若算法库升级改变覆盖面,测试先失败。
 *
 * > 放在 `ui/theme` 而非 `domain`:这是**算法库的行为事实**,不是产品领域概念;
 * > `domain` 的 [ColorStyle] 是对算法变体的纯枚举镜像,不应承载算法实现细节。
 */
val COLOR_SPEC_SENSITIVE_STYLES: Set<ColorStyle> = setOf(
    ColorStyle.TonalSpot,
    ColorStyle.Vibrant,
    ColorStyle.Expressive,
    ColorStyle.Neutral,
)

/** 「色彩标准」对该风格是否真的有效果。见 [COLOR_SPEC_SENSITIVE_STYLES]。 */
fun ColorStyle.isAffectedByColorSpec(): Boolean = this in COLOR_SPEC_SENSITIVE_STYLES
