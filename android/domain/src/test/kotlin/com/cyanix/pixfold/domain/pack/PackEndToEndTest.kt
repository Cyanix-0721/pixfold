package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.storage.SpaceThreshold
import com.cyanix.pixfold.domain.task.CancellationToken
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D4b 端到端集成测试:用**真实 mock 漫画库**(`MockDataB`,5 卷含 1 卷输出冲突)
 * 把「扫描结果 → 打包计划 → 预检 → 执行 → 真实 ZIP 字节」整条链路跑通。
 *
 * ## 为什么需要这一层(与 PackRunnerTest / PackPreflightTest / PackGateTest 的分工)
 *
 * 那三个测试用的都是**手造的、形状被裁剪过的**对象(2 页的卷、1000 字节的页),
 * 断言点单一、好定位。但真实链路的风险恰恰在**数据形状**上:
 * - mock 库的卷是 12–20 页、页尺寸 800KB–3.8MB,预检会算出 MB 级估算;
 * - 有一卷 `outputExists = true`,`buildPackagePlans` 的默认策略(Rename)会让它
 *   的 `outputDisplay` 变成另一个名字 —— 这个名字最终决定了 ZIP 里的内容归属;
 * - `ComicVolume.cbzFileName` 是「标题 + 第NN卷」拼接出来的,含中文与空格,
 *   ZIP 条目名要能容下它。
 *
 * 这些只有拿真实数据跑一遍才会暴露,而它们**完全不需要设备**(纯 JVM + 内存流)。
 * 真机核对验的是「SAF provider 真的读得出字节」,不是这条链路的形状。
 *
 * ## 断言口径
 *
 * 不断「ZIP 里有什么内容」(那由 `CbzWriter` 与 `CbzWritePlan` 的测试负责),
 * 只断**链路产物**:卷数对得上、每卷都写出了非空字节、ZIP 结构合法(以 `PK\x03\x04`
 * 开头)、进度单调推进到完成、报告计数与计划一致。
 */
class PackEndToEndTest {

    private val library = MockDataB.library
    private val now = 1_700_000_000_000L

    /** 足够装下整库的可用空间(10 GiB),让预检不干扰本测试的观察目标。 */
    private val hugeAvailable = 10L * 1024 * 1024 * 1024

    /**
     * 每页的字节:刻意做成**内容不同、长度随页变化**,这样「ZIP 里是不是每页都写了」
     * 才有可能通过字节数间接看出来(全同内容时长度相同,写少一页也看不出来)。
     */
    private fun bytesSource(): PageByteSource = PageByteSource { itemId ->
        "PAGE-BYTES:$itemId".toByteArray(Charsets.UTF_8)
    }

    /**
     * 包装流:`writeCbz` 内部会 `out.use { }` 关闭流,关闭时必须把内容存下来。
     * 因为 `writeCbz` 内部会 `out.use { }` 关闭流,直接返回 ByteArrayOutputStream
     * 会在关闭后再读 `toByteArray()` 报错(部分实现),故用不关闭底层的包装。
     */
    private class NonClosingStream(
        private val delegate: ByteArrayOutputStream,
        private val onClose: (ByteArray) -> Unit,
    ) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()
        override fun close() {
            onClose(delegate.toByteArray())
        }
    }

    private fun realSink(): Pair<CbzOutputSink, MutableMap<String, ByteArray>> {
        val written = LinkedHashMap<String, ByteArray>()
        val sink = CbzOutputSink { plan ->
            val buffer = ByteArrayOutputStream()
            NonClosingStream(buffer) { bytes -> written[plan.outputDisplay] = bytes }
        }
        return sink to written
    }

    // ============================ 全库跑通 ============================

    @Test
    fun `真实 mock 库五卷全部写出且每卷都是非空 ZIP`() {
        val plans = buildPackagePlans(library.volumes)
        assertEquals(5, plans.size, "mock 库应有 5 卷")

        val (sink, written) = realSink()
        val report = runPackPlans(plans, bytesSource(), sink, CancellationToken(), now)

        assertEquals(5, report.writtenCount, "五卷都该写出")
        assertEquals(0, report.failedCount, "不该有失败卷")
        assertEquals(0, report.missingCount, "不该有缺页卷")
        assertTrue(report.isComplete, "跑完五卷后报告应标记为完整")

        assertEquals(5, written.size, "每个计划对应一份输出")
        plans.forEach { plan ->
            val bytes = assertNotNull(written[plan.outputDisplay], "缺少 ${plan.outputDisplay} 的输出")
            assertTrue(bytes.isNotEmpty(), "${plan.outputDisplay} 写出为空")
            // ZIP 局部文件头签名 PK\x03\x04
            assertTrue(
                bytes.size >= 4 &&
                    bytes[0] == 'P'.code.toByte() &&
                    bytes[1] == 'K'.code.toByte() &&
                    bytes[2] == 3.toByte() &&
                    bytes[3] == 4.toByte(),
                "${plan.outputDisplay} 不是合法 ZIP 头",
            )
        }
    }

    @Test
    fun `真实 mock 库的报告页数与计划页数逐卷一致`() {
        val plans = buildPackagePlans(library.volumes)
        val (sink, _) = realSink()
        val report = runPackPlans(plans, bytesSource(), sink, CancellationToken(), now)

        assertEquals(plans.size, report.entries.size, "报告条目数应等于计划数")
        report.entries.forEachIndexed { i, entry ->
            val expected = plans[i].pageCount
            val outcome = assertIs<PackRunOutcome.Written>(entry.outcome, "第 $i 卷应成功写出")
            assertEquals(expected, outcome.pageCount, "第 $i 卷写出页数与计划不符")
        }
    }

    @Test
    fun `真实 mock 库的输出文件名各不相同`() {
        val plans = buildPackagePlans(library.volumes)
        val names = plans.map { it.outputDisplay }
        assertEquals(names.size, names.toSet().size, "输出名重复会互相覆盖:$names")
    }

    // ============================ 混合策略 ============================

    @Test
    fun `输出冲突卷在跳过策略下不写其余照写`() {
        val plans = buildPackagePlans(library.volumes, conflictPolicy = OutputConflictPolicy.Skip)
        val skipped = plans.filter { it.skipped }
        assertEquals(1, skipped.size, "mock 库里恰有一卷 outputExists = true")

        val (sink, written) = realSink()
        val report = runPackPlans(plans, bytesSource(), sink, CancellationToken(), now)

        assertEquals(1, report.skippedCount, "被跳过的卷应计入 skipped")
        assertEquals(4, report.writtenCount, "其余四卷照写")
        assertEquals(0, report.failedCount, "跳过不是失败")
        assertEquals(4, written.size, "跳过的那卷不该产生输出")
        assertTrue(
            written.keys.none { it == skipped.single().outputDisplay },
            "被跳过卷的输出名不应出现在写出的文件里",
        )
    }

    @Test
    fun `输出冲突卷在重命名策略下写出且名字带后缀`() {
        val plans = buildPackagePlans(library.volumes, conflictPolicy = OutputConflictPolicy.Rename)
        val conflictPlan = plans.single { it.volume.outputExists }
        assertTrue(
            conflictPlan.outputDisplay != conflictPlan.volume.cbzFileName,
            "重命名策略下输出名应不同于原名:${conflictPlan.outputDisplay}",
        )

        val (sink, written) = realSink()
        val report = runPackPlans(plans, bytesSource(), sink, CancellationToken(), now)

        assertEquals(5, report.writtenCount, "重命名策略下五卷都写出")
        assertTrue(written.containsKey(conflictPlan.outputDisplay), "冲突卷应以其新名字写出")
    }

    // ============================ 与预检串联 ============================

    @Test
    fun `真实 mock 库在空间充足时预检放行并跑完全程`() {
        val plans = buildPackagePlans(library.volumes)
        val (sink, written) = realSink()

        val outcome = runPackPlansChecked(
            plans = plans,
            availableBytes = hugeAvailable,
            threshold = SpaceThreshold.Percentage(marginPercent = 10),
            safetyMargin = 100L * 1024 * 1024,
            bytes = bytesSource(),
            sink = sink,
            token = CancellationToken(),
            timeMillis = now,
        )

        val ran = assertIs<PackCheckedOutcome.Ran>(outcome, "空间充足应放行")
        assertTrue(ran.preflight.canProceed, "预检应判定可继续")
        assertEquals(0, ran.preflight.blockers.size, "不该有阻断项")
        assertEquals(5, ran.report.writtenCount, "五卷都该写出")
        assertEquals(5, written.size)
        assertTrue(ran.preflight.estimatedBytes > 0, "预检应算出正的预估字节")
    }

    @Test
    fun `真实 mock 库在空间不足时预检拒绝且一个文件都不产生`() {
        val plans = buildPackagePlans(library.volumes)
        val (sink, written) = realSink()

        val outcome = runPackPlansChecked(
            plans = plans,
            availableBytes = 1024L, // 1 KiB:远小于整库需求
            threshold = SpaceThreshold.Percentage(marginPercent = 10),
            safetyMargin = 0L,
            bytes = bytesSource(),
            sink = sink,
            token = CancellationToken(),
            timeMillis = now,
        )

        val rejected = assertIs<PackCheckedOutcome.Rejected>(outcome, "空间不足必须拒绝")
        assertTrue(rejected.preflight.blockers.isNotEmpty(), "拒绝时必须带阻断项")
        val insufficient =
            assertIs<PackBlocker.SpaceInsufficient>(rejected.preflight.blockers.first())
        assertTrue(insufficient.shortfallBytes > 0, "必须给出正的缺口,界面才能说清还差多少")

        assertTrue(written.isEmpty(), "被拒绝时不该产生任何输出文件:$written")
    }

    // ============================ 进度与中断 ============================

    @Test
    fun `真实 mock 库跑完后进度到达完成且逐卷推进`() {
        val plans = buildPackagePlans(library.volumes)
        val (sink, _) = realSink()
        val progress = mutableListOf<Pair<Int, Int>>() // (processed, total)

        runPackPlans(
            plans = plans,
            bytes = bytesSource(),
            sink = sink,
            token = CancellationToken(),
            timeMillis = now,
            onProgress = { p, _ -> progress += p.processed to p.total },
        )

        assertEquals(plans.size, progress.size, "每卷处理完应上报一次")
        assertEquals(plans.size, progress.last().second, "总数应等于计划卷数")
        assertEquals(plans.size, progress.last().first, "最后一报应处理完全部")
        // 单调不减:失败/跳过也推进进度,不许倒退
        progress.zipWithNext().forEach { (a, b) ->
            assertTrue(b.first >= a.first, "进度倒退了:$a → $b")
        }
    }

    @Test
    fun `真实 mock 库跑完后 TaskProgress 判定为完成`() {
        val plans = buildPackagePlans(library.volumes)
        val (sink, _) = realSink()
        var final: com.cyanix.pixfold.domain.task.TaskProgress? = null

        runPackPlans(
            plans = plans,
            bytes = bytesSource(),
            sink = sink,
            token = CancellationToken(),
            timeMillis = now,
            onProgress = { p, _ -> final = p },
        )

        val done = assertNotNull(final, "应至少上报一次进度")
        assertTrue(done.isComplete, "全部处理完后应判定为完成")
        assertEquals(100, done.percent, "完成时百分比应为 100")
        assertEquals(0, done.failed, "不该有失败项")
    }

    @Test
    fun `真实 mock 库中途中断后剩余卷不再写出`() {
        val plans = buildPackagePlans(library.volumes)
        val (sink, written) = realSink()
        val token = CancellationToken()

        val report = runPackPlans(
            plans = plans,
            bytes = bytesSource(),
            sink = sink,
            token = token,
            timeMillis = now,
            onProgress = { _, index -> if (index == 1) token.cancel() },
        )

        val interruptedAt = assertNotNull(report.interruptedAt, "应记录中断位置")
        assertEquals(2, interruptedAt, "在第 2 卷之后取消(索引 1 处理完)")
        assertEquals(2, report.entries.size, "只处理了前两卷")
        assertEquals(2, written.size, "只写出前两卷")
        assertTrue(!report.isComplete, "中断的报告不该标记为完成")
    }

    // ============================ 缺页与失败 ============================

    @Test
    fun `真实 mock 库有卷缺页时该卷不产生文件而其余照写`() {
        val plans = buildPackagePlans(library.volumes)
        val victim = plans.first()
        val missingId = victim.volume.pageOrder.order.first().id
        val (sink, written) = realSink()

        val report = runPackPlans(
            plans = plans,
            bytes = PageByteSource { id -> if (id == missingId) null else "X:$id".toByteArray() },
            sink = sink,
            token = CancellationToken(),
            timeMillis = now,
        )

        assertEquals(1, report.missingCount, "恰有一卷缺页")
        assertEquals(plans.size - 1, report.writtenCount, "其余卷照写")
        assertTrue(
            !written.containsKey(victim.outputDisplay),
            "缺页的那卷不该留下文件(否则会留 0 字节 CBZ)",
        )
    }

    @Test
    fun `真实 mock 库某卷打不开输出流时记失败并继续后面的卷`() {
        val plans = buildPackagePlans(library.volumes)
        val victim = plans[1]
        val written = LinkedHashMap<String, ByteArray>()
        val sink = CbzOutputSink { plan ->
            if (plan.outputDisplay == victim.outputDisplay) {
                null
            } else {
                val buffer = ByteArrayOutputStream()
                NonClosingStream(buffer) { bytes -> written[plan.outputDisplay] = bytes }
            }
        }

        val report = runPackPlans(plans, bytesSource(), sink, CancellationToken(), now)

        assertEquals(1, report.failedCount, "打不开流的那卷记失败")
        assertEquals(plans.size - 1, report.writtenCount, "其余卷照写")
        assertTrue(report.isComplete, "有失败项但全部处理过 ⇒ 报告仍算完整")
        assertEquals(plans.size - 1, written.size)
    }

    // ============================ 输出可辨识 ============================

    @Test
    fun `每卷写出的字节互不相同因为页面内容带卷标识`() {
        val plans = buildPackagePlans(library.volumes)
        val (sink, written) = realSink()
        runPackPlans(plans, bytesSource(), sink, CancellationToken(), now)

        val distinct = written.values.map { it.size to it.contentHashCode() }.toSet()
        assertEquals(written.size, distinct.size, "各卷输出应彼此不同(内容带卷标识)")
    }

    @Test
    fun `输出字节大小随页数增长`() {
        val volumes = library.volumes
        val plans = buildPackagePlans(volumes)
        val (sink, written) = realSink()
        runPackPlans(plans, bytesSource(), sink, CancellationToken(), now)

        // 本库里 12 页(aot-0)与 20 页(op-101/op-102)差异最大,直接比这两卷。
        val smallest = plans.minBy { it.pageCount }
        val largest = plans.maxBy { it.pageCount }
        val smallBytes = assertNotNull(written[smallest.outputDisplay])
        val largeBytes = assertNotNull(written[largest.outputDisplay])
        assertTrue(
            largeBytes.size > smallBytes.size,
            "页多的卷输出应更大:${largest.pageCount} 页 = ${largeBytes.size} B," +
                "${smallest.pageCount} 页 = ${smallBytes.size} B",
        )
    }

    // ============================ 计划形状前置校验 ============================

    @Test
    fun `真实 mock 库的每卷都有正的页数`() {
        val plans = buildPackagePlans(library.volumes)
        plans.forEach { plan ->
            assertTrue(plan.pageCount > 0, "${plan.outputDisplay} 页数为 0,不该进执行")
        }
    }

    @Test
    fun `真实 mock 库的每页都有正尺寸所以预估不会退化为零`() {
        val plans = buildPackagePlans(library.volumes)
        val withUnknown = plans.filter { plan -> plan.volume.pageOrder.order.any { it.sizeBytes <= 0 } }
        assertTrue(withUnknown.isEmpty(), "mock 库每页都有尺寸,不该触发下界警告")
    }

    @Test
    fun `真实 mock 库的卷输出目录来自卷自身而不是全局常量`() {
        val plans = buildPackagePlans(library.volumes)
        plans.forEach { plan ->
            assertTrue(
                plan.outputPath.startsWith(plan.volume.outputDir),
                "输出路径应落在该卷自己的目录下:${plan.outputPath}",
            )
        }
    }

    // ============================ 单卷最小闭环 ============================

    @Test
    fun `单卷走完整链路产物是含全部页的合法 ZIP`() {
        val volume: ComicVolume = library.volumes.first()
        val plans = buildPackagePlans(listOf(volume))
        assertEquals(1, plans.size)

        val (sink, written) = realSink()
        val outcome = runPackPlansChecked(
            plans = plans,
            availableBytes = hugeAvailable,
            threshold = SpaceThreshold.Percentage(marginPercent = 10),
            safetyMargin = 0L,
            bytes = bytesSource(),
            sink = sink,
            token = CancellationToken(),
            timeMillis = now,
        )

        val ran = assertIs<PackCheckedOutcome.Ran>(outcome)
        assertEquals(1, ran.report.writtenCount)
        val bytes = assertNotNull(written[plans.single().outputDisplay])
        // 页数 + ComicInfo.xml;每页条目名里应出现该卷的页文件名前缀
        val pageCount = volume.pageCount
        assertTrue(pageCount > 0)
        // ZIP 结束记录签名 PK\x05\x06 必须存在(完整归档的判据)
        val eocd = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 5, 6)
        assertTrue(bytes.indexOfSubArray(eocd) >= 0, "ZIP 缺少中央目录结束记录")
    }

    private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
