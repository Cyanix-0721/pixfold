package com.cyanix.pixfold.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode

/**
 * PixFold 主题(HANGOFF §6.5「UI 须符合最新 Material Design」)。
 *
 * 配色来源按优先级:
 *  1. **动态取色**(强调色 = 跟随系统)→ 种子色取自系统壁纸(`dynamic*ColorScheme` 是
 *     API 31+,而本项目 minSdk 已于 2026-09-25 提到 **34**,故不再需要版本分支);
 *  2. 否则 → 取所选**预设种子色**。
 *
 * 种子色再经**色彩风格**(TonalSpot / Vibrant / …)与**色彩标准**(2021 / 2025)
 * 由 [ColorSchemes] 派生出完整 [androidx.compose.material3.ColorScheme]。
 *
 * ## 一个刻意的取舍:动态取色也要过一遍算法
 *
 * 系统已提供 `dynamicLight/DarkColorScheme(context)`(Android 自己的 M3 配色)。
 * 我们**没有直接用它**,而是取它的 `primary` 作为种子、再走一遍色彩算法。
 *
 * **理由**:只有这样「色彩风格」「色彩标准」两个开关在**跟随系统时也生效**;
 * 若跟随系统时直接用系统配色,这两个开关就会在默认(也是最常用)的设置下失效,
 * 用户会认为"开关没用"。代价是跟随系统时的配色与系统自身有细微差异 ——
 * 这是可接受的,且换来行为一致性。
 */
@Composable
fun PixFoldTheme(
    settings: PixFoldSettings,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dark = when (settings.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val context = LocalContext.current
    val seedArgb = if (settings.monetEnabled) {
        // 取系统动态配色的 primary 作种子:深浅两种模式下它都来自同一套壁纸色调。
        val systemScheme = if (dark) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
        systemScheme.primary.toArgb()
    } else {
        // 含 AccentChoice.System 但 API < 31 的情况(回退到内置默认种子)。
        AccentPresets.seedOf(settings.accent).toArgb()
    }

    var scheme = ColorSchemes.fromSeed(
        seedArgb = seedArgb,
        dark = dark,
        style = settings.colorStyle,
        spec = settings.colorSpec,
    )
    if (settings.pureBlack && dark) {
        scheme = scheme.withPureBlack()
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/**
 * 「危险动作」按钮配色(破坏性 / 回退类动作,如报告页的「撤销本次执行」)。
 *
 * ## 为什么不直接用 `colorScheme.error` 着底
 *
 * material3 的 error 族是**按模式反转**的(已从 `material3@1.4.0` 的
 * `ColorDarkTokens` / `PaletteTokens` 逐行核实):
 *
 * | 角色              | 浅色               | 深色               |
 * | ----------------- | ------------------ | ------------------ |
 * | `error`           | `#B3261E` 深红 ✅  | `#F2B8B5` **淡粉** ❌ |
 * | `onError`         | `#FFFFFF` 白       | `#601410` 暗红     |
 * | `errorContainer`  | `#F9DEDC` **粉底** ❌ | `#8C1D18` 深红 ✅  |
 * | `onErrorContainer`| `#8C1D18` 深红     | `#F9DEDC` 米白     |
 *
 * `error` 在深色下是**淡粉**——它设计给「错误文字」用,不是给「危险动作的底」用;
 * 而 `errorContainer` 在浅色下是**粉底**,同样不够醒目。所以**两个模式必须取不同档位**,
 * 没有哪一个角色能同时在深浅两色下都「像危险」。
 *
 * ## 为什么不能改 `error` 角色本身
 *
 * 全仓 `colorScheme.error` / `onError` 有 18 处当**文字色**用(冲突、警告、失败原因、
 * 跳过原因…),当底色用的只有本函数。淡粉当**文字**在深色下是合适的
 * (深底上要提亮才读得清);所以这是「底色」与「文字色」两套诉求,
 * 不能改 `error` 角色本身去迁就其中一方(`ColorSchemes.kt:145-152` 把 error 族
 * 刻意钉死为 material3 默认,正是为了保住那 18 处文字色)。
 *
 * ## 深浅判定为什么用 `background.luminance()`
 *
 * `ColorScheme` **没有** `isLight()` 之类的现成辅助;`Theme.kt` 里算主题时那个 `dark`
 * 局部变量也传不到叶子页面。判底色明度是 M3 自己的做法
 * (material3 内部 `BasicEdgeToEdgeDialog` 即 `luminance() < 0.5f`)。
 * 它对纯黑模式也成立——`withPureBlack()` 把 `background` 压成 `Color.Black`。
 *
 * 禁用态**不在此处理**:`buttonColors` 只传 container/content 时,
 * `disabled*` 自动继承 `defaultButtonColors` 的灰化角色。
 */
@Composable
fun dangerActionButtonColors(): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() < 0.5f) {
        ButtonDefaults.buttonColors(
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = scheme.error,
            contentColor = scheme.onError,
        )
    }
}

/**
 * **次级**「危险动作」按钮配色(O1,2026-10-07)。
 *
 * ## 为什么需要第二档
 *
 * 撤销范围对话框里两个执行档的危险程度**不同**(删本次产物 / 连用户原有的也删),
 * 但原先两档都调 [dangerActionButtonColors] ⇒ 真机像素**逐位相同**(轮 23 读数:
 * 两档都是 `#8C1D18`),用户只能靠读文字分辨。用户 2026-10-07:「o1o2都要」
 * 即要求把这条分级落实。
 *
 * ## 为什么是「同色系换重量」而不是「换成另一个红色角色」
 *
 * 台账 §4.1 的 O1 曾举例「危险档用 `error`、安全档用 `errorContainer`」。
 * 这个方案**不能用**:如 [dangerActionButtonColors] 的 KDoc 所述,error 族
 * **按模式反转**——浅色下 `error` 深红而 `errorContainer` 是粉底,深色下正好反过来。
 * 换角色会得到一个**随模式互换**的分级,而且每个模式下总有一档是**淡粉**
 * (`#F9DEDC` / `#F2B8B5`),恰恰是"不像危险动作"的那种颜色。
 *
 * 故分级改用**视觉重量**:两档同为红色系,但
 *
 *  | 档位 | 底色 | 描边 | 用于 |
 *  | ---- | ---- | ---- | ---- |
 *  | [dangerActionButtonColors] | 深红**填充** | 无 | 删得最多的那一档 |
 *  | 本函数 | **透明** | 红 1dp | 只删本次产物的那一档 |
 *  | `TextButton` | **透明** | 无 | 取消(非动作) |
 *
 * 这与"删得越多越醒目"同序,且**与模式无关**(描边/内容色用 `error`——它正是
 * 那 18 处**文字**色的来源,浅色深红、深色淡粉,在各自底色上都读得清)。
 *
 * 底栏的「撤销本次执行」仍用 [dangerActionButtonColors](填充),不受影响。
 */
@Composable
fun dangerOutlineButtonColors(): ButtonColors =
    ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.error,
    )

/** 与 [dangerOutlineButtonColors] 配套的描边;同一个 `error` 红,避免两处色号走偏。 */
@Composable
fun dangerOutlineBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.error)

/**
 * 兼容重载:旧的 `PixFoldTheme(darkTheme, dynamicColor, content)` 调用点与测试
 * 无需改动。内部转换为等价设置:
 *  - `darkTheme` -> [ThemeMode.Light] / [ThemeMode.Dark]
 *  - `dynamicColor` -> [AccentChoice.System] / [AccentChoice.Default]
 *  - 色彩风格与标准取默认值(TonalSpot / 2021),即升级前的观感
 */
@Composable
fun PixFoldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    PixFoldTheme(
        settings = PixFoldSettings(
            themeMode = if (darkTheme) ThemeMode.Dark else ThemeMode.Light,
            accent = if (dynamicColor) AccentChoice.System else AccentChoice.Default,
        ),
        content = content,
    )
}
