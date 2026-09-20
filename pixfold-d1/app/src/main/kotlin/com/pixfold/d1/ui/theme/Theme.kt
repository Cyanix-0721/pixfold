package com.pixfold.d1.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.pixfold.d1.domain.settings.AccentChoice
import com.pixfold.d1.domain.settings.PixFoldSettings
import com.pixfold.d1.domain.settings.ThemeMode

/**
 * PixFold 主题(HANGOFF §6.5「UI 须符合最新 Material Design」)。
 *
 * 配色来源按优先级:
 *  1. **动态取色**(强调色 = 跟随系统,且 API ≥ 31)→ 种子色取自系统壁纸;
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
    val seedArgb = if (settings.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    MaterialTheme(colorScheme = scheme, content = content)
}

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
