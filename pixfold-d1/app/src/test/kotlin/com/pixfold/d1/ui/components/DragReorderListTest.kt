package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
 * 列表视图拖拽(验收清单第 4 项)。
 *
 * 与网格**同一套语义**(归档结论 2):松手才落地,拖动中不提交。
 * 缺口背景:第 4 项曾被误记为完成,实际 `ThumbRow` 无任何拖拽处理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp")
class DragReorderListTest {

    @get:Rule
    val rule = createComposeRule()

    private fun items(n: Int): List<SourceItem> = (1..n).map {
        SourceItem(
            id = "i$it", collectionId = "c", dir = "day1", baseName = "img$it", ext = "jpg",
            sizeBytes = 1, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = it,
        )
    }

    private fun setList(
        list: List<SourceItem>,
        onMove: (String, Int) -> Unit,
        onPin: (String) -> Unit = {},
    ) {
        rule.setContent {
            DragReorderList(
                items = list,
                onMoveTo = onMove,
                onPin = onPin,
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 900.dp),
            )
        }
    }

    /** 用**节点本地坐标**模拟完整拖拽(须先长按超过阈值)。 */
    private fun dragRow(fromText: String, toText: String, release: Boolean = true) {
        val node = rule.onNodeWithText(fromText).fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val from = node.boundsInRoot.center - origin
        val to = rule.onNodeWithText(toText).fetchSemanticsNode().boundsInRoot.center - origin

        rule.onNodeWithText(fromText).performTouchInput {
            down(from)
            advanceEventTime(700) // 长按阈值必须真实经过,否则拖拽不启动
            moveBy(Offset(0f, -20f))
            moveTo(to)
            if (release) up()
        }
    }

    @Test
    fun `drag first row onto third row moves it there`() {
        val moved = mutableListOf<Pair<String, Int>>()
        setList(items(9), onMove = { id, t -> moved += id to t })

        dragRow("img1.jpg", "img3.jpg")

        assertEquals(listOf("i1" to 2), moved, "松手后应上报移动到第 2 行")
    }

    @Test
    fun `list drag does not report a move while still dragging`() {
        val moved = mutableListOf<Pair<String, Int>>()
        setList(items(9), onMove = { id, t -> moved += id to t })

        dragRow("img1.jpg", "img3.jpg", release = false)

        assertTrue(moved.isEmpty(), "拖动中不得提交(松手才落地),实际 $moved")
    }

    @Test
    fun `dragging a list row onto itself reports nothing`() {
        val moved = mutableListOf<Pair<String, Int>>()
        setList(items(9), onMove = { id, t -> moved += id to t })

        dragRow("img2.jpg", "img2.jpg")

        assertTrue(moved.isEmpty(), "拖到自己行不应上报")
    }

    @Test
    fun `drag downward to a later row works`() {
        val moved = mutableListOf<Pair<String, Int>>()
        setList(items(9), onMove = { id, t -> moved += id to t })

        dragRow("img1.jpg", "img7.jpg")

        assertEquals(listOf("i1" to 6), moved, "跨行下拖应落到第 6 行")
    }

    @Test
    fun `pin button is clickable on list rows`() {
        val pinned = mutableListOf<String>()
        setList(items(4), onMove = { _, _ -> }, onPin = { pinned += it })

        rule.onNodeWithTag(pinTag("i1")).performClick()

        assertEquals(listOf("i1"), pinned, "列表行必须有可点图钉入口")
    }
}
