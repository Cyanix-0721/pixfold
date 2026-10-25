package com.cyanix.pixfold.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceOrderingTest {

    private fun entry(name: String, pinned: Boolean = false) =
        WorkspaceEntry(WorkspaceHandle(treeUri = "content://tree/$name", displayName = name), pinned)

    private fun listing(name: String, pinned: Boolean = false, granted: Boolean = true) =
        WorkspaceListing(entry(name, pinned), granted)

    // ---- Review Focus 1：空列表与单条列表 ----

    @Test
    fun `空列表排序后仍是空`() {
        assertEquals(0, orderedForDisplay(emptyList()).size)
    }

    @Test
    fun `单条列表的拖拽被丢弃`() {
        val store = listOf(entry("a"))
        assertNull(movedOrder(store, "content://tree/a", "content://tree/a"))
    }

    @Test
    fun `URI 不在序列里时拖拽被丢弃`() {
        val store = listOf(entry("a"), entry("b"))
        assertNull(movedOrder(store, "content://tree/不存在", "content://tree/b"))
        assertNull(movedOrder(store, "content://tree/a", "content://tree/不存在"))
    }

    // ---- Review Focus 2：钉住的排最前，图钉之间保持存储相对顺序 ----

    @Test
    fun `钉住的排本区最前`() {
        val input = listOf(listing("a"), listing("b"), listing("c", pinned = true))
        assertEquals(listOf("c", "a", "b"), orderedForDisplay(input).map { it.entry.handle.displayName })
    }

    @Test
    fun `多个图钉之间保持存储相对顺序`() {
        // c 与 d 都钉住；存储里 c 在前，排序后仍须 c 在前 —— 这条就是「图钉之间可互换」的地基。
        val input = listOf(listing("a"), listing("c", pinned = true), listing("b"), listing("d", pinned = true))
        assertEquals(listOf("c", "d", "a", "b"), orderedForDisplay(input).map { it.entry.handle.displayName })
    }

    @Test
    fun `全部钉住时顺序不变`() {
        val input = listOf(listing("a", pinned = true), listing("b", pinned = true))
        assertEquals(listOf("a", "b"), orderedForDisplay(input).map { it.entry.handle.displayName })
    }

    // ---- 拖拽下标换算（spec §4.4 的先删后插） ----

    @Test
    fun `向前拖：被移的条目落到目标条目原来的位置上`() {
        val store = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        // 把 a 拖到 c 的位置 ⇒ b c a d
        val out = movedOrder(store, "content://tree/a", "content://tree/c")!!
        assertEquals(listOf("b", "c", "a", "d"), out.map { it.handle.displayName })
    }

    @Test
    fun `向后拖：被移的条目落到目标条目原来的位置上`() {
        val store = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        // 把 d 拖到 b 的位置 ⇒ a d b c
        val out = movedOrder(store, "content://tree/d", "content://tree/b")!!
        assertEquals(listOf("a", "d", "b", "c"), out.map { it.handle.displayName })
    }

    @Test
    fun `相邻互换`() {
        val store = listOf(entry("a"), entry("b"))
        assertEquals(listOf("b", "a"), movedOrder(store, "content://tree/a", "content://tree/b")!!.map { it.handle.displayName })
        assertEquals(listOf("b", "a"), movedOrder(store, "content://tree/b", "content://tree/a")!!.map { it.handle.displayName })
    }

    // ---- Review Focus 5：pinned 不参与存储位置，只影响渲染 ----

    @Test
    fun `拖拽结果里 pinned 原样保留`() {
        val store = listOf(entry("a", pinned = true), entry("b"))
        val out = movedOrder(store, "content://tree/a", "content://tree/b")!!
        assertEquals(listOf(false, true), out.map { it.pinned })
    }
}
