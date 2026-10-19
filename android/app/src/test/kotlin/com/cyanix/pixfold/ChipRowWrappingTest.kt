package com.cyanix.pixfold

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
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
        // 1 级行在选中系列时收缩到一行(maxLines = 1),否则不压缩。
        assertTrue(
            src.contains("maxLines = if (activeGroup.isSeries) 1 else Int.MAX_VALUE"),
            "1 级芯片行必须在选中系列时收缩到一行(用户 2026-10-19 裁决)",
        )
        // 2 级行只在选中系列时出现 —— 单本没有 2 级。
        assertTrue(
            src.contains("if (activeGroup.isSeries) {"),
            "2 级芯片行必须只在选中 series 时渲染",
        )
    }
}
