package com.cyanix.pixfold.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.PixFoldNativeTestHost
import com.cyanix.pixfold.ui.PixFoldUiTestDefaults
import com.cyanix.pixfold.ui.assertAccessibleTargets
import com.cyanix.pixfold.ui.assertAllNodesFitViewport
import com.cyanix.pixfold.ui.theme.LocalIsPureBlack
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.theme.pixFoldCardContainerColor
import com.cyanix.pixfold.ui.theme.pixFoldSegmentedColors
import com.cyanix.pixfold.ui.theme.pixFoldSurfaceBorder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice U1 M3E 基础原语、纯黑语义 Local、统一弹窗壳与色调底栏回归守卫（`junit4.v2`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_ZH_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveFoundationTest {

    @get:Rule
    val rule = createComposeRule()

    private fun channelDistance(a: Int, b: Int): Int =
        abs(AndroidColor.red(a) - AndroidColor.red(b)) +
            abs(AndroidColor.green(a) - AndroidColor.green(b)) +
            abs(AndroidColor.blue(a) - AndroidColor.blue(b))

    private fun relativeLuminance(color: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(AndroidColor.red(color)) +
            0.7152 * ch(AndroidColor.green(color)) +
            0.0722 * ch(AndroidColor.blue(color))
    }

    private fun hex(color: Int): String = String.format("#%08X", color)

    @Test
    fun `LocalIsPureBlack and theme helpers follow pureBlack and dark mode across both theme overloads`() {
        var darkPureBlack: Boolean? = null
        var darkPureBlackBorder: BorderStroke? = null
        var darkPureBlackDisabledBorder: BorderStroke? = null
        var lightPureBlack: Boolean? = null
        var lightPureBlackBorder: BorderStroke? = null
        var darkNormal: Boolean? = null
        var darkNormalBorder: BorderStroke? = null
        var systemDarkPureBlack: Boolean? = null
        var compatDarkPureBlack: Boolean? = null
        var cardColor: Color? = null
        var expectedSurfaceContainerLow: Color? = null
        var segmentedColors: ListItemColors? = null

        rule.setContent {
            PixFoldTheme(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
                darkPureBlack = LocalIsPureBlack.current
                darkPureBlackBorder = pixFoldSurfaceBorder()
                darkPureBlackDisabledBorder = pixFoldSurfaceBorder(enabled = false)
            }
            PixFoldTheme(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Light,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
                lightPureBlack = LocalIsPureBlack.current
                lightPureBlackBorder = pixFoldSurfaceBorder()
            }
            PixFoldTheme(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = false,
                ),
            ) {
                darkNormal = LocalIsPureBlack.current
                darkNormalBorder = pixFoldSurfaceBorder()
                cardColor = pixFoldCardContainerColor()
                expectedSurfaceContainerLow = MaterialTheme.colorScheme.surfaceContainerLow
                segmentedColors = pixFoldSegmentedColors()
            }
            PixFoldTheme(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.System,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
                systemDark = true,
            ) {
                systemDarkPureBlack = LocalIsPureBlack.current
            }
            PixFoldTheme(darkTheme = true, dynamicColor = false) {
                compatDarkPureBlack = LocalIsPureBlack.current
            }
        }
        rule.waitForIdle()

        assertEquals(true, darkPureBlack, "Dark + pureBlack=true 时 LocalIsPureBlack 应为 true")
        assertNotNull(darkPureBlackBorder, "纯黑模式下 pixFoldSurfaceBorder() 应返回 1dp 描边")
        assertEquals(1.dp, darkPureBlackBorder?.width)
        assertNotNull(darkPureBlackDisabledBorder, "纯黑禁用态仍需保留降权描边")
        assertEquals(false, lightPureBlack, "浅色模式即便 pureBlack=true 也不应启用纯黑态")
        assertNull(lightPureBlackBorder, "浅色模式下 pixFoldSurfaceBorder() 应为 null")
        assertEquals(false, darkNormal, "非纯黑深色模式 LocalIsPureBlack 应为 false")
        assertNull(darkNormalBorder, "非纯黑深色模式 pixFoldSurfaceBorder() 应为 null")
        assertEquals(true, systemDarkPureBlack, "System 且 systemDark=true 且 pureBlack=true 应为 true")
        assertEquals(false, compatDarkPureBlack, "兼容重载默认 pureBlack=false")
        assertEquals(expectedSurfaceContainerLow, cardColor)
        assertEquals(expectedSurfaceContainerLow, segmentedColors?.containerColor)
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Test
    fun `PixFoldShapeBadge and PixFoldStatusPill render cleanly with expected dimensions`() {
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = false,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PixFoldShapeBadge(
                        polygon = MaterialShapes.Cookie9Sided,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        iconRes = R.drawable.ic_settings,
                        modifier = Modifier.testTag("badge"),
                    )
                    PixFoldStatusPill(
                        text = "3 卷",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        iconRes = R.drawable.ic_warning_triangle,
                        modifier = Modifier.testTag("pill"),
                    )
                }
            }
        }
        rule.waitForIdle()

        val badgeBounds = rule.onNodeWithTag("badge").getBoundsInRoot()
        assertEquals(48f, (badgeBounds.right - badgeBounds.left).value, 0.5f)
        assertEquals(48f, (badgeBounds.bottom - badgeBounds.top).value, 0.5f)
        rule.onNodeWithTag("pill").assertIsDisplayed()
        rule.onNodeWithText("3 卷").assertIsDisplayed()
        assertAllNodesFitViewport(rule)
    }

    @Test
    fun `PixFoldSegmentedItem supports all 4 variants and draws visible PureBlack border`() {
        var clickCount = 0
        var selected by mutableStateOf(false)
        var checked by mutableStateOf(false)

        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    PixFoldSegmentedItem(
                        shapes = ListItemDefaults.segmentedShapes(0, 4),
                        modifier = Modifier.testTag("seg-static"),
                        supportingContent = { Text("静态副文案") },
                    ) {
                        Text("静态项")
                    }
                    PixFoldSegmentedItem(
                        onClick = { clickCount++ },
                        shapes = ListItemDefaults.segmentedShapes(1, 4),
                        modifier = Modifier.testTag("seg-click"),
                    ) {
                        Text("可点击项")
                    }
                    PixFoldSegmentedItem(
                        selected = selected,
                        onClick = { selected = !selected },
                        shapes = ListItemDefaults.segmentedShapes(2, 4),
                        modifier = Modifier.testTag("seg-select"),
                    ) {
                        Text("可选中项")
                    }
                    PixFoldSegmentedItem(
                        checked = checked,
                        onCheckedChange = { checked = it },
                        shapes = ListItemDefaults.segmentedShapes(3, 4),
                        modifier = Modifier.testTag("seg-check"),
                    ) {
                        Text("可勾选项")
                    }
                }
            }
        }
        rule.waitForIdle()

        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val firstBounds = rule.onNodeWithTag("seg-static").getBoundsInRoot()
        val density = rule.density.density
        val midX = (((firstBounds.left + firstBounds.right).value / 2f) * density).toInt()
        val edgeY = (firstBounds.top.value * density).toInt() + 1
        val outerBgPixel = bmp.getPixel(midX, 2)
        val edgePixel = bmp.getPixel(midX, edgeY)
        assertTrue(
            channelDistance(edgePixel, outerBgPixel) >= 24,
            "纯黑模式下 PixFoldSegmentedItem 顶边(${hex(edgePixel)})应与背景(${hex(outerBgPixel)})可分",
        )

        rule.onNodeWithTag("seg-click").performClick()
        rule.onNodeWithTag("seg-select").performClick()
        rule.onNodeWithTag("seg-check").performClick()
        rule.waitForIdle()

        assertEquals(1, clickCount)
        assertTrue(selected)
        assertTrue(checked)
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Test
    fun `PixFoldSectionCard and PixFoldEmptyState render PureBlack border and action`() {
        var actionClicks = 0
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    PixFoldSectionCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("section-card"),
                    ) {
                        PixFoldEmptyState(
                            polygon = MaterialShapes.SoftBurst,
                            iconRes = R.drawable.ic_settings,
                            title = "暂无项目",
                            subtitle = "请选择目录后开始整理",
                            action = {
                                Button(
                                    onClick = { actionClicks++ },
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = Modifier.testTag("empty-action"),
                                ) {
                                    Text("选择目录")
                                }
                            },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val cardBounds = rule.onNodeWithTag("section-card").getBoundsInRoot()
        val density = rule.density.density
        val midX = (((cardBounds.left + cardBounds.right).value / 2f) * density).toInt()
        val edgeY = (cardBounds.top.value * density).toInt() + 1
        val bgPixel = bmp.getPixel(midX, 2)
        val edgePixel = bmp.getPixel(midX, edgeY)
        assertTrue(
            channelDistance(edgePixel, bgPixel) >= 24,
            "纯黑模式下 PixFoldSectionCard 顶边(${hex(edgePixel)})应与背景(${hex(bgPixel)})可分",
        )

        rule.onNodeWithText("暂无项目").assertIsDisplayed()
        rule.onNodeWithText("请选择目录后开始整理").assertIsDisplayed()
        rule.onNodeWithTag("empty-action").performClick()
        rule.waitForIdle()
        assertEquals(1, actionClicks)
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    @Test
    fun `PixFoldTopBar satisfies 48dp touch target, contentDescription and subtitle variants`() {
        var backClicks = 0
        var showSubtitle by mutableStateOf(true)
        rule.setContent {
            PixFoldNativeTestHost {
                PixFoldTopBar(
                    title = "设置",
                    subtitle = if (showSubtitle) "外观与偏好" else null,
                    onBack = { backClicks++ },
                    backTag = "top-bar-back",
                    backCd = "返回首页",
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("设置").assertIsDisplayed()
        rule.onNodeWithText("外观与偏好").assertIsDisplayed()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        rule.onNodeWithTag("top-bar-back").performClick()
        rule.waitForIdle()
        assertEquals(1, backClicks)

        showSubtitle = false
        rule.waitForIdle()
        rule.onNodeWithText("设置").assertIsDisplayed()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    @Test
    fun `PixFoldDialogShell renders visible outlineVariant border in PureBlack mode`() {
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
                PixFoldDialogShell(
                    onDismissRequest = {},
                    title = { Text("纯黑弹窗") },
                    buttons = {
                        Button(onClick = {}, shapes = ButtonDefaults.shapes()) { Text("确定") }
                    },
                ) {
                    DialogBody("纯黑模式下弹窗应有清晰 1dp outlineVariant 描边。")
                }
            }
        }
        rule.waitForIdle()

        val dialogBmp = rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).captureToImage().asAndroidBitmap()
        val topEdge = dialogBmp.getPixel(dialogBmp.width / 2, 1)
        val interior = dialogBmp.getPixel(dialogBmp.width / 2, 24)
        assertTrue(
            channelDistance(topEdge, interior) >= 24,
            "纯黑模式下 PixFoldDialogShell 上边缘(${hex(topEdge)})应与纯黑内部(${hex(interior)})明显可分",
        )
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Test
    fun `PixFoldAlertDialog defaults title to headlineSmallEmphasized and supports heroBadge`() {
        var resolvedTitleStyle: TextStyle? = null
        var expectedTitleStyle: TextStyle? = null

        rule.setContent {
            PixFoldNativeTestHost {
                expectedTitleStyle = MaterialTheme.typography.headlineSmallEmphasized
                PixFoldAlertDialog(
                    onDismissRequest = {},
                    heroBadge = {
                        PixFoldShapeBadge(
                            polygon = MaterialShapes.SoftBurst,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            iconRes = R.drawable.ic_warning_triangle,
                            modifier = Modifier.testTag("dialog-hero-badge"),
                        )
                    },
                    title = {
                        resolvedTitleStyle = LocalTextStyle.current
                        Text("强调标题")
                    },
                    text = { DialogBody("正文说明") },
                    confirmButton = {
                        Button(onClick = {}, shapes = ButtonDefaults.shapes()) { Text("确认") }
                    },
                    dismissButton = {
                        TextButton(onClick = {}, shapes = ButtonDefaults.shapes()) { Text("取消") }
                    },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("dialog-hero-badge").assertIsDisplayed()
        rule.onNodeWithText("强调标题").assertIsDisplayed()
        assertEquals(
            expectedTitleStyle,
            resolvedTitleStyle,
            "PixFoldAlertDialog 标题槽默认排版应为 headlineSmallEmphasized",
        )
    }

    @Test
    fun `PixFoldConfirmDialog paints dangerous confirm button dark red`() {
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = false,
                ),
            ) {
                PixFoldConfirmDialog(
                    onDismissRequest = {},
                    title = "删除确认",
                    body = "该操作不可撤销。",
                    confirmText = "确认删除",
                    dismissText = "取消",
                    isDangerous = true,
                    confirmTag = "confirm-danger-btn",
                    onConfirm = {},
                )
            }
        }
        rule.waitForIdle()

        val dlgBounds = rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).getBoundsInRoot()
        val btnBounds = rule.onNodeWithTag("confirm-danger-btn").getBoundsInRoot()
        val dlgBmp = rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).captureToImage().asAndroidBitmap()
        val density = rule.density.density
        val x = ((btnBounds.left - dlgBounds.left + 4.dp).value * density).toInt()
        val y = ((((btnBounds.top + btnBounds.bottom) / 2f) - dlgBounds.top).value * density).toInt()
        val pixel = dlgBmp.getPixel(x, y)

        val r = AndroidColor.red(pixel)
        val g = AndroidColor.green(pixel)
        val b = AndroidColor.blue(pixel)
        val lum = relativeLuminance(pixel)
        assertTrue(r > g + 40 && r > b + 40, "危险确认按钮应为偏红填充底，实测 ${hex(pixel)}")
        assertTrue(lum < 0.25, "深色模式下危险确认按钮应为暗红，实测亮度=$lum (${hex(pixel)})")
    }

    @Test
    fun `PageActionBar renders tonal surfaceContainer distinct from page background in light and dark themes`() {
        var mode by mutableStateOf(ThemeMode.Light)
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = mode,
                    accent = AccentChoice.Default,
                    pureBlack = false,
                ),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.weight(1f))
                    PageActionBar(
                        trailing = {
                            Button(
                                onClick = {},
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.testTag("bar-trailing"),
                            ) {
                                Text("下一步")
                            }
                        },
                    )
                }
            }
        }

        listOf(ThemeMode.Light, ThemeMode.Dark).forEach { targetMode ->
            mode = targetMode
            rule.waitForIdle()

            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            val barBounds = rule.onNodeWithTag(TAG_PAGE_ACTION_BAR).getBoundsInRoot()
            val density = rule.density.density
            val sampleX = (24.dp.value * density).toInt()
            val bgY = ((barBounds.top - 24.dp).value * density).toInt()
            val barY = ((((barBounds.top + barBounds.bottom) / 2f).value) * density).toInt()

            val bgPixel = bmp.getPixel(sampleX, bgY)
            val barPixel = bmp.getPixel(sampleX, barY)
            assertTrue(
                channelDistance(barPixel, bgPixel) >= 8,
                "$targetMode 下 PageActionBar 应具有独立 surfaceContainer 色调底（实测 bar=${hex(barPixel)} bg=${hex(bgPixel)}）",
            )
        }
    }

    @Test
    fun `ExpressivePrimitives and PageActionBar do not hardcode RoundedCornerShape`() {
        listOf(
            "src/main/kotlin/com/cyanix/pixfold/ui/components/ExpressivePrimitives.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/PageActionBar.kt",
        ).forEach { rel ->
            val file = File(rel).takeIf { it.exists() } ?: File("android/app/$rel")
            assertTrue(file.exists(), "文件不存在: ${file.path}")
            val source = file.readText()
            assertFalse(
                source.contains("RoundedCornerShape("),
                "$rel 不得硬编码 RoundedCornerShape(...)",
            )
        }
    }
}
