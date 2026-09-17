package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.SortField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 排序规则**变动即自动应用**在页面上的落地(用户 2026-09-17 指定)。
 *
 * 与 [SortRuleDirectionTest] 的分工:那里断言"编辑器是否正确上报",
 * 这里断言"**页面是否真的按新规则重排了网格**" —— 后者才是用户可见的结果,
 * 也才能挡住"上报了但没接上"这类缺陷(归档原型 4 轮未定位的缺陷类别)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp")
class RuleAutoApplyPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val collections: List<ImageCollection> = MockData.workspaceA.collections

    private fun setPage() {
        rule.setContent {
            SortAndPreviewPage(
                collections = collections,
                contentInsets = WindowInsets(0, 0, 0, 0),
                initialCollectionId = "trip",
            )
        }
    }

    /** 读网格**首项**;先滚到顶部,避免"语义树只含可见项"导致的误读(见 HANGOOF W1)。 */
    private fun firstCardName(): String {
        val action = SemanticsActions.ScrollToIndex
        val grid = rule.onNodeWithTag(TAG_GRID).fetchSemanticsNode()
        grid.config[action].action?.invoke(0)
        rule.waitForIdle()

        val key = SemanticsProperties.ContentDescription
        return rule.onAllNodesWithTag("grid-card").fetchSemanticsNodes()
            .mapNotNull { n ->
                val b = n.boundsInRoot
                val name = if (n.config.contains(key)) n.config[key].first() else "?"
                Triple(b.top, b.left, name)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .first().third
    }

    @Test
    fun `changing sort field reorders the grid without pressing any apply button`() {
        setPage()

        // trip 默认 = 目录名升序,文件名自然升序 -> 首项为 day1 的 001
        assertTrue(
            firstCardName().contains("IMG_20260701_001"),
            "初始首项应为 day1/001,实际=${firstCardName()}",
        )

        // 改第 1 级为"文件名(自然)"并**再点一次**翻成降序 —— 全程不点任何"应用"按钮
        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick()
        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick()

        // 文件名降序 -> 最大者 IMG_20260702_012 应排首(与领域语义一致)
        val first = firstCardName()
        assertTrue(
            first.contains("IMG_20260702_012"),
            "改为'文件名(自然)降序'后应立即重排,首项应为 IMG_20260702_012,实际=$first",
        )
    }

    @Test
    fun `flipping direction alone reorders the grid immediately`() {
        setPage()

        // 默认首项 day1/001;把第 2 级(文件名自然)翻转不影响主序(主序是目录名),
        // 故这里直接改第 1 级为文件名自然(升序)-> 首项仍是 day1/001,
        // 再翻转一次 -> 首项应变为 day2/012
        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick() // 切字段(升序)
        val afterSwitch = firstCardName()
        assertTrue(afterSwitch.contains("IMG_20260701_001"), "升序时首项应为 001,实际=$afterSwitch")

        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick() // 翻成降序
        val afterFlip = firstCardName()
        assertTrue(afterFlip.contains("IMG_20260702_012"), "翻转后首项应为 012,实际=$afterFlip")
    }

    @Test
    fun `adding a level reorders according to the new tiebreaker immediately`() {
        setPage()

        val before = firstCardName()
        rule.onNodeWithTag(TAG_RULE_ADD).performClick()

        // 新增级默认升序,不应让首项变成空/异常;仍应能读到卡片
        val after = firstCardName()
        assertTrue(after.isNotEmpty(), "新增排序级后网格仍应有内容")
        assertEquals(before, after, "仅追加升序并列键不应改变首项")
    }
}
