package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.model.SourceItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "回到顶部"按钮(W2,用户 2026-09-17 提出)。
 *
 * 需求:网格或列表**产生下滑**时,底部中间或右下角**实时**显示回顶按钮;
 * 回到顶部后隐藏。
 *
 * 判据(避免误显):
 *  - 内容不足一屏(无法下滑) -> **不显示**;
 *  - 已回到顶部 -> **不显示**;
 *  - 下滑中 -> **显示**,点击后回顶。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ScrollToTopTest {

    @get:Rule
    val rule = createComposeRule()

    private fun items(n: Int): List<SourceItem> = (1..n).map {
        SourceItem(
            id = "i$it", collectionId = "c", dir = "day1", baseName = "img$it", ext = "jpg",
            sizeBytes = 1, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = it,
        )
    }

    private fun setGrid(n: Int, onTopClick: () -> Unit = {}) {
        rule.setContent {
            DragReorderGrid(
                items = items(n),
                onMoveTo = { _, _ -> },
                onPin = {},
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 600.dp),
                onScrollToTopClick = onTopClick,
            )
        }
    }

    private fun setList(n: Int, onTopClick: () -> Unit = {}) {
        rule.setContent {
            DragReorderList(
                items = items(n),
                onMoveTo = { _, _ -> },
                onPin = {},
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 600.dp),
                onScrollToTopClick = onTopClick,
            )
        }
    }

    @Test
    fun `grid hides scroll to top button when content fits`() {
        setGrid(3) // 3 张 = 1 行,不足一屏
        assertTrue(
            rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty(),
            "内容不足一屏时不应显示回顶按钮",
        )
    }

    @Test
    fun `grid hides button at top and shows it after scrolling`() {
        setGrid(60)

        // 初始在顶部 -> 隐藏
        assertTrue(
            rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty(),
            "已在顶部时不应显示回顶按钮",
        )

        // 下滑
        rule.onNodeWithTag(TAG_GRID_CONTAINER).performScrollToIndexCompat(30)

        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()
    }

    @Test
    fun `list hides button at top and shows it after scrolling`() {
        setList(60)

        assertTrue(
            rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty(),
            "已在顶部时不应显示回顶按钮",
        )

        rule.onNodeWithTag(TAG_LIST_CONTAINER).performScrollToIndexCompat(30)

        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()
    }

    @Test
    fun `clicking the button scrolls back to top and hides it`() {
        var clicked = 0
        setGrid(60, onTopClick = { clicked++ })

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performScrollToIndexCompat(30)
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()

        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).performClick()
        assertEquals(1, clicked, "点击应触发回顶回调")

        // 回顶后按钮应消失(Robolectric 下动画立即结束;必要时等待)
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty(),
            "回到顶部后应隐藏回顶按钮",
        )
    }
}

/** 用语义 Action 滚动到指定 index(LazyGrid/LazyList 通用)。 */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToIndexCompat(index: Int) {
    val key = androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex
    val node = fetchSemanticsNode()
    assertTrue(node.config.contains(key), "该容器应支持 ScrollToIndex")
    node.config[key].action?.invoke(index)
}
