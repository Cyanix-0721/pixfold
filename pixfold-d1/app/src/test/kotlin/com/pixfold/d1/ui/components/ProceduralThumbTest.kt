package com.pixfold.d1.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProceduralThumbTest {

    @Test
    fun `same seed yields identical color`() {
        assertEquals(seedBaseColor(42), seedBaseColor(42))
        assertEquals(seedAccentColor(42), seedAccentColor(42))
    }

    @Test
    fun `different seeds yield different colors`() {
        assertNotEquals(seedBaseColor(1), seedBaseColor(2))
    }

    @Test
    fun `color is deterministic across many seeds`() {
        for (s in 0..200) {
            assertEquals(seedBaseColor(s), seedBaseColor(s), "seed=$s 应稳定")
        }
    }

    @Test
    fun `accent is darker than base`() {
        for (s in 0..20) {
            val b = seedBaseColor(s)
            val a = seedAccentColor(s)
            val lumB = 0.299 * b.red + 0.587 * b.green + 0.114 * b.blue
            val lumA = 0.299 * a.red + 0.587 * a.green + 0.114 * a.blue
            assertTrue(lumA < lumB, "seed=$s 的 accent 应比 base 暗")
        }
    }

    @Test
    fun `colors are fully opaque`() {
        for (s in 0..20) {
            assertEquals(1f, seedBaseColor(s).alpha)
            assertEquals(1f, seedAccentColor(s).alpha)
        }
    }

    @Test
    fun `display number and line count are bounded and deterministic`() {
        for (s in -50..200) {
            val n = seedDisplayNumber(s)
            assertTrue(n in 1..99, "seed=$s 序号应在 1..99,实际 $n")
            val lines = seedLineCount(s)
            assertTrue(lines in 2..4, "seed=$s 纹样线数应在 2..4,实际 $lines")
        }
        assertEquals(seedDisplayNumber(7), seedDisplayNumber(7))
    }

    @Test
    fun `negative seeds do not crash and stay valid`() {
        // Collections.hashCode 等可能产生负数 seed;不能出现负色相/越界
        val c: Color = seedBaseColor(-12345)
        assertTrue(c.red in 0f..1f && c.green in 0f..1f && c.blue in 0f..1f)
    }

    // ---- 缩略图文字(2026-09-16 用户要求:mock 图写上文字,更直观) ----

    @Test
    fun `thumbnail caption prefers a trimmed file name`() {
        assertEquals("IMG_20260701_001", thumbCaption(fileName = "IMG_20260701_001.jpg"))
        assertEquals("page_01", thumbCaption(fileName = "page_01.png"))
    }

    @Test
    fun `thumbnail caption keeps usable short names intact`() {
        // 目录内文件名才是人核对顺序的依据,不能被截到无法辨认
        assertEquals("img12", thumbCaption(fileName = "img12.jpg"))
        assertEquals("封:面?", thumbCaption(fileName = "封:面?.png"))
    }

    @Test
    fun `thumbnail caption is unique per item unlike the seed number`() {
        // 这正是不用 seed 数字的原因:day1 的 seed=1 与 day2 的 seed=101 都显示 "2"
        val names = listOf("IMG_20260701_001.jpg", "IMG_20260701_002.jpg", "IMG_20260702_001.jpg")
        val captions = names.map { thumbCaption(fileName = it) }
        assertEquals(captions.size, captions.toSet().size, "文字应能唯一区分不同图片")
    }

    @Test
    fun `thumbnail caption is empty for blank input`() {
        assertEquals("", thumbCaption(fileName = ""))
        assertEquals("", thumbCaption(fileName = "   "))
    }

    @Test
    fun `thumbnail caption truncates very long names keeping the tail`() {
        // 长名保留尾部:序号多半在尾部(如 _0001),截尾比截头更易辨认
        val long = "a".repeat(40) + "_final_0099"
        val c = thumbCaption(fileName = long, maxChars = 12)
        assertTrue(c.length <= 13, "应限制长度(实际 ${c.length})")
        assertTrue(c.endsWith("0099"), "应保留尾部以便辨认序号(实际 $c)")
        assertTrue(c.startsWith("…"), "截断处应有省略号(实际 $c)")
    }
}
