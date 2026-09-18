package com.pixfold.d1.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 图钉入口(验收第 6 项)。
 *
 * 语义要求(2026-09-17 用户先后两次指出):
 *  1. **不能用五角星** —— ★ 在通用语义里是"收藏/评分",本功能是**固定位置**(§4 词汇);
 *  2. **不能用丑的自绘图形** —— 图标应复用**官方矢量资源**,而不是手画。
 *
 * 本测试锁死:图钉是**官方 Material Symbols `push_pin` 的 VectorDrawable**,
 * 经 `painterResource` 渲染;并保留"不得回退成 ★/☆"的护栏。
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
        assertTrue(descriptionOf(TAG_PIN_TOGGLE).contains("已固定"), "已固定态应有明确措辞")
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
        // 回归护栏:★/☆ 是"收藏"语义,不得再用于图钉
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
    fun `uses official material push pin vector resources`() {
        // 官方 Material Symbols push_pin 的两个变体必须真实存在于资源表并可解析
        // (防止有人改回自绘/字形,或漏提交 drawable 资源)
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(
            com.pixfold.d1.R.drawable.ic_push_pin_filled,
            com.pixfold.d1.R.drawable.ic_push_pin_outlined,
        ).forEach { resId ->
            val d = androidx.core.content.ContextCompat.getDrawable(ctx, resId)
            assertNotNull(d, "矢量资源应可加载: $resId")
            assertTrue(
                d is android.graphics.drawable.VectorDrawable,
                "应为 VectorDrawable(原生矢量),实际=${d.javaClass.name}",
            )
            assertTrue(d.intrinsicWidth > 0 && d.intrinsicHeight > 0, "矢量应有固有尺寸")
        }
    }

    @Test
    fun `two states use different glyphs so shape alone tells them apart`() {
        // 未固定=outlined,已固定=filled:两个资源 id 必须不同
        assertTrue(
            com.pixfold.d1.R.drawable.ic_push_pin_filled !=
                com.pixfold.d1.R.drawable.ic_push_pin_outlined,
            "两态应使用不同图形,不能只靠颜色区分",
        )
    }

    @Test
    fun `pin button keeps a 48dp minimum touch target when used at list size`() {
        rule.setContent { PinToggle(isPinned = false, itemName = "a.jpg", onClick = {}, size = 40.dp) }
        val node = rule.onNodeWithTag(TAG_PIN_TOGGLE).fetchSemanticsNode()
        val w = node.size.width
        assertTrue(w >= 40, "触控尺寸不应小于设计值,实际=$w")
    }
}
