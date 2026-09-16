package com.pixfold.d1.domain.workflowa

import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.defaultRule
import com.pixfold.d1.domain.sort.pageOrderOf
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
class WorkflowAStateTest {

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
        val s = workflowAStateOf(collections())
        assertEquals(setOf("c1", "c2"), s.orders.keys)
        assertTrue(s.customRuleCollections.isEmpty())
        assertEquals(defaultRule(), s.batchRule)
    }

    @Test
    fun `applying to non-custom collection updates batch rule and reorders all non-custom`() {
        val s0 = workflowAStateOf(collections())
        val s1 = applyRuleToCollection(s0, "c1", nameDesc)

        assertEquals(nameDesc, s1.batchRule, "非独立集合的规则应成为批次默认")
        // 两个集合都不是独立 -> 都应被重排
        assertEquals(listOf("a10", "a2"), s1.orders.getValue("c1").order.map { it.id })
        assertEquals(listOf("b10", "b2"), s1.orders.getValue("c2").order.map { it.id })
    }

    @Test
    fun `applying to custom collection does not touch batch rule or other collections`() {
        val s0 = workflowAStateOf(collections())
        val s1 = toggleCustomRule(s0, "c1")            // c1 变独立
        val before = s1.orders.getValue("c2").order.map { it.id }

        val s2 = applyRuleToCollection(s1, "c1", nameDesc)

        assertEquals(defaultRule(), s2.batchRule, "独立集合的规则不应污染批次默认")
        assertEquals(listOf("a10", "a2"), s2.orders.getValue("c1").order.map { it.id })
        assertEquals(before, s2.orders.getValue("c2").order.map { it.id }, "其他集合不应被重排")
    }

    @Test
    fun `toggling custom keeps current order`() {
        val s0 = workflowAStateOf(collections())
        // 先手动改顺序
        val s1 = applyRuleToCollection(s0, "c1", nameDesc)
        val orderBefore = s1.orders.getValue("c1").order.map { it.id }

        val s2 = toggleCustomRule(s1, "c1")

        assertTrue("c1" in s2.customRuleCollections)
        assertEquals(orderBefore, s2.orders.getValue("c1").order.map { it.id }, "转独立应保留当前顺序")
    }

    @Test
    fun `removing custom immediately reorders with batch rule`() {
        val s0 = workflowAStateOf(collections())
        val s1 = toggleCustomRule(s0, "c1")
        val s2 = applyRuleToCollection(s1, "c1", nameDesc)   // 独立:降序
        assertEquals(listOf("a10", "a2"), s2.orders.getValue("c1").order.map { it.id })

        val s3 = toggleCustomRule(s2, "c1")                  // 取消独立

        assertFalse("c1" in s3.customRuleCollections)
        assertEquals(
            s3.orders.getValue("c1").order.map { it.id },
            com.pixfold.d1.domain.sort.sortItems(
                s3.collections.first { it.id == "c1" }.images, s3.batchRule,
            ).map { it.id },
            "取消独立后应立刻用 batchRule 重排",
        )
    }

    @Test
    fun `effective rule follows custom membership`() {
        val s0 = workflowAStateOf(collections())
        val s1 = toggleCustomRule(s0, "c1")
        val s2 = applyRuleToCollection(s1, "c1", nameDesc)

        assertEquals(nameDesc, s2.effectiveRule("c1"), "独立集合用自己的规则")
        assertEquals(defaultRule(), s2.effectiveRule("c2"), "非独立集合用批次规则")
    }

    @Test
    fun `batch change does not reorder custom collections`() {
        // 覆盖缺口(变异测试发现):上面两条用例都没验证"改批次规则时独立集合必须原地不动"。
        val s0 = workflowAStateOf(collections())
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
        val s0 = workflowAStateOf(collections())
        assertFalse(s0.hasManualAnywhere)

        // 注意:必须移动到**不同**下标才会记录人工标记
        // (拖到自己格是 no-op 且不记标记 —— P1 已钉死的语义)
        val c1 = s0.orders.getValue("c1")
        val moved = com.pixfold.d1.domain.sort.moveItemTo(c1, c1.order.first().id, c1.order.size - 1)
        val s2 = s0.copy(orders = s0.orders + ("c1" to moved))

        assertTrue(s2.hasManualAnywhere)
        assertEquals(1, s2.manualCountTotal)
    }

    @Test
    fun `pageOrderOf ids are stable across collections`() {
        val s = workflowAStateOf(collections())
        assertEquals(listOf("c1", "c2"), s.collections.map { it.id })
        // 每个集合的页序都应恰好含自己的图
        s.orders.forEach { (cid, order) ->
            assertTrue(order.order.all { it.collectionId == cid }, "$cid 页序不应混入其他集合的图")
        }
    }
}
