package com.pixfold.d1.domain.sort

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalOrderTest {

    @Test
    fun `numbers compare numerically not lexically`() {
        assertTrue(naturalCompare("img2", "img10") < 0, "2 应小于 10")
        assertTrue(naturalCompare("img10", "img1") > 0, "10 应大于 1")
        assertEquals(0, naturalCompare("img1", "img1"))
    }

    @Test
    fun `equal numbers prefer fewer leading zeros`() {
        // 归档语义:数值相等时比原始位数,位数少者在前 -> "1" < "01"
        assertTrue(naturalCompare("1", "01") < 0, "1 应在 01 之前")
        assertTrue(naturalCompare("01", "1") > 0)
    }

    @Test
    fun `very long digit runs do not overflow`() {
        // 归档用 int.parse 会抛错;新实现必须安全比较
        val big = "9".repeat(30)
        val bigger = "1" + "0".repeat(30)
        assertTrue(naturalCompare(big, bigger) < 0, "30 位 9 应小于 31 位的 10^30")
        assertEquals(0, naturalCompare(big, big))
    }

    @Test
    fun `tokenizer separates digit runs`() {
        // 钉死 Kotlin Regex.split 丢弃捕获组的陷阱:必须用 findAll 手动切记号
        assertEquals(listOf("IMG_", "10", ".jpg"), tokenizeNatural("IMG_10.jpg"))
        assertEquals(listOf("page_", "01", ".png"), tokenizeNatural("page_01.png"))
        assertEquals(listOf("plain.jpg"), tokenizeNatural("plain.jpg"))
    }

    @Test
    fun `digit and non-digit compare by code unit`() {
        // 第 2 位 '1'(0x31) vs 'b'(0x62) -> a1 在前
        assertTrue(naturalCompare("a1", "ab") < 0)
    }

    @Test
    fun `shorter string comes first when prefix equal`() {
        assertTrue(naturalCompare("img", "img1") < 0)
    }
}
