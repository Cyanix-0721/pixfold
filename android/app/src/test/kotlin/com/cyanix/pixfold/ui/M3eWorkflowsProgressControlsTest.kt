package com.cyanix.pixfold.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.domain.library.libraryStateOf
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.library.CbzUpdateProgressDialog
import com.cyanix.pixfold.ui.library.LibraryPage
import com.cyanix.pixfold.ui.library.TAG_UPDATE_PROGRESS_LOADING
import com.cyanix.pixfold.ui.library.TAG_UPDATE_PROGRESS_WAVY
import com.cyanix.pixfold.ui.library.volumeUpdateTag
import com.cyanix.pixfold.ui.organize.SortAndPreviewPage
import com.cyanix.pixfold.ui.organize.TAG_GRID
import com.cyanix.pixfold.ui.organize.TAG_LIST
import com.cyanix.pixfold.ui.organize.TAG_MODE_GRID_OPTION
import com.cyanix.pixfold.ui.organize.TAG_MODE_LIST_OPTION
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
 * Slice U3（工作流 A/B/C 波浪进度、连接切换按钮、分段列表项与容装加载器）专项守卫（`junit4.v2`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_ZH_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M3eWorkflowsProgressControlsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun channelDistance(a: Int, b: Int): Int =
        abs(AndroidColor.red(a) - AndroidColor.red(b)) +
            abs(AndroidColor.green(a) - AndroidColor.green(b)) +
            abs(AndroidColor.blue(a) - AndroidColor.blue(b))

    @Test
    fun `cbzUpdateProgressDialog determinate renders CircularWavyProgressIndicator and hides loading indicator`() {
        rule.setContent {
            PixFoldNativeTestHost {
                CbzUpdateProgressDialog(
                    done = 5,
                    total = 20,
                    cancelling = false,
                    volumeName = "Vol.01.cbz",
                    onCancel = {},
                )
            }
        }
        rule.waitForIdle()

        val wavy = rule.onNodeWithTag(TAG_UPDATE_PROGRESS_WAVY)
        wavy.assertIsDisplayed()
        wavy.assertRangeInfoEquals(ProgressBarRangeInfo(current = 0.25f, range = 0f..1f, steps = 0))
        val bounds = wavy.getUnclippedBoundsInRoot()
        assertEquals(88f, (bounds.right - bounds.left).value, 0.5f)
        assertEquals(88f, (bounds.bottom - bounds.top).value, 0.5f)

        rule.onNodeWithTag(TAG_UPDATE_PROGRESS_LOADING).assertDoesNotExist()
    }

    @Test
    fun `cbzUpdateProgressDialog indeterminate renders ContainedLoadingIndicator and hides wavy ring`() {
        rule.setContent {
            PixFoldNativeTestHost {
                CbzUpdateProgressDialog(
                    done = 3,
                    total = 0,
                    cancelling = false,
                    volumeName = "Vol.01.cbz",
                    onCancel = {},
                )
            }
        }
        rule.waitForIdle()

        val loading = rule.onNodeWithTag(TAG_UPDATE_PROGRESS_LOADING)
        loading.assertIsDisplayed()
        val bounds = loading.getUnclippedBoundsInRoot()
        assertEquals(88f, (bounds.right - bounds.left).value, 0.5f)
        assertEquals(88f, (bounds.bottom - bounds.top).value, 0.5f)

        rule.onNodeWithTag(TAG_UPDATE_PROGRESS_WAVY).assertDoesNotExist()
    }

    @Test
    fun `sortAndPreviewPage connected mode toggle fits 361dp at 1_3x in zh-rCN with 48dp targets`() {
        verifyConnectedModeToggleAtLargeFont()
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `sortAndPreviewPage connected mode toggle fits 361dp at 1_3x in en with 48dp targets`() {
        verifyConnectedModeToggleAtLargeFont()
    }

    private fun verifyConnectedModeToggleAtLargeFont() {
        rule.setContent {
            PixFoldNativeTestHost(fontScale = 1.3f) {
                SortAndPreviewPage(
                    collections = MockData.workspaceA.collections,
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    onNext = {},
                )
            }
        }
        rule.waitForIdle()

        val gridOption = rule.onNodeWithTag(TAG_MODE_GRID_OPTION)
        val listOption = rule.onNodeWithTag(TAG_MODE_LIST_OPTION)
        assertRowsFitViewport(listOf(gridOption, listOption))

        val gridBounds = gridOption.getUnclippedBoundsInRoot()
        val listBounds = listOption.getUnclippedBoundsInRoot()
        assertTrue(
            (gridBounds.bottom - gridBounds.top).value >= 48f - 0.5f,
            "网格切换按钮高度必须 >= 48dp，实测 ${(gridBounds.bottom - gridBounds.top).value}dp",
        )
        assertTrue(
            (listBounds.bottom - listBounds.top).value >= 48f - 0.5f,
            "列表切换按钮高度必须 >= 48dp，实测 ${(listBounds.bottom - listBounds.top).value}dp",
        )
        assertTrue(
            gridBounds.right.value <= listBounds.left.value + 0.5f,
            "网格(${gridBounds.right})与列表(${listBounds.left})连接切换按钮不应重叠",
        )
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
        listOption.performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LIST).assertIsDisplayed()

        gridOption.performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
    }

    @Test
    fun `sortAndPreviewPage PureBlack mode draws visible outlineVariant border on unchecked mode toggle`() {
        rule.setContent {
            PixFoldNativeTestHost(
                settings = PixFoldSettings(
                    themeMode = ThemeMode.Dark,
                    accent = AccentChoice.Default,
                    pureBlack = true,
                ),
            ) {
                SortAndPreviewPage(
                    collections = MockData.workspaceA.collections,
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    onNext = {},
                )
            }
        }
        rule.waitForIdle()

        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val uncheckedBounds = rule.onNodeWithTag(TAG_MODE_LIST_OPTION).getBoundsInRoot()
        val density = rule.density.density
        val midX = (((uncheckedBounds.left + uncheckedBounds.right).value / 2f) * density).toInt()
        val topEdgeY = (uncheckedBounds.top.value * density).toInt() + 1
        val outerBgPixel = bmp.getPixel(midX, (uncheckedBounds.top.value * density).toInt() - 4)
        val edgePixel = bmp.getPixel(midX, topEdgeY)
        assertTrue(
            channelDistance(edgePixel, outerBgPixel) >= 24,
            "纯黑模式下未选中列表切换按钮上边应有清晰 outlineVariant 描边（edge=$edgePixel bg=$outerBgPixel）",
        )
    }

    @Test
    fun `libraryPage segmented items and Workflow C button fit 361dp at 1_3x with 48dp targets`() {
        val base = MockDataB.library
        val volumes = base.volumes.mapIndexed { idx, v ->
            if (idx == 0) v.copy(outputExists = true) else v
        }
        rule.setContent {
            PixFoldNativeTestHost(fontScale = 1.3f) {
                var state by remember { mutableStateOf(libraryStateOf(base.rootPath, volumes)) }
                LibraryPage(
                    state = state,
                    onStateChange = { state = it },
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    onNext = {},
                    onUpdateMeta = {},
                )
            }
        }
        rule.waitForIdle()

        val updateBtn = rule.onNodeWithTag(volumeUpdateTag(volumes.first().id))
        updateBtn.assertIsDisplayed()
        val touchBounds = updateBtn.fetchSemanticsNode().touchBoundsInRoot
        val density = rule.density.density
        val touchWidthDp = touchBounds.width / density
        val touchHeightDp = touchBounds.height / density
        assertTrue(
            touchWidthDp >= 48f - 0.5f,
            "工作流 C 入口图标按钮触控宽度必须 >= 48dp，实测 ${touchWidthDp}dp",
        )
        assertTrue(
            touchHeightDp >= 48f - 0.5f,
            "工作流 C 入口图标按钮触控高度必须 >= 48dp，实测 ${touchHeightDp}dp",
        )
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    @Test
    fun `all U3 files avoid hardcoded RoundedCornerShape`() {
        val u3Files = listOf(
            "src/main/kotlin/com/cyanix/pixfold/ui/components/TaskProgressBar.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/PackProgressDialog.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/library/CbzUpdateDialogs.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/organize/OrganizeSteps.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/library/LibrarySteps.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/ResetAllButton.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/DirMetaButton.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/MetaActionButton.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/ScrollToTopButton.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/DraggableThumbCard.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/ThumbRow.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/DragReorderGrid.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/components/DragReorderList.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/organize/SortAndPreviewPage.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/organize/SortRuleEditor.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/organize/NamingPage.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/organize/SchemeEditor.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/organize/ProposalTable.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/library/LibraryPage.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/library/MetadataPage.kt",
            "src/main/kotlin/com/cyanix/pixfold/ui/library/PackPreviewPage.kt",
        )
        u3Files.forEach { rel ->
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
