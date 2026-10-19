package com.cyanix.pixfold.ui.organize

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.ui.components.TAG_GRID_CARD
import com.cyanix.pixfold.ui.components.TAG_LIST_ROW
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验收第 1 项:缩略图网格/列表双视图可切换,缩略图渲染正确。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SortAndPreviewPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val onlyCollection =
        MockData.workspaceA.collections.filter { it.id == "misc" }

    @Test
    fun `defaults to grid view`() {
        rule.setContent { SortAndPreviewPage(onlyCollection) }
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
    }

    @Test
    fun `toggling switches to list and back`() {
        rule.setContent { SortAndPreviewPage(onlyCollection) }
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()

        // 点"列表"选项(而非整行 toggle —— 整行中心可能落在两按钮之间)
        rule.onNodeWithTag(TAG_MODE_LIST_OPTION).performClick()
        rule.onNodeWithTag(TAG_LIST).assertIsDisplayed()

        rule.onNodeWithTag(TAG_MODE_GRID_OPTION).performClick()
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
    }

    @Test
    fun `grid renders cards`() {
        rule.setContent { SortAndPreviewPage(onlyCollection, initialMode = ViewMode.Grid) }
        assertTrue(
            rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isNotEmpty(),
            "网格应渲染卡片",
        )
    }

    @Test
    fun `list renders rows`() {
        rule.setContent { SortAndPreviewPage(onlyCollection, initialMode = ViewMode.List) }
        assertTrue(
            rule.onAllNodesWithTag(TAG_LIST_ROW).fetchSemanticsNodes().isNotEmpty(),
            "列表应渲染行",
        )
    }

    @Test
    fun `clicking a grid card reports its index`() {
        var opened = -1
        rule.setContent {
            SortAndPreviewPage(onlyCollection, initialMode = ViewMode.Grid, onOpenPreview = { opened = it })
        }
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()
        assertEquals(0, opened)
    }

    @Test
    fun `clicking a list row reports its index`() {
        var opened = -1
        rule.setContent {
            SortAndPreviewPage(onlyCollection, initialMode = ViewMode.List, onOpenPreview = { opened = it })
        }
        rule.onAllNodesWithTag(TAG_LIST_ROW)[0].performClick()
        assertEquals(0, opened)
    }

    // ========== 第 13 条回归:网格里看到的中文数字顺序(第 2 层) ==========
    //
    // 领域层单测只证明比较器正确,证明不了"用户看到的网格真的按这个顺序排"。
    // 这里钉住**渲染出来的卡片顺序**,与领域层形成两层守护。

    private fun chineseVolume(dir: String, name: String) = SourceItem(
        id = name, collectionId = "cn", dir = dir, baseName = name,
        ext = "jpg", sizeBytes = 1024, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = 1,
    )

    @Test
    fun `grid renders chinese numeral names in numeric order`() {
        // 故意打乱输入顺序,且混入阿拉伯数字写法(与 第十话 同值)
        val shuffled = listOf(
            "第十一话", "第三话", "第10话", "第一话", "第十二话", "第二话",
        ).map { chineseVolume("", it) }
        val collection = ImageCollection("cn", "中文卷", "中文卷", shuffled)

        rule.setContent { SortAndPreviewPage(listOf(collection)) }
        rule.waitForIdle()

        val rendered = renderedCardNames()

        assertEquals(
            listOf(
                "第一话.jpg", "第二话.jpg", "第三话.jpg",
                "第10话.jpg", "第十一话.jpg", "第十二话.jpg",
            ),
            rendered,
            "网格必须按数值显示中文数字卷;修复前是逐字比码点的乱序",
        )
    }

    /**
     * 按**渲染顺序**收集网格里所有卡片的名字。
     *
     * ⚠️ 不能只 `onAllNodesWithTag(TAG_GRID_CARD)` 一次就断言:那是 LazyGrid,只组合
     * **可见窗口**内的项。2026-09-26 列数控件改为默认自适应后,默认 320dp 宽度下自适应
     * 算出 2 列(旧实现写死 3 列),6 张卡从 2 行变 3 行、超出视口,于是"一次取全部"只拿到
     * 前 4 张 —— 这条断言当场变红,但**排序并没有坏**,坏的是断言对布局的隐含假设。
     *
     * 故这里逐屏滚动收集(每屏按 boundsInRoot 的 (top,left) 排序),按首次出现顺序拼接,
     * 使断言只依赖"展示顺序"这一真正的不变量,与列数/视口高矮解耦。
     */
    private fun renderedCardNames(): List<String> {
        val grid = rule.onNodeWithTag(TAG_GRID).fetchSemanticsNode()
        val scrollTo = grid.config[SemanticsActions.ScrollToIndex].action
        val seen = LinkedHashSet<String>()
        // 卡片数上界很小(6),逐屏滚到底即可;seen 保证不重复、且保持首次出现顺序
        for (i in 0 until 8) {
            runCatching { scrollTo?.invoke(i) }
            rule.waitForIdle()
            rule.onAllNodesWithTag(TAG_GRID_CARD)
                .fetchSemanticsNodes()
                .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
                .forEach { node ->
                    val cfg = node.config
                    if (cfg.contains(SemanticsProperties.ContentDescription)) {
                        cfg[SemanticsProperties.ContentDescription].firstOrNull()?.let(seen::add)
                    }
                }
            if (seen.size >= CARD_COUNT) break
        }
        return seen.toList()
    }

    private companion object {
        const val CARD_COUNT = 6
    }
}
