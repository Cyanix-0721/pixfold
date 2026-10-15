package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
 *  3. 断言**界面**在拖动中实时重排(`cards reflow live while dragging`),
 *     同时断言**数据**只在松开时提交一次(`drag does not report a move while
 *     still dragging`)—— 用户 2026-10-14 要求实时让位后,这两条一守视觉、
 *     一守「不给领域层制造抖动」,缺任一都会放过一整类错误实现。
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

    /**
     * 读「左上角那一格」的文件名 —— 实时让位的判据。
     *
     * 读的是 `TAG_GRID_CARD` 的 `contentDescription`([DraggableThumbCard] 上写的就是
     * `item.fileName`),不是 `onNodeWithText`:卡片文字被截断成 `img1...`,
     * 按文本定位会随字体测量而变。按**位置**选首格也刻意避开索引 —— 断言的是
     * 「谁现在占据左上角」这一**视觉**事实,而不是「列表里第 0 项是谁」。
     */
    private fun topLeftCardName(): String {
        val cards = rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes()
        val first = cards.minWithOrNull(
            compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }),
        ) ?: error("没有任何网格卡片")
        return first.config[SemanticsProperties.ContentDescription].firstOrNull().orEmpty()
    }

    /**
     * 拖动中**实时让位** —— 用户 2026-10-14 逐字:「拖拽应该在拖拽后的位置挤压旁边的,
     * 自动显示新的位置而不是生硬的悬浮在某个卡片或行上」。
     *
     * 这条**推翻**了本组件早先的「不做悬停实时重排」(HANGOFF §12.5 结论 2)。
     * 旧结论担心的不是"重排本身",而是"**每次指针移动都提交一次重排**" ⇒ 顺序来回换位。
     * 故本用例与下一条必须同时成立、缺一不可:
     *  - 本条守「**界面**在松手前已经重排」(实时让位真的发生);
     *  - 下一条守「**数据**在松手前没有提交」(不给领域层制造抖动)。
     * 只满足一条的实现都是错的:只提交不重排 ⇒ 本条红;拖动中持续提交 ⇒ 下一条红。
     */
    @Test
    fun `cards reflow live while dragging`() {
        val list = items(9)
        setGrid(list, onMove = { _, _ -> })

        assertEquals("img1.jpg", topLeftCardName(), "前提:未拖动时左上角是 img1")

        val node = rule.onNodeWithText("img1.jpg").fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val from = node.boundsInRoot.center - origin
        val to = rule.onNodeWithText("img3.jpg").fetchSemanticsNode().boundsInRoot.center - origin

        rule.onNodeWithText("img1.jpg").performTouchInput {
            down(from)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveTo(to)
            // 刻意不 up():让位必须发生在**松手之前**
        }
        rule.waitForIdle()

        assertEquals(
            "img2.jpg",
            topLeftCardName(),
            "拖动中左上角应已被 img2 顶上(实时让位);若仍是 img1,说明只在松手后才重排",
        )
        rule.onAllNodesWithTag(TAG_GRID_CARD).assertCountEquals(9)
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
        // ⚠ 与 `cards reflow live while dragging` **必须同时成立**:实时让位是
        // **界面**行为,它绝不能靠"拖动中不断提交给领域层"来实现 —— 那样
        // 每次指针移动都会写一次 PageOrderState(manualIds 反复增长、撤销记录
        // 被灌满、指针在格边界抖动时顺序来回换位,正是旧裁决担心的那个坑)。
        // 正确分工:拖动中只改**展示顺序**(本地),松开时提交一次。
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
            // 不 up():拖动过程中不得上报(松开才落地)
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

    // ---------------- 跟手浮层(HANGOFF §11 第 9 条,2026-09-25 用户裁决) ----------------

    @Test
    fun `no ghost before any drag starts`() {
        // 浮层只在拖动中出现 —— 静止时不得常驻(否则它会盖住真实卡片)
        setGrid(items(9), onMove = { _, _ -> })

        rule.onNodeWithTag(TAG_DRAG_GHOST).assertDoesNotExist()
    }

    @Test
    fun `ghost follows the pointer while dragging`() {
        val list = items(9)
        setGrid(list, onMove = { _, _ -> })

        val card = rule.onNodeWithText("img1.jpg").fetchSemanticsNode().boundsInRoot
        val from = card.center - card.topLeft

        rule.onNodeWithText("img1.jpg").performTouchInput {
            down(from)
            advanceEventTime(700)    // 长按阈值
            moveBy(Offset(0f, -20f)) // 越过 slop,进入拖拽
        }

        // 拖动中:手里必须有东西(补缺口前这里只有源卡的 0.35 alpha,没有任何跟手物)
        rule.onNodeWithTag(TAG_DRAG_GHOST).assertExists()

        // **真正要守的是"跟手"**:浮层中心贴着指针。若只断言"存在",
        // 把 graphicsLayer 的 translation 去掉(浮层钉在容器左上角)照样通过。
        val pointer = card.center + Offset(0f, -20f)
        val ghost = rule.onNodeWithTag(TAG_DRAG_GHOST).fetchSemanticsNode().boundsInRoot
        assertTrue(
            kotlin.math.abs(ghost.center.x - pointer.x) <= 2f &&
                kotlin.math.abs(ghost.center.y - pointer.y) <= 2f,
            "浮层中心应贴住指针:pointer=$pointer ghostCenter=${ghost.center}",
        )
    }

    @Test
    fun `ghost is gone after release`() {
        val list = items(9)
        setGrid(list, onMove = { _, _ -> })

        val card = rule.onNodeWithText("img1.jpg").fetchSemanticsNode().boundsInRoot
        val from = card.center - card.topLeft
        val to = rule.onNodeWithText("img3.jpg").fetchSemanticsNode().boundsInRoot.center - card.topLeft

        rule.onNodeWithText("img1.jpg").performTouchInput {
            down(from)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveTo(to)
            up()
        }

        rule.onNodeWithTag(TAG_DRAG_GHOST).assertDoesNotExist()
    }

    @Test
    fun `ghost does not duplicate the dragged file name in the semantics tree`() {
        // 浮层是纯装饰:内容被 clearAndSetSemantics 屏蔽。若漏了这一步,
        // 拖动中 `onNodeWithText("img1.jpg")` 会匹配到两个节点 —— 所有
        // 按文本定位的既有断言都会在拖拽态下变成"匹配到多个节点"而报错。
        val list = items(9)
        setGrid(list, onMove = { _, _ -> })

        val card = rule.onNodeWithText("img1.jpg").fetchSemanticsNode().boundsInRoot
        val from = card.center - card.topLeft

        rule.onNodeWithText("img1.jpg").performTouchInput {
            down(from)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
        }

        rule.onAllNodesWithText("img1.jpg").assertCountEquals(1)
        // 同时确认浮层确实在(否则这条在"浮层根本没出现"时也会绿)
        rule.onNodeWithTag(TAG_DRAG_GHOST).assertExists()
    }
}
