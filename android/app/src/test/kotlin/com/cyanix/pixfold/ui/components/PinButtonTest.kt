package com.cyanix.pixfold.ui.components

import com.cyanix.pixfold.TestText

import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.SourceItem

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
        assertTrue(d.contains(TestText.of(R.string.pin_toggle_unpinned, "").trim()), "应表达'固定位置'语义,实际=$d")
        assertTrue(!d.contains("收藏"), "不得用'收藏'语义(那是五角星的语义),实际=$d")
    }

    @Test
    fun `pinned description distinguishes state`() {
        rule.setContent { PinToggle(isPinned = true, itemName = "a.jpg", onClick = {}) }
        assertTrue(
            descriptionOf(TAG_PIN_TOGGLE).contains(TestText.of(R.string.pin_toggle_pinned, "").trim()),
            "已固定态应有明确措辞",
        )
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
            com.cyanix.pixfold.R.drawable.ic_push_pin_filled,
            com.cyanix.pixfold.R.drawable.ic_push_pin_outlined,
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
            com.cyanix.pixfold.R.drawable.ic_push_pin_filled !=
                com.cyanix.pixfold.R.drawable.ic_push_pin_outlined,
            "两态应使用不同图形,不能只靠颜色区分",
        )
    }

    /**
     * 列表行里的图钉**视觉尺寸** = 32dp(用户 2026-09-26:「PinToggle48太大了,32差不多了」)。
     *
     * ## 为什么这里断言的是 32 而不是 48
     *
     * 用户说的"48"是**触控目标**的尺寸,不是看到的方块:真机 dump 实测网格图钉的语义节点
     * 是 168×168px = 48dp(560dpi),而它的视觉方块只有 112px = 32dp。多出来的 16dp/侧
     * 来自 `Modifier.clickable` 自己向外撑的扩展触控区
     * (foundation `Clickable.kt` 的 `getExtendedTouchPadding`:向外补齐到
     * `ViewConfiguration.minimumTouchTargetSize`)。⇒ **32dp 视觉 + 48dp 触控同时成立**,
     * 用户的裁决不违反无障碍规范。
     *
     * ## 为什么断言挂在 ThumbRow 上
     *
     * 这里原本挂着一条名叫"48dp minimum touch target"却断言 `w >= 40` 的用例
     * —— 名字与断言不符,而且它自己传 `size = 40.dp`,即便把生产代码从 40 改回 48
     * 也照样绿。缺陷的真身是 `ThumbRow` 调用 `PinToggle` 时多传了一个 `size = 40.dp`
     * 覆盖掉组件默认值,所以必须**从 ThumbRow 这一侧**观察才能锁住。
     *
     * ⚠️ Robolectric 不做真实绘制,但**会**做真实布局:此处的 `size.width` 就是
     * `Modifier.size(size)` 的结果,故能精确断到 32。
     */
    @Test
    fun `pin inside a list row renders at 32dp`() {
        val item = SourceItem(
            id = "i1", collectionId = "c", dir = "day1", baseName = "img1", ext = "jpg",
            sizeBytes = 1, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = 1,
        )
        rule.setContent {
            ThumbRow(item = item, onClick = {}, isPinned = false, onPin = {}, modifier = Modifier)
        }
        val node = rule.onNodeWithTag(pinTag(item.id)).fetchSemanticsNode()
        assertEquals(32, node.size.width, "列表行图钉的视觉边长应为 32dp,实际=${node.size.width}")
        assertEquals(32, node.size.height, "列表行图钉的视觉边长应为 32dp,实际=${node.size.height}")
    }
}
