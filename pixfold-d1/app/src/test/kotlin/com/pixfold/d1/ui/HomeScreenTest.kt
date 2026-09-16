package com.pixfold.d1.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pixfold.d1.PixFoldApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

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
}
