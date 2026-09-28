package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.pixfold.d1.R
import com.pixfold.d1.TestText
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.ui.components.TAG_SCROLL_TO_TOP
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-09-26 UI 打磨轮的五项修订(用户逐条提出)。
 *
 * | 项 | 用户原话 | 本文件对应 |
 * | --- | --- | --- |
 * | ① | 排序规则展开时无法下滑 | [expanding_the_rule_editor_does_not_shrink_the_scroll_container] 等 |
 * | ③ | 网格/列表切换选中有高亮所以不需要"✔" | [mode_toggle_suppresses_the_default_checkmark] |
 * | ④ | 集合按钮内的字没有居中,集合名(数量)后面还有一块空格的感觉 | [collection_chip_format_does_not_use_full_width_parentheses] |
 * | ⑤ | 网格视图加个列数按钮(默认选自适应) | [column_button_shows_the_current_count_in_the_menu] 等 |
 *
 * ② (PinToggle 32dp) 的断言在 [com.pixfold.d1.ui.components.PinButtonTest]
 * —— 它是组件级行为,与页面无关。
 *
 * ## ⑤ 的第二轮(用户 2026-09-26 再提,本文件多数据此改写)
 *
 * 第一轮是 4 个数字按钮 + 一个「自适应」按钮,单独占一行。用户随后指出三个问题:
 * 自适应档被拆成两行、行高与 2/3/4 不一致;要求**纯图标 + 长按出说明 + 点击下拉列表**;
 * 且图标应挪到网格/列表切换按钮**右边**而不单独占行。最终落地形态见
 * [com.pixfold.d1.ui.workflowa.ColumnCountButton] 的 KDoc。
 *
 * 本段断言的**核心难点**:`DropdownMenuItem` 不设 `selected` 语义(已核实 M3 1.4.0
 * `Menu.kt` 的 `DropdownMenuItemContent` 只挂 `clickable`),所以"高亮的是哪一档"
 * 在语义树里唯一的痕迹是 `leadingIcon` 那个对勾图标 —— 断言必须查
 * [columnCheckTag],且必须 `useUnmergedTree = true`(菜单项的 `clickable` 会合并子节点)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SortPagePolishTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * 默认不传两个"设置页默认列数"参数 ⇒ 两者为 `null` ⇒ 落到自适应算式,
     * 这正是「用户没在设置里选过」的初始状态。
     */
    private fun setPage(
        portraitDefaultColumns: Int? = null,
        landscapeDefaultColumns: Int? = null,
    ) {
        rule.setContent {
            SortAndPreviewPage(
                collections = MockData.workspaceA.collections,
                contentInsets = WindowInsets(0, 0, 0, 0),
                initialCollectionId = "trip",
                portraitDefaultColumns = portraitDefaultColumns,
                landscapeDefaultColumns = landscapeDefaultColumns,
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
        // 只看 `ModeToggle` 这一个函数体 —— 文件里还有别的分段按钮用法,
        // 按整份文件断言会让本用例在 `ModeToggle` 被改回去时仍然通过。
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
     * 列数控件已从 `SegmentedButton` 换成 `DropdownMenuItem`,**没有"默认对勾"这回事**。
     *
     * 这条守卫钉的是"回退":`SingleChoiceSegmentedButtonRow` 每个 `SegmentedButton` 默认都会
     * 在选中时画 `SegmentedButtonDefaults.Icon(selected)`,一旦有人把列数控件改回分段按钮
     * 而忘了传 `icon = {}`,界面上就会重新出现"高亮 + 勾"的双重选中表达
     * (用户 2026-09-26:「网格/列表切换按钮选中有高亮所以不需要"✔"」)。
     *
     * 之所以是**源码结构断言**:`Icons.Filled.Check` 的 `contentDescription = null`,
     * 在语义树里不作节点 —— 任何"查一下有没有勾"的运行时断言都是恒真的空断言。
     */
    @Test
    fun column_button_is_not_a_segmented_button_row() {
        val code = decommented(source("ui/workflowa/SortAndPreviewPage.kt"))
        val body = functionBody(code, "private fun ColumnCountButton(")

        assertTrue(
            !body.contains("SegmentedButton"),
            "列数控件又改回了分段按钮 ⇒ 选中态会重新画默认对勾(✔),与模式切换不一致",
        )
        assertTrue(
            body.contains("DropdownMenuItem("),
            "列数控件不再是下拉列表项 ⇒ 用户裁决的「点击下拉列表选择」失效",
        )
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
     * 列数按钮只在网格模式出现(列数只对网格有意义),且默认**不展开**菜单。
     *
     * 第二轮该按钮从"单独一行"挪进了网格/列表切换按钮**右边**(用户 2026-09-26:
     * 「因为只有一个图标所以可以放在网格/列表的切换按钮右边而不用单独占用一行」),
     * 但"仅网格"这条没变。
     */
    @Test
    fun column_button_appears_only_in_grid_mode() {
        setPage()
        rule.onNodeWithTag(TAG_COLUMN_BUTTON).assertExists()
        // 按钮存在 ≠ 列表已展开:下拉菜单默认必须是关的,否则它会盖住网格
        rule.onNodeWithTag(TAG_COLUMN_MENU).assertDoesNotExist()

        rule.onNodeWithTag(TAG_MODE_LIST_OPTION).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_COLUMN_BUTTON).assertDoesNotExist()
    }

    /**
     * 点图标展开下拉列表;列表里**只有数字**(没有「自适应」项),当前档位打勾。
     *
     * w400dp-h800dp 下自适应算式 `(400 - 16) / 80 = 4.8` ⇒ 4 列,故勾应在 4。
     * 竖屏上限 5、下界 2 ⇒ 列表恰为 2/3/4/5(用户裁决:「去除自适应选项」+「竖屏显示 2–5」)。
     * 若有人把「自适应」当选项加回列表,`columnTag(6)` 之外的条目数会变,这里的边界断言即失效。
     */
    @Test
    fun column_menu_lists_the_numbers_and_checks_the_current_one() {
        setPage()
        openColumnMenu()

        listOf(2, 3, 4, 5).forEach { rule.onNodeWithTag(columnTag(it)).assertExists() }
        rule.onNodeWithTag(columnTag(1)).assertDoesNotExist() // 下界 2
        rule.onNodeWithTag(columnTag(6)).assertDoesNotExist() // 竖屏上限 5

        assertTrue(hasChecked(4), "400dp 下自适应是 4 列,勾应在 4 列那一档")
        listOf(2, 3, 5).forEach {
            assertTrue(!hasChecked(it), "当前是 4 列,不该同时给 $it 列打勾")
        }
    }

    /**
     * 选一档后:菜单关闭、卡片宽度真的变、勾转移到新档。
     *
     * Robolectric **会**做真实布局(只是不测量文本),故卡片宽度是可断言的物理量。
     * 实测(w400dp-h800dp,网格容器 400dp;`contentPadding` 8dp × 2,格间**无** spacing,
     * 格间视觉间隙来自每格自己的 `padding(4.dp)`):
     * `(400 - 16) / n - 8` ⇒ 4 列=88、3 列=128、2 列=184。
     * ⇒ `w(2) > w(3) > w(4)`,且 `w(4) == w(自适应)`。
     *
     * 最后一条把"自适应在 400dp 下正好是 4 列"钉住:`ADAPTIVE_COLUMN_MIN_WIDTH` 若被调大
     * (例如 80dp → 110dp,400dp 只剩 3 列),前面的单调性断言**仍然成立**,只有这条会红
     * —— 这正是它存在的理由。
     */
    @Test
    fun column_selection_changes_card_width_and_moves_the_check() {
        setPage()
        val adaptive = firstCardWidth()
        // 当前档位不靠菜单断言:按钮自己的 contentDescription 就是「N 列」,
        // 不展开菜单也能读(菜单里的对勾只在展开时存在,见 hasChecked)。
        assertAnnouncedColumns(4, "w400dp 下自适应应为 4 列(播报不对说明算式或上界变了)")

        val two = pickColumn(2)
        // 选完必须立刻关闭:菜单不关会一直盖住网格,用户以为没生效
        rule.onNodeWithTag(TAG_COLUMN_MENU).assertDoesNotExist()
        val three = pickColumn(3)
        val four = pickColumn(4)

        assertTrue(two > three, "2 列应比 3 列更宽(2列=$two 3列=$three)")
        assertTrue(three > four, "3 列应比 4 列更宽(3列=$three 4列=$four)")
        assertTrue(
            adaptive == four,
            "400dp 下自适应应等于 4 列(自适应=$adaptive 4列=$four)" +
                " —— 不等说明 ADAPTIVE_COLUMN_MIN_WIDTH 让默认列数变了",
        )

        // 展开菜单核对对勾**转移**了(而不只是"多了一个勾")
        openColumnMenu()
        assertTrue(hasChecked(4), "改选 4 列后勾应在 4 列那一档")
        assertTrue(!hasChecked(2), "改选 4 列后 2 列不该还留着勾")
    }

    /**
     * 自适应真的随可用宽度变。
     *
     * 320dp 下可用 `320 - 16 = 304`,`304 / 80 = 3.8` ⇒ 3 列;同一算式在 400dp 下是 4 列。
     * 若有人把页面算出的列数写死成常数(即忽略自适应),本用例会红 ——
     * 这是"默认列数跟随机器分辨率"这条用户诉求的离线守卫。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h800dp")
    fun adaptive_column_count_follows_the_available_width() {
        setPage()
        val adaptive = firstCardWidth()
        assertAnnouncedColumns(3, "320dp 下自适应应为 3 列")

        val two = pickColumn(2)
        assertTrue(
            two > adaptive,
            "320dp 下 2 列应比自适应(3 列)更宽(自适应=$adaptive 2列=$two)",
        )

        val three = pickColumn(3)
        assertTrue(
            three == adaptive,
            "320dp 下 3 列应等于自适应(自适应=$adaptive 3列=$three)",
        )
    }

    /**
     * 设置页的「默认列数」是排序页的**起点**(用户裁决:「设置 = 默认值」)。
     *
     * 传 `2` 时当前档位应是 2 列,而不是自适应算出的 4 列 ——
     * 这条把优先级 `设置默认值 > 自适应` 钉住;没有它,把参数完全忽略也能全绿。
     */
    @Test
    fun setting_default_columns_seeds_the_page() {
        setPage(portraitDefaultColumns = 2)

        assertAnnouncedColumns(2, "设置里选了 2 列,排序页应停在 2 列")
    }

    /**
     * 横屏用**横屏**那一项、竖屏用竖屏那一项(用户裁决:
     * 「排序页根据屏幕方向实时切换使用哪一个」)。
     *
     * `w800dp-h400dp` 下 `Configuration.orientation == LANDSCAPE`。两个字段故意给**不同值**
     * (竖屏 5 / 横屏 2),当前档位是 2 ⇒ 取的是横屏那一项。若把两个字段写反、或写死取竖屏,
     * 会播报 5 ⇒ 本用例红。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h400dp")
    fun landscape_uses_the_landscape_default() {
        setPage(portraitDefaultColumns = 5, landscapeDefaultColumns = 2)

        assertAnnouncedColumns(2, "横屏应取 landscapeDefaultColumns=2(播报 5 说明误用了竖屏那一项)")
    }

    /**
     * 自适应值超过该方向上限时**临时并入**下拉列表
     * (用户裁决选项 A:「把它临时并入下拉列表,保证当前值永远可见可选」)。
     *
     * w1200dp 下 `(1200 - 16) / 80 = 14.8` ⇒ 14 列,而横屏上限只有 9。
     * 若不并入,列表最高只到 9,界面就在**说谎**(勾在 9,网格实际 14 列)。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w1200dp-h800dp")
    fun out_of_range_adaptive_value_is_merged_into_the_menu() {
        setPage()
        openColumnMenu()

        listOf(2, 9, 14).forEach {
            rule.onNodeWithTag(columnTag(it)).assertExists()
        }
        rule.onNodeWithTag(columnTag(10)).assertDoesNotExist()
        assertTrue(hasChecked(14), "当前 14 列,勾应在临时并入的 14 那一档")
    }

    /**
     * 长按图标弹出说明文字(用户裁决:「长按图标是显示说明这是列数」)。
     *
     * 说明文字 = 资源 `a_cols_label`(如「列数」)。**必须连文案一起断言**:
     * 只断言"弹出了东西"无法发现"弹出的是别的提示"。
     *
     * ⚠️ 这条是本文件里唯一依赖 M3 内部手势时序的断言(`TooltipBox` 的长按走
     * `BasicTooltip.handleGestures`,且提示 1.5s 后自动消失)。若在 Robolectric 下不稳定,
     * 按 §7.4 的处置是**撤掉它并写明原因**,而不是留一条会随机红的断言 ——
     * 提示的视觉形态本来也只能真机判定。
     */
    @Test
    fun long_press_shows_the_column_count_label() {
        setPage()

        rule.onNodeWithTag(TAG_COLUMN_BUTTON).performTouchInput { longClick() }
        rule.waitForIdle()

        rule.onNodeWithText(TestText.of(R.string.a_cols_label)).assertExists()
    }

    /** 展开列数下拉菜单(点图标)。 */
    private fun openColumnMenu() {
        rule.onNodeWithTag(TAG_COLUMN_BUTTON).performClick()
        rule.waitForIdle()
    }

    /** 展开菜单 → 点某一档 → 返回该档生效后的卡片宽度。 */
    private fun pickColumn(n: Int): Int {
        openColumnMenu()
        rule.onNodeWithTag(columnTag(n)).performClick()
        rule.waitForIdle()
        return firstCardWidth()
    }

    /**
     * 当前生效档位是否打了勾(即 `leadingIcon` 存在)。
     *
     * **必须 `useUnmergedTree = true`**:`DropdownMenuItem` 自身挂了 `clickable`,
     * 合并语义树里子节点(对勾图标)根本不可见。用 `onAllNodes...isNotEmpty()`
     * 而不是 `assertExists()` 是为了能取布尔值做**否定**断言(缺勾也要能测)。
     */
    private fun hasChecked(n: Int): Boolean = rule
        .onAllNodesWithTag(columnCheckTag(n), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()

    /**
     * 断言按钮**当前播报的档位**是 `n` 列。
     *
     * 读的是按钮自己的 `contentDescription`(资源 `a_cols_cd`,如「4 列」)——
     * 这是不展开菜单时唯一能反映"现在生效的是几列"的语义痕迹
     * (对勾只在下拉菜单展开期间存在,见 [hasChecked])。
     */
    private fun assertAnnouncedColumns(n: Int, message: String) {
        val config = rule.onNodeWithTag(TAG_COLUMN_BUTTON).fetchSemanticsNode().config
        assertTrue(
            config.contains(SemanticsProperties.ContentDescription),
            "列数按钮没有 contentDescription ⇒ 界面读屏与离线测试都看不出当前是几列",
        )
        val actual = config[SemanticsProperties.ContentDescription].firstOrNull()
        assertEquals(TestText.of(R.string.a_cols_cd, n), actual, message)
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
