package com.pixfold.d1.ui

import com.pixfold.d1.TestText

import com.pixfold.d1.R

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.pixfold.d1.PixFoldApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** U1:首页必须同时渲染两条工作流入口(产品级并列结构,不可合并/隐藏)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `home renders both workflow entries`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).assertIsDisplayed()
    }

    @Test
    fun `clicking workflow A entry invokes callback`() {
        var clicked = 0
        rule.setContent { HomeScreen(onOpenWorkflowA = { clicked++ }, onOpenWorkflowB = {}) }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `clicking workflow B entry invokes callback`() {
        var clicked = 0
        rule.setContent { HomeScreen(onOpenWorkflowA = {}, onOpenWorkflowB = { clicked++ }) }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).performClick()
        assertEquals(1, clicked)
    }

    /**
     * 真机回归(2026-09-16):targetSdk 36 强制 edge-to-edge,首页标题曾被状态栏压住。
     *
     * Robolectric 下系统 inset 恒为 0,若只断言"top > 0"会因 16dp 内边距而**假通过**。
     * 做法:注入一个已知的 40dp 顶部 inset,断言标题顶部 ≈ 40(inset) + 16(内边距) = 56dp。
     * 若 inset 未被消费,实测值会是 ~16dp,断言即失败 —— 该用例可被变异检出。
     */
    @Test
    fun `home consumes injected top inset`() {
        val insetDp = 40
        val paddingDp = 16
        rule.setContent {
            HomeScreen(
                onOpenWorkflowA = {},
                onOpenWorkflowB = {},
                contentInsets = WindowInsets(top = insetDp.dp),
            )
        }

        val top = rule.onNodeWithTag(TAG_TITLE).getUnclippedBoundsInRoot().top.value
        val expected = (insetDp + paddingDp).toFloat()

        // 判据从"精确等于 inset+padding"改为"**不得上移进 inset 区域**"。
        //
        // 原因:标题现在与右侧**设置图标按钮**同处一个 Row(设置入口在右上角),
        // 该 Row 高度由 IconButton 的 48dp 触控目标决定,标题在其中**垂直居中**,
        // 故顶部会比 inset+padding 再低若干 dp(实测 +6dp)。
        // 若坚持精确相等,就会把"布局有意变更"误报为失败。
        //
        // 而本用例真正要防的回归是"**标题被状态栏/挖孔遮挡**"—— 即 top 小于
        // inset+padding。忽略 inset 时 top ≈ 16dp,远小于 expected,该判据仍会拦住。
        assertTrue(
            top >= expected - 2f,
            "注入 ${insetDp}dp 顶部 inset 后,标题顶部(${top}dp)不得小于 ${expected}dp —— " +
                "小于即说明 inset 未被消费(真机表现为标题被状态栏遮挡)",
        )
        assertTrue(
            top < expected + 16f,
            "标题顶部(${top}dp)不应比 ${expected}dp 低太多 —— 过大会挤压内容区",
        )
    }
}
