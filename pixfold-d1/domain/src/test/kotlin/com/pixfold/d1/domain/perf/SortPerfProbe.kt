package com.pixfold.d1.domain.perf

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.sort.applyRule
import com.pixfold.d1.domain.sort.moveItemTo
import com.pixfold.d1.domain.sort.pageOrderOf
import com.pixfold.d1.domain.sort.sortItems
import kotlin.test.Test

class SortPerfProbe {

    private fun items(n: Int): List<SourceItem> = (1..n).map { i ->
        SourceItem(
            id = "id-$i",
            collectionId = "c",
            dir = "day%02d".format(i % 40),
            baseName = "IMG_202607%02d_%04d".format(1 + i % 28, i),   // 含数字段,走自然排序
            ext = "jpg",
            sizeBytes = 50L * 1024 * 1024,                            // 50MB/张
            modifiedEpochMillis = 1_700_000_000_000L + i,
            createdEpochMillis = 1_600_000_000_000L + i,
            seed = i,
        )
    }

    private fun bench(label: String, warm: Int = 3, runs: Int = 5, block: () -> Any?) {
        repeat(warm) { block() }
        val times = (1..runs).map {
            val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1_000_000.0
        }.sorted()
        println("PERF %-46s min=%7.1fms  median=%7.1fms  max=%7.1fms".format(label, times.first(), times[times.size/2], times.last()))
    }

    @Test
    fun bench3000() {
        val n = 3000
        val list = items(n)
        println("PERF 规模: n=$n, 每张 50MB(排序只看元数据,不读图片内容)")

        val ruleNatural = SortRule(listOf(SortKey(SortField.NaturalName, true)))
        val ruleDirThenName = SortRule(listOf(SortKey(SortField.DirName, true), SortKey(SortField.NaturalName, true)))
        val ruleDesc = SortRule(listOf(SortKey(SortField.NaturalName, false)))

        bench("sortItems 单级自然升序") { sortItems(list, ruleNatural) }
        bench("sortItems 两级(目录+自然)") { sortItems(list, ruleDirThenName) }
        bench("sortItems 单级自然降序") { sortItems(list, ruleDesc) }

        val st = pageOrderOf(list, ruleDirThenName)
        bench("applyRule(无固定项)") { applyRule(st, ruleNatural) }

        // 300 个固定项(极端:大量图钉)
        val manyPinned = st.copy(pinnedIds = list.take(300).map { it.id }.toSet())
        bench("applyRule(300 个固定项)") { applyRule(manyPinned, ruleNatural) }

        bench("moveItemTo(末项拖到首位)") { moveItemTo(st, list.last().id, 0) }

        // 整体:模拟"改一次排序规则"的端到端领域成本
        bench("端到端:改规则一次(applyRule)") { applyRule(st, SortRule(listOf(SortKey(SortField.Size, true)))) }
    }
}
