package com.cyanix.pixfold.ui.workflowa

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.ui.components.TAG_SCROLL_TO_TOP
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * W1(2026-09-17 实测缺陷):**切换集合后视图自动回到顶部**。
 *
 * 缺陷表现:切集合后网格/列表**保留上一个集合的滚动位置**,用户切过去看到的是
 * "中段"而不是第一张 —— 需要手动滑回顶部才知道自己切成功了。
 *
 * 为什么它是**真缺陷**而不是"体验偏好":
 *  - 切换集合的语义是"换一批图看",保留上一个集合的偏移量是**无意义的残留状态**;
 *  - 它还会**误导测试**:语义树只含可见项,保留偏移时读到的是"视口中段",
 *    很容易被误当成列表头部(该缺陷本身就被记为会误导测试)。
 *
 * 实现复用 W4 已建立的机制(`scrollToTopSignal` 自增 -> `requestScrollToItem(0)`),
 * 而不是另接一套滚动逻辑 —— 两处需求相同(视口位置失去意义),共用一条通道。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp")
class CollectionSwitchScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private val collections: List<ImageCollection> = MockData.workspaceA.collections

    /**
     * 本文件测的是 W1(切集合回顶),**与列数无关** ⇒ 把列数钉死,不让它随
     * `ADAPTIVE_COLUMN_MIN_WIDTH` 漂移。
     *
     * 起因:自适应最小卡宽从 110dp 调到 80dp 后 `w400dp-h1000dp` 的默认列数由 3 变 4,
     * 卡片变小 ⇒ `scan`(只有 14 张)在 4 列下不再溢出视口,"下滑"这个前置条件**根本无法成立**,
     * 两条用例因此假红。用 2 列(卡片最大)让各集合在测试视口下必然可滚,
     * 前置条件从此与列数算式解耦。
     */
    private fun setPage(mode: ViewMode = ViewMode.Grid) {
        rule.setContent {
            SortAndPreviewPage(
                collections = collections,
                contentInsets = WindowInsets(0, 0, 0, 0),
                initialCollectionId = "trip",
                initialMode = mode,
                portraitDefaultColumns = SCROLL_TEST_COLUMNS,
                landscapeDefaultColumns = SCROLL_TEST_COLUMNS,
            )
        }
    }

    /** 用语义动作把网格滚到第 [index] 项(替代真实手势,Robolectric 下更稳定)。 */
    private fun scrollGridTo(index: Int) {
        val grid = rule.onNodeWithTag(TAG_GRID).fetchSemanticsNode()
        grid.config[SemanticsActions.ScrollToIndex].action?.invoke(index)
        rule.waitForIdle()
    }

    /** 同上,但作用于**列表**视图 —— 两个视图各有自己的滚动状态,必须分别验证。 */
    private fun scrollListTo(index: Int) {
        val list = rule.onNodeWithTag(TAG_LIST).fetchSemanticsNode()
        list.config[SemanticsActions.ScrollToIndex].action?.invoke(index)
        rule.waitForIdle()
    }

    /** 回顶按钮是否可见(= 已下滑)。 */
    private fun scrollToTopVisible(): Boolean =
        rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `switching collection scrolls the view back to top`() {
        setPage()

        // 前置:在 trip 集合里下滑,回顶按钮出现 -> 证明确实不在顶部
        scrollGridTo(20)
        assertTrue(scrollToTopVisible(), "前置条件:下滑后应显示回顶按钮（说明不在顶部）")

        // 切到另一个集合
        rule.onNodeWithTag(collectionTag("scan")).performClick()
        rule.waitForIdle()

        assertTrue(
            !scrollToTopVisible(),
            "切换集合后应自动回到顶部（回顶按钮随之隐藏），实际仍在非顶部位置",
        )
    }

    @Test
    fun `switching back and forth keeps resetting to top`() {
        setPage()

        // trip -> 下滑 -> 切 scan
        scrollGridTo(20)
        rule.onNodeWithTag(collectionTag("scan")).performClick()
        rule.waitForIdle()
        assertTrue(!scrollToTopVisible(), "第 1 次切换后应回顶")

        // scan -> 下滑 -> 切回 trip(每个集合各自都要重置换行位置)
        scrollGridTo(10)
        assertTrue(scrollToTopVisible(), "前置条件:scan 里下滑后应有回顶按钮")
        rule.onNodeWithTag(collectionTag("trip")).performClick()
        rule.waitForIdle()
        assertTrue(
            !scrollToTopVisible(),
            "切回 trip 也应回顶 —— 每个集合的偏移量都不该被带到别的集合上",
        )
    }

    @Test
    fun `switching collection does not disturb the ability to scroll afterwards`() {
        // 防止修 W1 时引入 W4 那类过度回顶:回顶只发生一次,之后用户仍能自由滚动
        setPage()
        scrollGridTo(20)
        rule.onNodeWithTag(collectionTag("scan")).performClick()
        rule.waitForIdle()

        scrollGridTo(9)
        assertTrue(
            scrollToTopVisible(),
            "切换集合后用户仍应能正常下滑（回顶只发生一次，不得反复把用户拽回顶部）",
        )
    }

    // ---- 列表视图同样要修:两个视图各有独立的滚动状态,只修网格不算修好 ----

    @Test
    fun `switching collection also scrolls the list view back to top`() {
        setPage(mode = ViewMode.List)

        scrollListTo(20)
        assertTrue(scrollToTopVisible(), "前置条件:列表下滑后应显示回顶按钮")

        rule.onNodeWithTag(collectionTag("scan")).performClick()
        rule.waitForIdle()

        assertTrue(
            !scrollToTopVisible(),
            "列表视图切换集合后同样应回到顶部",
        )
    }

    @Test
    fun `tapping the already active collection chip does not yank the user back to top`() {
        // 少了这个场景时,「无条件回顶」的变异能溜过去(实测发现,已补)。
        // 语义:没有真的换集合,就不该认为"视口位置失去意义"。
        setPage()
        scrollGridTo(20)
        assertTrue(scrollToTopVisible(), "前置条件:下滑后应显示回顶按钮")

        // 点**当前已选中**的集合并(activeId 不变)
        rule.onNodeWithTag(collectionTag("trip")).performClick()
        rule.waitForIdle()

        assertTrue(
            scrollToTopVisible(),
            "重复点击当前集合不应把用户拽回顶部（没有真的切换集合）",
        )
    }

    @Test
    fun `switching collection while already at top is a no-op`() {
        setPage()
        // 本就在顶部:切集合不应产生异常,也不该出现回顶按钮
        assertTrue(!scrollToTopVisible())
        rule.onNodeWithTag(collectionTag("scan")).performClick()
        rule.waitForIdle()
        assertTrue(!scrollToTopVisible(), "本就在顶部时切换集合仍应停在顶部")
    }

    private companion object {
        /**
         * 钉死 2 列 —— 卡片最大,保证 trip(30 张)/ scan(14 张)在本文件视口下都必然溢出。
         *
         * 不写成页面默认值(自适应)是刻意的:本文件不该因为列数算式的改动而变红,
         * 那条算式由 `SortPagePolishTest` 与 `ColumnOptionsTest` 专门守护。
         */
        const val SCROLL_TEST_COLUMNS = 2
    }
}
