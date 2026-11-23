package com.cyanix.pixfold.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.plan.executePackagePlans
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.ui.components.ChipRowTags
import com.cyanix.pixfold.ui.components.SingleSelectChipRow
import com.cyanix.pixfold.ui.components.TwoLevelChipRows
import com.cyanix.pixfold.ui.components.TwoLevelGroup
import com.cyanix.pixfold.ui.plan.ExecutionReportPage
import com.cyanix.pixfold.ui.plan.TAG_REPORT_UNDO
import com.cyanix.pixfold.ui.plan.TAG_UNDO_SCOPE_DIALOG
import com.cyanix.pixfold.ui.settings.SettingsScreen
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 无 ADB Phase 2：基于 Roborazzi `1.79.0` + Robolectric `@GraphicsMode(NATIVE)` 的纯 JVM 视觉回归黄金图套件。
 *
 * 几何与语言限定符统一对齐真机 PJX110（`zh-rCN-w361dp-h794dp-560dpi`），
 * 比较容差设为 `changeThreshold = 0.01f`（1% 像素容差）以吸收跨环境 FreeType/Skia 亚像素抗锯齿差异，
 * 同时对纯黑卡片描边丢失、M3 2021 vs M3E 2025 色彩规范回退、底栏危险按钮变色等真实回归保持高杀伤力。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_ZH_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualRegressionSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(
            changeThreshold = 0.01f,
        ),
    )

    private fun snapshotPath(name: String): String =
        "src/test/snapshots/$name.png"

    private val readyWorkspace = WorkspaceState.Ready(
        handle = WorkspaceHandle(
            treeUri = "content://com.cyanix.pixfold.test/tree/comics",
            displayName = "漫画书库工作区 (PixFold)",
        ),
        collections = MockData.workspaceA.collections,
    )

    // ================= a) HomeScreen: Light / Dark / PureBlack =================

    @Test
    fun homeScreen_light() {
        renderHomeScreen(
            PixFoldSettings(
                themeMode = ThemeMode.Light,
                accent = AccentChoice.Default,
                pureBlack = false,
            ),
        )
        rule.onRoot().captureRoboImage(
            filePath = snapshotPath("home_light"),
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun homeScreen_dark() {
        renderHomeScreen(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                pureBlack = false,
            ),
        )
        rule.onRoot().captureRoboImage(
            filePath = snapshotPath("home_dark"),
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun homeScreen_pureBlack() {
        renderHomeScreen(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                pureBlack = true,
            ),
        )
        rule.onRoot().captureRoboImage(
            filePath = snapshotPath("home_pure_black"),
            roborazziOptions = roborazziOptions,
        )
        // 单独锁定纯黑模式首张大卡局部黄金图：整屏 1263×2779px 中 1dp 描边仅占 ~0.71% 面积，
        // 裁剪至单卡（1151×308px）后 1dp outlineVariant 描边占比达 ~3.52%（> 1% 容差阈值），
        // 确保移除或弱化纯黑卡片描边时必然触发视觉回归失败。
        rule.onNodeWithText("整理图片").captureToImage().asAndroidBitmap().captureRoboImage(
            filePath = snapshotPath("home_pure_black_card"),
            roborazziOptions = roborazziOptions,
        )
    }

    private fun renderHomeScreen(settings: PixFoldSettings) {
        rule.setContent {
            PixFoldNativeTestHost(settings = settings) {
                HomeScreen(
                    onOpenOrganize = {},
                    onOpenLibrary = {},
                    onOpenSettings = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    workspaceState = readyWorkspace,
                    onPickWorkspace = {},
                    onOpenWorkspaceList = {},
                )
            }
        }
        rule.waitForIdle()
    }

    // ================= b) Chips: Collapsed / Expanded / TwoLevel Popup =================

    private val chipItems = listOf(
        "短名",
        "超长中文系列名称测试用于验证原生排版宽度",
        "Very Long English Comic Volume Title For Native Layout",
        "第01卷",
        "另一个超长日文与中文混合作品名合集测试第02卷",
        "Short",
        "中长标题测试第03卷",
        "Series Alpha Vol 04",
        "番外篇合集特别收录第05卷",
        "Standalone Extra 06",
        "长标题第七卷归档预览样本",
        "Final Volume 08",
    )

    @Test
    fun chips_singleSelect_collapsedAndExpanded() {
        rule.setContent {
            PixFoldNativeTestHost {
                Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                    SingleSelectChipRow(
                        items = chipItems,
                        activeId = chipItems.first(),
                        idOf = { it },
                        onSelect = {},
                        selectorTag = TAG_CHIP_ROW,
                        chipTag = { id -> "chip-$id" },
                        allEntryTag = TAG_CHIP_ALL,
                        chipDescription = { it },
                        allEntryText = { n -> "全部 $n 项" },
                        chipLabel = { it },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            filePath = snapshotPath("chips_single_select_collapsed"),
            roborazziOptions = roborazziOptions,
        )

        rule.onNodeWithTag(TAG_CHIP_ALL).performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            filePath = snapshotPath("chips_single_select_expanded"),
            roborazziOptions = roborazziOptions,
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun chips_twoLevel_seriesPopupOpen() {
        val groups = listOf(
            TwoLevelGroup(
                key = "series:long-cn",
                name = "超长中文系列名称测试用于验证两级浮层排布",
                isSeries = true,
                members = listOf(
                    "第01卷 初遇篇",
                    "第02卷 超长副标题测试用来验证浮层内芯片自动换行",
                    "第03卷 English Subtitle Edition",
                    "第04卷 完结篇",
                ),
            ),
            TwoLevelGroup(
                key = "single:short",
                name = "单本短名",
                isSeries = false,
                members = listOf("单本短名"),
            ),
            TwoLevelGroup(
                key = "series:en",
                name = "Long English Anthology Series Collection",
                isSeries = true,
                members = listOf(
                    "Volume 01 Extended Cut",
                    "Volume 02 Bonus Chapters",
                ),
            ),
        )
        rule.setContent {
            PixFoldNativeTestHost {
                Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                    TwoLevelChipRows(
                        groups = groups,
                        activeItemId = groups.first().members.first(),
                        idOf = { it },
                        onSelect = {},
                        topTags = ChipRowTags(
                            selectorTag = TAG_TOP_ROW,
                            chipTag = { key -> "top-$key" },
                            allEntryTag = TAG_TOP_ALL,
                        ),
                        childTags = ChipRowTags(
                            selectorTag = TAG_CHILD_ROW,
                            chipTag = { id -> "child-$id" },
                            allEntryTag = TAG_CHILD_ALL,
                        ),
                        singleTopChipTag = { id -> "single-$id" },
                        topChipDescription = { it.name },
                        topChipLabel = { it.name },
                        topAllEntryText = { n -> "全部 $n 项" },
                        childChipDescription = { it },
                        childChipLabel = { it },
                        childAllEntryText = { n -> "全部 $n 卷" },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("top-${groups.first().key}").performClick()
        rule.waitForIdle()

        // 捕获合并多窗口整屏（含 1 级宿主行 + 2 级 Popup 浮层定位）与裁剪后的 Popup 独立窗口位图
        captureScreenRoboImage(
            filePath = snapshotPath("chips_two_level_popup_screen"),
            roborazziOptions = roborazziOptions,
        )
        rule.onNode(isPopup()).captureToImage().asAndroidBitmap().captureRoboImage(
            filePath = snapshotPath("chips_two_level_popup_window"),
            roborazziOptions = roborazziOptions,
        )
    }

    // ================= c) Bottom Bar (Workflow / Plan & Report): Light / Dark =================

    @Test
    fun bottomBar_workflowAndReport_light() {
        renderReportWithBottomBar(
            PixFoldSettings(
                themeMode = ThemeMode.Light,
                accent = AccentChoice.Default,
                pureBlack = false,
            ),
        )
        rule.onRoot().captureRoboImage(
            filePath = snapshotPath("bottom_bar_plan_light"),
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun bottomBar_workflowAndReport_dark() {
        renderReportWithBottomBar(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                pureBlack = false,
            ),
        )
        rule.onRoot().captureRoboImage(
            filePath = snapshotPath("bottom_bar_plan_dark"),
            roborazziOptions = roborazziOptions,
        )
    }

    private fun renderReportWithBottomBar(settings: PixFoldSettings) {
        val op102 = MockDataB.library.volumes.first { it.id == "op-102" }
        val report = executePackagePlans(
            buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite),
        )
        rule.setContent {
            PixFoldNativeTestHost(settings = settings) {
                ExecutionReportPage(
                    report = report,
                    undoMessage = null,
                    onUndoScope = {},
                    onDone = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()
    }

    // ================= d) SettingsScreen: M3 2021 vs M3E 2025 Color Spec =================

    @Test
    fun settingsScreen_colorSpec2021() {
        renderSettingsScreen(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                colorStyle = ColorStyle.Expressive,
                colorSpec = ColorSpecVersion.V2021,
                pureBlack = false,
            ),
        )
        rule.onRoot().captureRoboImage(
            filePath = snapshotPath("settings_color_spec_2021"),
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun settingsScreen_colorSpec2025() {
        renderSettingsScreen(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                colorStyle = ColorStyle.Expressive,
                colorSpec = ColorSpecVersion.V2025,
                pureBlack = false,
            ),
        )
        rule.onRoot().captureRoboImage(
            filePath = snapshotPath("settings_color_spec_2025"),
            roborazziOptions = roborazziOptions,
        )
    }

    private fun renderSettingsScreen(settings: PixFoldSettings) {
        rule.setContent {
            PixFoldNativeTestHost(settings = settings) {
                var current by remember { mutableStateOf(settings) }
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
    }

    // ================= e) Key Dialog (PixFoldDialog / Undo Scope Dialog): Dark / PureBlack =================

    @Test
    fun undoScopeDialog_dark() {
        renderAndOpenUndoScopeDialog(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                pureBlack = false,
            ),
        )
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).captureRoboImage(
            filePath = snapshotPath("dialog_undo_scope_dark"),
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun undoScopeDialog_pureBlack() {
        renderAndOpenUndoScopeDialog(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                pureBlack = true,
            ),
        )
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).captureRoboImage(
            filePath = snapshotPath("dialog_undo_scope_pure_black"),
            roborazziOptions = roborazziOptions,
        )
    }

    private fun renderAndOpenUndoScopeDialog(settings: PixFoldSettings) {
        val op102 = MockDataB.library.volumes.first { it.id == "op-102" }
        val report = executePackagePlans(
            buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite),
        )
        rule.setContent {
            PixFoldNativeTestHost(settings = settings) {
                ExecutionReportPage(
                    report = report,
                    undoMessage = null,
                    onUndoScope = {},
                    onDone = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()
        rule.waitForIdle()
    }

    private companion object {
        const val TAG_CHIP_ROW = "snapshot-chip-row"
        const val TAG_CHIP_ALL = "snapshot-chip-all"
        const val TAG_TOP_ROW = "snapshot-two-level-top"
        const val TAG_TOP_ALL = "snapshot-two-level-top-all"
        const val TAG_CHILD_ROW = "snapshot-two-level-child"
        const val TAG_CHILD_ALL = "snapshot-two-level-child-all"
    }
}
