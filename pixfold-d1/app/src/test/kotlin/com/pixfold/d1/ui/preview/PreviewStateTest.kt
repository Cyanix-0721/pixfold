package com.pixfold.d1.ui.preview

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewStateTest {

    @Test
    fun `initial state is unzoomed`() {
        val s = previewStateOf(0, 5)
        assertEquals(1f, s.scale)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
        assertEquals(0, s.index)
    }

    @Test
    fun `zoom is clamped to range`() {
        val s = previewStateOf(0, 5)
        assertEquals(MAX_SCALE, s.zoomed(99f).scale)
        assertEquals(MIN_SCALE, s.zoomed(0.01f).scale)
    }

    @Test
    fun `zoom back to minimum resets offset`() {
        val s = previewStateOf(0, 5).zoomed(3f).panned(50f, 60f)
        assertTrue(s.offsetX != 0f)
        // zoomed() 是**倍率**语义:要缩回下限需给 <1 的倍率(0.01 会被夹到 MIN_SCALE)
        val reset = s.zoomed(0.01f)
        assertEquals(MIN_SCALE, reset.scale)
        assertEquals(0f, reset.offsetX, "回到最小缩放应复位平移")
        assertEquals(0f, reset.offsetY)
    }

    @Test
    fun `explicit resetZoom clears zoom and offset`() {
        val s = previewStateOf(0, 5).zoomed(2f).panned(30f, 40f).resetZoom()
        assertEquals(MIN_SCALE, s.scale)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
    }

    @Test
    fun `next stops at last page`() {
        val last = previewStateOf(4, 5)
        assertEquals(4, last.next().index, "末页再下一页应停住,不环绕")
    }

    @Test
    fun `previous stops at first page`() {
        val first = previewStateOf(0, 5)
        assertEquals(0, first.previous().index)
    }

    @Test
    fun `page change resets zoom and offset`() {
        val s = previewStateOf(1, 5).zoomed(3f).panned(20f, 20f)
        val next = s.next()
        assertEquals(2, next.index)
        assertEquals(1f, next.scale, "翻页应复位缩放")
        assertEquals(0f, next.offsetX)
    }

    @Test
    fun `resetZoom restores scale and offset`() {
        val s = previewStateOf(0, 3).zoomed(4f).panned(10f, -10f).resetZoom()
        assertEquals(1f, s.scale)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
    }

    @Test
    fun `zoom accumulates and stays within range`() {
        var s = previewStateOf(0, 3)
        repeat(10) { s = s.zoomed(1.5f) }
        assertEquals(MAX_SCALE, s.scale, "连续放大会被夹到上限")
        repeat(20) { s = s.zoomed(0.5f) }
        assertEquals(MIN_SCALE, s.scale, "连续缩小会被夹到下限")
    }

    @Test
    fun `single item has no neighbours`() {
        val only = previewStateOf(0, 1)
        assertEquals(0, only.next().index)
        assertEquals(0, only.previous().index)
        assertTrue(only.hasNext.not())
        assertTrue(only.hasPrevious.not())
    }

    @Test
    fun `page label is one based`() {
        val s = previewStateOf(0, 7)
        assertEquals("第 1 / 7 页", s.pageLabel)
        assertEquals("第 7 / 7 页", previewStateOf(6, 7).pageLabel)
    }
}
