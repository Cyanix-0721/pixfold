package com.cyanix.pixfold.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.cyanix.pixfold.R
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 芯片的**四条排布规则**与「纯图标动作按钮」的回归护栏(2026-10-21 用户裁决)。
 *
 * ## 为什么这些用例只能断语义、不能断外观
 *
 * Robolectric **不做真实文本测量** —— 芯片标签宽度一律算成 ~0(本仓库已多次实测:
 * trip 34.9dp / scan 34.6 / misc 34.3 / cn 34.3)。因此「宽度跟随内容」「文字居中」
 * 这类**依赖真实宽度**的结论在离线环境里算出来的 bounds 全都一样,
 * 按 bounds 断言等于什么都没断(2026-09-25 那条按屏宽比的断言就是在变异下存活的)。
 *
 * 所以这里断的是**能反映规则的语义事实**:
 * - 芯片标签文本 = 截断后的字符串(长度上限与首尾保留由 [truncateChipLabel] 的纯函数用例负责)
 * - 行容器的 `maxLines` 语义由源码结构守卫负责(`ChipRowWrappingTest`)
 * - 溢出入口是**全宽**的可点击节点,且带下箭头语义
 *
 * 真机核对负责「看起来对不对」这一层(§7.4 纪律)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class ChipLayoutRulesTest {

    @get:Rule
    val rule = createComposeRule()

    private val manyItems = (1..8).map { "item-$it" }

    private fun showRow(
        items: List<String> = manyItems,
        maxLines: Int = Int.MAX_VALUE,
        emphasized: Boolean = false,
        chipTextMaxLines: Int = Int.MAX_VALUE,
        activeId: String = items.first(),
    ) {
        rule.setContent {
            SingleSelectChipRow(
                items = items,
                activeId = activeId,
                idOf = { it },
                onSelect = {},
                selectorTag = TAG_ROW,
                chipTag = { id -> "chip-$id" },
                allEntryTag = TAG_ALL,
                sheetTag = TAG_SHEET,
                sheetListTag = TAG_SHEET_LIST,
                sheetChipTag = { id -> "sheet-chip-$id" },
                chipDescription = { it },
                allEntryText = { n -> "all $n" },
                maxLines = maxLines,
                emphasized = emphasized,
                chipLabel = { it },
                chipTextMaxLines = chipTextMaxLines,
            )
        }
    }

    /**
     * 溢出时 `FlowRow(maxLines)` 会**直接裁掉**放不下的芯片 ⇒ 必须有一个能到达
     * 全部条目的入口,否则被裁掉的那些既看不见也没有入口(2026-09-25 缺陷形态)。
     *
     * 这里断「限行 1 行 + 只有 2 个条目时仍然给入口」—— 那条规则(Robolectric 量不出
     * 宽度 ⇒ 限行就放宽到「多于 1 个」)正是为了堵这个洞。
     */
    @Test
    fun `a cramped row always offers the all entry even with two items`() {
        showRow(items = listOf("a", "b"), maxLines = 1)

        rule.onNodeWithTag(TAG_ALL).assertIsDisplayed()
    }

    /**
     * 溢出入口必须**占满整行宽**(用户 2026-10-21 裁决「加个屏框的下箭头长按钮」)。
     *
     * 这一条能离线断:`fillMaxWidth` 只依赖父约束,不依赖文本测量 ⇒ Robolectric 算得准
     * (与「芯片宽度跟随内容」不同,那条依赖真实文字宽度,离线只能守源码结构)。
     * 变异实测:去掉 `fillMaxWidth` 后此断言红。
     */
    @Test
    fun `the all entry spans the full row width`() {
        showRow()

        val row = rule.onNodeWithTag(TAG_ROW).getUnclippedBoundsInRoot()
        val entry = rule.onNodeWithTag(TAG_ALL).getUnclippedBoundsInRoot()

        // 入口必须铺到**不比芯片行窄**。
        //
        // 刻意不写「两者相等」:芯片行(`FlowRow`)自己有 `padding(horizontal = 12.dp)`,
        // 而入口在行外层 ⇒ 入口比芯片行宽 24dp 是**正确**的(实测 行=336.86 入口=360.86,
        // 差值正好两个 12dp)。去掉 `fillMaxWidth` 后入口退化成「文字宽度 + 内边距」,
        // 会明显小于行宽 —— 这条就能杀掉那个变异。
        val rowWidth = row.right.value - row.left.value
        val entryWidth = entry.right.value - entry.left.value
        assertTrue(
            entryWidth >= rowWidth,
            "溢出入口必须铺满整行(至少不比芯片行窄),实际 行=$rowWidth 入口=$entryWidth",
        )
    }

    /**
     * 芯片内文字必须**居中**(用户 2026-10-21 裁决「内容文字要居中」)。
     *
     * 只能守源码结构:Robolectric 把文本宽度算成 ~0 ⇒ `textAlign` 的视觉后果量不出来。
     * 变异实测:把 `Center` 改成 `Start` 后所有布局断言仍绿,故必须回到源码这一层。
     */
    @Test
    fun `the chip text is centered inside the chip`() {
        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/SingleSelectChipRow.kt")
            .readText()
        val chipBody = src.substringAfter("private fun <T> SingleSelectChip(")
            .substringBefore("/**")

        assertTrue(
            chipBody.contains("textAlign = TextAlign.Center"),
            "芯片内文字必须居中(用户 2026-10-21 裁决)",
        )
    }

    /**
     * 行数上限必须**真的作用在文字上**。
     *
     * 变异实测:把 `maxLines = textMaxLines` 改成 `Int.MAX_VALUE`(即参数接了但不用)
     * 后所有断言仍绿 —— 因为 Robolectric 里文字单行就放得下,2 行上限无效。
     * 所以判据只能落在源码:参数必须被传给 `Text`。
     */
    @Test
    fun `the text line cap is actually applied to the text`() {
        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/SingleSelectChipRow.kt")
            .readText()
        val chipBody = src.substringAfter("private fun <T> SingleSelectChip(")
            .substringBefore("/**")

        assertTrue(
            chipBody.contains("maxLines = textMaxLines"),
            "芯片文字的行数上限必须来自参数 textMaxLines,不能是写死的值",
        )
    }

    /** 不限行且条目没超常驻上限 ⇒ 不出现入口(它只是噪声,还白占一行高度)。 */
    @Test
    fun `an uncramped row with few items shows no all entry`() {
        showRow(items = listOf("a", "b"))

        rule.onNodeWithTag(TAG_ALL).assertDoesNotExist()
    }

    /**
     * 溢出入口必须**可点**,并且点了真的打开 sheet —— 断「节点存在」会放过
     * 「按钮在但点不动」(本项目踩过:菜单项存在而点击落在别的层)。
     */
    @Test
    fun `the all entry opens the sheet`() {
        showRow()

        rule.onNodeWithTag(TAG_ALL).performClick()

        rule.onNodeWithTag(TAG_SHEET).assertIsDisplayed()
    }

    /**
     * 一级芯片**行**恒定最多 2 行芯片。
     *
     * 用户 2026-10-21 原话「第一层chip最多显示2行(指chip)」,经真机反问后澄清为
     * 「芯片行最多 2 行」—— 不是「芯片内文字 2 行」。这条把两件事分开钉住:
     * 行数由 `TOP_CHIP_MAX_LINES` 管,文字行数由 `CHIP_TEXT_MAX_LINES` 管。
     *
     * 同时守住「点击系列后收缩到 1 行」的回归:用户明确否掉了 1 行 + 下拉。
     */
    @Test
    fun `the top level row caps at two rows of chips`() {
        assertEquals(2, TOP_CHIP_MAX_LINES)

        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt")
            .readText()
        val lines = src.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }

        assertTrue(
            lines.any { it.trim() == "maxLines = TOP_CHIP_MAX_LINES," },
            "一级行必须恒定受 TOP_CHIP_MAX_LINES 限制(用户 2026-10-21 裁决)",
        )
        assertEquals(
            false,
            lines.any { it.contains("maxLines = if (activeGroup.isSeries)") },
            "不得再按『是否选中系列』切换行数(用户否掉了 1 行 + 下拉)",
        )
    }

    /** 芯片内文字的行数上限是兜底常量,不是一级/二级各一套。 */
    @Test
    fun `both levels share the same chip text line cap`() {
        assertEquals(3, CHIP_TEXT_MAX_LINES)

        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt")
            .readText()
        assertEquals(
            false,
            src.contains("chipTextMaxLines ="),
            "两级芯片的文字行数上限应统一走默认值,不应各自指定",
        )
    }

    /**
     * 一级 / 二级芯片行必须**视觉可分**(用户 2026-10-21 裁决「第一层和第二层在UI上区分开,
     * 比如加个遮罩」)。
     *
     * 只能守源码:遮罩是 `background(...)` 的颜色值,Robolectric 的 LEGACY 图形后端
     * 渲染不出真实颜色(§7.4:视觉规格需 `@GraphicsMode(NATIVE)` + `captureToImage()`),
     * 而这里更简单 —— 只要参数被传给 `background` 的色值即可。
     */
    @Test
    fun `both levels paint a background and the top one is darker`() {
        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/SingleSelectChipRow.kt")
            .readText()

        // 用户 2026-10-21 两次反订:
        //  ① 「你这个遮罩都顶到边缘了很难看」⇒ 色块要有 8dp 外边距,不能铺满行宽;
        //  ② 「只有第一层又第二层又没有也很难看」⇒ 两级都垫底色,只差强度。
        assertTrue(
            src.contains("alpha = if (emphasized) 0.45f else 0.18f"),
            "两级都要垫底色,1 级 0.45 / 2 级 0.18(用户 2026-10-21 裁决「加个遮罩」)",
        )
        assertTrue(
            src.contains(".padding(horizontal = 8.dp, vertical = 2.dp)"),
            "遮罩必须留白不顶边(用户:「遮罩都顶到边缘了很难看」)",
        )
        assertFalse(
            src.contains("Color.Transparent"),
            "二级行不能再是透明 —— 一层有一层没有读起来像画漏了",
        )
    }

    /** 两级行必须把 `emphasized` 只给一级(给二级等于没区分)。 */
    @Test
    fun `only the top level row is emphasized`() {
        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt")
            .readText()

        assertEquals(
            1,
            src.lines().count { it.trim() == "emphasized = true," },
            "只有一级行该垫底色",
        )
    }

    // ---- 纯图标动作按钮 ----

    /**
     * 轻点必须触发动作,长按必须浮出说明 —— 两者共存靠 `TooltipBox` 的
     * `PointerEventPass.Initial` 观察(不消费短按)。这条守住「改图标后短按被浮字吃掉」。
     */
    @Test
    fun `the icon action fires on tap and shows a tooltip on long press`() {
        var clicks = 0
        val description = "恢复建议值 标题"
        rule.setContent {
            MetaActionButton(
                icon = ImageVector.vectorResource(R.drawable.ic_undo),
                description = description,
                onClick = { clicks++ },
                tag = TAG_ACTION,
            )
        }

        rule.onNodeWithTag(TAG_ACTION).performClick()
        assertEquals(1, clicks)

        // 长按后说明文本必须出现在树上(浮字本身由 M3 负责定位,离线只断「出现了」)。
        rule.onNodeWithTag(TAG_ACTION).performTouchInput { longClick() }
        rule.onNodeWithText(description).assertIsDisplayed()
    }

    /** 禁用态不得触发动作 —— 「当前值已等于建议值」时按钮灰着,误触不该改数据。 */
    @Test
    fun `a disabled icon action does not fire`() {
        var clicks = 0
        rule.setContent {
            MetaActionButton(
                icon = ImageVector.vectorResource(R.drawable.ic_undo),
                description = "恢复建议值 标题",
                onClick = { clicks++ },
                tag = TAG_ACTION,
                enabled = false,
            )
        }

        rule.onNodeWithTag(TAG_ACTION).performClick()
        assertEquals(0, clicks)
    }

    private companion object {
        const val TAG_ROW = "test-chip-row"
        const val TAG_ALL = "test-chip-all"
        const val TAG_SHEET = "test-chip-sheet"
        const val TAG_SHEET_LIST = "test-chip-sheet-list"
        const val TAG_ACTION = "test-meta-action"
    }
}
