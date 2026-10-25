package com.cyanix.pixfold.ui.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceListing
import com.cyanix.pixfold.ui.components.pinTag
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工作区列表弹窗的行为契约。
 *
 * ## 这一层守的是什么
 *
 * 列表弹窗是「工作区」这个概念的**唯一可见界面**：目录在哪、哪些还真的能读、
 * 顺序是什么、怎么删掉一条 —— 全部只能从这里看出来。所以这里钉的不是像素，
 * 而是四类**一旦错了用户无法自行诊断**的事实：
 *
 *  1. **分区标题只在该分区有内容时出现** —— 空标题会让用户以为「下面那条丢了」，
 *     而实际上它只是被分到了另一个区；
 *  2. **点击各自带着自己的 treeUri 回调** —— 列表里有多条时，串了 uri 就会
 *     「点 A 打开了 B」，且不会有任何报错；
 *  3. **撤销授权开关默认关、且移除时如实回传** —— 默认开等于把「删一条记录」
 *     升格成「动系统授权」这种不可逆动作；
 *  4. **图钉与移除按钮的触控区 ≥48dp**（AGENTS.md §2.7）。
 *
 * ## 为什么文案一律走 [TestText]
 *
 * Robolectric 默认 locale 是 en_US，即 `values/` 的英文槽位。写死中文的断言在
 * i18n 落地后全部失效，而那种「失效」看起来像实现坏了、其实只是断言在选语言。
 *
 *  5. **拖拽松手后上报的是哪两个下标** —— 见本类末尾两条用例。
 *
 * ## 拖拽能测到哪一层（原来的口径划得过宽，2026-10-25 订正）
 *
 * 原先这里写着「拖拽本身不测」，理由引 AGENTS.md §7.4「动画观感 / 拖拽过程只能真机」。
 * 那条只对**拖动过程中的跟手与让位动画**成立；**「松手后 `onMove` 收到哪两个下标」
 * 是纯结构断言**，属 §7.4 表的「结构 / 接线」行，可离线断 —— 先例
 * `ui/components/DragReorderListTest.kt:61-74` 早就这么做了。
 *
 * 口径划宽的实际代价（评审实测）：`onMove` 原先写死空 lambda，导致 8 条拖拽 / 偏移
 * 变异（去掉下分区 `granted.size` 偏移、`(f,t)` 写成 `(t,f)`、去掉 `to != from`、
 * 让位符号反、去掉 `coerceIn` 等）在整个测试集下**全部存活**。其中「去掉偏移」的线上
 * 表现正是`WorkspaceDragList.kt` KDoc 写的「拖了没反应」：不崩、不报错、就是不对。
 *
 * 仍然**不测**的部分：拖动中的实时让位手感、边缘自动滚动、跨视口拖拽 —— 那些要真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class WorkspaceListDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private fun listing(name: String, granted: Boolean, pinned: Boolean = false) =
        WorkspaceListing(WorkspaceEntry(WorkspaceHandle("content://tree/$name", name), pinned), granted)

    /** 回调捕获。带计数的原因见 `点图钉回调 togglePin` 的 KDoc。 */
    private class Captured {
        var opened: String? = null
        var removed: Pair<String, Boolean>? = null
        var pinned: String? = null
        var pinCalls = 0
        var added = 0

        /** 最近一次 `onMove` 收到的 `(from, to)`；`null` = 一次都没收到。 */
        var move: Pair<Int, Int>? = null
    }

    private fun show(
        listings: List<WorkspaceListing>,
        current: String? = null,
        rejectHint: String? = null,
    ): Captured {
        val c = Captured()
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                WorkspaceListDialog(
                    listings = listings,
                    currentTreeUri = current,
                    onDismiss = {},
                    onOpen = { c.opened = it },
                    onRemove = { treeUri, alsoRelease -> c.removed = treeUri to alsoRelease },
                    onTogglePin = { c.pinned = it; c.pinCalls++ },
                    onMove = { from, to -> c.move = from to to },
                    onAdd = { c.added++ },
                    rejectHint = rejectHint,
                )
            }
        }
        return c
    }

    @Test
    fun `两个分区都有内容时都画标题`() {
        show(listOf(listing("a", granted = true), listing("b", granted = false)))
        rule.onNodeWithText(TestText.of(R.string.workspace_section_granted)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_section_stale)).assertIsDisplayed()
    }

    /**
     * 空分区整块不渲染（规格 §7.3「分区为空 ⇒ 该分区整块不渲染，不显示空标题」）。
     *
     * **这个方向**：上分区有内容、下分区为空 ⇒ 只有上标题。
     *
     * ⚠️ 这条**单独存在是不够的**。变异验证实测（M1 第一轮）：把
     * `if (granted.isNotEmpty())` 改成恒真后本类 10 条**全绿** —— 因为本场景里
     * granted 本来就非空，变异对断言的三件事（上标题在、下标题不在）毫无影响。
     * 两个分区各需一个「自己为空」的场景，否则 `if` 的另一支永远没有断言压着。
     */
    @Test
    fun `分区为空时不画该分区标题`() {
        show(listOf(listing("a", granted = true)))
        rule.onNodeWithText(TestText.of(R.string.workspace_section_granted)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_section_stale)).assertDoesNotExist()
    }

    /**
     * **反方向**：下分区有内容、上分区为空 ⇒ 只有下标题。
     *
     * 与上一条成对，才把「空分区不画标题」的两个 `if` 分支都钉住。
     * 少了它，上分区的守卫（`granted.isNotEmpty()`）整支没有断言 —— 变异 M1 存活
     * 就是这么来的（2026-10-25 实测，不是推断）。
     */
    @Test
    fun `只有失效条目时不画已持久授权标题`() {
        show(listOf(listing("b", granted = false)))
        rule.onNodeWithText(TestText.of(R.string.workspace_section_stale)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_section_granted)).assertDoesNotExist()
    }

    @Test
    fun `点一行带着 treeUri 回调 open`() {
        val c = show(listOf(listing("a", granted = true)))
        rule.onNodeWithTag(workspaceRowTag("content://tree/a")).performClick()
        assertEquals("content://tree/a", c.opened)
    }

    /**
     * 「现在打开的是哪一个」必须写在那一条上，而且**只有那一条** ——
     * 用户是拿列表在多个目录间切换的，看不出来就会点进自己刚离开的那个。
     *
     * **为什么是两行数据 + 计数断言，而不是一行 + 「存在」断言**：
     * 单行场景下「只标当前行」与「每行都标」在语义树上完全同形 —— 都是一条
     * `使用中`，断言照样过。评审实测（M25）：把 `if (treeUri == currentTreeUri)`
     * 改成 `if (currentTreeUri != null)` 后，旧断言 836 tests 0 failures 存活；
     * 换成两行 + `assertCountEquals(1)` 立刻红
     * `Expected exactly '1' node but found '2' nodes`。
     * 多标一条的后果是用户以为两个目录同时开着，而状态栏只显示一个。
     */
    @Test
    fun `当前打开的那条标出使用中`() {
        show(
            listOf(listing("a", granted = true), listing("b", granted = true)),
            current = "content://tree/a",
        )
        rule.onAllNodesWithText(TestText.of(R.string.workspace_in_use)).assertCountEquals(1)
    }

    /**
     * 默认关 + 移除时如实回传 false。
     *
     * 变异靶子：把 `releaseGrant` 初值改成 true，本条即红。
     * 为什么这条重要：默认开会让「从列表删一条记录」静默升级成「撤销系统授权」，
     * 而后者对用户是不可逆的（要重新走一次系统目录选择器）。
     */
    @Test
    fun `撤销开关默认关，移除时回传 false`() {
        val c = show(listOf(listing("a", granted = true)))
        rule.onNodeWithTag(TAG_WORKSPACE_RELEASE_SWITCH).assertIsOff()
        rule.onNodeWithTag(workspaceRemoveTag("content://tree/a")).performClick()
        assertEquals("content://tree/a" to false, c.removed)
    }

    @Test
    fun `打开撤销开关后移除会回传 true`() {
        val c = show(listOf(listing("a", granted = true)))
        rule.onNodeWithTag(TAG_WORKSPACE_RELEASE_SWITCH).performClick()
        rule.onNodeWithTag(TAG_WORKSPACE_RELEASE_SWITCH).assertIsOn()
        rule.onNodeWithTag(workspaceRemoveTag("content://tree/a")).performClick()
        assertEquals("content://tree/a" to true, c.removed)
    }

    /**
     * 图钉回调必须带着**被点那一条**的 uri。
     *
     * 断言 `pinCalls == 1` 而不只是比较 uri：实现里 48dp 触控容器与 32dp 图钉
     * 各自可点（视觉尺寸由用户 2026-09-26 定为 32dp，触控区必须扩到 48dp），
     * 若两层都真的触发了，uri 相同、`pinned` 相同，只看值发现不了双触发。
     * 双触发的后果是幂等操作被连做两次 —— 图钉可逆所以看不出来，但同样的写法
     * 复用到「移除」上就是删两条。
     */
    @Test
    fun `点图钉回调 togglePin`() {
        val c = show(listOf(listing("a", granted = true), listing("b", granted = true)))
        rule.onNodeWithTag(pinTag("content://tree/b")).performClick()
        assertEquals("content://tree/b", c.pinned)
        assertEquals(1, c.pinCalls, "图钉只应触发一次（48dp 容器与 32dp 图钉不得双重消费）")
    }

    @Test
    fun `添加按钮回调 onAdd`() {
        val c = show(listOf(listing("a", granted = true)))
        rule.onNodeWithTag(TAG_WORKSPACE_ADD).performClick()
        assertEquals(1, c.added)
    }

    @Test
    fun `被拒提示出现时显示`() {
        show(listOf(listing("a", granted = true)), rejectHint = "ALL_PINNED")
        rule.onNodeWithText("ALL_PINNED").assertIsDisplayed()
    }

    /**
     * 触控目标 ≥48dp（AGENTS.md §2.7 / 规格 §7.4）。
     *
     * `PinToggle` 的视觉尺寸是 32dp（用户 2026-09-26 的明确要求，不可改默认值），
     * 所以它的 48dp 触控区**必须由本弹窗的容器提供**；`✕` 则是 `IconButton` 显式
     * `size(48.dp)`。这条断言钉的正是「视觉 32 / 触控 48」这个双尺寸约定 ——
     * 把容器改回 32dp 时它立刻红。
     */
    @Test
    fun `图钉与移除按钮的触控区都不小于 48dp`() {
        show(listOf(listing("a", granted = true)))
        val pin = rule.onNodeWithTag(pinTag("content://tree/a")).getUnclippedBoundsInRoot()
        val pinHeight = pin.bottom.value - pin.top.value
        assertTrue(pinHeight >= 48f, "图钉触控区必须 ≥48dp（AGENTS.md §2.7），实际 ${pinHeight}dp")

        val remove = rule.onNodeWithTag(workspaceRemoveTag("content://tree/a")).getUnclippedBoundsInRoot()
        val removeHeight = remove.bottom.value - remove.top.value
        assertTrue(removeHeight >= 48f, "移除按钮触控区必须 ≥48dp，实际 ${removeHeight}dp")
    }

    /**
     * 用**节点本地坐标**模拟一次完整拖拽，并把 `(from, to)` 的**整表下标**读回来。
     *
     * 手法与 `ui/components/DragReorderListTest.kt:61-74` 一致：
     * `down → advanceEventTime(700) → moveBy → moveTo → up`。
     * **`advanceEventTime(700)` 不能省**：`detectDragGesturesAfterLongPress` 要真的越过
     * 长按阈值（默认 500ms）才会把 down 升级成 drag，少这一步只有一次点击，`onMove`
     * 一次都不会调，用例会以为「拖了没反应」而红 —— 那种红是手势没启动，不是实现错。
     *
     * 坐标统一换算到 **from 行的 topLeft** 这个 origin 上：`performTouchInput` 的坐标是
     * 节点本地坐标，直接用两个不同的 `boundsInRoot.center` 相减会带上两行之间的偏移。
     */
    private fun dragRow(c: Captured, fromTag: String, toTag: String): Pair<Int, Int>? {
        // 弹窗正文是 300dp 上限的 verticalScroll：行可能在视口外，先确保它进来。
        rule.onNodeWithTag(fromTag).performScrollTo()
        rule.waitForIdle()

        val node = rule.onNodeWithTag(fromTag).fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val from = node.boundsInRoot.center - origin
        val to = rule.onNodeWithTag(toTag).fetchSemanticsNode().boundsInRoot.center - origin

        rule.onNodeWithTag(fromTag).performTouchInput {
            down(from)
            advanceEventTime(700) // 长按阈值必须真实经过，否则拖拽不启动
            moveBy(Offset(0f, -20f))
            moveTo(to)
            up()
        }
        rule.waitForIdle()
        return c.move
    }

    /**
     * **上分区**拖拽上报的是整表下标：第 1 行拖到第 2 行 ⇒ `(0, 1)`。
     *
     * 上分区不做偏移（它本来就排在整表头部），所以这条压的是「别多此一举加偏移」
     * 那一侧（变异 M26：上分区加上 `granted.size` 偏移 ⇒ 上报 `(2,3)` 变红），
     * 以及 `onMove(f, t)` 被写成 `onMove(t, f)` 的方向反转（变异 M9 ⇒ `(1,0)`）。
     */
    @Test
    fun `上分区拖拽上报整表下标`() {
        val c = show(listOf(listing("ga", granted = true), listing("gb", granted = true)))
        val moved = dragRow(c, workspaceRowTag("content://tree/ga"), workspaceRowTag("content://tree/gb"))
        assertEquals(0 to 1, moved, "上分区第 1 行拖到第 2 行应上报整表下标 (0, 1)")
    }

    /**
     * **下分区**拖拽必须加上 `granted.size` 偏移：下分区第 1 行（整表第 3 行）拖到
     * 第 2 行（整表第 4 行）⇒ `(2, 3)`，**不是** `(0, 1)`。
     *
     * **这条用例专门压 `granted.size` 偏移**。少了偏移的线上表现是「拖了没反应」：
     * 不崩、不报错，只是把整表第 0/1 条换了个位置（变异 M1 实测上报 `(0,1)`）；
     * 偏移写成 `listings.size`（4）则是 `(4,5)`，越界（变异 M21）。
     * 上分区名用 `ga/gb`、下分区名用 `sc/sd`，两区不重名 —— 否则同名行在语义树里
     * 各出现一次，`onNodeWithTag` 之外的文本定位会变成 ambiguous。
     */
    @Test
    fun `下分区拖拽上报的是加了偏移的整表下标`() {
        val c = show(
            listOf(
                listing("ga", granted = true),
                listing("gb", granted = true),
                listing("sc", granted = false),
                listing("sd", granted = false),
            ),
        )
        val moved = dragRow(c, workspaceRowTag("content://tree/sc"), workspaceRowTag("content://tree/sd"))
        assertEquals(2 to 3, moved, "下分区第 1 行拖到第 2 行应上报整表下标 (2, 3)")
    }
}
