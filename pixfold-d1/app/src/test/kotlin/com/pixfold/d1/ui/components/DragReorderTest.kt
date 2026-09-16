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
 * 拖拽排序的 UI 层断言(验收清单第 3 项)。
 *
 * 关键教训(HANGOFF §12.5):上轮"能拖但不改顺序"反复 4 轮才定位 ——
 * 前 3 轮都停在"数据测试通过"的假象上(根因是状态通知链断,数据对、界面不动)。
 * 故本测试:
 *  1. 使用**真实组件** [DragReorderGrid](不是简化 harness);
 *  2. 模拟**完整手势**(按下 → 越过 slop → 移到目标 → 抬起);
 *  3. 断言**松手后**才提交,且拖动中不提交。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class DragReorderTest {

    @get:Rule
    val rule = createComposeRule()

    private fun items(n: Int): List<SourceItem> = (1..n).map {
        SourceItem(
            id = "i$it", collectionId = "c", dir = "", baseName = "img$it", ext = "jpg",
            sizeBytes = 1, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = it,
        )
    }

    private fun setGrid(
        list: List<SourceItem>,
        onMove: (String, Int) -> Unit,
        onPin: (String) -> Unit = {},
    ) {
        rule.setContent {
            DragReorderGrid(
                items = list,
                onMoveTo = onMove,
                onPin = onPin,
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 700.dp),
            )
        }
    }

    @Test
    fun `drag first card onto third slot moves it there`() {
        val list = items(9)
        val moved = mutableListOf<Pair<String, Int>>()
        setGrid(list, onMove = { id, t -> moved += id to t })

        val node = rule.onNodeWithText("img1.jpg").fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val from = node.boundsInRoot.center - origin                 // 节点本地坐标
        val to = rule.onNodeWithText("img3.jpg").fetchSemanticsNode().boundsInRoot.center - origin

        rule.onNodeWithText("img1.jpg").performTouchInput {
            down(from)
            advanceEventTime(700)    // 长按阈值(约 500ms)必须真实经过,否则拖拽不启动
            moveBy(Offset(0f, -20f)) // 越过 slop,进入拖拽
            moveTo(to)
            up()
        }

        assertEquals(listOf("i1" to 2), moved, "松手后应上报移动到第 2 格")
    }

    @Test
    fun `drag does not report a move while still dragging`() {
        val list = items(9)
        val moved = mutableListOf<Pair<String, Int>>()
        setGrid(list, onMove = { id, t -> moved += id to t })

        val node = rule.onNodeWithText("img1.jpg").fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val from = node.boundsInRoot.center - origin
        val to = rule.onNodeWithText("img3.jpg").fetchSemanticsNode().boundsInRoot.center - origin

        rule.onNodeWithText("img1.jpg").performTouchInput {
            down(from)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveTo(to)
            // 不 up():拖动过程中不得上报(松手才落地)
        }

        assertTrue(moved.isEmpty(), "拖动中不得提交移动,实际 $moved")
    }

    @Test
    fun `dragging onto itself reports nothing`() {
        val list = items(9)
        val moved = mutableListOf<Pair<String, Int>>()
        setGrid(list, onMove = { id, t -> moved += id to t })

        val node = rule.onNodeWithText("img2.jpg").fetchSemanticsNode()
        val c = node.boundsInRoot.center - node.boundsInRoot.topLeft
        rule.onNodeWithText("img2.jpg").performTouchInput {
            down(c)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveTo(c)
            up()
        }

        assertTrue(moved.isEmpty(), "拖到自己格不应上报(与 moveItemTo 的 no-op 呼应)")
    }

    @Test
    fun `drag downward to a later slot works`() {
        val list = items(9)
        val moved = mutableListOf<Pair<String, Int>>()
        setGrid(list, onMove = { id, t -> moved += id to t })

        val node = rule.onNodeWithText("img1.jpg").fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val from = node.boundsInRoot.center - origin
        val to = rule.onNodeWithText("img7.jpg").fetchSemanticsNode().boundsInRoot.center - origin

        rule.onNodeWithText("img1.jpg").performTouchInput {
            down(from)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveTo(to)
            up()
        }

        assertEquals(listOf("i1" to 6), moved, "跨排下拖应落到第 6 格")
    }

    @Test
    fun `pin button is clickable on every card`() {
        // 走查反馈④的静默缺口:卡片收了 onPin 却无可点入口(只有静态角标)
        val list = items(4)
        val pinned = mutableListOf<String>()
        setGrid(list, onMove = { _, _ -> }, onPin = { pinned += it })

        rule.onNodeWithTag(pinTag("i1")).performClick()

        assertEquals(listOf("i1"), pinned, "网格卡片必须有可点图钉入口")
    }
}
