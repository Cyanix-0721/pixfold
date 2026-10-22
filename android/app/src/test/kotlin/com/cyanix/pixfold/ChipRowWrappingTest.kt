package com.cyanix.pixfold

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 可操作控件不得靠横向滚动藏在视口外(2026-09-25 真机实测缺陷的源码结构守卫)。
 *
 * ## 为什么只能用"读源码"这种方式守护
 *
 * 缺陷本体是**布局溢出**:361dp 下芯片行放不下,靠后的芯片落在屏幕外,用户看不见也点不到。
 * 但这层在离线测试里**无法断言** —— Robolectric 不做真实文本测量,芯片标签宽度全部算成 ~0
 * (`trip 34.9dp / scan 34.6 / misc 34.3 / cn 34.3`,实测),于是「宽度够不够」恒为「够」。
 * 曾写过一条按 `getBoundsInRoot()` 比屏宽的断言,把 `horizontalScroll` 删掉后**测试仍全绿**
 * (变异存活)⇒ 该断言毫无杀伤力,已删除。撑满 20 个中文字符的第 5 个集合也照旧全绿。
 *
 * 真机才是唯一能证伪的场合(`dumpui.bat` 量真实坐标)。所以离线这层退而守住**结构性事实**:
 * 这几处芯片行**必须是会换行的容器**(`FlowRow`),不能是 `Row + horizontalScroll`。
 * 它证明不了"换行后一定放得下",但能保证**不会退回**那个已实测出缺陷的写法 ——
 * 与 `LauncherIconTest` 里那条"回退档源码结构断言"同类。
 *
 * ## 真机实测记录(修复前 / 修复后)
 *
 * | 位置 | 修复前可见 | 修复后可见 |
 * | --- | --- | --- |
 * | A 步骤1 集合芯片 | 3/4(`杂图` 右边界 = 1264 = 屏宽) | 4/4(第 2 行) |
 * | A 步骤1 排序字段芯片 | 3/6(`目录名` 已到 x1182) | 6/6(分 2 行) |
 * | B 卷切换行 | 3/5(第 4、5 卷完全在外) | 5/5(第 2 行) |
 * | B 批量字段芯片 | 3/3(贴边) | 3/3 |
 * | B 打包页卷芯片 | 3/5 | 5/5 |
 *
 * ⚠️ 工作目录是 `android/app`(Gradle 测试进程的 cwd,已实测),故源码路径是 `src/main/…`。
 */
class ChipRowWrappingTest {

    private fun mainSource(relative: String): String {
        val f = File("src/main/kotlin/com/cyanix/pixfold/$relative")
        assertTrue(f.exists(), "源码文件不存在: ${f.absolutePath}(cwd=${File(".").absolutePath})")
        return f.readText()
    }

    /**
     * 四个含芯片行的界面文件里,不得再出现 `horizontalScroll`。
     *
     * 用整文件扫描而不是逐处精确匹配:**只要有人在这个文件里重新用横向滚动放可操作控件,
     * 就应当被拦下**。容器换回 `Row` 而忘了 `horizontalScroll` 的情况由下一条覆盖。
     */
    @Test
    fun chip_rows_do_not_use_horizontal_scroll() {
        val files = listOf(
            "ui/organize/SortAndPreviewPage.kt",
            "ui/organize/SortRuleEditor.kt",
            "ui/library/MetadataPage.kt",
            "ui/library/PackPreviewPage.kt",
            // 2026-10-18 起卷切换芯片行抽成共享组件(步骤 2/3 共用),守卫必须跟过来,
            // 否则"把共享组件改回横向滚动"这条路绕过守卫。
            "ui/components/VolumeChipRow.kt",
            // 2026-10-19 起卷芯片与集合芯片共用同一底层 `SingleSelectChipRow`;
            // 真正的容器在那里,守卫也必须跟过去 —— 否则改底层绕过了前五条。
            "ui/components/SingleSelectChipRow.kt",
            "ui/organize/CollectionChipRows.kt",
            // 2026-10-19 两级芯片:状态逻辑抽到 TwoLevelChipRows,卷芯片再包一层 VolumeChipRows
            // —— 两者都可能成为「在这里偷偷加横向滚动」的新落点。
            "ui/components/TwoLevelChipRows.kt",
            "ui/components/VolumeChipRows.kt",
        )
        files.forEach { rel ->
            val src = mainSource(rel)
            // 注释里保留了历史说明(含 "horizontalScroll" 字样),故只看去掉注释后的代码。
            val code = src.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            val hit = code.filter { it.contains("horizontalScroll") }
            assertEquals(
                emptyList(),
                hit,
                "$rel 又用回了横向滚动容器;可操作控件会落在视口外(2026-09-25 真机实测缺陷)",
            )
        }
    }

    /**
     * 四处芯片行必须真的用 `FlowRow` 承载。
     *
     * 与上一条互补:上一条拦"用回横向滚动",这一条拦"把容器换成不会换行的普通 `Row`"
     * —— 后者同样会让靠后的芯片溢出,而且**连横向滚动都没有**,比原缺陷更糟(彻底够不着)。
     *
     * ⚠️ 2026-10-19 芯片抽象后,**唯一**的 `FlowRow` 搬到了 `SingleSelectChipRow.kt`;
     * 三个调用方(`VolumeChipRow` / `CollectionChipRows` / `SortAndPreviewPage`)自身
     * 的 FlowRow 计数已归零。这张表必须跟着搬,否则会拿旧口径判新结构(直接红)。
     */
    @Test
    fun chip_rows_use_flow_row() {
        // 文件 -> 该文件里应为 FlowRow 的芯片行数量
        val expected = mapOf(
            // 改写后集合芯片整体搬去 CollectionChipRows(自身也不再有 FlowRow)
            "ui/organize/SortAndPreviewPage.kt" to 0,
            "ui/organize/SortRuleEditor.kt" to 1, // 每级排序字段芯片
            // 2026-10-18:卷切换行已上移到共享组件,本文件只剩批量字段芯片
            "ui/library/MetadataPage.kt" to 1,
            // 2026-10-18:同上,只剩冲突策略 + 源处置策略
            "ui/library/PackPreviewPage.kt" to 2,
            // 2026-10-18:卷切换芯片行(工作流 B 步骤 2/3 共用)现在是薄壳,容器在底层
            "ui/components/VolumeChipRow.kt" to 0,
            // 2026-10-19:全站唯一的芯片行容器(卷芯片 + 集合芯片共用)
            "ui/components/SingleSelectChipRow.kt" to 1,
            // 2026-10-19:两级集合芯片行 —— 两个 SingleSelectChipRow 调用,自身无 FlowRow
            "ui/organize/CollectionChipRows.kt" to 0,
            // 2026-10-19:两级状态逻辑的唯一实现(集合芯片与卷芯片共用),自身无 FlowRow
            "ui/components/TwoLevelChipRows.kt" to 0,
            // 2026-10-19:两级卷芯片行 —— 只有一层适配,容器在 TwoLevelChipRows 里
            "ui/components/VolumeChipRows.kt" to 0,
        )
        expected.forEach { (rel, count) ->
            val src = mainSource(rel)
            val actual = src.lines().count { it.trimStart().startsWith("FlowRow(") }
            assertEquals(count, actual, "$rel 的 FlowRow 数量变了;芯片行容器被改动")
        }
    }

    /**
     * 两级芯片行的**结构**不得退化(2026-10-19 用户裁决)。
     *
     * 用户原话:「芯片分2级别,series或单本作为1级,series作为2级,如果选择series,
     * 收缩series芯片组到一行,下面添加显示2级芯片组选择series下的单本」。
     *
     * 这个结构在 Robolectric 里无法靠布局断言证明(芯片宽度 ~0),但**能**证明:
     * 两个级别各自只有一个 `SingleSelectChipRow` 调用,且 2 级只在选中系列时渲染。
     *
     * ⚠️ 2026-10-19 卷芯片也改成两级后,断言目标从 `CollectionChipRows.kt` 移到
     * `TwoLevelChipRows.kt` —— 两级结构只在这一处实现,两个调用方都是薄适配。
     * 仍盯原来的文件会变成「断言一个只剩参数透传的文件」,毫无杀伤力。
     */
    @Test
    fun two_level_chips_stay_two_level() {
        val src = mainSource("ui/components/TwoLevelChipRows.kt")
        val calls = src.lines().count { it.trimStart().startsWith("SingleSelectChipRow(") }
        assertEquals(
            2,
            calls,
            "两级芯片必须恰好两个 SingleSelectChipRow(1 级 = series/单本,2 级 = 选中 series 下的单本)",
        )
        // 1 级行**恒定**最多 2 行(用户 2026-10-21 反订了旧的「选中系列时收缩到 1 行」:
        // 「点击 series 后也是 2 行 + 下拉,而不是 1 行 + 下拉」)。
        assertTrue(
            src.contains("maxLines = TOP_CHIP_MAX_LINES"),
            "1 级芯片行必须恒定限行(TOP_CHIP_MAX_LINES = 2,用户 2026-10-21 裁决)",
        )
        assertFalse(
            src.contains("if (activeGroup.isSeries) 1 else"),
            "不该再按选中状态分档收缩 —— 恒定 2 行才能让下方 2 级行不上下窜",
        )
        // 2 级行只在选中系列时出现 —— 单本没有 2 级。
        assertTrue(
            src.contains("if (activeGroup.isSeries) {"),
            "2 级芯片行必须只在选中 series 时渲染",
        )
    }

    /**
     * 芯片行必须**每行居中**,「全部 N 项」入口也要居中(2026-10-21 真机反馈)。
     *
     * ## 为什么只能用源码结构守卫
     *
     * 用户原话:「1级芯片没有居中显示,全部5项打开的同样不居中且太靠左贴边了」。
     * 「居中」的判据是**芯片块相对可用宽度的水平位置**,而 Robolectric 把芯片标签宽度
     * 全部算成 ~0 ⇒ 左对齐与居中算出来的 bounds 几乎重合,**布局断言没有杀伤力**
     * (本文件顶部记录的 2026-09-25 教训:按 bounds 比屏宽那条断言在变异下存活)。
     *
     * 所以退而守住**结构事实**:容器必须把 `Alignment.CenterHorizontally` 交给
     * `Arrangement.spacedBy`,入口必须包在居中的 `Box` 里。这证明不了「看起来居中」,
     * 但能拦住「有人把它改回默认的左对齐」——那是本轮反馈的原形态。
     *
     * ⚠️ 全局对齐口径只能有**一处**:`SingleSelectChipRow` 是所有芯片行(卷芯片、
     * 集合芯片、两级芯片的两行)的唯一容器。若将来有人在调用点各写一套对齐,
     * 这条守卫守不住 —— 那属于换实现,须连同本文件一起改判。
     */
    @Test
    fun chip_rows_align_left_and_center_the_text_inside() {
        val src = mainSource("ui/components/SingleSelectChipRow.kt")
        val code = src.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }

        // **芯片在行内左对齐**(用户 2026-10-21:「不是 chip 本身居中」)。
        // 曾按「1 级芯片没有居中显示」改成逐行居中 —— 那句话指的是芯片内的**文字**。
        // 瀑布流从左上起排、各行左端对齐;居中会让每行起点随该行总宽左右漂。
        assertTrue(
            code.any { it.contains("Arrangement.spacedBy(6.dp)") },
            "芯片行必须左对齐(Arrangement.spacedBy(6.dp))",
        )
        assertFalse(
            code.any { it.contains("Alignment.CenterHorizontally") },
            "芯片不该整行居中 —— 用户要的是 chip 内部文字居中",
        )
        // **文字在 chip 内部居中**。chip 宽度跟随内容,文字是它唯一的填充物。
        assertTrue(
            code.any { it.contains("textAlign = TextAlign.Center") },
            "chip 内部文字必须居中(textAlign = TextAlign.Center)",
        )
        assertTrue(
            code.any { it.contains("contentAlignment = Alignment.Center") },
            "「全部 N 项」入口必须居中(contentAlignment = Alignment.Center)",
        )
        // **下箭头**是用户 2026-10-21 明确要的形态(「多的加个屏框的下箭头长按钮」):
        // 它是「还有更多、往下展开」的固定语义。首版没有断言,变异(删掉 Icon)存活。
        assertTrue(
            code.any { it.contains("ic_arrow_drop_down") },
            "溢出入口必须带下箭头图标(ic_arrow_drop_down)",
        )
    }

    /**
     * 「全部 N 项」底部弹层的内容不能贴屏幕左沿(2026-10-21 真机反馈「太靠左贴边了」)。
     *
     * 标题自带 `horizontal = 16.dp`,芯片列表若没有同样的内边距,两者左沿不齐 ——
     * 视觉上像两栏错位。这条只看列表的 `padding(horizontal = 16.dp)` 是否存在,
     * 同样属于结构守卫(真实左沿只有真机能量)。
     */
    @Test
    fun all_items_sheet_pads_chips_horizontally() {
        val src = mainSource("ui/components/SingleSelectChipRow.kt")
        val sheetStart = src.indexOf("private fun <T> AllItemsSheet")
        assertTrue(sheetStart > 0, "找不到 AllItemsSheet;若已改名,本守卫须随之改判")
        val sheet = src.substring(sheetStart)
        val code = sheet.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        val listStart = code.indexOfFirst { it.contains("LazyColumn(") }
        assertTrue(listStart >= 0, "AllItemsSheet 里找不到 LazyColumn")
        // 取 LazyColumn 到首个 "{" 之间的修饰符链,确认内边距在列表上。
        val chain = code.drop(listStart).takeWhile { !it.trimEnd().endsWith("{") }.joinToString("\n")
        assertTrue(
            chain.contains("padding(horizontal = 16.dp)"),
            "弹层里的芯片列表必须有 padding(horizontal = 16.dp) 与标题左沿对齐,否则芯片贴屏幕左沿",
        )
    }
}
