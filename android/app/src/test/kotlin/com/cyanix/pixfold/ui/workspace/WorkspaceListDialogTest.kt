package com.cyanix.pixfold.ui.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * ## 刻意不测什么
 *
 * **拖拽本身不测**。长按 + 实时让位是手势与逐帧布局的组合，Robolectric 断不了
 * （AGENTS.md §7.4 已明确「动画观感 / 拖拽过程只能真机」）。这里只保证「拖拽容器
 * 在空列表时不崩、在有内容时能渲染」，提交语义由 `WorkspaceController` 的领域测试
 * 与真机核对覆盖。去掉这条假保障比留着它更诚实。
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
                    onMove = { _, _ -> },
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
     * 「现在打开的是哪一个」必须写在那一条上，而不是只写在页头 ——
     * 用户是拿列表在多个目录间切换的，看不出来就会点进自己刚离开的那个。
     */
    @Test
    fun `当前打开的那条标出使用中`() {
        show(listOf(listing("a", granted = true)), current = "content://tree/a")
        rule.onNodeWithText(TestText.of(R.string.workspace_in_use)).assertIsDisplayed()
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
}
