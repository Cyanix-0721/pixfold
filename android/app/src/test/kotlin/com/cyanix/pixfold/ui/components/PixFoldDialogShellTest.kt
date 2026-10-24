package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「全站弹窗只有一个外观来源」的可执行检查。
 *
 * ## 这组测试在防什么
 *
 * 2026-10-20 之前工程里有 **11 处弹窗、两套外观**：走 `PixFoldDialog` 的是一套
 * （28dp 圆角 / `surfaceContainerHigh` / 24dp 内边距），走 `PixFoldAlertDialog`
 * 的另一套（Material3 `AlertDialog` 自己的 token）。用户要求「所有弹窗都是这个
 * 效果」——「这个效果」只能有一个来源，否则下次调外观又会漏掉一批。
 *
 * 现在所有弹窗都委托 `PixFoldDialogShell`。本测试钉两件事：
 *  1. **每一个入口都带上统一容器的 tag** —— tag 在 Shell 里加，谁绕过 Shell 谁就没 tag；
 *  2. **嵌套浮层共用同一个模糊计数** —— 浮层可能叠浮层（对话框里再开对话框），
 *     计数必须能叠加，否则内层一关外层就被解糊。
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class PixFoldDialogShellTest {

    @get:Rule
    val rule = createComposeRule()

    /** 直接把 `openCount` 抄到一个普通变量里，供 `Rule` 之外的断言读。 */
    private class Counter {
        var value = 0
    }

    @Test
    fun `the plain dialog renders the shared container tag`() {
        rule.setContent {
            PixFoldDialog(
                onDismissRequest = {},
                confirmButton = { TextButton(onClick = {}) { Text("ok") } },
                title = "标题",
            ) { DialogBody("正文") }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).assertExists()
    }

    @Test
    fun `the alert-shaped dialog renders the same shared container tag`() {
        // 它以前直接包 Material3 的 `AlertDialog`，外观锁定在 M3 自己的 token 里，
        // 与 `PixFoldDialog` 是**两套**。现在必须给出同一个 tag —— 那意味着
        // 两者真的走了同一个 Surface。
        rule.setContent {
            PixFoldAlertDialog(
                onDismissRequest = {},
                confirmButton = { TextButton(onClick = {}) { Text("ok") } },
                title = { Text("标题") },
                text = { Text("正文") },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).assertExists()
    }

    @Test
    fun `the undo scope dialog also uses the shared container`() {
        // 撤销范围对话框是唯一「三颗全宽竖排按钮」的弹窗。它曾自己拼 `Dialog` +
        // `Surface`，外观与其它弹窗各写一份。现在也必须带统一 tag。
        rule.setContent {
            PixFoldDialogShell(
                onDismissRequest = {},
                tag = TAG_PIXFOLD_DIALOG,
                title = { Text("撤销范围") },
                buttons = {
                    Button(onClick = {}, modifier = Modifier.testTag("this-run")) { Text("本次") }
                    Button(onClick = {}, modifier = Modifier.testTag("everything")) { Text("全部") }
                    TextButton(onClick = {}, modifier = Modifier.testTag("cancel")) { Text("取消") }
                },
            ) { Text("正文") }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).assertExists()
        // 三颗按钮都必须真的渲染出来 —— 竖排三颗正是不能塞进 AlertDialog 两槽的原因。
        rule.onNodeWithTag("this-run").assertExists()
        rule.onNodeWithTag("everything").assertExists()
        rule.onNodeWithTag("cancel").assertExists()
    }

    @Test
    fun `a scrollable content does not crash the shell`() {
        // 设置页的单选弹窗正文自己就是滚动容器。Shell 再套一层 `verticalScroll`
        // 会抛 `Vertically scrollable component was measured with an infinity maximum
        // height constraints`（实测 11 条设置页测试同时红）。`contentScrollable = false`
        // 是这条的逃生口 —— 本测试证明它真的能用。
        rule.setContent {
            PixFoldDialogShell(
                onDismissRequest = {},
                contentScrollable = false,
                buttons = { TextButton(onClick = {}) { Text("ok") } },
            ) {
                LazyColumn(modifier = Modifier.testTag("inner-list")) {
                    items((0 until 20).toList()) { i -> Text("第 $i 项") }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("inner-list").assertExists()
        rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).assertExists()
    }

    @Test
    fun `the chip row never registers a background blur`() {
        // 这条的来历：一次变异存活 —— 把 `SingleSelectChipRow` 里的
        // `PixFoldBottomSheet` 换回裸 `ModalBottomSheet`，700 个测试一个都没红。
        //
        // 2026-10-23 之后情况**反过来了**：用户裁决「全部 X 项就不要下面弹出窗口了,
        // 而是延续瀑布流显示」⇒ 芯片行里不该再有任何弹层，因此**任何时候都不该
        // 注册背景模糊**。断言从「点开后 == 1」改成「点开后仍是 0」，
        // 守卫的强度没降：谁把弹层加回来（哪怕换成走统一基底的
        // `PixFoldBottomSheet`），计数都会变 1，这条立刻红。
        val counter = Counter()
        rule.setContent {
            val controller = remember { DialogBlurController() }
            CompositionLocalProvider(LocalDialogBlur provides controller) {
                counter.value = controller.openCount
                SingleSelectChipRow(
                    items = (1..12).map { "item-$it" },
                    activeId = "item-1",
                    idOf = { it },
                    onSelect = {},
                    selectorTag = "sel-row",
                    chipTag = { "chip-$it" },
                    allEntryTag = "all-entry",
                    chipDescription = { it },
                    allEntryText = { n -> "全部 $n 项" },
                    chipLabel = { it },
                )
            }
        }
        rule.waitForIdle()
        assertEquals("芯片行本身不该注册模糊", 0, counter.value)

        rule.onNodeWithTag("all-entry").performClick()
        rule.waitForIdle()

        assertEquals(
            "「全部 N 项」已改原地展开,不该再注册背景模糊(弹层已按 2026-10-23 裁决移除)",
            0,
            counter.value,
        )
    }

    @Test
    fun `a dialog inside a dialog counts two`() {
        // 2026-10-23 起底部弹层（`PixFoldBottomSheet`）已随芯片改版删除 ——
        // 「全部 N 项」改成原地展开后全站再没有它的使用者，留着等于留一份没人调的
        // 外观来源。原先这条用例守的是「弹层 + 对话框计到 2」的**计数语义**，
        // 组件没了但语义还得守（`DialogBlurController` 仍用计数而非布尔），
        // 故改成「对话框里再开对话框」这个同样真实存在的嵌套场景。
        val counter = Counter()
        rule.setContent {
            val controller = remember { DialogBlurController() }
            CompositionLocalProvider(LocalDialogBlur provides controller) {
                counter.value = controller.openCount
                // 用 `PixFoldDialog`（带 content 槽的那个入口）而不是
                // `PixFoldConfirmDialog` —— 后者没有 content 槽，塞不进内层。
                PixFoldDialog(
                    onDismissRequest = {},
                    confirmButton = { TextButton(onClick = {}) { Text("确定") } },
                    title = "外层",
                ) {
                    PixFoldDialog(
                        onDismissRequest = {},
                        confirmButton = { TextButton(onClick = {}) { Text("确定") } },
                        title = "内层",
                    ) { Text("正文") }
                }
            }
        }
        rule.waitForIdle()

        // 计数而不是布尔值 —— 布尔值在内层关闭时会被置回 false，外层还开着
        // 而背景已经清晰了。这两条一起构成「计数行为」的可执行判据。
        assertEquals("嵌套对话框必须计到 2", 2, counter.value)
    }

    @Test
    fun `closing the only dialog drops the count back to zero`() {
        val counter = Counter()
        rule.setContent {
            var show by remember { mutableStateOf(true) }
            val controller = remember { DialogBlurController() }
            CompositionLocalProvider(LocalDialogBlur provides controller) {
                counter.value = controller.openCount
                if (show) {
                    PixFoldConfirmDialog(
                        onDismissRequest = { show = false },
                        title = "确认",
                        body = "正文",
                        confirmText = "取消",
                        onConfirm = { show = false },
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(1, counter.value)

        rule.onNodeWithText("取消").performClick()
        rule.waitForIdle()

        assertEquals(
            "弹窗关掉必须把计数释放回 0，否则背景会永久模糊",
            0,
            counter.value,
        )
        rule.onAllNodesWithTag(TAG_PIXFOLD_DIALOG).assertCountEquals(0)
    }
}
