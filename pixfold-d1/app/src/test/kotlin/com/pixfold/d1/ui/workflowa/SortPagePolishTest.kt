package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.ui.components.TAG_SCROLL_TO_TOP
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertTrue

/**
 * 2026-09-26 UI 打磨轮的五项修订(用户逐条提出)。
 *
 * | 项 | 用户原话 | 本文件对应 |
 * | --- | --- | --- |
 * | ① | 排序规则展开时无法下滑 | [expanding_the_rule_editor_does_not_shrink_the_scroll_container] 等 |
 * | ③ | 网格/列表切换选中有高亮所以不需要"✔" | [mode_toggle_suppresses_the_default_checkmark] |
 * | ④ | 集合按钮内的字没有居中,集合名(数量)后面还有一块空格的感觉 | [collection_chip_format_does_not_use_full_width_parentheses] |
 * | ⑤ | 网格视图加个列数按钮(默认选自适应) | [column_control_changes_card_width] 等 |
 *
 * ② (PinToggle 32dp) 的断言在 [com.pixfold.d1.ui.components.PinButtonTest]
 * —— 它是组件级行为,与页面无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SortPagePolishTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setPage() {
        rule.setContent {
            SortAndPreviewPage(
                collections = MockData.workspaceA.collections,
                contentInsets = WindowInsets(0, 0, 0, 0),
                initialCollectionId = "trip",
            )
        }
    }

    private fun heightOf(tag: String): Int =
        rule.onNodeWithTag(tag).fetchSemanticsNode().size.height

    private fun expandEditor() {
        rule.onNodeWithTag(TAG_RULE_SUMMARY_TOGGLE).performClick()
        rule.waitForIdle()
    }

    // ---------------------------------------------------------------- ① 展开后无法下滑

    /**
     * 根因断言:展开编辑器**不得压缩网格/列表的可用高度**。
     *
     * 修订前的实现把 `SortRuleEditor` 当作 `Column` 的固定(非加权)子项,高度无上限;
     * 展开后 `weight(1f)` 的网格被挤到几乎 0 高,而编辑器自身不在任何滚动容器内 ⇒
     * 内容一旦超出屏高,既不能由列表滚、也不能自己滚(用户看到的"滑不动")。
     *
     * 修订后编辑器是滚动容器**内部的头部**,容器高度只由"剩下的固定行"决定,
     * 与编辑器展开与否无关。故这里断言 `hExpanded == hCollapsed`。
     *
     * ⚠️ 这是对**根因**的精确定位断言:修订前该断言必然为红
     * (实测 hCollapsed 远大于 hExpanded)。
     */
    @Test
    fun expanding_the_rule_editor_does_not_shrink_the_scroll_container() {
        setPage()
        val collapsed = heightOf(TAG_GRID)

        expandEditor()
        rule.onNodeWithTag(TAG_SORT_RULE_EDITOR).assertExists()
        val expanded = heightOf(TAG_GRID)

        // 用 `>=` 而不是 `==`:摘要栏在折叠态多显示一行"批次默认规则"(scopeLabel),
        // 展开后那行交给编辑器自己的标题行显示,于是展开态反而多出约 24dp 给网格
        // (实测 503 -> 527)。真正的不变量是"展开**不得压缩**容器"。
        //
        // 修订前该断言必红:编辑器作为固定子项要吃掉约 428dp,网格从 503 掉到 ~75。
        assertTrue(
            expanded >= collapsed,
            "展开排序规则编辑器后网格容器被压扁了($collapsed -> $expanded):" +
                "编辑器又被当成固定子项了,展开态会滑不动",
        )
        assertTrue(collapsed > 0, "折叠态网格不该是 0 高,实际=$collapsed")
    }

    /**
     * 展开态的编辑器与图片**共用同一个滚动容器**,故编辑器可以随之下滑出视口。
     *
     * 这是"编辑器并进滚动区"(用户裁决 A)与"编辑器限高自滚"(被否决的 B)的判别标准:
     * 只有 A 会让编辑器的根坐标在滚动后向上移动。
     */
    @Test
    fun the_expanded_editor_scrolls_out_of_view_with_the_grid() {
        setPage()
        rule.onNodeWithTag(TAG_GRID).fetchSemanticsNode()
            .config[SemanticsActions.ScrollToIndex].action?.invoke(0)
        expandEditor()

        val editorTopBefore = rule.onNodeWithTag(TAG_SORT_RULE_EDITOR).fetchSemanticsNode()
            .boundsInRoot.top
        val firstCardTopBefore = firstCardTop()

        // 滚到远处:编辑器在头部,应当被推出视口上方
        rule.onNodeWithTag(TAG_GRID).fetchSemanticsNode()
            .config[SemanticsActions.ScrollToIndex].action?.invoke(20)
        rule.waitForIdle()

        // 先证明确实滚动了 —— 否则"编辑器消失"可能只是因为它压根没渲染
        val firstCardTopAfter = firstCardTop()
        assertTrue(
            firstCardTopAfter < firstCardTopBefore,
            "网格没有真的滚动(首卡 top $firstCardTopBefore -> $firstCardTopAfter)," +
                "本断言其余部分失去意义",
        )

        val editorTopAfter = runCatching {
            rule.onNodeWithTag(TAG_SORT_RULE_EDITOR).fetchSemanticsNode().boundsInRoot.top
        }.getOrNull()

        assertTrue(
            editorTopAfter == null || editorTopAfter < editorTopBefore,
            "编辑器没有跟着网格一起滚(前=$editorTopBefore 后=$editorTopAfter):" +
                "它大概仍留在滚动容器外,展开态因此滑不动",
        )
    }

    /** 首张网格卡片在根坐标系里的 top;无卡片时返回一个大值。 */
    private fun firstCardTop(): Float {
        val tops = rule.onAllNodesWithTag("grid-card")
            .fetchSemanticsNodes()
            .map { it.boundsInRoot.top }
        return if (tops.isEmpty()) Float.MAX_VALUE else tops.min()
    }

    /**
     * 折叠编辑器**不得**把用户拽回顶部。
     *
     * 「前插锚定陷阱」的修法是只在**展开**时回顶。若写成"每次切换都回顶",
     * 用户在深处折叠时会被强行拽走 —— 比"没反应"更糟。
     *
     * 用「回到顶部」按钮的可见性当"我是否在顶部"的探针
     * (与 `CollectionSwitchScrollTest` / `RuleAutoApplyPageTest` 同一手法):
     * 该按钮只在 `canScrollBackward` 时出现。
     */
    @Test
    fun collapsing_the_rule_editor_keeps_the_current_scroll_position() {
        setPage()
        expandEditor()

        scrollGridTo(20)
        assertTrue(!atTop(), "滚到 20 号后仍显示在顶部,本用例失去意义")

        // 折叠(此时用户在深处)
        rule.onNodeWithTag(TAG_RULE_SUMMARY_TOGGLE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SORT_RULE_EDITOR).assertDoesNotExist()

        assertTrue(
            !atTop(),
            "折叠编辑器把用户拽回了顶部:折叠不该改变滚动位置(只有展开才需要回顶)",
        )
    }

    /**
     * 展开编辑器时**必须**回顶 —— 这是「前插锚定陷阱」的修法本身。
     *
     * 展开是在视口**上方**插入内容,LazyList 会锚定"当前首个可见项"(按 key 解析),
     * 于是画面看起来毫无反应:用户点了展开,编辑器和图片都没动。
     *
     * 判别方法:先滚到远处(折叠态),再展开;若没有 `requestScrollToItem(0)`,
     * 「回到顶部」按钮会**依然可见**(说明仍在深处);有回顶则它消失。
     */
    @Test
    fun expanding_the_rule_editor_scrolls_back_to_top() {
        setPage()
        scrollGridTo(20)
        assertTrue(!atTop(), "滚到 20 号后仍显示在顶部,本用例失去意义")

        expandEditor()

        assertTrue(
            atTop(),
            "展开编辑器后没有回到顶部:前插锚定陷阱未修,用户点展开会看不到任何反应",
        )
    }

    /**
     * 列表模式同样把编辑器并进滚动容器(① 的另一个分支)。
     *
     * 网格与列表是两套独立的容器实现,只测网格会让列表分支的接线悄悄漏掉。
     */
    @Test
    fun expanding_the_editor_works_in_list_mode_too() {
        setPage()
        rule.onNodeWithTag(TAG_MODE_LIST_OPTION).performClick()
        rule.waitForIdle()

        val collapsed = heightOf(TAG_LIST)
        assertTrue(collapsed > 0, "折叠态列表不该是 0 高,实际=$collapsed")

        expandEditor()
        rule.onNodeWithTag(TAG_SORT_RULE_EDITOR).assertExists()

        val expanded = heightOf(TAG_LIST)
        assertTrue(
            expanded >= collapsed,
            "列表模式下展开编辑器把列表压扁了($collapsed -> $expanded)",
        )
    }

    /**
     * 展开的编辑器必须**横跨整行**,而不是缩进一个格子。
     *
     * 网格里 header 若忘了 `span = { GridItemSpan(maxLineSpan) }`,它就会被塞进单个
     * 卡片宽度(400dp 下实测 120dp),里面几排字段芯片互相挤压、根本按不动 ——
     * 看起来"编辑器还在",实际不可用。
     *
     * 判据取"比两张卡还宽"而不是"等于容器宽":header 占满 `maxLineSpan` 后,
     * 还要刨掉网格的 `contentPadding`(左右各 8dp)与编辑器自己的内衬(左右各 12dp),
     * 实测 400 - 16 - 24 = 360dp;单格形态则只有约 96dp。
     */
    @Test
    fun expanded_editor_spans_the_full_grid_width() {
        setPage()
        val twoCards = firstCardWidth() * 2

        expandEditor()

        val editorWidth = rule.onNodeWithTag(TAG_SORT_RULE_EDITOR).fetchSemanticsNode().size.width
        assertTrue(
            editorWidth > twoCards,
            "编辑器没有横跨整行(两张卡=$twoCards 编辑器=$editorWidth):" +
                "网格 header 的 span 丢了,控件被挤在一个格子里",
        )
    }

    private fun scrollGridTo(index: Int) {
        rule.onNodeWithTag(TAG_GRID).fetchSemanticsNode()
            .config[SemanticsActions.ScrollToIndex].action?.invoke(index)
        rule.waitForIdle()
    }

    /** 「回到顶部」按钮不可见 ⇔ 当前在顶部(该按钮只在 `canScrollBackward` 时出现)。 */
    private fun atTop(): Boolean =
        rule.onAllNodesWithTag(TAG_SCROLL_TO_TOP).fetchSemanticsNodes().isEmpty()

    // ---------------------------------------------------------------- ③ 去掉选中对勾

    /**
     * 网格/列表切换不得再有选中对勾(用户:「选中有高亮所以不需要"✔"」)。
     *
     * ## 为什么是"源码结构守卫"
     *
     * `Icons.Filled.Check` 的 `contentDescription = null`,在语义树里**不作节点**
     * —— Robolectric 里查不到也删不掉,任何"查一下有没有勾"的断言都是恒真的空断言。
     * 而 Robolectric 也不做真实绘制,像素比对同样不成立。
     * 故只能钉住**结构性事实**:该文件确实给 `SegmentedButton` 传了空 `icon`。
     * 这与 `ChipRowWrappingTest` / `LauncherIconTest` 的"回退档源码结构断言"同类。
     *
     * 真机视觉确认仍是最终判据(见本轮真机核对记录)。
     */
    @Test
    fun mode_toggle_suppresses_the_default_checkmark() {
        val code = decommented(source("ui/workflowa/SortAndPreviewPage.kt"))
        // 只看 `ModeToggle` 这一个函数体 —— 文件里的 `ColumnToggle`(列数控件)同样传了
        // `icon = {}`,按整份文件断言会让本用例在 `ModeToggle` 被改回去时仍然通过。
        val body = functionBody(code, "private fun ModeToggle(")

        assertTrue(
            body.contains("icon = {}"),
            "模式切换的 SegmentedButton 没有传空 icon ⇒ 选中态仍会画默认对勾(✔)",
        )
        assertTrue(
            !body.contains("SegmentedButtonDefaults.Icon("),
            "又用回了 SegmentedButtonDefaults.Icon ⇒ 对勾回来了",
        )
    }

    /**
     * 列数控件也不得带对勾。
     *
     * 它和模式切换是两个独立的 `SingleChoiceSegmentedButtonRow`,漏掉任何一个都会让
     * 界面上出现"高亮 + 勾"的双重选中表达(用户明确说只要高亮)。
     */
    @Test
    fun column_toggle_suppresses_the_default_checkmark() {
        val body = functionBody(
            decommented(source("ui/workflowa/SortAndPreviewPage.kt")),
            "private fun ColumnToggle(",
        )
        assertTrue(body.contains("icon = {}"), "列数控件仍会画默认对勾(✔)")
        assertTrue(!body.contains("SegmentedButtonDefaults.Icon("), "列数控件又用回了默认图标")
    }

    // ---------------------------------------------------------------- ④ 集合芯片文案

    /**
     * 集合芯片不得用**全角括号**(用户:「字没有居中,集合名(数量)后面还有一块空格的感觉」)。
     *
     * ## 根因(真机实测,不是猜测)
     *
     * 左右内衬实测各 56px = 16dp,**完全对称** —— padding 不是病因。病因是全角 `（`/`）`
     * 的字形度量:全角字形占 1em 的方块,墨迹只落在 em 框一侧,于是「旅行照片(30)」
     * 整体看上去右偏,且 `）` 右侧留下半个 em 的空白。两个抱怨是同一个根因。
     * 改用半角后字形紧贴文字,与 en 侧形态一致。
     *
     * ⚠️ 这是**数据格式**(`%1$s (%2$d)`),不是可自由改写的文案:它决定计数怎么拼进
     * 标签,故断言格式串本身而不是渲染结果(Robolectric 不测量文本)。
     */
    @Test
    fun collection_chip_format_does_not_use_full_width_parentheses() {
        val zh = resourceText("values-zh/strings_workflowa.xml")
        val en = resourceText("values/strings_workflowa.xml")

        // ⚠️ 只看**这一条**资源。中文资源里别处仍有正当的全角括号
        // (如 `a_sort_levels` 的「排序规则（%1$d/%2$d 级）」),按整份文件断言会误伤。
        val zhLine = zh.lines().first { it.contains("name=\"a_collection_chip\"") }

        assertTrue(
            !zhLine.contains("（") && !zhLine.contains("）"),
            "中文集合芯片又用回了全角括号 ⇒ 文字会看起来偏右、尾部留空: $zhLine",
        )
        assertTrue(
            zhLine.contains("%1\$s (%2\$d)"),
            "中文集合芯片格式应为半角 `%1\$s (%2\$d)`,实际: $zhLine",
        )
        assertTrue(
            en.contains("%1\$s (%2\$d)"),
            "英文集合芯片格式被改动了(两侧应同为半角括号)",
        )
    }

    /** 同一处全角括号缺陷的邻居:「重置人工调整(0 处)」。 */
    @Test
    fun reset_manual_label_does_not_use_full_width_parentheses() {
        val zh = resourceText("values-zh/strings_workflowa.xml")
        val line = zh.lines().first { it.contains("name=\"a_reset_manual\"") }
        assertTrue(
            !line.contains("（") && !line.contains("）"),
            "「重置人工调整」仍用全角括号,与同一行的集合芯片形态不一致: $line",
        )
    }

    // ---------------------------------------------------------------- ⑤ 列数控件

    /**
     * 列数控件默认存在,且默认停在「自适应」一档。
     *
     * "默认停在自适应"不能只看节点存在 —— 必须读 `SemanticsProperties.Selected`,
     * 否则把默认值改成 2 列时本用例照样通过。
     */
    @Test
    fun column_control_defaults_to_adaptive() {
        setPage()
        rule.onNodeWithTag(TAG_COLUMN_ROW).assertExists()
        rule.onNodeWithTag(TAG_COLUMN_ADAPTIVE).assertExists()
        listOf(2, 3, 4).forEach { rule.onNodeWithTag(columnTag(it)).assertExists() }

        assertTrue(isSelected(TAG_COLUMN_ADAPTIVE), "默认应选中「自适应」")
        listOf(2, 3, 4).forEach {
            assertTrue(!isSelected(columnTag(it)), "默认不该选中 $it 列")
        }
    }

    /** 点某一档后,选中态独占转移到该档(同一行内只能有一个选中)。 */
    @Test
    fun column_control_selection_is_exclusive() {
        setPage()

        rule.onNodeWithTag(columnTag(2)).performClick()
        rule.waitForIdle()
        assertTrue(isSelected(columnTag(2)), "点了 2 列却没选中")
        assertTrue(!isSelected(TAG_COLUMN_ADAPTIVE), "选了 2 列后「自适应」仍显示选中")

        rule.onNodeWithTag(TAG_COLUMN_ADAPTIVE).performClick()
        rule.waitForIdle()
        assertTrue(isSelected(TAG_COLUMN_ADAPTIVE), "点回「自适应」却没选中")
        assertTrue(!isSelected(columnTag(2)), "点回「自适应」后 2 列仍显示选中")
    }

    private fun isSelected(tag: String): Boolean {
        val config = rule.onNodeWithTag(tag).fetchSemanticsNode().config
        return config.contains(SemanticsProperties.Selected) &&
            config[SemanticsProperties.Selected]
    }

    /** 列表模式不显示列数控件(列数只对网格有意义)。 */
    @Test
    fun column_control_is_hidden_in_list_mode() {
        setPage()
        rule.onNodeWithTag(TAG_MODE_LIST_OPTION).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_COLUMN_ROW).assertDoesNotExist()
    }

    /**
     * 点数字档真的改变卡片宽度。
     *
     * Robolectric **会**做真实布局(只是不测量文本),故卡片宽度是可断言的物理量。
     * 实测(w400dp-h800dp,网格容器 400x503):自适应=120、2 列=184、3 列=120、4 列=88。
     * ⇒ `w(2) > w(自适应) > w(4)`,且 `w(3) == w(自适应)`。
     *
     * 最后一条把"自适应在 400dp 下正好是 3 列"钉住:`ADAPTIVE_COLUMN_MIN_WIDTH` 若被调大
     * (例如 110dp → 130dp,400dp 只剩 2 列),`column_control_changes_card_width` 的
     * 单调性断言仍然成立,只有这条会红。
     */
    @Test
    fun column_control_changes_card_width() {
        setPage()

        val adaptive = firstCardWidth()
        rule.onNodeWithTag(columnTag(2)).performClick()
        rule.waitForIdle()
        val two = firstCardWidth()
        rule.onNodeWithTag(columnTag(3)).performClick()
        rule.waitForIdle()
        val three = firstCardWidth()
        rule.onNodeWithTag(columnTag(4)).performClick()
        rule.waitForIdle()
        val four = firstCardWidth()

        assertTrue(two > adaptive, "选 2 列后卡片应更宽(自适应=$adaptive 2列=$two)")
        assertTrue(adaptive > four, "选 4 列后卡片应更窄(自适应=$adaptive 4列=$four)")
        assertTrue(
            three == adaptive,
            "400dp 下自适应应等于 3 列(自适应=$adaptive 3列=$three)" +
                " —— 不等说明 ADAPTIVE_COLUMN_MIN_WIDTH 让默认列数变了",
        )
    }

    /**
     * 自适应真的随宽度变。
     *
     * 320dp 下可用约 304dp,`304 / 110 = 2.76` ⇒ 2 列;同一档位在 400dp 下是 3 列。
     * 若把 `GridCells.Adaptive` 退回 `GridCells.Fixed(3)`(即忽略 `columns == null`),
     * 本用例会红。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h800dp")
    fun adaptive_column_count_follows_the_available_width() {
        setPage()
        val adaptive = firstCardWidth()

        rule.onNodeWithTag(columnTag(2)).performClick()
        rule.waitForIdle()
        assertTrue(
            firstCardWidth() == adaptive,
            "320dp 下自适应应等于 2 列(自适应=$adaptive 2列=${firstCardWidth()})",
        )

        rule.onNodeWithTag(columnTag(3)).performClick()
        rule.waitForIdle()
        assertTrue(
            firstCardWidth() < adaptive,
            "320dp 下 3 列应比自适应(2 列)更窄(自适应=$adaptive 3列=${firstCardWidth()})",
        )
    }

    private fun firstCardWidth(): Int =
        rule.onAllNodesWithTag("grid-card")[0].fetchSemanticsNode().size.width

    // ---------------------------------------------------------------- helpers

    private fun source(relative: String): String {
        val f = File("src/main/kotlin/com/pixfold/d1/$relative")
        assertTrue(f.exists(), "源码文件不存在: ${f.absolutePath}(cwd=${File(".").absolutePath})")
        return f.readText()
    }

    /**
     * 去掉注释行。注释里会解释"为什么要传 `icon = {}`",不去掉的话 `contains` 会被自己的
     * 说明文字满足(与 `ChipRowWrappingTest` 跳过 `//` / `*` 开头行同一原因)。
     */
    private fun decommented(src: String): String = src.lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * 截取从 `signature` 所在行到下一个顶层 `}` 的函数体。
     *
     * KDoc/注释在调用前已被 [decommented] 剥掉,故这里只需按花括号配对。
     * 断言必须落在**单个函数**上:同一个文件里有多个同类控件,
     * 按整份文件断言会让"某一个忘了传"漏网。
     */
    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(start >= 0, "源码里找不到函数: $signature")
        var depth = 0
        var i = code.indexOf('{', start)
        assertTrue(i > 0, "函数 $signature 后面没有 `{`")
        val from = i
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(from, i + 1)
                }
            }
            i++
        }
        error("函数 $signature 的花括号没有闭合")
    }

    /**
     * 读 res 下的资源文件,并**去掉 XML 注释**。
     *
     * 去注释是必须的:本轮就在注释里写了「全角括号 `（`/`）` 的字形度量」这类说明,
     * 不去注释的话断言会被自己的注释绊倒(与 `ChipRowWrappingTest` 跳过 `//` 同一原因)。
     */
    private fun resourceText(relative: String): String {
        val f = File("src/main/res/$relative")
        assertTrue(f.exists(), "资源文件不存在: ${f.absolutePath}(cwd=${File(".").absolutePath})")
        return f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }
}
