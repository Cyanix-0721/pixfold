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
 * - 「按 3 行芯片收口」由 [CHIP_COLLAPSE_LINES] + 收口芯片是否出现来断
 *   —— `widthIn(min = 96.dp)` 是**确定性约束**,不依赖文本测量,离线也成立
 * - 溢出入口是**与普通芯片同尺寸区间**的可点击节点(2026-10-23 起不再是全宽按钮)
 * - 行容器结构(手写 ChipFlow / 浮层)由源码结构守卫负责(`ChipRowWrappingTest`)
 *
 * 真机核对负责「看起来对不对」这一层(§7.4 纪律)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class ChipLayoutRulesTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * 12 块而不是 8 块:新版按**行数**收口,而轨道宽 321dp、芯片最窄 96dp ⇒ 一行 3 块、
     * 3 行 9 块。8 块只有 3 行以内的量,永远不会触发收口 —— 用它当默认夹具的话,
     * 「溢出」相关的用例会全部变成空转。
     */
    private val manyItems = (1..12).map { "item-$it" }

    private fun showRow(
        items: List<String> = manyItems,
        collapseLines: Int = CHIP_COLLAPSE_LINES,
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
                chipDescription = { it },
                allEntryText = { n -> "all $n" },
                collapseLines = collapseLines,
                emphasized = emphasized,
                chipLabel = { it },
                chipTextMaxLines = chipTextMaxLines,
            )
        }
    }

    /**
     * 收起态下被行数上限挡住的条目,必须有一个**能到达全部**的入口。
     *
     * 这条守卫的来历没变(2026-09-25 缺陷形态:限行把芯片裁掉 ⇒ 既看不见也没有入口),
     * 但 2026-10-23 起判据从「按条数超限」换成「按芯片行数超限」:
     * 12 块 × 最窄 96dp 在 321dp 轨道上一行 3 块 ⇒ 4 行 > [CHIP_COLLAPSE_LINES] = 3。
     *
     * 旧版这里用「2 个条目 + 限 1 行」当夹具,那是条数上限时代的写法;
     * 新版 2 块只占 1 行,**不该**收口 ⇒ 夹具必须换成真正会溢出的一批。
     */
    @Test
    fun `a collapsing row offers an entry to everything`() {
        showRow()

        rule.onNodeWithTag(TAG_ALL).assertIsDisplayed()
    }

    /**
     * 收口芯片的数字必须是**条目总数**,不是「已经显示了几块」。
     *
     * ## 这条是 2026-10-24 自查发现的真缺陷
     *
     * `ChipFlow` 回调给文案的是 `hidden`(被收起的块数),而 `SingleSelectChipRow`
     * 里写的是 `allEntryText(total - hidden)` —— 那算出来的是 **shownCount**
     * (显示出来的块数),不是总数。12 块在 321dp 轨道上收口到 7 块时会显示
     * 「全部 7 项」,而实际上有 12 项。入口写着「全部 N」却少报,用户点开才发现
     * 还有更多,是比没有数字更糟的误导。
     *
     * 断言用 [onNodeWithText] 而不是 bounds:文案是纯语义事实,Robolectric 读得准。
     */
    @Test
    fun `the collapse entry counts every item, not just the shown ones`() {
        showRow()

        // 12 块硬约束下必然收口(一行 3 块、3 行 9 块),但**不会**显示 12 块。
        rule.onNodeWithText("all 12").assertIsDisplayed()
    }

    /**
     * 收口芯片必须与普通芯片**同一尺寸区间**(用户 2026-10-23 裁决「全部 X 项不要下面
     * 弹出窗口了,而是延续瀑布流显示」)。
     *
     * 这条能离线断,而且断得住:`widthIn(min = 96.dp)` 是**确定性约束**,不依赖文本测量
     * (Robolectric 把文字宽算成 ~0,但 min 会把芯片撑到 96dp)。旧版入口是 `TextButton`
     * + `fillMaxWidth`,宽度等于整行 360.86dp —— 两者差值足够大,这条断言有杀伤力。
     */
    @Test
    fun `the collapse chip is the same size as a normal chip`() {
        showRow()

        val chip = rule.onNodeWithTag("chip-item-1").getUnclippedBoundsInRoot()
        val entry = rule.onNodeWithTag(TAG_ALL).getUnclippedBoundsInRoot()

        val chipWidth = chip.right.value - chip.left.value
        val entryWidth = entry.right.value - entry.left.value
        assertTrue(
            entryWidth in 96f..156f,
            "收口芯片宽度必须落在 [96,156]dp(与普通芯片同区间),实际 $entryWidth",
        )
        // **刻意不断「两者等宽」**(2026-10-24 评审 M1 删):Robolectric 把文字宽算成 ~0,
        // 芯片宽度恒被 widthIn 的 min 撑到 96dp,与收口芯片恒等宽 —— 那是离线的巧合,
        // 真机上长标签芯片会宽到 156dp,两者本来就不等。断它等于给假保障(§7.4)。
        // 该守的只有「收口芯片也落在同一个区间」:上面那条已覆盖。
        assertTrue(
            chipWidth in 96f..156f,
            "普通芯片宽度也必须落在 [96,156]dp,实际 $chipWidth",
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

    /** 没超行 ⇒ 不出现入口(它只是噪声,还白占一块位置)。 */
    @Test
    fun `a row within the line cap shows no entry`() {
        showRow(items = listOf("a", "b"))

        rule.onNodeWithTag(TAG_ALL).assertDoesNotExist()
    }

    /**
     * 收口芯片必须**可点**,点了**在原地**把剩下的条目铺开(2026-10-23 用户裁决
     * 「全部 X 项就不要下面弹出窗口了,而是延续瀑布流显示」)。
     *
     * 断「节点存在」会放过「按钮在但点不动」(本项目踩过:菜单项存在而点击落在别的层),
     * 所以这里必须点一下、并断言被挡住的条目真的出现在了树上。
     *
     * **刻意不在这里断言「sheet 不再存在」**(2026-10-24 审计改判):原先有一句
     * `onNodeWithTag(TAG_SHEET).assertDoesNotExist()`,但 `"test-chip-sheet"` 这个 tag
     * 在 `android/app/src/main` 里已经一个都不存在 —— 那条断言**永远为真、零判别力**,
     * 正是 §7.4 说的「假保障」。真正的防复活守卫在
     * `ChipRowWrappingTest.the_all_entry_no_longer_opens_a_sheet`(扫源码,弹窗一复活就红)。
     */
    @Test
    fun `the collapse chip expands the remaining chips in place`() {
        showRow()
        rule.onNodeWithTag("chip-item-12").assertDoesNotExist()

        rule.onNodeWithTag(TAG_ALL).performClick()

        rule.onNodeWithTag("chip-item-12").assertIsDisplayed()
    }

    /**
     * **展开态**里收口芯片（此时文案是「收起」）必须仍然可见且可点。
     *
     * ## 这条守的是 2026-10-24 评审 C1
     *
     * 展开 40 卷约 14 行 ≈ 1200dp,而视口只有 794dp。芯片行在宿主页面里常驻在
     * 滚动容器**之外**（2026-10-18 裁决，否则换卷要先回顶），它自己不滚 ⇒
     * 展开后「收起」芯片会落到视口外,**用户既看不到下方内容也收不回来**,
     * 只能靠返回键逃出。这是 2026-10-18 那个 834.9dp 缺陷的同型形态。
     *
     * 修法是展开态加 `heightIn(max = CHIP_EXPANDED_MAX_HEIGHT)` + 内部竖滚。
     * 这条断言只覆盖「存在且可点」；「滚下去能不能够到」由
     * `PackVolumeChipScaleTest.the collapse chip expands every volume in place`
     * 里的 `performScrollTo` 负责（那里才有真实的超长行）。
     */
    @Test
    fun `the collapse chip stays reachable after expanding`() {
        showRow()
        rule.onNodeWithTag(TAG_ALL).performClick()
        rule.waitForIdle()

        // 展开后同一枚芯片变成「收起」入口 —— 它必须是可见且可点的。
        rule.onNodeWithTag(TAG_ALL).assertIsDisplayed().performClick()
        rule.waitForIdle()

        // 再点一次真的收起来了(不是把点击吞掉)。
        rule.onNodeWithTag("chip-item-12").assertDoesNotExist()
    }

    /**
     * 收口芯片的触摸目标必须 ≥ 48dp（AGENTS.md §2.7 硬约束）。
     *
     * ## 为什么手写 Box 比 FilterChip 更需要这条
     *
     * FilterChip 由 M3 的 `minimumInteractiveComponentSize()` 兜住 48dp;手写的
     * `Box + clickable` **没有**这层,视觉上 32dp 就真只有 32dp。而收口芯片是
     * 「溢出部分**唯一**的入口」,点空等于那批条目不可达。
     *
     * 离线可断:高度方向的 48dp 由 `minimumInteractiveComponentSize` 撑出来,
     * **不依赖文本测量**(宽度才依赖,Robolectric 量不准)。所以只断高度。
     */
    @Test
    fun `the collapse chip has a 48dp touch target`() {
        showRow()

        val entry = rule.onNodeWithTag(TAG_ALL).getUnclippedBoundsInRoot()
        val height = entry.bottom.value - entry.top.value
        assertTrue(
            height >= 48f,
            "收口芯片触摸目标必须 ≥48dp(AGENTS.md §2.7),实际 $height",
        )
    }

    /**
     * 收起态按**芯片行数**收口,且这个数就是 [CHIP_COLLAPSE_LINES]。
     *
     * ## 为什么从「条数上限」改成「行数上限」(2026-10-23 用户裁决)
     *
     * 旧实现钉 `VISIBLE_SINGLE_SELECT_CHIPS = 5`:不管芯片多宽,一律 5 块。
     * 瀑布流里块高不一致(1 行文字 31dp、4 行 88dp),同样的 5 块可能是 2.7 行、
     * 也可能是 5 行 —— **视觉上那条线守不住**。换成按行数收口后,「显示到第几行」
     * 才是确定的那条线。
     *
     * 同时作废 `TOP_CHIP_MAX_LINES = 2`(一级行 2 行):那是「行」限制芯片**行**,
     * 与现在的「收口在 3 行」是同一维度的两个数,留着必然打架。
     */
    @Test
    fun `the row collapses at three chip lines`() {
        assertEquals(3, CHIP_COLLAPSE_LINES)

        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt")
            .readText()
        // 只扫**代码行**:源码里还留着一句解释「TOP_CHIP_MAX_LINES 已作废」的注释,
        // 不过滤的话这条永远红 —— 而它要守的是「没人再引用那个常量」,不是「没人提这个词」。
        val code = src.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }

        assertFalse(
            code.any { it.contains("TOP_CHIP_MAX_LINES") },
            "TOP_CHIP_MAX_LINES 已作废(2026-10-23 改为按芯片行数收口),不得再被引用",
        )
        // 二级(浮层内)必须**不收口**:用户已经明确要看该系列全部,再套一层收口
        // 等于让他点两次才看全。
        assertTrue(
            src.contains("collapseLines = Int.MAX_VALUE"),
            "浮层内的二级行必须传 collapseLines = Int.MAX_VALUE(浮层内不收口)",
        )
    }

    /** 芯片内文字的行数上限是兜底常量,不是一级/二级各一套。 */
    @Test
    fun `both levels share the same chip text line cap`() {
        assertEquals(4, CHIP_TEXT_MAX_LINES)

        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt")
            .readText()
        assertEquals(
            false,
            src.contains("chipTextMaxLines ="),
            "两级芯片的文字行数上限应统一走默认值,不应各自指定",
        )
    }

    /**
     * 一级行必须**垫底色**,二级行必须**由浮层卡片承担底色**。
     *
     * 用户 2026-10-21 两次反订:
     *  ① 「你这个遮罩都顶到边缘了很难看」⇒ 色块要有 8dp 外边距,不能铺满行宽;
     *  ② 「只有第一层又第二层又没有也很难看」⇒ 两级都不得裸着。
     *
     * 2026-10-23 改浮层后第 ② 条的**实现方式变了**:二级不再自己垫 0.18 的底托
     * (那会在卡片里再叠一层灰,读成「框中框」),改由浮层卡片统一给底色 + 阴影。
     * 所以断言也跟着改 —— 若只删掉 0.18 而不补卡片底色,这条能抓住「二级又裸了」。
     *
     * 只能守源码:Robolectric 的 LEGACY 图形后端渲染不出真实颜色(§7.4)。
     */
    @Test
    fun `the top row paints a backdrop and the popup card paints its own`() {
        val row = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/SingleSelectChipRow.kt")
            .readText()
        val two = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt")
            .readText()

        assertTrue(
            row.contains("alpha = if (emphasized) 0.45f else 0.18f"),
            "1 级行要垫底色(0.45 强调 / 0.18 普通),不能裸着",
        )
        assertTrue(
            row.contains(".padding(horizontal = 8.dp, vertical = 2.dp)"),
            "遮罩必须留白不顶边(用户:「遮罩都顶到边缘了很难看」)",
        )
        assertFalse(
            row.contains("Color.Transparent"),
            "底色不能是透明 —— 一层有一层没有读起来像画漏了",
        )
        // 二级:浮层卡片必须自己给底色与阴影,并让内层行不再垫底托。
        assertTrue(
            two.contains("color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f)"),
            "浮层卡片必须自己给底色,否则二级行在卡片里是裸的",
        )
        assertTrue(
            two.contains("shadowElevation = 6.dp"),
            "浮层必须与页面分层(阴影),否则读起来像页面自己长出来的一块",
        )
        assertTrue(
            two.contains("surface = false"),
            "浮层内的二级行不再垫底托(卡片已有底色),否则灰叠灰读成框中框",
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

    /**
     * 二级必须**收在浮层里**,不能退回兄弟行(2026-10-23 用户裁决)。
     *
     * 旧实现点开 series 后,下面**长出一行芯片**、页面内容整体下移 —— 这正是
     * 2026-10-18 那次「40 卷吃满 834.9dp 视口」缺陷的同型形态。改成浮层后
     * 浮层不参与父布局,页面不再因点开系列而位移。
     *
     * 只能守源码结构:Robolectric 下 Popup 是独立 window,离线很难断言「它盖在
     * 页面之上、不参与父布局」这层语义(实测 Popup 内容在语义树上可见,但
     * 「是否撑高父容器」量不出来)。
     */
    @Test
    fun `the child level lives in a popup, not a sibling row`() {
        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt")
            .readText()
        // 去掉注释行后的「代码」:关键词在 KDoc 里也出现时,`src.contains` 会恒真。
        val code = src.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertTrue(
            src.contains("Popup("),
            "二级必须画在 Popup 里(它不参与父布局,点开系列不会把页面顶下去)",
        )
        // ## focusable 必须是 false(2026-10-24 真机逮到的真缺陷)
        //
        // 原断言要求 `focusable = true`,理由是「返回键/点外部要能关」。真机上这条
        // 直接把用户裁决的收起触发②(**点另一个 series**)打回两次点击:focusable 窗口
        // 持焦点,落在窗口外的触摸被它当成「点外部」消费成 dismiss,且**不透传**
        // 给下层窗口 —— 第一次只关浮层,第二次才打开新的。
        //
        // 改判:float 窗口不持焦点,触发② 一步到位;返回键改由组件内的 BackHandler 兜。
        // 强度没降 —— 下面两条一起守住「既要不抢焦点,又不能困在里面」。
        // ⚠️ 必须**过滤注释行**再断。首版写 `src.contains("focusable = false")`,
        // 而上面的 KDoc 里就有一句「focusable 必须是 false(2026-10-24 真机逮到的真缺陷)」
        // ⇒ 断言恒真。变异 MF(把代码里的 false 改回 true)照样存活,当场暴露。
        // 这是本项目第四次踩「注释里含关键词导致断言零判别力」。
        assertTrue(
            code.contains("focusable = false,"),
            "浮层窗口不能持焦点:focusable = true 会把「点另一个 series」吃成点外部⇒关掉",
        )
        assertTrue(
            code.contains("BackHandler(enabled = true)"),
            "非 focusable 窗口收不到系统返回键,必须由 BackHandler 兜住这条出口",
        )
        assertTrue(
            src.contains("openSeriesKey"),
            "浮层展开态必须按 series 的 key 记 —— 布尔分不清『换一个系列』与『同系列再点一次』",
        )
        // 三条收起触发都必须有:少了任何一条,用户就会找不到出口。
        val body = code
        assertTrue(
            body.contains("if (!group.isSeries)") && body.contains("openSeriesKey = null"),
            "点 series 外的单本必须收起浮层(收起触发③)",
        )
        assertTrue(
            body.contains("} else if (openSeriesKey == key) {"),
            "再点同一个 series 必须收起(收起触发①)",
        )
    }

    /**
     * 浮层锚点必须**按 series 分别记**,不能只留一个。
     *
     * ## 这条守的是 2026-10-24 评审 C2(真缺陷)
     *
     * 原写法是单个 `var anchor`,而 `onChipBounds` 对**每个** series 芯片都会回调。
     * SubcomposeLayout 按 index 顺序 place ⇒ 最后一次回调(最后一个 series 芯片)
     * 永远胜出。于是点第一个 series,浮层却长在**最后一个**下面 ——
     * 与用户裁决「以 series 芯片为顶点」正相反。
     *
     * 只能守源码:锚点矩形是运行时布局产物,离线量不出「浮层贴哪枚芯片」。
     * 但「有没有按 key 分桶」是纯结构事实,一个变量名就看得出来。
     */
    @Test
    fun `the popup anchors to the tapped series, not the last one`() {
        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt")
            .readText()
        val code = src.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertTrue(
            code.contains("anchors = anchors + (id to bounds)"),
            "锚点必须按 series key 分桶存 —— 单个 anchor 变量会被最后一次回调覆盖",
        )
        assertTrue(
            code.contains("anchors[openSeriesKey]") || code.contains("anchors[it]"),
            "渲染时必须按当前展开的 series 取对应那块矩形",
        )
        assertFalse(
            Regex("""var\s+anchor\s+by\s+remember""").containsMatchIn(code),
            "不得退回单个 anchor 变量(会被最后一个 series 芯片覆盖)",
        )
    }

    /**
     * 展开态下再点**同一个** series 只收起浮层,**不得顺手换书**。
     *
     * 这一条守的是真实缺陷形态:旧实现里 1 级芯片的 onClick 无条件会
     * `onSelect(remembered ?: first)`,如果收起分支也走这条路,用户点一下想关浮层、
     * 结果当前那本被换成了第一本 —— 数据没坏,但用户看到的是「我点错了什么」。
     */
    @Test
    fun `re-tapping the same series closes without switching book`() {
        val src = java.io.File("src/main/kotlin/com/cyanix/pixfold/ui/components/TwoLevelChipRows.kt")
            .readText()
        val lines = src.lines()
        val branch = lines.indexOfFirst { it.trim() == "} else if (openSeriesKey == key) {" }

        assertTrue(branch >= 0, "必须有『再点同一个 series』的分支")
        // 该分支体只有一行 openSeriesKey = null,到下一个 } else { 之前不得出现 onSelect。
        val body = lines.drop(branch + 1)
            .takeWhile { it.trim() != "} else {" }
            .joinToString("\n")
        assertFalse(
            body.contains("onSelect("),
            "收起分支不得调 onSelect —— 用户只想关浮层,不该顺手把当前那本换成第一本",
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
        const val TAG_ACTION = "test-meta-action"
    }
}
