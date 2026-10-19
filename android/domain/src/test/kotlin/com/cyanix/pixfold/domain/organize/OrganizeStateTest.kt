package com.cyanix.pixfold.domain.organize

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SortField
import com.cyanix.pixfold.domain.model.SortKey
import com.cyanix.pixfold.domain.model.SortRule
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.model.defaultRule
import com.cyanix.pixfold.domain.sort.pageOrderOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工作流 A 的"批次统一 + 逐项例外"(规格 §5.4 / 验收清单第 7 项)。
 *
 * 语义要点:
 *  - `batchRule` 是批次默认;`customRuleCollections` 记录**本组独立**的集合;
 *  - 应用到**非独立**集合 -> 更新 batchRule,并重排**所有非独立**集合;
 *  - 应用到**独立**集合 -> 只重排该集合,不动 batchRule;
 *  - `toggleCustomRule` 加入独立时**保留当前顺序**;移除独立时**立刻用 batchRule 重排**该集合。
 */
class OrganizeStateTest {

    private fun img(id: String, coll: String, base: String, dir: String = "") = SourceItem(
        id = id, collectionId = coll, dir = dir, baseName = base, ext = "jpg",
        sizeBytes = 1, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = 1,
    )

    private fun collections(): List<ImageCollection> = listOf(
        ImageCollection("c1", "集合1", "集合1", listOf(img("a10", "c1", "p10"), img("a2", "c1", "p2"))),
        ImageCollection("c2", "集合2", "集合2", listOf(img("b10", "c2", "p10"), img("b2", "c2", "p2"))),
    )

    private val nameAsc = SortRule(listOf(SortKey(SortField.NaturalName, true)))
    private val nameDesc = SortRule(listOf(SortKey(SortField.NaturalName, false)))

    @Test
    fun `initial state uses default rule for all collections`() {
        val s = organizeStateOf(collections())
        assertEquals(setOf("c1", "c2"), s.orders.keys)
        assertTrue(s.customRuleCollections.isEmpty())
        assertEquals(defaultRule(), s.batchRule)
    }

    @Test
    fun `applying to non-custom collection updates batch rule and reorders all non-custom`() {
        val s0 = organizeStateOf(collections())
        val s1 = applyRuleToCollection(s0, "c1", nameDesc)

        assertEquals(nameDesc, s1.batchRule, "非独立集合的规则应成为批次默认")
        // 两个集合都不是独立 -> 都应被重排
        assertEquals(listOf("a10", "a2"), s1.orders.getValue("c1").order.map { it.id })
        assertEquals(listOf("b10", "b2"), s1.orders.getValue("c2").order.map { it.id })
    }

    @Test
    fun `applying to custom collection does not touch batch rule or other collections`() {
        val s0 = organizeStateOf(collections())
        val s1 = toggleCustomRule(s0, "c1")            // c1 变独立
        val before = s1.orders.getValue("c2").order.map { it.id }

        val s2 = applyRuleToCollection(s1, "c1", nameDesc)

        assertEquals(defaultRule(), s2.batchRule, "独立集合的规则不应污染批次默认")
        assertEquals(listOf("a10", "a2"), s2.orders.getValue("c1").order.map { it.id })
        assertEquals(before, s2.orders.getValue("c2").order.map { it.id }, "其他集合不应被重排")
    }

    @Test
    fun `toggling custom keeps current order`() {
        val s0 = organizeStateOf(collections())
        // 先手动改顺序
        val s1 = applyRuleToCollection(s0, "c1", nameDesc)
        val orderBefore = s1.orders.getValue("c1").order.map { it.id }

        val s2 = toggleCustomRule(s1, "c1")

        assertTrue("c1" in s2.customRuleCollections)
        assertEquals(orderBefore, s2.orders.getValue("c1").order.map { it.id }, "转独立应保留当前顺序")
    }

    @Test
    fun `removing custom immediately reorders with batch rule`() {
        val s0 = organizeStateOf(collections())
        val s1 = toggleCustomRule(s0, "c1")
        val s2 = applyRuleToCollection(s1, "c1", nameDesc)   // 独立:降序
        assertEquals(listOf("a10", "a2"), s2.orders.getValue("c1").order.map { it.id })

        val s3 = toggleCustomRule(s2, "c1")                  // 取消独立

        assertFalse("c1" in s3.customRuleCollections)
        assertEquals(
            s3.orders.getValue("c1").order.map { it.id },
            com.cyanix.pixfold.domain.sort.sortItems(
                s3.collections.first { it.id == "c1" }.images, s3.batchRule,
            ).map { it.id },
            "取消独立后应立刻用 batchRule 重排",
        )
    }

    @Test
    fun `effective rule follows custom membership`() {
        val s0 = organizeStateOf(collections())
        val s1 = toggleCustomRule(s0, "c1")
        val s2 = applyRuleToCollection(s1, "c1", nameDesc)

        assertEquals(nameDesc, s2.effectiveRule("c1"), "独立集合用自己的规则")
        assertEquals(defaultRule(), s2.effectiveRule("c2"), "非独立集合用批次规则")
    }

    @Test
    fun `batch change does not reorder custom collections`() {
        // 覆盖缺口(变异测试发现):上面两条用例都没验证"改批次规则时独立集合必须原地不动"。
        val s0 = organizeStateOf(collections())
        // c1 转独立并设为降序(此时 c2 仍用批次默认)
        val s1 = toggleCustomRule(s0, "c1")
        val s2 = applyRuleToCollection(s1, "c1", nameDesc)
        val customOrder = s2.orders.getValue("c1").order.map { it.id }
        assertEquals(listOf("a10", "a2"), customOrder)

        // 现在改**批次**规则(作用于 c2)
        val s3 = applyRuleToCollection(s2, "c2", nameAsc)

        assertEquals(customOrder, s3.orders.getValue("c1").order.map { it.id },
            "批次变更不得重排独立集合")
        assertEquals(nameDesc, s3.effectiveRule("c1"), "独立集合仍用自己的规则")
    }

    @Test
    fun `hasManual reflects per-collection state`() {
        val s0 = organizeStateOf(collections())
        assertFalse(s0.hasManualAnywhere)

        // 注意:必须移动到**不同**下标才会记录人工标记
        // (拖到自己格是 no-op 且不记标记 —— P1 已钉死的语义)
        val c1 = s0.orders.getValue("c1")
        val moved = com.cyanix.pixfold.domain.sort.moveItemTo(c1, c1.order.first().id, c1.order.size - 1)
        val s2 = s0.copy(orders = s0.orders + ("c1" to moved))

        assertTrue(s2.hasManualAnywhere)
        assertEquals(1, s2.manualCountTotal)
    }

    @Test
    fun `pageOrderOf ids are stable across collections`() {
        val s = organizeStateOf(collections())
        assertEquals(listOf("c1", "c2"), s.collections.map { it.id })
        // 每个集合的页序都应恰好含自己的图
        s.orders.forEach { (cid, order) ->
            assertTrue(order.order.all { it.collectionId == cid }, "$cid 页序不应混入其他集合的图")
        }
    }

    // ---- 变异测试补强(轮 10):固定项与规则变更的交互 ----

    @Test
    fun `applying a rule to a custom collection keeps its pinned item`() {
        // 覆盖缺口(变异测试发现):独立集合已有固定项时,改规则必须基于**当前状态**重排;
        // 若从"原始图 + 新规则"重算,固定项的位置会被丢掉。
        val s0 = organizeStateOf(collections())
        val s1 = toggleCustomRule(s0, "c1")                     // c1 独立,此时默认序 [a2, a10]
        val c1 = s1.orders.getValue("c1")
        val moved = com.cyanix.pixfold.domain.sort.moveItemTo(c1, "a10", 0)   // 人工把 a10 拖到首位
        val s2 = updatePageOrder(s1, "c1", com.cyanix.pixfold.domain.sort.togglePin(moved, "a10"))
        assertEquals(listOf("a10", "a2"), s2.orders.getValue("c1").order.map { it.id })

        // nameAsc 的规则序是 [a2, a10] -> 固定项 a10 必须仍占首位
        val s3 = applyRuleToCollection(s2, "c1", nameAsc)

        assertEquals(
            listOf("a10", "a2"),
            s3.orders.getValue("c1").order.map { it.id },
            "改规则时固定项必须留在原位(不得从原始图重算)",
        )
        assertEquals(1, s3.orders.getValue("c1").pinnedCount, "固定标记必须保留")
    }

    @Test
    fun `pinnedCountTotal counts pins and is not the manual count`() {
        // 覆盖缺口(变异测试发现):三个派生量里只有 pinnedCountTotal 从无断言,
        // 把它写成 manualCount 也无人发现。
        val s0 = organizeStateOf(collections())
        assertEquals(0, s0.pinnedCountTotal)

        val c1 = s0.orders.getValue("c1")
        // 只固定、不移动 -> manualCount 仍为 0,两个计数必须区分得开
        val s1 = updatePageOrder(s0, "c1", com.cyanix.pixfold.domain.sort.togglePin(c1, c1.order.first().id))

        assertEquals(1, s1.pinnedCountTotal, "固定一项后应计 1")
        assertEquals(0, s1.manualCountTotal, "只固定不算人工调整")
        assertFalse(s1.hasManualAnywhere)
    }
}
