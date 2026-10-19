package com.cyanix.pixfold.ui.workflowb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PackProgressBar] 的界面层断言(D2b 第 2 项「后台任务与进度」的 UI 落点)。
 *
 * ## 这层为什么必须测(而不是"领域层的 `TaskProgress` 已经有 26 个用例了")
 *
 * 领域层保证的是"进度**怎么算**"([TaskProgress.processed] / [TaskProgress.fraction]
 * 的取值);本类保证的是"界面**到底把哪个数画上去了**"。这两件事会各自独立地错:
 * 实现里把 `processed` 写成 `done` 时,领域层的 26 条测试**一条都不会红** ——
 * 因为领域层根本没参与那次错误。同理,`cancelling` 忘了接 `enabled` 时,
 * 界面上按钮长得还是那个按钮,只有点下去才暴露重复发信号。
 *
 * 故这里只断**用户能观察到的事实**:显示什么数、能不能点、点了会不会真的回调。
 * 刻意不断颜色/尺寸 —— 那是主题体系的事(见 `ThemeTest`),且 Robolectric 的
 * LEGACY 后端画不出真实颜色。
 *
 * ⚠️ 期望文案一律经 [TestText] 从资源解析,不写语言字面量:Robolectric 的默认
 * locale 是 `en_US`(渲染的是 `values/` 的英文),写死中文在 i18n 后必然失效。
 * 唯一的例外是"纯数字 / 斜杠"这类**语言无关**的计数文本,它们本来就是拼出来的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class PackProgressBarTest {

    @get:Rule
    val rule = createComposeRule()

    /** 组件只做展示,故脚手架不需要状态机;需要交互的用例自己传可变参数。 */
    private fun show(
        progress: TaskProgress,
        cancelling: Boolean = false,
        onCancel: () -> Unit = {},
    ) {
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                PackProgressBar(progress = progress, onCancel = onCancel, cancelling = cancelling)
            }
        }
    }

    /** 进度计数文本的形状见组件 KDoc「文案取舍」:纯数字 + 斜杠,与语言无关。 */
    private fun countText(processed: Int, total: Int): String = "$processed / $total"

    // ================= 1. 计数与进度条本体 =================

    @Test
    fun `two of five processed shows the count`() {
        show(TaskProgress(total = 5, done = 2))
        rule.onNodeWithTag(TAG_PACK_PROGRESS).assertIsDisplayed()
        rule.onNodeWithText(countText(2, 5)).assertIsDisplayed()
    }

    /**
     * **本类最重要的一条**:计数必须走 `processed`(成功 + 失败 + 跳过),
     * 不能走 `done`(只算成功)。
     *
     * 夹具刻意让 `done` 与 `processed` 不相等:2 成功 + 1 失败 = 处理过 3 卷。
     * 若实现写成 `done`,这里会显示「2 / 5」而不是「3 / 5」,断言当场变红。
     * 只断 `done == processed` 的夹具(例如只有成功的卷)会让两个字段同时满足断言,
     * 那种夹具测不出这个错误 —— 而"进度条说已成功、条形按已处理画"正是要防的缺陷。
     */
    @Test
    fun `failed volumes count as processed not as successful`() {
        show(TaskProgress(total = 5, done = 2, failed = 1))
        rule.onNodeWithText(countText(3, 5)).assertIsDisplayed()
        // 反向:若实现退化成 done,界面上会出现「2 / 5」——这条把它钉死。
        assertTrue(
            rule.onAllNodesWithText(countText(2, 5)).fetchSemanticsNodes().isEmpty(),
            "失败卷不推进进度时,用户会看到进度条卡住不动(2026-10-19 领域层刻意拆分的语义)",
        )
    }

    /** 跳过同理:它不是失败,但确实"处理过了"。 */
    @Test
    fun `skipped volumes also count as processed`() {
        show(TaskProgress(total = 4, done = 1, skipped = 2))
        rule.onNodeWithText(countText(3, 4)).assertIsDisplayed()
    }

    // ================= 2. 完成后按钮仍在且可点 =================

    /**
     * 完成后**不隐藏**取消按钮:①隐藏/出现会改变行高造成布局跳动;
     * ②"点取消的瞬间任务刚好跑完"是真实会发生的,按钮先消失会让点击落到别处。
     * 组件的策略见 `PackProgressBar.kt` 的 KDoc「关于完成后取消按钮还在」。
     */
    @Test
    fun `progress bar and cancel button survive completion`() {
        show(TaskProgress(total = 3, done = 3))
        rule.onNodeWithTag(TAG_PACK_PROGRESS).assertIsDisplayed()
        rule.onNodeWithText(countText(3, 3)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_PACK_CANCEL).assertIsDisplayed()
        rule.onNodeWithTag(TAG_PACK_CANCEL).assertIsEnabled()
    }

    // ================= 3. cancelling 时禁用但保留文案 =================

    @Test
    fun `cancelling disables the button but keeps its label`() {
        show(TaskProgress(total = 5, done = 2), cancelling = true)
        rule.onNodeWithTag(TAG_PACK_CANCEL).assertIsNotEnabled()
        // 按钮禁用 ≠ 按钮消失:文案必须还在,否则整行会重排(布局跳动)。
        // 走合并语义树取按钮自身的文本(TextButton 会 mergeDescendants)。
        rule.onNodeWithTag(TAG_PACK_CANCEL)
            .assertTextEquals(TestText.of(R.string.common_cancel))
    }

    /** 禁用不能只是"看起来灰了":点下去必须**不再**回调。 */
    @Test
    fun `a disabled cancel button does not invoke the callback`() {
        var clicks = 0
        show(TaskProgress(total = 5, done = 2), cancelling = true, onCancel = { clicks++ })
        rule.onNodeWithTag(TAG_PACK_CANCEL).performClick()
        rule.waitForIdle()
        assertEquals(0, clicks, "已请求取消后不应再向上一层重复发信号")
    }

    // ================= 4. 点击回调恰好一次 =================

    @Test
    fun `tapping cancel invokes the callback exactly once`() {
        var clicks = 0
        show(TaskProgress(total = 5, done = 2), onCancel = { clicks++ })
        rule.onNodeWithTag(TAG_PACK_CANCEL).performClick()
        rule.waitForIdle()
        assertEquals(1, clicks, "一次点击只能产生一次回调(0 与 2 都是缺陷)")
    }

    // ================= 5. 空任务(0/0)不崩 =================

    /**
     * `total = 0` 时领域层的 `fraction` 直接返回 `1f`(见 `TaskProgress`),
     * 故这里既不能除零、也不该显示成"还没开始"。界面只需诚实显示 0 / 0。
     */
    @Test
    fun `an empty task shows zero of zero instead of crashing`() {
        show(TaskProgress(total = 0))
        rule.onNodeWithTag(TAG_PACK_PROGRESS).assertIsDisplayed()
        rule.onNodeWithText(countText(0, 0)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_PACK_CANCEL).assertIsEnabled()
    }

    /** 空任务在领域层已算"完成",但组件不据此隐藏按钮(见用例 2 的理由)。 */
    @Test
    fun `an empty task still offers cancel just like any other state`() {
        var clicks = 0
        show(TaskProgress(total = 0), onCancel = { clicks++ })
        rule.onNodeWithTag(TAG_PACK_CANCEL).performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }

    // ================= 6. 状态推进时界面真的跟着走 =================

    /**
     * 钉住 `progress = { progress.fraction }` 这层**真的接到了状态上**:
     * 把进度换成新快照后,文字必须跟着变。若某次改动把 `progress` 参数换成组合期
     * 求值的常量(条形不再随状态重绘),文字这条也会因为不再重组而变化 —— 这是
     * 本类唯一能在离线阶段碰到"进度条不动"这个症状的地方。
     */
    @Test
    fun `advancing the progress updates the count`() {
        rule.setContent {
            var progress by remember { mutableStateOf(TaskProgress(total = 5, done = 1)) }
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                PackProgressBar(progress = progress, onCancel = { progress = progress.copy(done = 4) })
            }
        }
        rule.onNodeWithText(countText(1, 5)).assertIsDisplayed()

        rule.onNodeWithTag(TAG_PACK_CANCEL).performClick()
        rule.waitForIdle()

        rule.onNodeWithText(countText(4, 5)).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText(countText(1, 5)).fetchSemanticsNodes().isEmpty(),
            "状态推进后旧读数必须消失(否则就是「数据变了、界面不动」)",
        )
    }
}
