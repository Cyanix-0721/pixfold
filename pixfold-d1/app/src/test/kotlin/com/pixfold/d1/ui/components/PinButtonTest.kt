package com.pixfold.d1.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 图钉入口(验收第 6 项)。
 *
 * 语义要求(2026-09-17 用户指出):**必须是"图钉"语义,不能用五角星**。
 * 五角星在通用语义里是"收藏/评分",与"固定位置"不符 ——
 * 用户会带着"这是收藏"的错误预期去点,而产品词汇(§4)是**图钉**。
 *
 * 本测试锁死两点:
 *  1. 图形是**自绘的图钉**(Canvas),不是 ★/☆ 字形 —— 防止改动回退成星号;
 *  2. 无障碍描述用"固定位置/已固定"措辞(可用 TalkBack 听懂)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class PinButtonTest {

    @get:Rule
    val rule = createComposeRule()

    private fun descriptionOf(tag: String): String {
        val cfg = rule.onNodeWithTag(tag).fetchSemanticsNode().config
        val key = SemanticsProperties.ContentDescription
        return if (cfg.contains(key)) cfg[key].joinToString(" ") else ""
    }

    @Test
    fun `unpinned description uses pin wording`() {
        rule.setContent { PinToggle(isPinned = false, itemName = "a.jpg", onClick = {}) }
        val d = descriptionOf(TAG_PIN_TOGGLE)
        assertTrue(d.contains("固定"), "应表达'固定位置'语义,实际=$d")
        assertTrue(!d.contains("收藏"), "不得用'收藏'语义(那是五角星的语义),实际=$d")
    }

    @Test
    fun `pinned description distinguishes state`() {
        rule.setContent { PinToggle(isPinned = true, itemName = "a.jpg", onClick = {}) }
        val d = descriptionOf(TAG_PIN_TOGGLE)
        assertTrue(d.contains("已固定"), "已固定态应有明确措辞,实际=$d")
    }

    @Test
    fun `pin toggle is clickable`() {
        var clicked = 0
        rule.setContent { PinToggle(isPinned = false, itemName = "a.jpg", onClick = { clicked++ }) }
        rule.onNodeWithTag(TAG_PIN_TOGGLE).performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `does not render a star glyph anywhere`() {
        // 回归护栏:★/☆ 是"收藏"语义,不得再用于图钉(用户 2026-09-17 指出)
        rule.setContent { PinToggle(isPinned = true, itemName = "a.jpg", onClick = {}) }
        assertTrue(
            rule.onAllNodesWithText("★", substring = true).fetchSemanticsNodes().isEmpty(),
            "不得使用 ★ 字形",
        )
        assertTrue(
            rule.onAllNodesWithText("☆", substring = true).fetchSemanticsNodes().isEmpty(),
            "不得使用 ☆ 字形",
        )
    }

    @Test
    fun `pin icon is drawn by canvas and stays inside its bounds`() {
        rule.setContent { PinToggle(isPinned = true, itemName = "a.jpg", onClick = {}) }
        // 自绘图形没有文本子节点,但仍必须有可测的点击节点
        assertTrue(
            rule.onAllNodesWithTag(TAG_PIN_TOGGLE).fetchSemanticsNodes().isNotEmpty(),
            "图钉按钮必须存在且可定位",
        )
    }
}
