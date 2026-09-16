package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.defaultRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun items(n: Int): List<SourceItem> = (1..n).map {
    SourceItem(
        id = "i$it", collectionId = "c", dir = "", baseName = "img$it", ext = "jpg",
        sizeBytes = it.toLong(), modifiedEpochMillis = 0, createdEpochMillis = 0, seed = 0,
    )
}

class PageOrderTest {

    @Test
    fun `initial state has no manual or pinned`() {
        val s = pageOrderOf(items(5))
        assertFalse(s.hasManual)
        assertEquals(0, s.manualCount)
        assertEquals(0, s.pinnedCount)
        assertEquals(5, s.order.size)
    }

    @Test
    fun `moveItemTo removes then inserts at target index`() {
        val s = pageOrderOf(items(6))
        val ids = s.order.map { it.id }
        val moved = moveItemTo(s, ids[0], 5)
        // 钉死"先删后插":等价于 removeAt(0) 再 insert(5, ids[0])
        val expected = ids.toMutableList().also { it.removeAt(0); it.add(5, ids[0]) }
        assertEquals(expected, moved.order.map { it.id })
        assertTrue(moved.isManual(ids[0]), "应记录人工标记")
    }

    @Test
    fun `moveItemTo to last position`() {
        val s = pageOrderOf(items(4))
        val id = s.order.first().id
        val moved = moveItemTo(s, id, 3)
        assertEquals(id, moved.order.last().id)
    }

    @Test
    fun `moveItemTo clamps out of range`() {
        val s = pageOrderOf(items(4))
        val first = s.order.first().id
        assertEquals(first, moveItemTo(s, first, -5).order.first().id)
        val last = s.order.last().id
        assertEquals(last, moveItemTo(s, last, 99).order.last().id)
    }

    @Test
    fun `moveItemTo onto itself is a no-op and records no manual mark`() {
        val s = pageOrderOf(items(4))
        val ids = s.order.map { it.id }
        val same = moveItemTo(s, ids[1], 1)
        assertEquals(ids, same.order.map { it.id })
        assertFalse(same.isManual(ids[1]), "拖到自己格不应记录人工标记")
    }

    @Test
    fun `moveItemTo unknown id is a silent no-op`() {
        val s = pageOrderOf(items(3))
        val after = moveItemTo(s, "nope", 0)
        assertEquals(s.order.map { it.id }, after.order.map { it.id })
    }

    @Test
    fun `pinned item keeps position across applyRule while manual marks clear`() {
        val s0 = pageOrderOf(items(5))
        val lastId = s0.order.last().id

        // 1) 拖到首位 -> 人工标记
        val s1 = moveItemTo(s0, lastId, 0)
        assertEquals(lastId, s1.order.first().id)
        assertTrue(s1.isManual(lastId))

        // 2) 固定
        val s2 = togglePin(s1, lastId)
        assertTrue(s2.isPinned(lastId))
        assertEquals(1, s2.pinnedCount)

        // 3) 重新应用排序 -> 固定项保位、人工标记清空、固定标记保留
        val s3 = applyRule(s2, defaultRule())
        assertEquals(lastId, s3.order.first().id, "固定项应保持原位")
        assertFalse(s3.hasManual, "applyRule 应清空人工标记")
        assertTrue(s3.isPinned(lastId), "固定标记应保留")

        // 4) 解除固定后回归自动序
        val s4 = togglePin(s3, lastId)
        assertFalse(s4.isPinned(lastId))
        val s5 = applyRule(s4, defaultRule())
        assertEquals(lastId, s5.order.last().id, "解除固定后应回到规则决定的位置")
    }

    @Test
    fun `resetToAuto keeps pinned positions but drops manual marks`() {
        val s0 = pageOrderOf(items(5))
        val lastId = s0.order.last().id
        val s1 = togglePin(moveItemTo(s0, lastId, 0), lastId)
        val reset = resetToAuto(s1)
        assertEquals(lastId, reset.order.first().id)
        assertFalse(reset.hasManual)
    }

    @Test
    fun `applyRule with a new rule reorders the rest`() {
        val s = pageOrderOf(items(4))
        val descending = SortRule(listOf(SortKey(SortField.NaturalName, false)))
        val applied = applyRule(s, descending)
        assertEquals(listOf("i4", "i3", "i2", "i1"), applied.order.map { it.id })
    }

    @Test
    fun `togglePin twice unpins`() {
        val s = pageOrderOf(items(3))
        val id = s.order.first().id
        assertTrue(togglePin(s, id).isPinned(id))
        assertFalse(togglePin(togglePin(s, id), id).isPinned(id))
    }

    @Test
    fun `pinned collision falls back to the nearest free slot on the left`() {
        // 归档规则:固定项落位被占时"向左找第一个空位"。
        // 正常路径下记录下标互不相同且升序,slot 不会碰撞 —— 该分支仅在退化状态(页序长于全集)可达。
        // 这里直接构造退化状态,把这条文档化语义钉死;否则"向左"被写成"向右"不会有任何测试察觉。
        val all = items(3)
        fun extra(id: String, base: String) = SourceItem(
            id = id, collectionId = "c", dir = "", baseName = base, ext = "jpg",
            sizeBytes = 1, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = 0,
        )
        val e4 = extra("i4", "img4")
        val e5 = extra("i5", "img5")

        val degenerate = PageOrderState(
            images = all,                                        // slots 只有 3 格(下标 0..2)
            rule = defaultRule(),
            order = listOf(all[0], all[1], all[2], e4, e5),       // 固定项记录下标 3 与 4
            pinnedIds = setOf(e4.id, e5.id),
        )

        val applied = applyRule(degenerate, defaultRule())

        // 两个固定项的夹取目标都是下标 2:
        //   先处理记录下标 3(e4) -> 落 slots[2]
        //   再处理记录下标 4(e5) -> slots[2] 已占 -> 向左找到 slots[1]
        // 若实现误为"向右",第二项会越界或落错位,本断言即失败。
        assertEquals(e4.id, applied.order[2].id, "先处理的固定项应落在夹取位")
        assertEquals(e5.id, applied.order[1].id, "撞位的固定项应向左落到最近空位")
        assertEquals(3, applied.order.size, "结果长度应等于全集长度")
    }
}
