package com.pixfold.d1

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
 * ⚠️ 工作目录是 `pixfold-d1/app`(Gradle 测试进程的 cwd,已实测),故源码路径是 `src/main/…`。
 */
class ChipRowWrappingTest {

    private fun mainSource(relative: String): String {
        val f = File("src/main/kotlin/com/pixfold/d1/$relative")
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
            "ui/workflowa/SortAndPreviewPage.kt",
            "ui/workflowa/SortRuleEditor.kt",
            "ui/workflowb/MetadataPage.kt",
            "ui/workflowb/PackPreviewPage.kt",
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
     */
    @Test
    fun chip_rows_use_flow_row() {
        // 文件 -> 该文件里应为 FlowRow 的芯片行数量
        val expected = mapOf(
            "ui/workflowa/SortAndPreviewPage.kt" to 1, // 集合切换芯片
            "ui/workflowa/SortRuleEditor.kt" to 1, // 每级排序字段芯片
            "ui/workflowb/MetadataPage.kt" to 2, // 卷切换 + 批量字段
            "ui/workflowb/PackPreviewPage.kt" to 3, // 卷切换 + 冲突策略 + 源处置策略
        )
        expected.forEach { (rel, count) ->
            val src = mainSource(rel)
            val actual = src.lines().count { it.trimStart().startsWith("FlowRow(") }
            assertEquals(count, actual, "$rel 的 FlowRow 数量变了;芯片行容器被改动")
        }
    }
}
