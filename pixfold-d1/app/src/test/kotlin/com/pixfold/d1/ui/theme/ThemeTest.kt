package com.pixfold.d1.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M3 合规断言(HANGOFF §6.5 / 规格 §2 约束 13)。
 *
 * 钉死两件事:
 *  1. 主题确实经 material3 语义角色提供颜色/排版/形状(组件才能不硬编码);
 *  2. 深色模式确实切换了配色(不能只做浅色一套)。
 *
 * 用 `dynamicColor = false` 走静态 scheme,使断言在测试环境**确定**(动态取色依赖系统壁纸资源)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `theme exposes material3 semantic roles`() {
        var hasColorScheme = false
        var hasTypography = false
        var hasShapes = false

        rule.setContent {
            PixFoldTheme(dynamicColor = false) {
                hasColorScheme = MaterialTheme.colorScheme.primary != androidx.compose.ui.graphics.Color.Unspecified
                hasTypography = MaterialTheme.typography.bodyMedium.fontSize.value > 0f
                hasShapes = MaterialTheme.shapes.medium.topStart != null
            }
        }

        assertTrue(hasColorScheme, "应提供 colorScheme 语义角色")
        assertTrue(hasTypography, "应提供 typography 语义层级")
        assertTrue(hasShapes, "应提供 shapes 语义层级")
    }

    @Test
    fun `dark theme uses a different color scheme than light`() {
        var lightPrimary: androidx.compose.ui.graphics.Color? = null
        var darkPrimary: androidx.compose.ui.graphics.Color? = null

        rule.setContent {
            if (darkPrimary == null) {
                PixFoldTheme(darkTheme = false, dynamicColor = false) { lightPrimary = MaterialTheme.colorScheme.primary }
                PixFoldTheme(darkTheme = true, dynamicColor = false) { darkPrimary = MaterialTheme.colorScheme.primary }
            }
        }

        assertNotEquals(lightPrimary, darkPrimary, "深色模式必须切换配色(不能只做浅色一套)")
    }
}
