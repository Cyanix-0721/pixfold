package com.pixfold.d1.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * PixFold 主题(HANGOFF §6.5「UI 须符合最新 Material Design」)。
 *
 * 基准 = material3 `1.4.0`(stable,由 Compose BOM 锁定)。做法:
 *  - 静态回退配色用 [lightColorScheme]/[darkColorScheme](M3 语义角色,不把色值硬编码进组件);
 *  - Android 12+(API 31)起优先用**动态取色**,支持 Material You;
 *  - 颜色一律经 `MaterialTheme.colorScheme` 语义角色消费,组件内不写死色值。
 *
 * M3E(Material 3 Expressive)不在本阶段范围:其公开 API 仅存在于 1.5.0-alpha28,
 * stable 1.4.0 中相关类型为 internal(实测见 docs/notes/d1-toolchain-evidence.md §7)。
 */
@Composable
fun PixFoldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

// 静态回退方案(M3 语义角色)。仅在动态取色不可用(API < 31)或调用方显式关闭时使用。
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    secondary = Color(0xFF5C6BC0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FA8DA),
    secondary = Color(0xFFB0BEC5),
)
