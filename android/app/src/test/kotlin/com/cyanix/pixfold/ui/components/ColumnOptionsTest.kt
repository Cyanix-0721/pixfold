package com.cyanix.pixfold.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 列数算式与选项拼装的**纯 JVM 单测**(无需 Robolectric)。
 *
 * 为什么值得单独一层:[adaptiveColumnCount] 是"自适应默认列数"这条用户诉求的
 * 唯一事实来源 —— 设置页预览、排序页默认值、以及 [com.cyanix.pixfold.ui.workflowa.SortPagePolishTest]
 * 里那些走真实布局的断言,全都以它为准。把它压到纯函数层,就不必为了改一个常量
 * 去跑整套 Robolectric 页面测试。
 *
 * ⚠️ 这里算的是**算术**,不是布局:数字对不上说明常量或扣减项变了;
 * "卡片在屏幕上到底放不放得下"仍属 §7.4 表里"只能真机"的一类。
 */
class ColumnOptionsTest {

    // ------------------------------------------------------------ adaptiveColumnCount

    /**
     * 各机型/方向的实际档位。
     *
     * 算式 `floor((width - 16) / 80)`,其中 16dp = 网格容器左右 `contentPadding` 之和。
     * 前三档是**用户点名要的结果**:
     *  - PJX110 竖屏(361dp)⇒ 4 列 —— 用户第二轮裁决「调窄自适应的最小卡宽,
     *    让你机器上自适应 = 4 列」,且「我觉得我的手机 4 列作为默认比较好看」;
     *  - 旧值 110dp 在同机上给 3 列,故这条也顺手锁住"不许改回去";
     *  - 横屏 794dp ⇒ 9 列(横屏上限 [LANDSCAPE_MAX_COLUMNS])。
     */
    @Test
    fun `adaptive count matches the device widths`() {
        assertEquals(4, adaptiveColumnCount(361.dp), "PJX110 竖屏应得 4 列(用户指定)")
        assertEquals(4, adaptiveColumnCount(400.dp), "400dp 应得 4 列")
        assertEquals(3, adaptiveColumnCount(320.dp), "320dp 应得 3 列")
        assertEquals(9, adaptiveColumnCount(794.dp), "横屏 794dp 应得 9 列")
    }

    /** 边界:刚好整除、以及差 1dp 掉档。 */
    @Test
    fun `adaptive count is floor division at the boundary`() {
        // 16 + 80*4 = 336 ⇒ 恰好 4 列
        assertEquals(4, adaptiveColumnCount(336.dp), "恰好 336dp 应是 4 列的临界点")
        // 比临界点少 1dp ⇒ 掉到 3 列
        assertEquals(3, adaptiveColumnCount(335.dp), "335dp 应掉到 3 列")
    }

    /**
     * 容器再窄也**至少 1 列**。
     *
     * 返回 0 会让 `GridCells.Fixed(0)` 直接崩,而官方 `GridCells.Adaptive` 的算式
     * 也是 `maxOf(…, 1)`。这条在超窄窗口(分屏、自由窗口)下真实可达。
     */
    @Test
    fun `adaptive count never drops below one`() {
        assertEquals(1, adaptiveColumnCount(0.dp), "宽 0 也应给 1 列,不能给 0")
        assertEquals(1, adaptiveColumnCount(16.dp), "可用宽 0 时应给 1 列")
        assertEquals(1, adaptiveColumnCount(50.dp), "可用宽 34dp 时应给 1 列")
    }

    /** `minColumnWidth <= 0` 时直接返回 1,不产生除零/负列数。 */
    @Test
    fun `non positive min width falls back to one column`() {
        assertEquals(1, adaptiveColumnCount(400.dp, 0.dp))
        assertEquals(1, adaptiveColumnCount(400.dp, (-5).dp))
    }

    // ------------------------------------------------------------ columnOptions

    /**
     * 常规情况:竖屏 2–5、横屏 2–9(用户裁决「竖屏显示 2–5,横屏显示 2–9」)。
     *
     * 下界 2 是**刻意**的:裁决原文两处并存(「竖屏 1–5、横屏 2–9」与括号里的
     * 显示范围「2–5 / 2–9」),实现按更具体的括号执行;1 列仍可由自适应算出后
     * 临时并入,只是不主动列出。
     */
    @Test
    fun `options span the orientation range`() {
        assertEquals(listOf(2, 3, 4, 5), columnOptions(PORTRAIT_MAX_COLUMNS, 4))
        assertEquals(listOf(2, 3, 4, 5), columnOptions(PORTRAIT_MAX_COLUMNS, 2))
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8, 9), columnOptions(LANDSCAPE_MAX_COLUMNS, 9))
    }

    /**
     * 当前值**大于上限**时临时并入(用户裁决选项 A:「把它临时并入下拉列表,
     * 保证当前值永远可见可选」)。
     *
     * 这是最容易做错的一档:若不并入,列表最高只到上限,而网格其实画着更多列 ——
     * 界面就在说谎(勾在上限,实际不是)。横屏 794dp 算出 9 列、平板竖屏算出 14 列
     * 都会走到这里。
     */
    @Test
    fun `out of range current value is merged in`() {
        assertEquals(
            listOf(2, 3, 4, 5, 6, 7, 8, 9, 14),
            columnOptions(LANDSCAPE_MAX_COLUMNS, 14),
            "超上限的当前值必须并进列表末尾",
        )
        assertEquals(
            listOf(2, 3, 4, 5, 9),
            columnOptions(PORTRAIT_MAX_COLUMNS, 9),
            "竖屏上限 5 而当前 9 时必须并入 9",
        )
    }

    /** 当前值**小于下界**(超窄容器算出 1 列)时同样并入,且排在首位。 */
    @Test
    fun `below range current value is merged in and sorted`() {
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            columnOptions(PORTRAIT_MAX_COLUMNS, 1),
            "1 列应在最前,其余保持升序",
        )
    }

    /**
     * 列表永远**升序且不重复**,且**一定包含当前值**。
     *
     * 这是 [columnOptions] 的完整契约,用性质断言覆盖前几个用例的零散取值:
     * 无论当前值落在区间内、区间上方还是下方,下拉列表都要"看得见、选得中"。
     */
    @Test
    fun `options always contain the current value in ascending order`() {
        listOf(PORTRAIT_MAX_COLUMNS, LANDSCAPE_MAX_COLUMNS).forEach { max ->
            (0..20).forEach { current ->
                val options = columnOptions(max, current)
                assertTrue(
                    current in options,
                    "max=$max current=$current 时下拉列表里找不到当前值:$options",
                )
                assertEquals(
                    options.sorted(),
                    options,
                    "max=$max current=$current 时列表不是升序:$options",
                )
                assertEquals(
                    options.distinct(),
                    options,
                    "max=$max current=$current 时列表有重复项:$options",
                )
            }
        }
    }
}
