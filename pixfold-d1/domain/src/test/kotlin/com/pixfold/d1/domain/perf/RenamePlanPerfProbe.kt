package com.pixfold.d1.domain.perf

import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.naming.buildProposals
import com.pixfold.d1.domain.plan.buildLibraryRenamePlan
import com.pixfold.d1.domain.sort.pageOrderOf
import kotlin.test.Test

/**
 * 探针(不是护栏):量「用户实际规模」下工作流 A 计划页的领域成本。
 *
 * 起因 = 用户给定规模口径「一般都是几十到两百张图片,多的话大几百张,极端情况两三千张也有」。
 * 本文件只打印读数,不断言 —— 结论落台账 §7 与 HANGOFF §9.1。
 */
class RenamePlanPerfProbe {

    private fun items(n: Int, dirs: Int, dupPercent: Int = 0): List<SourceItem> = (1..n).map { i ->
        val dup = dupPercent > 0 && i % 100 <= dupPercent
        SourceItem(
            id = "id-$i",
            collectionId = "c",
            dir = "day%02d".format(i % dirs),
            // dupPercent>0 时让同一目录内出现重复序号 ⇒ 制造重名冲突(走 attachWarnings 的重分支)
            baseName = if (dup) "DUP_%04d".format(i / 100) else "IMG_%04d".format(i),
            ext = "jpg",
            sizeBytes = 50L * 1024 * 1024,
            modifiedEpochMillis = 1_700_000_000_000L + i,
            createdEpochMillis = 1_600_000_000_000L + i,
            seed = i,
        )
    }

    private fun bench(label: String, warm: Int = 2, runs: Int = 4, block: () -> Any?) {
        repeat(warm) { block() }
        val times = (1..runs).map {
            val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1_000_000.0
        }.sorted()
        println(
            "PERF %-48s min=%7.2fms  median=%7.2fms  max=%7.2fms".format(
                label, times.first(), times[times.size / 2], times.last(),
            ),
        )
    }

    private val scheme = NamingScheme(rootDirName = "root")
    private val naming = NamingState(scheme = scheme)
    private val rule = SortRule(listOf(SortKey(SortField.DirName, true), SortKey(SortField.NaturalName, true)))

    @Test
    fun benchRenamePlanAtRealScale() {
        println("PERF === 工作流 A 计划页领域成本(排序只看元数据;每张 50MB) ===")

        // buildProposals 单集合
        for (n in listOf(200, 800, 3000)) {
            val list = items(n, dirs = 40)
            bench("buildProposals n=$n / 40 目录") { buildProposals(list, scheme) }
        }

        // 全量整库计划 = 计划页每次重组都在跑的那条路径
        for (n in listOf(200, 800, 3000)) {
            val cols = listOf(ImageCollection("c1", "c1", "c1", items(n, dirs = 40)))
            val orders = mapOf("c1" to pageOrderOf(cols[0].images, rule))
            bench("buildLibraryRenamePlan n=$n / 1 集合") { buildLibraryRenamePlan(cols, orders, naming) }
        }

        // 最坏:重名冲突极多(attachWarnings 的重分支)
        val dupList = items(3000, dirs = 1, dupPercent = 100)
        bench("buildProposals n=3000 全同目录+大量重名") { buildProposals(dupList, scheme) }

        // 分片集合(真实形态:多个集合各自几十~几百张)
        val many = (1..20).map { k ->
            ImageCollection("c$k", "c$k", "c$k", items(150, dirs = 10).map { it.copy(collectionId = "c$k", id = "c$k-${it.id}") })
        }
        val manyOrders = many.associate { it.id to pageOrderOf(it.images, rule) }
        bench("buildLibraryRenamePlan 20 集合 ×150 张 = 3000") { buildLibraryRenamePlan(many, manyOrders, naming) }
    }
}
