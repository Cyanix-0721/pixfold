package com.cyanix.pixfold.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `humanBytes` 的边界断言。
 *
 * 这些期望值是**从语义独立推出的**，不是把实现跑一遍抄下来的读数：
 * `320 MB` 是因为 320×1024² 恰好整除；`1 MB` 是因为 1023.96 KB 舍入后越过了 1024；
 * `1023.9 KB` 是因为 1023.94 KB 舍入后没越过 —— 两条必须同时成立，
 * 只测前者的话「无条件进位」也能过。
 */
class ByteCountFormatTest {

    @Test
    fun `sub-kilobyte values stay in plain bytes`() {
        // 边界：0、1KB 的前一个字节、1KB 本身。
        assertEquals("0 B", humanBytes(0L))
        assertEquals("1023 B", humanBytes(1023L))
        assertEquals("1 KB", humanBytes(1024L))
    }

    @Test
    fun `steps up through the unit ladder`() {
        assertEquals("12 KB", humanBytes(12L * 1024))
        assertEquals("320 MB", humanBytes(320L * 1024 * 1024))
        assertEquals("1.5 GB", humanBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("2 TB", humanBytes(2L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun `carries into the next unit when rounding reaches it`() {
        // 1048535 B = 1023.96 KB，舍入到一位小数是 1024.0 —— 比 1 MB 更难读。
        assertEquals("1 MB", humanBytes(1048535L))
    }

    @Test
    fun `does not carry when rounding stays below the unit boundary`() {
        // 反方向：1048473 B = 1023.90 KB，舍入后仍是 1023.9，不该被推成 1 MB。
        // 没有这条断言，「无条件进位」的错误实现也能让上一条通过。
        assertEquals("1023.9 KB", humanBytes(1048473L))
    }

    @Test
    fun `negative values collapse to zero instead of showing a minus sign`() {
        // 平台层重复回调或 StatFs 极端值可能给出负数，界面不该出现「-1 B」。
        assertEquals("0 B", humanBytes(-1L))
        assertEquals("0 B", humanBytes(Long.MIN_VALUE))
    }

    @Test
    fun `integral values drop the decimal point`() {
        // 「320 MB」而不是「320.0 MB」。
        assertEquals("320 MB", humanBytes(320L * 1024 * 1024))
    }
}
