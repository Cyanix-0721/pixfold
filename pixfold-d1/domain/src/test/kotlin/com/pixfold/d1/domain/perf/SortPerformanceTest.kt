package com.pixfold.d1.domain.perf

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.sort.applyRule
import com.pixfold.d1.domain.sort.pageOrderOf
import com.pixfold.d1.domain.sort.sortItems
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 排序性能回归护栏(2026-09-17 建立)。
 *
 * 背景:用户问"一个集合最多 3000 张、单张最大 50MB,变动即重排是否有性能问题"。
 * 实测结论:**没有**。原因有两条,都在这里被钉住:
 *  1. 排序**只读元数据**(文件名/目录/大小/时间),**从不读图片内容** ——
 *     故 50MB/张与 5KB/张的排序成本相同(本测试正是不碰任何图片字节的证明前提);
 *  2. 排序键**每项只算一次**(decorate-sort-undecorate)。
 *     原实现在**每次比较**里 `lowercase()`+`tokenizeNatural()`,n=3000 时约 **1.7 万次**
 *     字符串分配,改为 n 次后单级自然排序从 ~45ms 降到 ~12ms。
 *
 * 断言用**宽松上界**(远大于实测值)而非精确耗时:目的是挡住**灾难性**复杂度回退,
 * 而不是考核机器性能(CI 抖动不该导致红灯)。
 * **实测发现的局限**:绝对上界挡不住 3~4× 级别的回退 ——
 * 退回"每次比较都分词"的旧实现(~45ms)仍在 320ms 内通过;
 * 故另加 `precomputed keys are faster than recomputing per comparison`,
 * 用**同机相对比较**自校准,那一条才是真正挡住本次优化的护栏。
 * 实测参考(n=3000):sortItems 单级 ~12ms,applyRule ~4ms。
 */
class SortPerformanceTest {

    private fun items(n: Int): List<SourceItem> = (1..n).map { i ->
        SourceItem(
            id = "id-$i",
            collectionId = "c",
            dir = "day%02d".format(i % 40),
            baseName = "IMG_202607%02d_%04d".format(1 + i % 28, i),
            ext = "jpg",
            // 50MB/张:故意用最大规模,证明"大小不影响排序耗时"(排序不读内容)
            sizeBytes = 50L * 1024 * 1024,
            modifiedEpochMillis = 1_700_000_000_000L + i,
            createdEpochMillis = 1_600_000_000_000L + i,
            seed = i,
        )
    }

    private fun millisOf(warmup: Int = 2, runs: Int = 3, block: () -> Unit): Long {
        repeat(warmup) { block() }
        // 取最快一次:排除 GC/调度抖动,反映算法本身成本
        return (1..runs).minOf {
            val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1_000_000
        }
    }

    @Test
    fun `sorting 3000 items stays well under one frame budget`() {
        val list = items(3000)
        val rule = SortRule(listOf(SortKey(SortField.NaturalName, true)))

        val ms = millisOf { sortItems(list, rule) }

        // 16ms = 60fps 一帧。留 20 倍余量(320ms)以免 CI 抖动误报,
        // 仍足以挡住 1.7 万次重复分词的旧实现量级回退。
        assertTrue(ms < 320, "n=3000 单级自然排序耗时 ${ms}ms,超出预期上界(实测参考 ~12ms)")
    }

    @Test
    fun `applying a rule to 3000 items stays well under one frame budget`() {
        val list = items(3000)
        val base = pageOrderOf(list, SortRule(listOf(SortKey(SortField.DirName, true))))
        val newRule = SortRule(listOf(SortKey(SortField.Size, false)))

        val ms = millisOf { applyRule(base, newRule) }

        assertTrue(ms < 320, "n=3000 applyRule 耗时 ${ms}ms,超出预期上界(实测参考 ~4ms)")
    }

    @Test
    fun `many pinned items do not explode the cost`() {
        // 固定项落位是"冲突向左找空位";构造大量固定项确认不会退化成 O(n²) 爆炸
        val list = items(3000)
        val base = pageOrderOf(list, SortRule(listOf(SortKey(SortField.DirName, true))))
        val manyPinned = base.copy(pinnedIds = list.take(1000).map { it.id }.toSet())

        val ms = millisOf { applyRule(manyPinned, SortRule(listOf(SortKey(SortField.Size, true)))) }

        assertTrue(ms < 320, "1000 个固定项时 applyRule 耗时 ${ms}ms,疑似复杂度回退")
    }

    @Test
    fun `cost grows roughly linearly not quadratically`() {
        // 量级护栏:n 翻 10 倍,耗时不应翻 ~100 倍(排序是 n log n)
        val small = items(300)
        val large = items(3000)
        val rule = SortRule(listOf(SortKey(SortField.NaturalName, true)))

        val tSmall = millisOf(runs = 5) { sortItems(small, rule) }.coerceAtLeast(1)
        val tLarge = millisOf(runs = 5) { sortItems(large, rule) }

        // 线性外推 n×10 约 10 倍(含 log 因子约 13 倍);给 5 倍裕量 -> 65 倍
        assertTrue(
            tLarge < tSmall * 65,
            "n 增 10 倍耗时增 ${"%.0f".format(tLarge.toDouble() / tSmall)} 倍,疑似超线性退化(小=${tSmall}ms 大=${tLarge}ms)",
        )
    }

    /**
     * **自校准护栏(真正挡住性能回退的那一条)**。
     *
     * 上两条用绝对耗时上界,只能挡住"灾难性退化";实测发现:退回旧实现(~45ms)
     * **不会**触发 320ms 上界 —— 说明绝对阈值挡不住本次这类 3~4× 回退。
     * 故这里改为**同机同数据对比**:朴素实现(每次比较现算)vs 优化实现(预计算一次),
     * 断言优化实现**确实更快**。比值判据与机器速度无关,CI 上同样成立。
     */
    @Test
    fun `precomputed keys are faster than recomputing per comparison`() {
        val list = items(3000)
        val rule = SortRule(listOf(SortKey(SortField.NaturalName, true)))

        // 朴素实现:忠实复刻优化前写法(每次比较都小写化 + 分词)
        fun naive(): List<SourceItem> = list.sortedWith { a, b ->
            var r = 0
            for (k in rule.keys) {
                val c = when (k.field) {
                    SortField.NaturalName ->
                        com.pixfold.d1.domain.sort.naturalCompare(
                            a.fileName.lowercase(), b.fileName.lowercase(),
                        )
                    else -> 0
                }
                if (c != 0) { r = if (k.ascending) c else -c; break }
            }
            if (r != 0) r else a.id.compareTo(b.id)
        }

        val tNaive = millisOf(runs = 3) { naive() }
        val tFast = millisOf(runs = 3) { sortItems(list, rule) }

        // 结果必须一致(性能优化不得改语义)
        assertTrue(naive().map { it.id } == sortItems(list, rule).map { it.id }, "两者结果应一致")

        // 优化实现应**显著更快**:实测约 3.6×,这里要求至少 2×
        // (留裕量以免 CI 抖动误报;退回旧实现时两者≈相等,必然触发)
        assertTrue(
            tFast * 2 <= tNaive,
            "预计算排序键(${tFast}ms)未显著快于每次比较现算(${tNaive}ms):" +
                "应至少快 2 倍,实测 ${"%.1f".format(tNaive.toDouble() / tFast)}× —— 疑似退回旧实现",
        )
    }
}
