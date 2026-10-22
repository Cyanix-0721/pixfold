package com.cyanix.pixfold.ui.library

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.libraryStateOf
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工作流 B · 步骤 1 的 UI 层断言。
 *
 * 钉死归档 `library_page.dart` 的列表语义:按**当前 series 值**分组、
 * 系列头聚合"待确认"、每卷有 included 勾选、建议值带来源、输出冲突有标记。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp")
class LibraryPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    private fun show(onChange: (LibraryState) -> Unit = {}) {
        rule.setContent {
            LibraryPage(
                state = libraryStateOf(library.rootPath, library.volumes),
                onStateChange = onChange,
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
    }

    /**
     * 读回某卷"建议 + 来源"那一行的文本。
     *
     * 必须用 **unmerged tree**:卷行整体 `clickable`(对无障碍是一个可点节点),
     * 合并语义会把行内 Text 的 testTag 收进父节点;合并树上找不到它 ——
     * 这是 Compose 语义合并的正常行为,不是缺陷。
     */
    private fun suggestionText(id: String): String =
        rule.onNodeWithTag(volumeSuggestionTag(id), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }

    @Test
    fun `every volume of the library is listed`() {
        show()
        library.volumes.forEach { v ->
            rule.onNodeWithTag(TAG_LIBRARY_LIST)
                .performScrollToNode(hasTestTag(volumeCardTag(v.id)))
            rule.onNodeWithTag(volumeCardTag(v.id)).assertIsDisplayed()
        }
    }

    @Test
    fun `volumes are grouped by series with a header per group`() {
        show()
        rule.onNodeWithTag(seriesHeaderTag("进击的巨人")).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(seriesHeaderTag("海贼王")))
        rule.onNodeWithTag(seriesHeaderTag("海贼王")).assertIsDisplayed()
    }

    @Test
    fun `summary counts series volumes included and pending`() {
        show()
        val state = libraryStateOf(library.rootPath, library.volumes)
        rule.onNodeWithText(
            TestText.plural(
                R.plurals.lib_summary,
                state.seriesGroups.size,
                state.seriesGroups.size,
                state.volumeCount,
                state.includedCount,
                state.pendingCount,
            ),
        ).assertIsDisplayed()
        assertEquals(1, state.pendingCount, "只有解析不出卷号的 aot-0 待确认")
    }

    @Test
    fun `series header aggregates the pending count`() {
        show()
        // 进击的巨人 3 卷里只有 aot-0 缺卷号 -> 1 卷待确认
        rule.onNodeWithText(TestText.of(R.string.lib_series_pending, 1)).assertIsDisplayed()
    }

    @Test
    fun `suggestion and source are shown per volume`() {
        show()
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeCardTag("aot-1")))
        // 建议值与其**来源**必须**真的显示在该卷那一行**上。
        // 用该卷专属的 tag 精确断言(而不是"页面某处有该文案"——那会放过"来源挂错行")。
        val text = suggestionText("aot-1")
        assertTrue(text.contains("进击的巨人"), "该卷应显示建议 Title:实际 「$text」")
        assertTrue(text.contains("目录层级 L2（系列层）"), "该卷应显示建议来源:实际 「$text」")
    }

    @Test
    fun `pending issue is shown on the volume row`() {
        show()
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeCardTag("aot-0")))
        rule.onNodeWithText(TestText.of(R.string.domain_pending_volume), substring = true).assertIsDisplayed()
    }

    @Test
    fun `output conflict is marked on the volume row`() {
        show()
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeCardTag("op-102")))
        rule.onNodeWithText(TestText.of(R.string.lib_output_conflict)).assertIsDisplayed()
    }

    @Test
    fun `unchecking a volume reports the new state`() {
        var latest: LibraryState? = null
        show { latest = it }
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeIncludedTag("aot-1")))
        rule.onNodeWithTag(volumeIncludedTag("aot-1")).performClick()
        rule.waitForIdle()
        assertEquals(4, latest?.includedCount, "取消勾选后已勾选数应变化")
    }

    @Test
    fun `tapping a volume selects it and marks it as current`() {
        var latest: LibraryState? = null
        show { latest = it }
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeCardTag("op-101")))
        rule.onNodeWithTag(volumeCardTag("op-101")).performClick()
        rule.waitForIdle()
        assertEquals("op-101", latest?.activeVolumeId, "点卷卡片应把它设为当前卷(供后续步骤使用)")
        rule.onNodeWithText(TestText.of(R.string.lib_volume_current)).assertIsDisplayed()
    }

    @Test
    fun `library heading shows the root path`() {
        show()
        rule.onNodeWithText(TestText.of(R.string.lib_root, library.rootPath)).assertIsDisplayed()
    }

    @Test
    fun `source is not truncated away at device width`() {
        // 真机宽度 361dp(比默认 400dp 更窄)。P4 的教训是"控件/文案在窄屏被挤没" ——
        // 故这里用 360dp 复现,断言建议的来源仍然完整出现在卷行上。
        rule.setContent {
            LibraryPage(
                state = libraryStateOf(library.rootPath, library.volumes),
                onStateChange = {},
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeCardTag("aot-1")))
        rule.onNodeWithTag(volumeSuggestionTag("aot-1"), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            suggestionText("aot-1").contains("目录层级 L2（系列层）"),
            "窄屏下建议来源仍须完整可见",
        )
    }

    @Test
    fun `every volume row exposes an included checkbox`() {
        show()
        library.volumes.forEach { v ->
            rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeIncludedTag(v.id)))
            rule.onNodeWithTag(volumeIncludedTag(v.id)).assertIsDisplayed()
        }
    }

    @Test
    fun `included and pending are independent dimensions`() {
        // 勾选状态(本次是否处理)与"待确认"(数据是否可信)必须分开报:
        // 若取消勾选就把待确认也抹掉,用户会看不到"这批里还有没确认的卷"。
        val volumes: List<ComicVolume> = library.volumes.map {
            if (it.id == "aot-0") it.copy(included = false) else it
        }
        rule.setContent {
            LibraryPage(
                state = libraryStateOf(library.rootPath, volumes),
                onStateChange = {},
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
        rule.onNodeWithText(TestText.plural(R.plurals.lib_summary, 2, 2, 5, 4, 1)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.lib_series_pending, 1)).assertIsDisplayed()
    }

    // ---- 结构警告条(2026-10-20 裁决「Series 三条语义」③:系列下嵌套目录要警告) ----

    @Test
    fun `nested dir warnings render as error-colored rows`() {
        rule.setContent {
            LibraryPage(
                state = libraryStateOf(library.rootPath, library.volumes),
                onStateChange = {},
                contentInsets = WindowInsets(0, 0, 0, 0),
                workspaceWarnings = listOf(
                    Msg(TextKey.NestedSeriesDir, listOf("系列A", "中间层")),
                ),
            )
        }
        rule.onNodeWithTag(structureWarningTag(0)).assertIsDisplayed()
        rule.onNodeWithText(
            TestText.of(R.string.domain_nested_series_dir, "系列A", "中间层"),
        ).assertIsDisplayed()
    }

    @Test
    fun `multiple warnings render in order with per-index tags`() {
        rule.setContent {
            LibraryPage(
                state = libraryStateOf(library.rootPath, library.volumes),
                onStateChange = {},
                contentInsets = WindowInsets(0, 0, 0, 0),
                workspaceWarnings = listOf(
                    Msg(TextKey.NestedSeriesDir, listOf("系列A", "乙")),
                    Msg(TextKey.NestedSeriesDir, listOf("系列A", "甲")),
                ),
            )
        }
        rule.onNodeWithTag(structureWarningTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(structureWarningTag(1)).assertIsDisplayed()
    }

    @Test
    fun `no warnings means no warning nodes`() {
        show()
        rule.onNodeWithTag(structureWarningTag(0)).assertDoesNotExist()
    }
}
