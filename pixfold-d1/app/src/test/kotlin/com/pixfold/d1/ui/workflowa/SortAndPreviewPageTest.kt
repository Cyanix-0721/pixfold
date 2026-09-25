package com.pixfold.d1.ui.workflowa

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.ui.components.TAG_GRID_CARD
import com.pixfold.d1.ui.components.TAG_LIST_ROW
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验收第 1 项:缩略图网格/列表双视图可切换,缩略图渲染正确。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

        val rendered = rule.onAllNodesWithTag(TAG_GRID_CARD)
            .fetchSemanticsNodes()
            .map { node ->
                val cfg = node.config
                if (cfg.contains(SemanticsProperties.ContentDescription)) {
                    cfg[SemanticsProperties.ContentDescription].firstOrNull() ?: ""
                } else {
                    ""
                }
            }

        assertEquals(
            listOf(
                "第一话.jpg", "第二话.jpg", "第三话.jpg",
                "第10话.jpg", "第十一话.jpg", "第十二话.jpg",
            ),
            rendered,
            "网格必须按数值显示中文数字卷;修复前是逐字比码点的乱序",
        )
    }
}
