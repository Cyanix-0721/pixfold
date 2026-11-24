package com.cyanix.pixfold.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.naming.setOverride
import com.cyanix.pixfold.domain.plan.ConfirmFlowState
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.plan.executePackagePlans
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.components.SingleSelectChipRow
import com.cyanix.pixfold.ui.organize.RenamePlanPage
import com.cyanix.pixfold.ui.organize.TAG_A_PLAN_FILTER_ALL
import com.cyanix.pixfold.ui.organize.TAG_A_PLAN_FILTER_ISSUES
import com.cyanix.pixfold.ui.plan.ExecutionReportPage
import com.cyanix.pixfold.ui.plan.TAG_REPORT_FILTER_ALL
import com.cyanix.pixfold.ui.plan.TAG_REPORT_FILTER_ISSUES
import com.cyanix.pixfold.ui.plan.TAG_REPORT_UNDO
import com.cyanix.pixfold.ui.plan.TAG_UNDO_SCOPE_CANCEL
import com.cyanix.pixfold.ui.plan.TAG_UNDO_SCOPE_DIALOG
import com.cyanix.pixfold.ui.plan.TAG_UNDO_SCOPE_EVERYTHING
import com.cyanix.pixfold.ui.plan.TAG_UNDO_SCOPE_HERO_BADGE
import com.cyanix.pixfold.ui.plan.TAG_UNDO_SCOPE_THIS_RUN
import com.cyanix.pixfold.ui.preview.ImagePreview
import com.cyanix.pixfold.ui.preview.PreviewRename
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_CLOSE
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_NEXT
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_PAGE_LABEL
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_PREV
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_RENAME
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_TOOLBAR
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Slice U4（计划页、执行报告页、撤销范围弹窗 SoftBurst 徽章、芯片排版微调与预览浮条）专项守卫（`junit4.v2`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_ZH_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M3ePlanReportPreviewTest {

    @get:Rule
    val rule = createComposeRule()

    private fun channelDistance(a: Int, b: Int): Int =
        abs(AndroidColor.red(a) - AndroidColor.red(b)) +
            abs(AndroidColor.green(a) - AndroidColor.green(b)) +
            abs(AndroidColor.blue(a) - AndroidColor.blue(b))

    @Test
    fun `undoScopeDialog renders centered 56dp SoftBurst hero badge above title and crisp PureBlack border`() {
        val op102 = MockDataB.library.volumes.first { it.id == "op-102" }
        val report = executePackagePlans(
            buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite),
        )
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
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

        val dlgBounds = rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).getBoundsInRoot()
        val badgeNode = rule.onNodeWithTag(TAG_UNDO_SCOPE_HERO_BADGE)
        badgeNode.assertIsDisplayed()
        val badgeBounds = badgeNode.getBoundsInRoot()
        assertEquals(56f, (badgeBounds.right - badgeBounds.left).value, 0.5f)
        assertEquals(56f, (badgeBounds.bottom - badgeBounds.top).value, 0.5f)

        val dlgCenterX = ((dlgBounds.left + dlgBounds.right) / 2f).value
        val badgeCenterX = ((badgeBounds.left + badgeBounds.right) / 2f).value
        assertTrue(
            abs(badgeCenterX - dlgCenterX) <= 1.5f,
            "SoftBurst 徽章应在对话框内水平居中（badgeCenterX=$badgeCenterX vs dlgCenterX=$dlgCenterX）",
        )

        val titleBounds = rule.onNodeWithText(TestText.of(R.string.report_undo_warning_title))
            .getBoundsInRoot()
        assertTrue(
            badgeBounds.bottom.value < titleBounds.top.value,
            "SoftBurst 徽章应位于标题上方（badgeBottom=${badgeBounds.bottom} vs titleTop=${titleBounds.top}）",
        )

        val safeTop = rule.onNodeWithTag(TAG_UNDO_SCOPE_THIS_RUN).getBoundsInRoot().top.value
        val dangerTop = rule.onNodeWithTag(TAG_UNDO_SCOPE_EVERYTHING).getBoundsInRoot().top.value
        val cancelTop = rule.onNodeWithTag(TAG_UNDO_SCOPE_CANCEL).getBoundsInRoot().top.value
        assertTrue(safeTop < dangerTop && dangerTop < cancelTop, "三按钮顺序须为 safe → danger → cancel")

        val dlgBmp = rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).captureToImage().asAndroidBitmap()
        val topEdge = dlgBmp.getPixel(dlgBmp.width / 2, 1)
        val interior = dlgBmp.getPixel(dlgBmp.width / 2, 24)
        assertTrue(
            channelDistance(topEdge, interior) >= 24,
            "纯黑模式下 UndoScopeDialog 顶边应有清晰 outlineVariant 描边",
        )

        val src = readAppSource("src/main/kotlin/com/cyanix/pixfold/ui/plan/ExecutionReportPage.kt")
        assertTrue(src.contains("MaterialShapes.SoftBurst"), "UndoScopeDialog 徽章应使用 MaterialShapes.SoftBurst")
    }

    @Test
    fun `issueFilterRow connected toggles fit 361dp at 1_3x in zh-rCN with 48dp targets`() {
        verifyRenamePlanIssueFilterAtLargeFont()
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `issueFilterRow connected toggles fit 361dp at 1_3x in en with 48dp targets`() {
        verifyRenamePlanIssueFilterAtLargeFont()
    }

    private fun verifyRenamePlanIssueFilterAtLargeFont() {
        val misc = MockData.workspaceA.collections.filter { it.id == "misc" }
        val naming = NamingState(NamingScheme(rootDirName = "杂图"))
            .let { setOverride(it, "misc-5", "dup.jpg") }
            .let { setOverride(it, "misc-9", "dup.jpg") }

        rule.setContent {
            PixFoldNativeTestHost(fontScale = 1.3f) {
                RenamePlanPage(
                    collections = misc,
                    orders = emptyMap(),
                    namingState = naming,
                    cleanEmptyDirs = false,
                    onCleanEmptyDirsChange = {},
                    flow = ConfirmFlowState(),
                    onStartConfirm = {},
                    onConfirmStep = {},
                    onCancelConfirm = {},
                    onExecute = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()

        val allBtn = rule.onNodeWithTag(TAG_A_PLAN_FILTER_ALL)
        val issuesBtn = rule.onNodeWithTag(TAG_A_PLAN_FILTER_ISSUES)
        assertRowsFitViewport(listOf(allBtn, issuesBtn))

        val allBounds = allBtn.getUnclippedBoundsInRoot()
        val issuesBounds = issuesBtn.getUnclippedBoundsInRoot()
        assertTrue(
            (allBounds.bottom - allBounds.top).value >= 48f - 0.5f,
            "全部筛选按钮高度必须 >= 48dp，实测 ${(allBounds.bottom - allBounds.top).value}dp",
        )
        assertTrue(
            (issuesBounds.bottom - issuesBounds.top).value >= 48f - 0.5f,
            "仅问题筛选按钮高度必须 >= 48dp，实测 ${(issuesBounds.bottom - issuesBounds.top).value}dp",
        )
        val gapDp = issuesBounds.left.value - allBounds.right.value
        assertTrue(
            gapDp in -0.5f..4.0f,
            "Connected Toggle Group 间距应为 ConnectedSpaceBetween (<= 4dp)，实测 ${gapDp}dp",
        )
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        val filterSrc = readAppSource("src/main/kotlin/com/cyanix/pixfold/ui/components/IssueFilterRow.kt")
        assertTrue(filterSrc.contains("FilledTonalToggleButton("), "IssueFilterRow 应使用 M3E FilledTonalToggleButton")
        assertTrue(filterSrc.contains("connectedLeadingButtonShapes()"), "IssueFilterRow 应使用 connectedLeadingButtonShapes")
        assertTrue(filterSrc.contains("connectedTrailingButtonShapes()"), "IssueFilterRow 应使用 connectedTrailingButtonShapes")
    }

    @Test
    fun `issueFilterRow draws visible outlineVariant border on unchecked toggle in PureBlack mode`() {
        val op102 = MockDataB.library.volumes.first { it.id == "op-102" }
        val report = executePackagePlans(
            buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite),
        )
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
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

        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val uncheckedBounds = rule.onNodeWithTag(TAG_REPORT_FILTER_ISSUES).getBoundsInRoot()
        val density = rule.density.density
        val midX = (((uncheckedBounds.left + uncheckedBounds.right).value / 2f) * density).toInt()
        val topEdgeY = (uncheckedBounds.top.value * density).toInt() + 1
        val outerBgY = ((uncheckedBounds.top.value * density).toInt() - 4).coerceAtLeast(0)
        val edgePixel = bmp.getPixel(midX, topEdgeY)
        val outerBgPixel = bmp.getPixel(midX, outerBgY)
        assertTrue(
            channelDistance(edgePixel, outerBgPixel) >= 24,
            "纯黑模式下 IssueFilterRow 未选中项顶边应有清晰 outlineVariant 描边（edge=$edgePixel bg=$outerBgPixel）",
        )
        rule.onNodeWithTag(TAG_REPORT_FILTER_ALL).assertIsDisplayed()
    }

    @Test
    fun `imagePreview renders HorizontalFloatingToolbar controls and 48dp a11y in zh-rCN at 1_3x`() {
        verifyImagePreviewToolbarAtLargeFont()
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `imagePreview renders HorizontalFloatingToolbar controls and 48dp a11y in en at 1_3x`() {
        verifyImagePreviewToolbarAtLargeFont()
    }

    private fun verifyImagePreviewToolbarAtLargeFont() {
        val images = MockData.workspaceA.collections.first().images.take(5)
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = false,
                ),
                fontScale = 1.3f,
            ) {
                ImagePreview(
                    items = images,
                    initialIndex = 1,
                    onClose = {},
                    rename = PreviewRename(
                        overrides = emptyMap(),
                        finalNameOf = { it.fileName },
                        onRename = { _, _ -> },
                    ),
                )
            }
        }
        rule.waitForIdle()

        val toolbarNode = rule.onNodeWithTag(TAG_PREVIEW_TOOLBAR)
        toolbarNode.assertIsDisplayed()
        val toolbarBounds = toolbarNode.getUnclippedBoundsInRoot()
        assertRectFitsViewport(toolbarBounds, label = TAG_PREVIEW_TOOLBAR)

        val pageLabelBounds = rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).getUnclippedBoundsInRoot()
        assertTrue(
            pageLabelBounds.left.value >= toolbarBounds.left.value - 0.5f &&
                pageLabelBounds.right.value <= toolbarBounds.right.value + 0.5f &&
                pageLabelBounds.top.value >= toolbarBounds.top.value - 0.5f &&
                pageLabelBounds.bottom.value <= toolbarBounds.bottom.value + 0.5f,
            "页码胶囊应位于底部 HorizontalFloatingToolbar 内（label=$pageLabelBounds toolbar=$toolbarBounds）",
        )

        listOf(TAG_PREVIEW_PREV, TAG_PREVIEW_RENAME, TAG_PREVIEW_NEXT, TAG_PREVIEW_CLOSE).forEach { tag ->
            val node = rule.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val semantics = node.fetchSemanticsNode()
            val cd = semantics.config.getOrNull(SemanticsProperties.ContentDescription)
            assertTrue(cd?.any { it.isNotBlank() } == true, "$tag 纯图标按钮必须提供非空 contentDescription")
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue((bounds.right - bounds.left).value >= 48f - 0.5f, "$tag 宽度必须 >= 48dp")
            assertTrue((bounds.bottom - bounds.top).value >= 48f - 0.5f, "$tag 高度必须 >= 48dp")
        }

        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        val previewSrc = readAppSource("src/main/kotlin/com/cyanix/pixfold/ui/preview/ImagePreview.kt")
        assertTrue(previewSrc.contains("HorizontalFloatingToolbar("), "ImagePreview 应使用 M3E HorizontalFloatingToolbar")
        assertTrue(previewSrc.contains("R.drawable.ic_chevron_left"), "ImagePreview 上一页应引用 ic_chevron_left")
    }

    @Test
    fun `singleSelectChipRow uses labelLargeEmphasized for selected chip and labelLarge for unselected`() {
        rule.setContent {
            PixFoldNativeTestHost {
                val baseTypography = MaterialTheme.typography
                val distinctTypography = baseTypography.copy(
                    labelLarge = baseTypography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    labelLargeEmphasized = baseTypography.labelLargeEmphasized.copy(fontWeight = FontWeight.Bold),
                )
                MaterialTheme(typography = distinctTypography) {
                    Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                        SingleSelectChipRow(
                            items = listOf("Alpha", "Beta"),
                            activeId = "Alpha",
                            idOf = { it },
                            onSelect = {},
                            selectorTag = "chip-weight-row",
                            chipTag = { id -> "chip-weight-$id" },
                            allEntryTag = "chip-weight-all",
                            chipDescription = { it },
                            chipLabel = { it },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        val selectedResults = mutableListOf<TextLayoutResult>()
        val unselectedResults = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("chip-weight-Alpha")
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(selectedResults)
        rule.onNodeWithTag("chip-weight-Beta")
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(unselectedResults)

        val selectedWeight = selectedResults.firstOrNull()?.layoutInput?.style?.fontWeight
        val unselectedWeight = unselectedResults.firstOrNull()?.layoutInput?.style?.fontWeight
        assertNotNull(selectedWeight, "应能读取选中芯片的字重")
        assertNotNull(unselectedWeight, "应能读取未选中芯片的字重")
        assertEquals(FontWeight.Bold, selectedWeight, "选中芯片应使用 MaterialTheme.typography.labelLargeEmphasized")
        assertEquals(FontWeight.Medium, unselectedWeight, "未选中芯片应使用 MaterialTheme.typography.labelLarge")
        assertTrue(
            selectedWeight.weight > unselectedWeight.weight,
            "选中芯片字重(${selectedWeight.weight})应大于未选中芯片(${unselectedWeight.weight})",
        )
    }

    @Test
    fun `all U4 files avoid hardcoded RoundedCornerShape`() {
        val u4Files = listOf(
            "src/main/kotlin/com/cyanix/pixfold/ui/components/IssueFilterRow.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/plan/PlanComponents.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/organize/RenamePlanPage.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/plan/ExecutionReportPage.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/library/CbzUpdateDialogs.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/SingleSelectChipRow.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/preview/ImagePreview.kt",
        )
        u4Files.forEach { rel ->
            val code = readAppSource(rel).lines().joinToString("\n") { it.substringBefore("//") }
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

    private fun readAppSource(relativePath: String): String {
        val file = File(relativePath).takeIf { it.exists() } ?: File("android/app/$relativePath")
        assertTrue(file.exists(), "源码文件不存在: ${file.path}")
        return file.readText()
    }
}
