package com.cyanix.pixfold.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.ui.settings.LicensesScreen
import com.cyanix.pixfold.ui.settings.SettingsScreen
import com.cyanix.pixfold.ui.settings.TAG_CHOICE_DIALOG
import com.cyanix.pixfold.ui.settings.TAG_LICENSES_BACK
import com.cyanix.pixfold.ui.settings.TAG_LICENSES_CORRECTION
import com.cyanix.pixfold.ui.settings.TAG_LICENSES_LIST
import com.cyanix.pixfold.ui.settings.TAG_LICENSES_OWN
import com.cyanix.pixfold.ui.settings.TAG_LICENSES_PAGE
import com.cyanix.pixfold.ui.settings.TAG_SETTINGS_ACCENT
import com.cyanix.pixfold.ui.settings.TAG_SETTINGS_ACCENT_SWATCH
import com.cyanix.pixfold.ui.settings.accentSwatchTag
import com.cyanix.pixfold.ui.settings.themeOptionTag
import com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_BAR
import com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_LOADING
import com.cyanix.pixfold.ui.workspace.WorkspaceBar
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice U2（首页、工作区条/弹窗、设置页与开源许可页 M3E 全面升级）专项守卫（`junit4.v2`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_ZH_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M3eHomeWorkspaceSettingsTest {

    @get:Rule
    val rule = createComposeRule()

    private val handle = WorkspaceHandle(
        treeUri = "content://com.cyanix.pixfold.test/tree/comics",
        displayName = "漫画书库工作区",
    )

    private fun channelDistance(a: Int, b: Int): Int =
        abs(AndroidColor.red(a) - AndroidColor.red(b)) +
            abs(AndroidColor.green(a) - AndroidColor.green(b)) +
            abs(AndroidColor.blue(a) - AndroidColor.blue(b))

    @Test
    fun `home hero cards render M3E shape badges and subordinate workspace bar is shorter than hero cards`() {
        val ready = WorkspaceState.Ready(handle, MockData.workspaceA.collections)
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = false,
                ),
            ) {
                HomeScreen(
                    onOpenOrganize = {},
                    onOpenLibrary = {},
                    onOpenSettings = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    workspaceState = ready,
                    onPickWorkspace = {},
                    onOpenWorkspaceList = {},
                )
            }
        }
        rule.waitForIdle()

        val organizeBadge = rule.onNodeWithTag(TAG_ORGANIZE_BADGE, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val libraryBadge = rule.onNodeWithTag(TAG_LIBRARY_BADGE, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals(52f, (organizeBadge.right - organizeBadge.left).value, 0.5f)
        assertEquals(52f, (libraryBadge.right - libraryBadge.left).value, 0.5f)

        val heroCardHeight = rule.onNodeWithText(TestText.of(R.string.home_workflow_a))
            .getBoundsInRoot()
            .let { (it.bottom - it.top).value }
        val workspaceBarHeight = rule.onNodeWithTag(TAG_WORKSPACE_BAR)
            .getBoundsInRoot()
            .let { (it.bottom - it.top).value }

        assertTrue(
            workspaceBarHeight in 48f..68f,
            "工作区条应保持紧凑从属条形态（48..68dp），实测 ${workspaceBarHeight}dp",
        )
        assertTrue(
            workspaceBarHeight < heroCardHeight - 16f,
            "工作区条(${workspaceBarHeight}dp)必须明显矮于首页工作流大卡(${heroCardHeight}dp)，绝不能长成第三张大卡",
        )
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    @Test
    fun `workspace bar renders M3E LoadingIndicator only while loading`() {
        var state by mutableStateOf<WorkspaceState>(WorkspaceState.Loading)
        rule.setContent {
            PixFoldNativeTestHost {
                WorkspaceBar(
                    state = state,
                    onPick = {},
                    onOpenList = {},
                )
            }
        }
        rule.waitForIdle()

        val indicator = rule.onNodeWithTag(TAG_WORKSPACE_LOADING, useUnmergedTree = true)
        indicator.assertIsDisplayed()
        val bounds = indicator.getUnclippedBoundsInRoot()
        assertEquals(24f, (bounds.right - bounds.left).value, 0.5f)
        assertEquals(24f, (bounds.bottom - bounds.top).value, 0.5f)

        state = WorkspaceState.Ready(handle, MockData.workspaceA.collections)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_WORKSPACE_LOADING, useUnmergedTree = true).assertDoesNotExist()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    @Test
    fun `settings connected theme toggle fits 361dp at 1_3x in zh-rCN with 48dp targets`() {
        verifyThemeToggleGroupAtLargeFont()
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `settings connected theme toggle fits 361dp at 1_3x in en with 48dp targets`() {
        verifyThemeToggleGroupAtLargeFont()
    }

    private fun verifyThemeToggleGroupAtLargeFont() {
        var latest = PixFoldSettings(themeMode = ThemeMode.System, accent = AccentChoice.Default)
        rule.setContent {
            PixFoldNativeTestHost(
                settings = latest,
                fontScale = 1.3f,
            ) {
                var current by remember { mutableStateOf(latest) }
                SettingsScreen(
                    settings = current,
                    onChange = { current = it; latest = it },
                    onBack = {},
                    onOpenLicenses = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()

        val themeNodes = ThemeMode.entries.map { rule.onNodeWithTag(themeOptionTag(it)) }
        assertRowsFitViewport(themeNodes)
        themeNodes.forEachIndexed { idx, node ->
            val bounds = node.getUnclippedBoundsInRoot()
            val height = (bounds.bottom - bounds.top).value
            assertTrue(
                height >= 48f - 0.5f,
                "主题相连切换按钮 #$idx 高度必须 >= 48dp，实测 ${height}dp",
            )
        }
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        rule.onNodeWithTag(themeOptionTag(ThemeMode.Dark)).performClick()
        rule.waitForIdle()
        assertEquals(ThemeMode.Dark, latest.themeMode)

        rule.onNodeWithTag(themeOptionTag(ThemeMode.Light)).performClick()
        rule.waitForIdle()
        assertEquals(ThemeMode.Light, latest.themeMode)
    }

    @Test
    fun `settings accent row and choice dialog show live Cookie9Sided color swatches`() {
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Purple,
                    colorStyle = ColorStyle.Expressive,
                    colorSpec = ColorSpecVersion.V2025,
                ),
            ) {
                var current by remember {
                    mutableStateOf(
                        PixFoldSettings(
                            themeMode = ThemeMode.Dark,
                            accent = AccentChoice.Purple,
                            colorStyle = ColorStyle.Expressive,
                            colorSpec = ColorSpecVersion.V2025,
                        ),
                    )
                }
                SettingsScreen(
                    settings = current,
                    onChange = { current = it },
                    onBack = {},
                    onOpenLicenses = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()

        val rowSwatch = rule.onNodeWithTag(TAG_SETTINGS_ACCENT_SWATCH, useUnmergedTree = true)
        rowSwatch.assertIsDisplayed()
        val swatchBounds = rowSwatch.getUnclippedBoundsInRoot()
        assertEquals(24f, (swatchBounds.right - swatchBounds.left).value, 0.5f)
        assertEquals(24f, (swatchBounds.bottom - swatchBounds.top).value, 0.5f)

        rule.onNodeWithTag(TAG_SETTINGS_ACCENT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_CHOICE_DIALOG).assertExists()

        AccentChoice.entries.forEach { choice ->
            rule.onNodeWithTag(accentSwatchTag(choice), useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `settings PureBlack mode draws visible outlineVariant border on unchecked theme button`() {
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
                SettingsScreen(
                    settings = PixFoldSettings(
                        themeMode = ThemeMode.Dark,
                        accent = AccentChoice.Default,
                        pureBlack = true,
                    ),
                    onChange = {},
                    onBack = {},
                    onOpenLicenses = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()

        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val uncheckedBounds = rule.onNodeWithTag(themeOptionTag(ThemeMode.System)).getBoundsInRoot()
        val density = rule.density.density
        val midX = (((uncheckedBounds.left + uncheckedBounds.right).value / 2f) * density).toInt()
        val topEdgeY = (uncheckedBounds.top.value * density).toInt() + 1
        val interiorY = ((((uncheckedBounds.top + uncheckedBounds.bottom).value / 2f)) * density).toInt()
        val outerBgPixel = bmp.getPixel(midX, (uncheckedBounds.top.value * density).toInt() - 4)
        val edgePixel = bmp.getPixel(midX, topEdgeY)
        assertTrue(
            channelDistance(edgePixel, outerBgPixel) >= 24,
            "纯黑模式下未选中主题切换按钮上边应有清晰 outlineVariant 描边（edge=$edgePixel bg=$outerBgPixel interior=${bmp.getPixel(midX, interiorY)}）",
        )
    }

    @Test
    fun `licenses screen uses PixFoldTopBar and renders own license, correction and list cleanly`() {
        var backClicked = false
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
                LicensesScreen(
                    onBack = { backClicked = true },
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_LICENSES_PAGE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LICENSES_OWN).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LICENSES_CORRECTION).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LICENSES_LIST).assertIsDisplayed()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        rule.onNodeWithTag(TAG_LICENSES_BACK).performClick()
        rule.waitForIdle()
        assertTrue(backClicked)
    }

    @Test
    fun `all U2 screens avoid hardcoded RoundedCornerShape`() {
        val u2Files = listOf(
            "src/main/kotlin/com/cyanix/pixfold/ui/HomeScreen.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceBar.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceDragList.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/workspace/WorkspaceListDialog.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/settings/SettingsScreen.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/settings/LicensesScreen.kt",
        )
        u2Files.forEach { rel ->
            val file = File(rel).takeIf { it.exists() } ?: File("android/app/$rel")
            assertTrue(file.exists(), "源码文件不存在: ${file.path}")
            val code = file.readLines()
                .map { it.substringBefore("//") }
                .joinToString("\n")
            assertFalse(
                code.contains("RoundedCornerShape("),
                "$rel 不得硬编码 RoundedCornerShape(...)，应使用 MaterialTheme.shapes / M3E shapes",
            )
            assertFalse(
                code.contains("import androidx.compose.foundation.shape.RoundedCornerShape"),
                "$rel 不得保留未使用的 RoundedCornerShape import",
            )
        }
    }
}
