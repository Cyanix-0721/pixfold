package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    // ---- 缺陷回归(用户 2026-09-17 指出):划到**最底部**时按钮消失 ----

    @Test
    fun `grid keeps the button visible at the very bottom`() {
        // 原判据用 canScrollForward(下方还有内容),而**滑到底部时它恰为 false**,
        // 于是最需要回顶时按钮反而消失 —— 不符合常规操作逻辑。
        // 正确判据:canScrollBackward(上方还有内容 = 不在顶部)。
        setGrid(60)

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performScrollToIndexCompat(59) // 最后一项
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()
    }

    @Test
    fun `list keeps the button visible at the very bottom`() {
        setList(60)

        rule.onNodeWithTag(TAG_LIST_CONTAINER).performScrollToIndexCompat(59)
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()
    }

    @Test
    fun `button hides again right after returning to the top from the bottom`() {
        setGrid(60)

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performScrollToIndexCompat(59)
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()

        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).performClick()
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty(),
            "从底部回顶后也应隐藏",
        )
    }

    // ---- 外部驱动回顶(W4:规则变更后自动回顶,用户 2026-09-17 指定) ----

    @Test
    fun `incrementing scrollToTopSignal scrolls grid back to top`() {
        var signal by mutableStateOf(0)
        rule.setContent {
            DragReorderGrid(
                items = items(60),
                onMoveTo = { _, _ -> },
                onPin = {},
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 600.dp),
                scrollToTopSignal = signal,
            )
        }

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performScrollToIndexCompat(30)
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()

        signal++                    // 外部信号(模拟"排序规则变更")
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty(),
            "收到回顶信号后应回到顶部(按钮随之隐藏)",
        )
    }

    @Test
    fun `incrementing scrollToTopSignal scrolls list back to top`() {
        var signal by mutableStateOf(0)
        rule.setContent {
            DragReorderList(
                items = items(60),
                onMoveTo = { _, _ -> },
                onPin = {},
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 600.dp),
                scrollToTopSignal = signal,
            )
        }

        rule.onNodeWithTag(TAG_LIST_CONTAINER).performScrollToIndexCompat(30)
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()

        signal++
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty(),
            "收到回顶信号后应回到顶部(按钮随之隐藏)",
        )
    }

    @Test
    fun `unrelated recomposition does not scroll back to top`() {
        // 防误伤:只有 signal **变化**才回顶,普通重组不得把用户拽回顶部
        var signal by mutableStateOf(0)
        var noise by mutableStateOf(0)
        rule.setContent {
            val n = noise
            DragReorderGrid(
                items = items(60),
                onMoveTo = { _, _ -> },
                onPin = {},
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 600.dp),
                scrollToTopSignal = signal,
            )
            if (n < 0) Unit   // 读取 noise,制造无关重组
        }

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performScrollToIndexCompat(30)
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()

        noise++                    // 无关状态变化 -> 不应回顶
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()
    }
}

/** 用语义 Action 滚动到指定 index(LazyGrid/LazyList 通用)。 */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToIndexCompat(index: Int) {
    val key = androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex
    val node = fetchSemanticsNode()
    assertTrue(node.config.contains(key), "该容器应支持 ScrollToIndex")
    node.config[key].action?.invoke(index)
}
