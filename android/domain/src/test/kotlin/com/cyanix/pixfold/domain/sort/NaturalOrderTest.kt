package com.cyanix.pixfold.domain.sort

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

    // ========== 中文数字归一化(用户裁决 2026-09-25:只认 一~九 与 十) ==========

    @Test
    fun `chinese numerals sort by value not by code point`() {
        // 修复前是逐字比码点(一 4E00 / 二 4E8C / 十 5341 ...),整段顺序被搅乱:
        // 实测得到 第一话 第七话 第三话 第九话 第二话 第五话 第八话 第六话 第十一话 第十三话 第十二话 第十话 第四话
        val names = listOf(
            "第一话", "第二话", "第三话", "第四话", "第五话", "第六话", "第七话",
            "第八话", "第九话", "第十话", "第十一话", "第十二话", "第十三话",
        )
        assertEquals(names, names.sortedWith { a, b -> naturalCompare(a, b) }, "中文数字必须按数值排序")
    }

    @Test
    fun `chinese and arabic numerals interleave by value`() {
        assertTrue(naturalCompare("第2话", "第十话") < 0, "2 < 10,两种数字必须混排")
        assertTrue(naturalCompare("第十话", "第11话") < 0, "10 < 11")
        assertEquals(0, naturalCompare("第十话", "第10话"), "同一个数的两种写法应比较相等")
    }

    @Test
    fun `tens forms parse to the right value`() {
        assertTrue(naturalCompare("第十话", "第二十话") < 0, "10 < 20")
        assertTrue(naturalCompare("第十二话", "第二十话") < 0, "12 < 20")
        assertTrue(naturalCompare("第二十话", "第二十一话") < 0, "20 < 21")
        assertEquals(0, naturalCompare("二十话", "20话"))
    }

    @Test
    fun `tokenizer normalizes chinese numerals into arabic tokens`() {
        assertEquals(listOf("第", "10", "话.jpg"), tokenizeNatural("第十话.jpg"))
        assertEquals(listOf("第", "21", "话.jpg"), tokenizeNatural("第二十一话.jpg"))
        assertEquals(listOf("第", "1", "话.jpg"), tokenizeNatural("第一话.jpg"))
    }

    @Test
    fun `chinese numeral recognition is limited to one through nine and ten`() {
        // 裁决范围边界:百/千/万/〇 不在归一化范围内,必须原样留作文本记号
        assertEquals(listOf("百"), tokenizeNatural("百"))
        assertEquals(listOf("千万"), tokenizeNatural("千万"))
        assertEquals(listOf("〇"), tokenizeNatural("〇"))
    }

    @Test
    fun `malformed chinese numeral runs are left as plain text`() {
        // 可解析的形式只有 十 / 十X / XY / X十Y(XY 各是一位 一~九)。
        // 其余罗列必须原样留作文本,否则会被当成一个数值参与比较,顺序静默改变。
        assertEquals(listOf("一二三"), tokenizeNatural("一二三"), "多字罗列不是数字")
        assertEquals(listOf("十二三"), tokenizeNatural("十二三"), "十后面最多接一位")
        assertEquals(listOf("十十"), tokenizeNatural("十十"), "十不是个位数字")
    }

    @Test
    fun `one side digits still compares by code unit`() {
        // 一边数字段、一边文本段时必须走码点比较:'9'(0x39) > '!'(0x21)。
        // 若误走"数值分支",会得到 1 位 < 2 位的反结果。
        assertTrue(naturalCompare("9", "!x") > 0, "一边数字一边非数字应按码点比较,不能按有效长度")
    }

    @Test
    fun `tokenizer does not append an empty token when the name ends with digits`() {
        // 末尾那句 `if (last < s.length)` 是刻意的:记号序列里只允许非空段。
        assertEquals(listOf("IMG_", "10"), tokenizeNatural("IMG_10"))
        assertEquals(listOf("page", "07"), tokenizeNatural("page07"))
        assertTrue(tokenizeNatural("").isEmpty(), "空串没有任何记号")
    }
}
