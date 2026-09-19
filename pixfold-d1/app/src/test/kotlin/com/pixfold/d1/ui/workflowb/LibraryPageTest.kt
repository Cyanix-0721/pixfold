package com.pixfold.d1.ui.workflowb

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.workflowb.WorkflowBState
import com.pixfold.d1.domain.workflowb.workflowBStateOf
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
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class LibraryPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    private fun show(onChange: (WorkflowBState) -> Unit = {}) {
        rule.setContent {
            LibraryPage(
                state = workflowBStateOf(library.rootPath, library.volumes),
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
        val state = workflowBStateOf(library.rootPath, library.volumes)
        rule.onNodeWithText(
            "识别到 ${state.seriesGroups.size} 个系列 / ${state.volumeCount} 卷 · " +
                "已勾选 ${state.includedCount} 卷 · 待确认 ${state.pendingCount} 卷",
        ).assertIsDisplayed()
        assertEquals(5, state.pendingCount, "五卷的语言全部未确认")
    }

    @Test
    fun `series header aggregates the pending count`() {
        show()
        // 进击的巨人 3 卷:语言全部未确认 + aot-0 缺卷号 -> 3 卷待确认
        rule.onNodeWithText("3 卷待确认").assertIsDisplayed()
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
        rule.onNodeWithText("卷号缺失", substring = true).assertIsDisplayed()
    }

    @Test
    fun `output conflict is marked on the volume row`() {
        show()
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeCardTag("op-102")))
        rule.onNodeWithText("输出冲突").assertIsDisplayed()
    }

    @Test
    fun `unchecking a volume reports the new state`() {
        var latest: WorkflowBState? = null
        show { latest = it }
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeIncludedTag("aot-1")))
        rule.onNodeWithTag(volumeIncludedTag("aot-1")).performClick()
        rule.waitForIdle()
        assertEquals(4, latest?.includedCount, "取消勾选后已勾选数应变化")
    }

    @Test
    fun `tapping a volume selects it and marks it as current`() {
        var latest: WorkflowBState? = null
        show { latest = it }
        rule.onNodeWithTag(TAG_LIBRARY_LIST).performScrollToNode(hasTestTag(volumeCardTag("op-101")))
        rule.onNodeWithTag(volumeCardTag("op-101")).performClick()
        rule.waitForIdle()
        assertEquals("op-101", latest?.activeVolumeId, "点卷卡片应把它设为当前卷(供后续步骤使用)")
        rule.onNodeWithText(" · 当前").assertIsDisplayed()
    }

    @Test
    fun `library heading shows the root path`() {
        show()
        rule.onNodeWithText("漫画库：${library.rootPath}").assertIsDisplayed()
    }

    @Test
    fun `source is not truncated away at device width`() {
        // 真机宽度 361dp(比默认 400dp 更窄)。P4 的教训是"控件/文案在窄屏被挤没" ——
        // 故这里用 360dp 复现,断言建议的来源仍然完整出现在卷行上。
        rule.setContent {
            LibraryPage(
                state = workflowBStateOf(library.rootPath, library.volumes),
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
                state = workflowBStateOf(library.rootPath, volumes),
                onStateChange = {},
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
        rule.onNodeWithText("已勾选 4 卷 · 待确认 5 卷", substring = true).assertIsDisplayed()
        rule.onNodeWithText("3 卷待确认").assertIsDisplayed()
    }
}
