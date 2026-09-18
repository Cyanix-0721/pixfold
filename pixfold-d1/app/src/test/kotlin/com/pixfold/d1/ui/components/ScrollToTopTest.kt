package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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

    // ---- 外部回顶必须"瞬时",不能有滑动动画 ----
    // 缺陷(用户 2026-09-17):切换升降序时"有个滑动卡一下" ——
    // 规则变更后的自动回顶用了 animateScrollToItem,在**刚刚重排过**的列表上
    // 从第 40 项动画滚到第 0 项;LazyGrid 长距离动画滚动需逐项组合中间帧,
    // 是 Compose 公认的卡顿源,且"对已重排的内容做滚动动画"本身没有意义。
    // 正解:瞬时归位(scrollToItem)。

    @Test
    fun `external scroll to top is instant in grid`() {
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

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performScrollToIndexCompat(40)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()

        // 关掉自动推进,逐帧推进并统计"到顶所需帧数"。
        // 阈值来自**实测**(本仓库 Robolectric,60 张图,从第 40 项回顶):
        //   scrollToItem(瞬时)     -> 25 帧
        //   animateScrollToItem    -> 42 帧
        // 取 32 帧为界:瞬时通过、动画失败。这是**行为差异**的判据(有无逐帧动画),
        // 不依赖具体机器性能。
        rule.mainClock.autoAdvance = false
        try {
            signal++
            var frames = -1
            for (f in 1..200) {
                rule.mainClock.advanceTimeByFrame()
                if (rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty()) {
                    frames = f
                    break
                }
            }
            assertTrue(
                frames in 1..32,
                "外部回顶用了 $frames 帧才到顶(瞬时实现实测 25 帧、动画实现 42 帧)。" +
                    "帧数过多说明用回了 animateScrollToItem —— 真机会表现为'滑动卡一下'",
            )
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `external scroll to top is instant in list`() {
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

        rule.onNodeWithTag(TAG_LIST_CONTAINER).performScrollToIndexCompat(40)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()

        rule.mainClock.autoAdvance = false
        try {
            signal++
            var frames = -1
            for (f in 1..200) {
                rule.mainClock.advanceTimeByFrame()
                if (rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty()) {
                    frames = f
                    break
                }
            }
            assertTrue(
                frames in 1..32,
                "列表外部回顶用了 $frames 帧才到顶(瞬时实测 25 帧、动画 42 帧),疑似用回了动画",
            )
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `after a rule change user can still scroll freely without being yanked back`() {
        // 风险守护:实现若写成"每次重组都 requestScrollToItem(0)",
        // 则规则变更(signal>0)之后,用户往下滚时任何一次重组都会把他**拽回顶部**。
        // 必须只在 signal **真正变化**的那一次归位。
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
            if (n < 0) Unit
        }

        // 1) 触发一次规则变更(signal 变 >0)
        signal++
        rule.waitForIdle()

        // 2) 用户往下滚
        rule.onNodeWithTag(TAG_GRID_CONTAINER).performScrollToIndexCompat(30)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SCROLL_TO_TOP).assertIsDisplayed()

        // 3) 发生一次无关重组 —— 不得把用户拽回顶部
        noise++
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isNotEmpty(),
            "规则变更后再滚动时被拽回了顶部 —— 归位必须只在 signal **变化**那一次发生," +
                "不能每次重组都 requestScrollToItem(0)",
        )
    }

    @Test
    fun `no partial row is left above the first card after an external scroll to top`() {
        // 用户 2026-09-17 补充说明:"仅网格视图、且在网格顶部出现 ——
        // 顶部卡片的上方像是还有卡片(仅底部)的半透明遮罩"。
        //
        // 这正是"**视口顶部残留上一行的下半截**"的几何特征:
        // 网格首卡上方本应是 contentPadding(8dp)+item padding(4dp) 的留白,
        // 若归位时偏移不为 0,就会有上一行卡片被裁切着露在顶部。
        //
        // 判据(直接量几何,比"按钮是否显示"更贴近症状):
        // 归位后,**最上面那张卡片的 top 必须大于容器 top** ——
        // 若恰好等于容器 top(或更小),说明有卡片被裁在了容器上边缘,
        // 即"上方还有卡片的下半截"。
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

        rule.onNodeWithTag(TAG_GRID_CONTAINER).performScrollToIndexCompat(40)
        rule.waitForIdle()

        signal++
        rule.waitForIdle()

        val containerTop = rule.onNodeWithTag(TAG_GRID_CONTAINER).fetchSemanticsNode()
            .boundsInRoot.top
        val cardTops = rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes()
            .map { it.boundsInRoot.top }
        assertTrue(cardTops.isNotEmpty(), "归位后应仍有可见卡片")

        val topmost = cardTops.min()
        assertTrue(
            topmost > containerTop + 1f,
            "最上面的卡片 top=%.1f 已贴到容器 top=%.1f —— " +
                "说明有卡片被裁在上边缘,即用户看到的\"顶部上方还有卡片(仅底部)\"".format(topmost, containerTop),
        )
    }


    @Test
    fun `reordering while already at the top keeps the new first item at the top`() {
        // **忠实复现用户场景**(2026-09-17 用户补充:"仅在顶部切换升降序才会触发,
        // 有一点下滑的情况都不会触发")。
        //
        // 关键:此前的测试都只做"滚动 + 发信号",**从未在顶部真正重排数据**,
        // 因此都没复现出来。这里在同一次重组里**同时**换顺序 + 发信号。
        //
        // 机制:LazyGrid 用 `key` 跟踪"首个可见项"。重排后它会**按 key 锚定**
        // ——把"原来那个首项"的新下标(此时已跑到末尾附近)当成滚动目标,
        // 于是视口瞬间跳到列表后段(顶部露出别处的行边)。
        // 在顶部时尤其明显:偏移本是 0,却因锚定被改成了"末项的偏移"。
        var reversed by mutableStateOf(false)
        var signal by mutableStateOf(0)
        val base = items(60)
        rule.setContent {
            DragReorderGrid(
                items = if (reversed) base.reversed() else base,
                onMoveTo = { _, _ -> },
                onPin = {},
                isPinned = { false },
                onClick = {},
                modifier = Modifier.size(360.dp, 600.dp),
                scrollToTopSignal = signal,
            )
        }
        rule.waitForIdle()

        // 确认初始在顶:首卡是 base[0]
        fun topCardDesc(): String {
            val key = androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
            val nodes = rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes()
                .mapNotNull { n ->
                    val b = n.boundsInRoot
                    val d = if (n.config.contains(key)) n.config[key].first() else ""
                    Triple(b.top, b.left, d)
                }
                .sortedWith(compareBy({ it.first }, { it.second }))
            return nodes.first().third
        }
        assertTrue(topCardDesc().contains("img1."), "初始首卡应为 img1,实际=${topCardDesc()}")

        val containerTop = rule.onNodeWithTag(TAG_GRID_CONTAINER).fetchSemanticsNode()
            .boundsInRoot.top

        // 在**顶部**切换:同时换顺序 + 发回顶信号(与 app 中"改规则"一致)
        reversed = true
        signal++
        rule.waitForIdle()

        val top = topCardDesc()
        val topmostPx = rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes()
            .map { it.boundsInRoot.top }.min()
        assertTrue(
            topmostPx > containerTop + 1f,
            "重排后最上面的卡片贴到了容器上边缘(top=%.1f, 容器=%.1f)—— 即'顶部上方还有卡片'".format(topmostPx, containerTop),
        )
        assertTrue(
            top.contains("img60."),
            "顶部重排后首卡应为新的第一项 img60,实际=$top —— " +
                "若为其他项,说明 LazyGrid 按 key 锚定到了旧首项的新位置",
        )
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToIndexCompat(index: Int) {
    val key = androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex
    val node = fetchSemanticsNode()
    assertTrue(node.config.contains(key), "该容器应支持 ScrollToIndex")
    node.config[key].action?.invoke(index)
}
