package com.pixfold.d1.ui

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
        rule.onNodeWithText("工作流 A · 图片整理与命名").assertIsDisplayed()
        rule.onNodeWithText("工作流 B · CBZ 制作").assertIsDisplayed()
    }

    @Test
    fun `clicking workflow A entry invokes callback`() {
        var clicked = 0
        rule.setContent { HomeScreen(onOpenWorkflowA = { clicked++ }, onOpenWorkflowB = {}) }
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `clicking workflow B entry invokes callback`() {
        var clicked = 0
        rule.setContent { HomeScreen(onOpenWorkflowA = {}, onOpenWorkflowB = { clicked++ }) }
        rule.onNodeWithText("工作流 B · CBZ 制作").performClick()
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

        assertTrue(
            kotlin.math.abs(top - expected) < 2f,
            "注入 ${insetDp}dp 顶部 inset 后,标题顶部应约为 ${expected}dp(实际 ${top}dp);" +
                "若约为 ${paddingDp}dp,说明 inset 未被消费(真机表现为标题被状态栏遮挡)",
        )
    }
}
