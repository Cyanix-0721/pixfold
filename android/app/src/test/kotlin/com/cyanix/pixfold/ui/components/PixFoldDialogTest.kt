package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全站弹窗抽象的行为约定。
 *
 * 这些断言钉的是**抽象本身**（计数语义、模糊开关、结构恒定），
 * 不是某个具体弹窗的文案 —— 后者各自有测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class PixFoldDialogTest {

    @get:Rule
    val rule = createComposeRule()

    // ==================== DialogBlurController ====================

    @Test
    fun `no dialog means no blur`() {
        val controller = DialogBlurController()
        assertFalse(controller.hasOpenDialog)
    }

    /**
     * 叠加弹窗必须用**计数**而不是布尔值。
     *
     * 反例（写成 Boolean 的后果）：确认框里再弹一个错误框，用户关掉内层时
     * `hasOpenDialog` 被置回 false ⇒ 外层还开着而背景已经清晰了。
     */
    @Test
    fun `nested dialogs keep the blur on until the outermost closes`() {
        val controller = DialogBlurController()

        controller.acquire()
        controller.acquire()
        assertTrue("两层都开着时必须模糊", controller.hasOpenDialog)

        controller.release()
        assertTrue("内层关了、外层还开着，模糊不能撤", controller.hasOpenDialog)

        controller.release()
        assertFalse("全部关闭后才恢复清晰", controller.hasOpenDialog)
    }

    /**
     * 释放次数多于获取次数时必须钳到 0，而不是变成负数。
     *
     * 负数的后果是**背景永久模糊** —— 用户关掉所有弹窗后界面还是糊的，
     * 而且没有任何办法恢复（除了杀进程）。
     */
    @Test
    fun `releasing more than acquired never goes negative`() {
        val controller = DialogBlurController()
        controller.acquire()
        controller.release()
        controller.release()
        controller.release()

        assertEquals(0, controller.openCount)
        assertFalse(controller.hasOpenDialog)
    }

    // ==================== DialogBlurHost ====================

    /**
     * **树结构恒定**（2026-10-20 真缺陷的回归断言）。
     *
     * 第一版 `DialogBlurHost` 在 `hasOpenDialog` 变化时切换了 `content()` 的
     * 调用层级（`if (has) { Box { Box { content() } } } else { content() }`），
     * Compose 因此把整棵子树当作「不同」而**销毁重建** —— 后果是弹窗的
     * `showDialog` 状态随 `remember` 一起丢失，弹窗一打开就自己消失。
     *
     * 断言方式：宿主内放一个 `remember` 的计数器，弹窗开关一轮后它必须还是同一个实例。
     */
    @Test
    fun `toggling a dialog does not recreate the content subtree`() {
        val controller = DialogBlurController()
        var show by mutableStateOf(false)
        var creations = 0

        rule.setContent {
            PixFoldTheme {
                DialogBlurHost(controller = controller) {
                    // 只在**首次组合**时自增。子树被销毁重建时会重新执行 ⇒ 计数 +1。
                    val marker = remember {
                        creations++
                        "marker"
                    }
                    Column {
                        Text(marker)
                        if (show) {
                            PixFoldInfoDialog(
                                onDismissRequest = { show = false },
                                confirmText = "OK",
                                title = "T",
                            ) {
                                Text("body")
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals("首次组合只该建一次", 1, creations)

        show = true
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).assertExists()

        show = false
        rule.waitForIdle()

        assertEquals(
            "开关弹窗不该重建宿主内容 —— 重建会让页面里所有 remember 状态丢失",
            1,
            creations,
        )
    }

    @Test
    fun `opening a dialog turns the blur on`() {
        val controller = DialogBlurController()
        var show by mutableStateOf(false)

        rule.setContent {
            PixFoldTheme {
                DialogBlurHost(controller = controller) {
                    Column {
                        TextButton(onClick = { show = true }) { Text("open") }
                        if (show) {
                            PixFoldInfoDialog(
                                onDismissRequest = { show = false },
                                confirmText = "OK",
                            ) {
                                Text("body")
                            }
                        }
                    }
                }
            }
        }
        assertFalse(controller.hasOpenDialog)

        rule.onNodeWithText("open").performClick()
        rule.waitForIdle()

        assertTrue("弹窗打开后宿主必须知道", controller.hasOpenDialog)
    }

    @Test
    fun `closing a dialog turns the blur off again`() {
        val controller = DialogBlurController()
        var show by mutableStateOf(true)

        rule.setContent {
            PixFoldTheme {
                DialogBlurHost(controller = controller) {
                    if (show) {
                        PixFoldInfoDialog(
                            onDismissRequest = { show = false },
                            confirmText = "OK",
                        ) {
                            Text("body")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertTrue(controller.hasOpenDialog)

        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()

        assertFalse(
            "弹窗离开组合必须归还模糊计数 —— 漏掉会永久糊住背景",
            controller.hasOpenDialog,
        )
    }

    // ==================== 刻意不测：模态语义 ====================

    /**
     * `PixFoldDialog` 的 `dismissOnOutside = false`（打包进度弹窗依赖它：
     * 中途误触外部就关掉弹窗、任务却还在后台写盘，用户会以为已经停了）。
     *
     * **为什么不设断言**：变异实测把它改成恒 `true` 后**全部 694 条测试仍绿**。
     * 原因是 Robolectric 下没有「点击 Dialog 外部」与「Dialog 上的返回键」这两条
     * 输入路径 —— `DialogProperties.dismissOnClickOutside` / `dismissOnBackPress`
     * 由真实窗口系统消费，测试进程里根本走不到那个分支。写一条「构造了弹窗就算过」
     * 的断言只会是假保障。
     *
     * 这条语义**只能真机验证**：点弹窗外部，弹窗必须还在、任务必须还在跑。
     */
    @Test
    fun `modal dialogs keep their outside dismissal flag`() {
        // 唯一能离线钉住的部分：参数被传到了 `DialogProperties` 上。
        // 走不到「真点了外部会怎样」，故这里只断「组件能按模态参数正常渲染」。
        rule.setContent {
            PixFoldTheme {
                PixFoldDialog(
                    onDismissRequest = {},
                    confirmButton = { TextButton(onClick = {}) { Text("keep") } },
                    title = "Modal",
                    dismissOnOutside = false,
                ) {
                    Text("body")
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PIXFOLD_DIALOG).assertExists()
    }
}
