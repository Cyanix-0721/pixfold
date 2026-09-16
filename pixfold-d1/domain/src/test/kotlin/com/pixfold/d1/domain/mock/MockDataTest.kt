package com.pixfold.d1.domain.mock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockDataTest {

    @Test
    fun `workspace A has three collections`() {
        val ws = MockData.workspaceA
        assertEquals(listOf("trip", "scan", "misc"), ws.collections.map { it.id })
    }

    @Test
    fun `trip collection spans two dirs with 30 images`() {
        val trip = MockData.workspaceA.collections.first { it.id == "trip" }
        assertEquals(setOf("day1", "day2"), trip.images.map { it.dir }.toSet())
        assertEquals(30, trip.images.size)
    }

    @Test
    fun `scan collection covers duplicate illegal and case-collision samples`() {
        val scan = MockData.workspaceA.collections.first { it.id == "scan" }
        // 跨目录同名 baseName
        assertEquals(2, scan.images.count { it.baseName == "page_01" })
        // 非法字符样例
        assertTrue(scan.images.any { it.baseName.contains(':') || it.baseName.contains('?') })
        // 大小写冲突对
        assertTrue(scan.images.any { it.ext == "PNG" })
    }

    @Test
    fun `misc collection exposes natural vs lexical difference`() {
        val misc = MockData.workspaceA.collections.first { it.id == "misc" }
        assertEquals(12, misc.images.size)
        assertTrue(misc.images.any { it.baseName == "img10" }, "应含 img10 以体现字典序与自然序差异")
    }

    @Test
    fun `mock data is deterministic across calls`() {
        val a = MockData.workspaceA.collections.first { it.id == "misc" }.images.map { it.id }
        val b = MockData.workspaceA.collections.first { it.id == "misc" }.images.map { it.id }
        assertEquals(a, b)
    }
}
