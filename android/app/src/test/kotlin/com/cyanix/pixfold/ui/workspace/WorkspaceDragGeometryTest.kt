package com.cyanix.pixfold.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「实时让位」的几何：纯函数，离线可断言。
 *
 * ## 为什么这个文件存在（2026-10-26 Task 9 变异 M10）
 *
 * 让位位移原先内联在 `WorkspaceDragList` 的 `forEachIndexed` 里，直接喂给
 * `graphicsLayer { translationY = ... }`。屏幕外的位移没有任何断言读得到，于是把
 * `-rowHeightPx` 与 `+rowHeightPx` 对调之后 **841 条测试全绿**。方向反了的真机表现是
 * 「拖谁谁不动、别人乱窜」，是用户一眼可见的缺陷 —— 不该只靠真机兜。
 *
 * 抽成 [rowShiftFor] / [rowDragOffsetFor] 两个 internal 纯函数之后，符号写反必然红。
 * 剩下的（动画观感、边缘自动滚动、跨视口拖拽）仍然只能真机。
 */
class WorkspaceDragGeometryTest {

    /** 单行高度用 56f 而不是 56dp 的 px 值：纯函数只做加减，单位无关。 */
    private val row = 56f

    private fun shifts0To3(dragFrom: Int, targetIndex: Int): List<Float> =
        (0..3).map { rowShiftFor(it, dragFrom, targetIndex, row) }

    @Test
    fun `没在拖的时候谁都不动`() {
        // dragFrom = -1（未开始拖）时 targetIndexFor 直接返回 -1（见它的 KDoc 第 1 条守卫），
        // 所以未在拖的真实取值对就是 (-1, -1)。**不要**在这里塞一个非负 targetIndex 当
        // 「更严格的守卫」：那对组合生产上根本不出现，而按 (dragFrom+1)..targetIndex 的
        // 几何它会真让位 —— 断言会红，但红的不是缺陷。
        assertEquals(listOf(0f, 0f, 0f, 0f), shifts0To3(dragFrom = -1, targetIndex = -1))
    }

    @Test
    fun `没有有效目标时谁都不动`() {
        assertEquals(listOf(0f, 0f, 0f, 0f), shifts0To3(dragFrom = 1, targetIndex = -1))
    }

    @Test
    fun `被拖的那一行不参与让位`() {
        // 它自己跟着手指走（见 rowDragOffsetFor），若同时再让位就位移了两倍。
        assertEquals(0f, rowShiftFor(index = 0, dragFrom = 0, targetIndex = 3, rowHeightPx = row))
    }

    @Test
    fun `向下拖时中间的行整体上移一格`() {
        // 第 0 行拖到第 3 行：[1..3] 上移一格补上被抽走的位置，符号为负（向上）。
        assertEquals(listOf(0f, -row, -row, -row), shifts0To3(dragFrom = 0, targetIndex = 3))
    }

    @Test
    fun `向上拖时中间的行整体下移一格`() {
        // 第 3 行拖到第 0 行：[0..2] 下移一格，符号为正（向下）。
        assertEquals(listOf(row, row, row, 0f), shifts0To3(dragFrom = 3, targetIndex = 0))
    }

    @Test
    fun `只拖一格时只有目标那一行动`() {
        assertEquals(listOf(0f, 0f, -row, 0f), shifts0To3(dragFrom = 1, targetIndex = 2))
    }

    @Test
    fun `拖动位移只给被拖的那一行`() {
        assertEquals(18f, rowDragOffsetFor(dragging = true, dragOffset = 18f), 0f)
        assertEquals(0f, rowDragOffsetFor(dragging = false, dragOffset = 18f), 0f)
    }
}