package com.cyanix.pixfold.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.cyanix.pixfold.domain.library.libraryStateOf
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.ui.library.MetadataPage
import com.cyanix.pixfold.ui.library.PackPreviewPage
import com.cyanix.pixfold.ui.library.TAG_BATCH_ROW
import com.cyanix.pixfold.ui.library.TAG_CLEAN_EMPTY_TOGGLE
import com.cyanix.pixfold.ui.library.TAG_METADATA_LIST
import com.cyanix.pixfold.ui.library.TAG_PACK_LIST
import com.cyanix.pixfold.ui.settings.SettingsScreen
import com.cyanix.pixfold.ui.settings.TAG_SETTINGS_RESET
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 四个核心页面（`HomeScreen`、`MetadataPage`、`PackPreviewPage`、`SettingsScreen`）的
 * 纯 JVM 原生渲染（`@GraphicsMode(NATIVE)`）布局溢出、字号缩放与无障碍守卫。
 *
 * 覆盖维度：`zh-rCN` / `en` × `fontScale = 1.0f / 1.3f` @ `361dp × 794dp`（PJX110 视口）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_ZH_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeCoreScreensLayoutA11yTest {

    @get:Rule
    val rule = createComposeRule()

    // ================= HomeScreen =================

    @Test
    fun `home screen fits viewport and satisfies a11y in zh-rCN at 1_0x`() {
        verifyHomeScreen(fontScale = 1.0f)
    }

    @Test
    fun `home screen fits viewport and satisfies a11y in zh-rCN at 1_3x`() {
        verifyHomeScreen(fontScale = 1.3f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `home screen fits viewport and satisfies a11y in en at 1_0x`() {
        verifyHomeScreen(fontScale = 1.0f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `home screen fits viewport and satisfies a11y in en at 1_3x`() {
        verifyHomeScreen(fontScale = 1.3f)
    }

    private fun verifyHomeScreen(fontScale: Float) {
        val readyState = WorkspaceState.Ready(
            handle = WorkspaceHandle(
                treeUri = "content://com.cyanix.pixfold.test/tree/comics",
                displayName = "超长测试工作区目录名称用于验证单行省略不溢出视口",
            ),
            collections = MockData.workspaceA.collections,
        )
        rule.setContent {
            PixFoldNativeTestHost(fontScale = fontScale) {
                HomeScreen(
                    onOpenOrganize = {},
                    onOpenLibrary = {},
                    onOpenSettings = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    workspaceState = readyState,
                    onPickWorkspace = {},
                    onOpenWorkspaceList = {},
                )
            }
        }
        rule.waitForIdle()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    // ================= MetadataPage =================

    @Test
    fun `metadata page fits viewport and satisfies a11y in zh-rCN at 1_0x`() {
        verifyMetadataPage(fontScale = 1.0f)
    }

    @Test
    fun `metadata page fits viewport and satisfies a11y in zh-rCN at 1_3x`() {
        verifyMetadataPage(fontScale = 1.3f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `metadata page fits viewport and satisfies a11y in en at 1_0x`() {
        verifyMetadataPage(fontScale = 1.0f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `metadata page fits viewport and satisfies a11y in en at 1_3x`() {
        verifyMetadataPage(fontScale = 1.3f)
    }

    private fun verifyMetadataPage(fontScale: Float) {
        val base = MockDataB.library
        // 让首卷 outputExists = true，连带覆盖工作流 C 底栏 extraContent 按钮
        val volumes = base.volumes.mapIndexed { idx, v ->
            if (idx == 0) v.copy(outputExists = true) else v
        }
        rule.setContent {
            PixFoldNativeTestHost(fontScale = fontScale) {
                var state by remember { mutableStateOf(libraryStateOf(base.rootPath, volumes)) }
                MetadataPage(
                    libraryState = state,
                    onLibraryChange = { state = it },
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    onBack = {},
                    onNext = {},
                    onUpdateMeta = {},
                )
            }
        }
        rule.waitForIdle()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        // 滚到批量设置区，覆盖底部的 FlowRow 芯片、输入框与按钮
        rule.onNodeWithTag(TAG_METADATA_LIST).performScrollToNode(hasTestTag(TAG_BATCH_ROW))
        rule.waitForIdle()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    // ================= PackPreviewPage =================

    @Test
    fun `pack preview page fits viewport and satisfies a11y in zh-rCN at 1_0x`() {
        verifyPackPreviewPage(fontScale = 1.0f)
    }

    @Test
    fun `pack preview page fits viewport and satisfies a11y in zh-rCN at 1_3x`() {
        verifyPackPreviewPage(fontScale = 1.3f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `pack preview page fits viewport and satisfies a11y in en at 1_0x`() {
        verifyPackPreviewPage(fontScale = 1.0f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `pack preview page fits viewport and satisfies a11y in en at 1_3x`() {
        verifyPackPreviewPage(fontScale = 1.3f)
    }

    private fun verifyPackPreviewPage(fontScale: Float) {
        val base = MockDataB.library
        rule.setContent {
            PixFoldNativeTestHost(fontScale = fontScale) {
                var state by remember { mutableStateOf(libraryStateOf(base.rootPath, base.volumes)) }
                PackPreviewPage(
                    libraryState = state,
                    onLibraryChange = { state = it },
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    showPlanSection = true,
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        // 滚到执行计划区，覆盖冲突/源策略 FlowRow 芯片与计划条目
        rule.onNodeWithTag(TAG_PACK_LIST).performScrollToNode(hasTestTag(TAG_CLEAN_EMPTY_TOGGLE))
        rule.waitForIdle()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    // ================= SettingsScreen =================

    @Test
    fun `settings screen fits viewport and satisfies a11y in zh-rCN at 1_0x`() {
        verifySettingsScreen(fontScale = 1.0f)
    }

    @Test
    fun `settings screen fits viewport and satisfies a11y in zh-rCN at 1_3x`() {
        verifySettingsScreen(fontScale = 1.3f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `settings screen fits viewport and satisfies a11y in en at 1_0x`() {
        verifySettingsScreen(fontScale = 1.0f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `settings screen fits viewport and satisfies a11y in en at 1_3x`() {
        verifySettingsScreen(fontScale = 1.3f)
    }

    private fun verifySettingsScreen(fontScale: Float) {
        rule.setContent {
            PixFoldNativeTestHost(fontScale = fontScale) {
                var settings by remember { mutableStateOf(PixFoldSettings(accent = AccentChoice.Default)) }
                SettingsScreen(
                    settings = settings,
                    onChange = { settings = it },
                    onBack = {},
                    onOpenLicenses = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        // 滚到底部「恢复默认设置」，完整覆盖下半屏的开关、操作行与底部按钮
        rule.onNodeWithTag(TAG_SETTINGS_RESET).performScrollTo()
        rule.waitForIdle()
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }
}
