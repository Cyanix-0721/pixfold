package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
 * 拖拽边缘自动滚动(HANGOFF §11 第 18 条)的断言。
 *
 * 分两层:
 *  1. **纯函数** [edgeAutoScrollStep] —— 方向、深度、加速、封顶、未布局;
 *  2. **端到端** —— 真实组件 + 完整手势,证明指针贴边时视口**真的动了**,
 *     即「初始视口之外的格子/行可以成为落点」。
 *
 * 第 2 层是关键:只断言"step 非零"无法排除"算了但没接到滚动状态上"(接线断开),
 * 故必须走真实拖拽并观察**落点下标**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class EdgeAutoScrollTest {

    @get:Rule
    val rule = createComposeRule()

    // ---------------- 纯函数 ----------------

    @Test
    fun `middle of the container does not scroll`() {
        assertEquals(
            0f,
            edgeAutoScrollStep(350f, 0f, 700f, 1f, 0),
            0f,
            "指针不在边缘带内不得滚动",
        )
    }

    @Test
    fun `bottom edge scrolls forward`() {
        val step = edgeAutoScrollStep(690f, 0f, 700f, 1f, 0)
        assertTrue(step > 0f, "下边缘应向上滚(内容上移),实际 $step")
        assertEquals(14f * (62f / 72f), step, 0.001f)
    }

    @Test
    fun `top edge scrolls backward`() {
        val step = edgeAutoScrollStep(10f, 0f, 700f, 1f, 0)
        assertTrue(step < 0f, "上边缘应向下滚(内容下移),实际 $step")
        assertEquals(-14f * (62f / 72f), step, 0.001f)
    }

    @Test
    fun `exactly at the border gives full depth`() {
        assertEquals(14f, edgeAutoScrollStep(700f, 0f, 700f, 1f, 0), 0.001f)
        assertEquals(-14f, edgeAutoScrollStep(0f, 0f, 700f, 1f, 0), 0.001f)
    }

    @Test
    fun `deeper into the edge scrolls faster`() {
        val shallow = edgeAutoScrollStep(660f, 0f, 700f, 1f, 0) // 距底 40
        val deep = edgeAutoScrollStep(690f, 0f, 700f, 1f, 0)    // 距底 10
        assertTrue(deep > shallow, "越贴近边缘滚得越快:deep=$deep shallow=$shallow")
    }

    @Test
    fun `burst accelerates and is capped`() {
        val first = edgeAutoScrollStep(690f, 0f, 700f, 1f, 0)
        val second = edgeAutoScrollStep(690f, 0f, 700f, 1f, 1)
        assertEquals(first * 2f, second, 0.001f, "第二个连续触边事件应加倍")

        val atCap = edgeAutoScrollStep(690f, 0f, 700f, 1f, EDGE_SCROLL_MAX_MULTIPLIER - 1)
        assertEquals(
            atCap,
            edgeAutoScrollStep(690f, 0f, 700f, 1f, 100),
            0.001f,
            "加速倍数必须封顶,否则连续触边会瞬移",
        )
    }

    @Test
    fun `unlaid-out container does not scroll`() {
        assertEquals(0f, edgeAutoScrollStep(690f, 0f, 0f, 1f, 0), 0f)
    }

    @Test
    fun `container offset is subtracted`() {
        // 容器整体下移 200:同一根坐标 690 此时落在容器中部(690-200=490),不应滚动
        assertEquals(0f, edgeAutoScrollStep(690f, 200f, 700f, 1f, 0), 0f)
        // 而 890 落在容器下边缘附近(890-200=690),应滚动
        assertTrue(edgeAutoScrollStep(890f, 200f, 700f, 1f, 0) > 0f)
    }

    // ---------------- 端到端 ----------------

    private fun items(n: Int): List<SourceItem> = (1..n).map {
        SourceItem(
            id = "i$it", collectionId = "c", dir = "", baseName = "img$it", ext = "jpg",
            sizeBytes = 1, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = it,
        )
    }

    private fun setGrid(list: List<SourceItem>, onMove: (String, Int) -> Unit) {
        rule.setContent {
            DragReorderGrid(
                items = list,
                onMoveTo = onMove,
                onPin = {},
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 700.dp),
            )
        }
    }

    private fun setList(list: List<SourceItem>, onMove: (String, Int) -> Unit) {
        rule.setContent {
            DragReorderList(
                items = list,
                onMoveTo = onMove,
                onPin = {},
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 900.dp),
            )
        }
    }

    /** 当前**已组合**（= 初始视口内）的最大下标;未滚动时落点不可能超过它。 */
    private fun visibleMaxIndex(n: Int): Int {
        var last = -1
        for (i in 0 until n) {
            if (rule.onAllNodesWithText("img${i + 1}.jpg").fetchSemanticsNodes().isNotEmpty()) last = i
        }
        return last
    }

    @Test(timeout = 60_000)
    fun `dragging to the bottom edge scrolls the grid to a far slot`() {
        val n = 30
        val moved = mutableListOf<Pair<String, Int>>()
        setGrid(items(n), onMove = { id, t -> moved += id to t })

        val visibleMax = visibleMaxIndex(n)
        val containerTopLeft = rule.onNodeWithTag(TAG_GRID_CONTAINER)
            .fetchSemanticsNode().boundsInRoot.topLeft
        val start = rule.onNodeWithText("img1.jpg").fetchSemanticsNode().boundsInRoot.center -
            containerTopLeft

        // 注入到**容器**上(容器不滚动,容器局部坐标 = 稳定的根坐标),
        // 于是"指针停在边缘带内"这件事不会随内容滚动而漂移。
        rule.onNodeWithTag(TAG_GRID_CONTAINER).performTouchInput {
            down(start)
            advanceEventTime(700)          // 长按阈值
            moveBy(Offset(0f, -20f))       // 越过 slop,进入拖拽
            moveBy(Offset(0f, 600f))       // 甩到下边缘带内
            repeat(30) { moveBy(Offset(0f, 1f), delayMillis = 16) }
            up()
        }
        rule.waitForIdle()

        println("PROBE-GRID visibleMax=$visibleMax moved=$moved")
        assertTrue(moved.isNotEmpty(), "拖到下边缘后松手应上报移动(空说明手势被滚动打断)")
        assertTrue(
            moved.first().second > visibleMax,
            "跨视口拖拽必须能到达初始视口之外的格子:" +
                "target=${moved.firstOrNull()?.second} visibleMax=$visibleMax",
        )
    }

    @Test(timeout = 60_000)
    fun `dragging back to the top edge scrolls the grid up again`() {
        // 反向滚动必须成立,否则"拖到远处后发现放错了"只能松手重来。
        // 注意:滚动回顶部后若**松手在起始格上**,不上报移动才是正确行为
        // (与 `dragging onto itself reports nothing` 同一语义),故不能断言
        // "必须上报";改为断言①视口回到顶部 ②落点按**滚动后**的布局解析。
        val n = 30
        val moved = mutableListOf<Pair<String, Int>>()
        setGrid(items(n), onMove = { id, t -> moved += id to t })

        val containerTopLeft = rule.onNodeWithTag(TAG_GRID_CONTAINER)
            .fetchSemanticsNode().boundsInRoot.topLeft
        val start = rule.onNodeWithText("img1.jpg").fetchSemanticsNode().boundsInRoot.center -
            containerTopLeft

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performTouchInput {
            down(start)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            // 下边缘:往远处滚(容器高 700,下边缘带 72px)。
            // 必须用 moveBy **逐像素步进**:moveTo 到同一位置时位移为 0,
            // 事件会被丢弃 -> 加速根本不会累积(实测踩过)。
            moveBy(Offset(0f, 600f))
            repeat(30) { moveBy(Offset(0f, 1f), delayMillis = 16) }
            // 上边缘:滚回来
            moveBy(Offset(0f, -690f))
            repeat(30) { moveBy(Offset(0f, -1f), delayMillis = 16) }
            // 松手在第 2 排(滚动回顶后应为下标 3..5),证明落点用的是滚动后的布局
            moveTo(Offset(start.x, 252f))
            up()
        }
        rule.waitForIdle()

        // ① 视口必须回到开头:第一张图重新可见
        assertTrue(
            rule.onAllNodesWithText("img1.jpg").fetchSemanticsNodes().isNotEmpty(),
            "贴回上边缘后第 1 张图必须重新可见(视口回到顶部)",
        )
        // ② 落点按滚动后的布局解析(第 2 排),而不是停在远端的旧坐标
        println("PROBE-GRID-UP moved=$moved")
        assertEquals(1, moved.size, "松手应上报一次移动,实际 $moved")
        assertTrue(
            moved.first().second in 3..5,
            "落点应为滚回顶部后的第 2 排(下标 3..5),实际 ${moved.firstOrNull()?.second}",
        )
    }

    @Test(timeout = 60_000)
    fun `dragging to the bottom edge scrolls the list to a far row`() {
        val n = 40
        val moved = mutableListOf<Pair<String, Int>>()
        setList(items(n), onMove = { id, t -> moved += id to t })

        val visibleMax = visibleMaxIndex(n)
        val containerTopLeft = rule.onNodeWithTag(TAG_LIST_CONTAINER)
            .fetchSemanticsNode().boundsInRoot.topLeft
        val start = rule.onNodeWithText("img1.jpg").fetchSemanticsNode().boundsInRoot.center -
            containerTopLeft

        rule.onNodeWithTag(TAG_LIST_CONTAINER).performTouchInput {
            down(start)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveBy(Offset(0f, 800f))
            repeat(40) { moveBy(Offset(0f, 1f), delayMillis = 16) }
            up()
        }
        rule.waitForIdle()

        println("PROBE-LIST visibleMax=$visibleMax moved=$moved")
        assertTrue(moved.isNotEmpty(), "列表拖到下边缘后松手应上报移动")
        assertTrue(
            moved.first().second > visibleMax,
            "列表跨视口拖拽必须能到达初始视口之外的行:" +
                "target=${moved.firstOrNull()?.second} visibleMax=$visibleMax",
        )
    }

    @Test(timeout = 60_000)
    fun `dragging starts on the card under the finger after scrolling`() {
        // 起拖身份必须由**当前可见**的格决定。滚出视口的格被 LazyGrid 销毁后,
        // 它在 slotBounds 里的旧坐标会与滚上来的新格重合;若不清掉过期项,
        // `firstOrNull { contains }` 会先命中那个**已销毁的**下标 ——
        // 长按 img20 却抓起 img1,浮层与落点全错(变异 G-H 守护此条)。
        val n = 30
        val moved = mutableListOf<Pair<String, Int>>()
        setGrid(items(n), onMove = { id, t -> moved += id to t })

        // 先滚下去(此时不是拖拽,userScrollEnabled = true,由 LazyGrid 自己接手势)
        rule.onNodeWithTag(TAG_GRID_CONTAINER).performTouchInput { swipeUp() }
        rule.waitForIdle()

        val visible = (1..n).filter {
            rule.onAllNodesWithText("img$it.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            visible.isNotEmpty() && visible.first() > 1,
            "前置条件:视口应已下移(第 1 张已不可见),实际可见 $visible",
        )

        // 长按其中一个**可见**的卡片,拖到另一个可见卡片上
        val dragId = "img${visible.first()}.jpg"
        val targetId = "img${visible.last()}.jpg"
        val containerTopLeft = rule.onNodeWithTag(TAG_GRID_CONTAINER)
            .fetchSemanticsNode().boundsInRoot.topLeft
        val from = rule.onNodeWithText(dragId).fetchSemanticsNode().boundsInRoot.center -
            containerTopLeft
        val to = rule.onNodeWithText(targetId).fetchSemanticsNode().boundsInRoot.center -
            containerTopLeft

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performTouchInput {
            down(from)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveTo(to)
            up()
        }
        rule.waitForIdle()

        println("PROBE-AFTER-SCROLL visible=$visible moved=$moved")
        assertEquals(1, moved.size, "滚下去后拖拽仍应上报一次移动,实际 $moved")
        assertEquals(
            "i${visible.first()}",
            moved.first().first,
            "抓起的必须是手指下的那张可见卡片,而不是滚出视口后被销毁的旧下标",
        )
    }

    @Test(timeout = 60_000)
    fun `ghost survives after the source card scrolls out of view`() {
        // 浮层尺寸在起拖瞬间定格:源卡滚出视口被销毁后,若每帧重新去 slotBounds
        // 取尺寸,会拿到过期/缺失值 -> 浮层变形或消失(变异 G-K 守护此条)。
        // 注意这里**不 up()**:能在手势外观察到浮层,即证明手势与浮层都还活着。
        val n = 30
        setGrid(items(n), onMove = { _, _ -> })

        val containerTopLeft = rule.onNodeWithTag(TAG_GRID_CONTAINER)
            .fetchSemanticsNode().boundsInRoot.topLeft
        val start = rule.onNodeWithText("img1.jpg").fetchSemanticsNode().boundsInRoot.center -
            containerTopLeft

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performTouchInput {
            down(start)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveBy(Offset(0f, 600f))                                  // 甩到下边缘带内
            repeat(30) { moveBy(Offset(0f, 1f), delayMillis = 16) }   // 逐像素步进,持续贴边
        }
        rule.waitForIdle()

        val stillVisible = rule.onAllNodesWithText("img1.jpg").fetchSemanticsNodes().isNotEmpty()
        val present = (1..n).filter {
            rule.onAllNodesWithText("img$it.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        println(
            "PROBE-GHOST-SURVIVE sourceStillVisible=$stillVisible present=$present " +
                "ghost=${rule.onAllNodesWithTag(TAG_DRAG_GHOST).fetchSemanticsNodes().size}",
        )
        assertTrue(!stillVisible, "前置条件:源卡应已滚出视口被销毁")
        assertEquals(
            1,
            rule.onAllNodesWithTag(TAG_DRAG_GHOST).fetchSemanticsNodes().size,
            "源卡滚出视口后浮层仍须存在(尺寸应在起拖时定格)",
        )
    }

    @Test(timeout = 60_000)
    fun `long press on empty space starts no drag`() {
        // 起拖必须按**包含**判定。若兜底到"最近格"(变异 G-E),长按网格空白处
        // 也会莫名抓起一张卡片 —— 用户只是想滚动,却看到浮层跟手。
        // 判据用**浮层是否出现**:只断言"松手后的上报"会被"目标格 == 起始格
        // -> 不提交"掩盖,故必须在手势仍进行时观察。
        // 4 张卡在 360x700 的容器里只占 y<330,下方是整片空白。
        setGrid(items(4), onMove = { _, _ -> })

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performTouchInput {
            down(Offset(180f, 500f))
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
        }
        rule.waitForIdle()

        assertEquals(
            0,
            rule.onAllNodesWithTag(TAG_DRAG_GHOST).fetchSemanticsNodes().size,
            "长按空白处不得起拖(浮层不应出现)",
        )
    }
}
