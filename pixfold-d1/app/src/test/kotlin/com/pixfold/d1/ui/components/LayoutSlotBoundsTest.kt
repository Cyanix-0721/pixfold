package com.pixfold.d1.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Test
import kotlin.test.assertEquals

/**
 * 命中判定几何换算 [layoutSlotBounds] 的纯函数断言。
 *
 * ## 为什么必须单独钉这个函数
 *
 * 它是**拖拽命中判定的唯一几何来源**:拖动到哪一格、起拖是否命中、容器外是否兜底,
 * 全都建立在它给出的矩形上。而它有一段**极易写错的换算**:
 * `rect = 容器左上角 + contentPadding + layoutInfo.offset`,且前导 header 项会把
 * **lazy 下标**整体错位,必须换成逻辑下标。
 *
 * 换错任何一项都不会崩、也不会报错,只会让手指落在格子边缘时命中相邻格 ——
 * 表现为"拖到这却落到那"(2026-10-15 真机「抽搐」修复的一部分)。
 *
 * ⚠️ 该函数**不用 item 节点的 `onGloballyPositioned`**,原因见其 KDoc:
 * 那个读数会被 `animateItem` 的飞行中位移污染。本组用例只测纯换算,不测动画。
 */
class LayoutSlotBoundsTest {

    private fun info(index: Int, x: Int, y: Int, w: Int = 180, h: Int = 100) =
        SlotInfo(
            index = index,
            offset = Offset(x.toFloat(), y.toFloat()),
            size = Size(w.toFloat(), h.toFloat()),
        )

    @Test
    fun `container top left and padding are both added`() {
        // 有 header(占 lazy 下标 0)、容器在根坐标 (10, 20)、padding 各 8px。
        val bounds = layoutSlotBounds(
            visible = listOf(
                info(0, 0, 0, 360, 40),   // header
                info(1, 0, 40),
                info(2, 180, 40),
            ),
            containerTopLeft = Offset(10f, 20f),
            padLeft = 8f,
            padTop = 8f,
            headerOffset = 1,
        )
        // 逻辑下标 0 = 第一张卡:left = 10 + 8 + 0 = 18,top = 20 + 8 + 40 = 68
        assertEquals(
            Rect(18f, 68f, 198f, 168f),
            bounds[0],
            "容器左上角与 contentPadding 都必须计入(漏一项就整体偏移,手指落在格边缘时命中错格)",
        )
        assertEquals(Rect(198f, 68f, 378f, 168f), bounds[1], "第二列同理")
    }

    @Test
    fun `header is skipped and logical indices are shifted back`() {
        val bounds = layoutSlotBounds(
            visible = listOf(info(0, 0, 0, 360, 40), info(1, 0, 40)),
            containerTopLeft = Offset.Zero,
            padLeft = 0f,
            padTop = 0f,
            headerOffset = 1,
        )
        assertEquals(1, bounds.size, "header 不得占一个逻辑下标")
        assertEquals(
            Rect(0f, 40f, 180f, 140f),
            bounds[0],
            "lazy 下标 1 - headerOffset 1 = 逻辑下标 0(不减就会整体错位一格)",
        )
    }

    @Test
    fun `without header indices are unchanged`() {
        val bounds = layoutSlotBounds(
            visible = listOf(info(0, 0, 0), info(1, 180, 0)),
            containerTopLeft = Offset.Zero,
            padLeft = 0f,
            padTop = 0f,
            headerOffset = 0,
        )
        assertEquals(2, bounds.size)
        assertEquals(0f, bounds[0]?.top)
        assertEquals(180f, bounds[1]?.left)
    }

    @Test
    fun `empty visible list yields empty map`() {
        assertEquals(
            emptyMap<Int, Rect>(),
            layoutSlotBounds(emptyList(), Offset.Zero, 0f, 0f, 0),
            "尚未布局时不得产生任何格子(否则命中判定会拿到陈旧矩形)",
        )
        // ⚠️ 变异记录(诚实留档):把函数开头的 `if (visible.isEmpty()) return emptyMap()`
        // 删掉(M6)**不会让任何用例失败**。这不是断言缺口,而是**等价变异** ——
        // 空输入下 for 循环一次都不执行,`out` 仍是空 map,返回值逐位相同。
        // 按 §7.4:等价变异要**证明等价**而不是补一条假断言,故此处刻意不设。
    }

    @Test
    fun `size comes from the layout info`() {
        val bounds = layoutSlotBounds(
            visible = listOf(info(0, 0, 0, w = 100, h = 50)),
            containerTopLeft = Offset.Zero,
            padLeft = 0f,
            padTop = 0f,
            headerOffset = 0,
        )
        assertEquals(Rect(0f, 0f, 100f, 50f), bounds[0], "矩形尺寸必须取自 layoutInfo.size")
    }
}
