package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.SourcePolicy
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 打包执行编排（D4b 的实质内核，2026-10-19）。
 *
 * ## 这一层补的是什么缺口
 *
 * D3 已交付 [buildCbzWritePlan] 与 [writeCbz] 两块拼图，D2a 交付了「从 SAF 读字节」的端口，
 * 但**没有东西把它们按卷串起来**。散着调用的后果是三件真实会出的事：
 *
 * 1. **半成品落盘**：[writeCbz] 缺页时「一个字节都不写」，但那时目标文件**已经创建**了
 *    （平台层 `createDocument` 先于写入）⇒ 磁盘上留下一个 0 字节 CBZ。阅读器打开它不报错，
 *    只显示空白 —— 用户看不出这是失败。
 * 2. **中断只在卷间生效**：若一半卷写完了才发现用户点了取消，前面那些卷已经落盘，
 *    而报告说「已取消」，用户无从知道到底写完了几卷。
 * 3. **进度不推进**：没有编排就没有 `TaskProgress` 的来源，进度条只能靠猜。
 *
 * ## 归责边界
 *
 * - **归这里**：按卷顺序执行、**先探测后开流**（全有或全无落到磁盘）、中断检查点、
 *   半成品清理的**调用决策**、进度与报告的数据结构；
 * - **不归这里**：字节从哪来（[PageByteSource]）、流怎么开（[CbzOutputSink]）。
 *   两者都是 `fun interface`，平台层用 SAF 实现、测试用内存实现。
 */
class PackRunnerTest {

    // ==================== 夹具 ====================

    /** 一个只有最小字段的卷；其余字段用 mock 默认值撑起来。 */
    private fun volume(id: String, pages: Int = 2): ComicVolume {
        val base = com.cyanix.pixfold.domain.mock.MockDataB.library.volumes.first()
        // item id 必须带卷前缀:否则两个卷共享同一批 id,「让 A 卷缺页」会连 B 卷一起缺 ——
        // 那正是首轮「一卷缺页不影响后续卷」失败的原因(断言写对了,夹具错了)。
        val items = base.pages.take(pages).map { it.copy(id = "$id-${it.id}") }
        return base.copy(
            id = id,
            cbzFileName = "$id.cbz",
            outputDir = "out",
            pages = items,
            pageOrder = com.cyanix.pixfold.domain.sort.pageOrderOf(items),
        )
    }

    private fun plan(
        id: String,
        pages: Int = 2,
        skipped: Boolean = false,
    ): VolumePackPlan {
        val v = volume(id, pages)
        return VolumePackPlan(
            volume = v,
            outputDisplay = "${v.cbzFileName}",
            pageCount = v.pageCount,
            skipped = skipped,
            skipReason = null,
            conflictPolicy = OutputConflictPolicy.Rename,
            sourcePolicy = SourcePolicy.Keep,
        )
    }

    /** 内存实现：把每卷写进一个独立缓冲，并记录 open / discard 顺序。 */
    private class RecordingSink(
        private val failOnOpen: Set<String> = emptySet(),
        private val throwOnWrite: Set<String> = emptySet(),
    ) : CbzOutputSink {
        val opened = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val written = mutableMapOf<String, ByteArray>()

        override fun openFor(plan: VolumePackPlan): OutputStream? {
            opened += plan.outputDisplay
            if (plan.outputDisplay in failOnOpen) return null
            val buf = ByteArrayOutputStream()
            if (plan.outputDisplay in throwOnWrite) {
                return object : OutputStream() {
                    override fun write(b: Int) = throw IOException("模拟写盘失败")
                    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("模拟写盘失败")
                }
            }
            return object : OutputStream() {
                override fun write(b: Int) {
                    buf.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    buf.write(b, off, len)
                }

                override fun close() {
                    written[plan.outputDisplay] = buf.toByteArray()
                    buf.close()
                }
            }
        }

        override fun discardFor(plan: VolumePackPlan) {
            discarded += plan.outputDisplay
        }
    }

    /** 按 itemId 返回固定字节；[missing] 里的 id 返回 null。 */
    private fun bytesSource(missing: Set<String> = emptySet()) = PageByteSource { itemId ->
        if (itemId in missing) null else "PAGE:$itemId".toByteArray()
    }

    private val now = 1_700_000_000_000L

    // ==================== 正常路径 ====================

    @Test
    fun `单卷正常写入`() {
        val p = plan("v1")
        val sink = RecordingSink()
        val report = runPackPlans(listOf(p), bytesSource(), sink, CancellationToken(), now)

        assertEquals(1, report.entries.size)
        val written = assertIs<PackRunOutcome.Written>(report.entries[0].outcome)
        assertEquals(2, written.pageCount)
        assertEquals(listOf("v1.cbz"), sink.opened)
        assertTrue(sink.written.containsKey("v1.cbz"))
        assertTrue(report.isComplete)
    }

    @Test
    fun `多卷按计划顺序执行`() {
        val plans = listOf(plan("a"), plan("b"), plan("c"))
        val sink = RecordingSink()
        runPackPlans(plans, bytesSource(), sink, CancellationToken(), now)
        assertEquals(listOf("a.cbz", "b.cbz", "c.cbz"), sink.opened)
    }

    @Test
    fun `写出的字节是合法 ZIP`() {
        // 不必解析内容,只要认出 ZIP 头就够了:领域层不该知道 CBZ 的语义,
        // 但必须真的把 CbzWriter 的输出交到 sink,而不是空流。
        val sink = RecordingSink()
        runPackPlans(listOf(plan("v1")), bytesSource(), sink, CancellationToken(), now)
        val bytes = sink.written.getValue("v1.cbz")
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
    }

    @Test
    fun `空计划直接完成`() {
        val report = runPackPlans(emptyList(), bytesSource(), RecordingSink(), CancellationToken(), now)
        assertTrue(report.entries.isEmpty())
        assertTrue(report.isComplete)
    }

    // ==================== 全有或全无：缺页不能留下半成品 ====================

    /** ⭐ 核心：缺页时**根本不该打开输出流** —— 否则磁盘上留下 0 字节 CBZ。 */
    @Test
    fun `缺页时不打开输出流避免留下零字节文件`() {
        val p = plan("v1")
        val missingId = p.volume.pageOrder.order.first().id
        val sink = RecordingSink()

        val report = runPackPlans(listOf(p), bytesSource(missing = setOf(missingId)), sink, CancellationToken(), now)

        assertIs<PackRunOutcome.MissingPages>(report.entries[0].outcome)
        assertEquals(emptyList(), sink.opened, "取不到页时一个字节都不该写,流也不该开")
        assertTrue(sink.written.isEmpty())
    }

    @Test
    fun `缺页报告保留计划顺序且只列缺的那些`() {
        val p = plan("v1", pages = 3)
        val order = p.volume.pageOrder.order
        val sink = RecordingSink()
        val report = runPackPlans(
            listOf(p),
            bytesSource(missing = setOf(order[0].id, order[2].id)),
            sink,
            CancellationToken(),
            now,
        )
        val missing = assertIs<PackRunOutcome.MissingPages>(report.entries[0].outcome)
        assertEquals(listOf(order[0].id, order[2].id), missing.missing.map { it.itemId })
    }

    @Test
    fun `一卷缺页不影响后续卷`() {
        val bad = plan("bad")
        val missingId = bad.volume.pageOrder.order.first().id
        val sink = RecordingSink()
        val report = runPackPlans(
            listOf(bad, plan("good")),
            bytesSource(missing = setOf(missingId)),
            sink,
            CancellationToken(),
            now,
        )
        assertIs<PackRunOutcome.MissingPages>(report.entries[0].outcome)
        assertIs<PackRunOutcome.Written>(report.entries[1].outcome)
        assertEquals(listOf("good.cbz"), sink.opened)
    }

    // ==================== 跳过与失败 ====================

    @Test
    fun `计划里标记跳过的卷不打开输出流`() {
        val sink = RecordingSink()
        val report = runPackPlans(listOf(plan("skipped", skipped = true)), bytesSource(), sink, CancellationToken(), now)
        assertIs<PackRunOutcome.Skipped>(report.entries[0].outcome)
        assertEquals(emptyList(), sink.opened)
    }

    @Test
    fun `打不开输出流时记为失败`() {
        val sink = RecordingSink(failOnOpen = setOf("v1.cbz"))
        val report = runPackPlans(listOf(plan("v1")), bytesSource(), sink, CancellationToken(), now)
        assertIs<PackRunOutcome.Failed>(report.entries[0].outcome)
    }

    /** ⭐ 核心：写盘中途失败必须请求清理半成品。 */
    @Test
    fun `写盘过程中抛异常时记为失败并清理半成品`() {
        val sink = RecordingSink(throwOnWrite = setOf("v1.cbz"))
        val report = runPackPlans(listOf(plan("v1")), bytesSource(), sink, CancellationToken(), now)

        assertIs<PackRunOutcome.Failed>(report.entries[0].outcome)
        assertEquals(listOf("v1.cbz"), sink.discarded, "写了一半的文件必须被丢弃,不能留在目标目录")
    }

    @Test
    fun `成功写完后不请求清理`() {
        val sink = RecordingSink()
        runPackPlans(listOf(plan("v1")), bytesSource(), sink, CancellationToken(), now)
        assertEquals(emptyList(), sink.discarded)
    }

    // ==================== 中断 ====================

    /** ⭐ 核心：中断只在卷间检查 —— 至少不能把已经开始写的那卷砍成半成品。 */
    @Test
    fun `中断后剩余的卷不再执行`() {
        val token = CancellationToken()
        val sink = RecordingSink()
        val plans = listOf(plan("a"), plan("b"), plan("c"))

        val report = runPackPlans(
            plans,
            bytesSource(),
            sink,
            token,
            now,
            onProgress = { _, index -> if (index == 0) token.cancel() },
        )

        assertEquals(listOf("a.cbz"), sink.opened)
        assertEquals(1, report.entries.size)
        assertFalse(report.isComplete)
        assertEquals(1, report.interruptedAt, "第 2 卷开始前停下,中断点是索引 1")
    }

    @Test
    fun `一开始就中断则一卷都不写`() {
        val token = CancellationToken()
        token.cancel()
        val sink = RecordingSink()
        val report = runPackPlans(listOf(plan("a"), plan("b")), bytesSource(), sink, token, now)

        assertEquals(emptyList(), sink.opened)
        assertEquals(0, report.interruptedAt)
    }

    @Test
    fun `中断的卷不计入失败`() {
        // 用户主动取消不是失败 —— 报告里把它当失败会让用户以为出了问题。
        val token = CancellationToken()
        token.cancel()
        val report = runPackPlans(listOf(plan("a")), bytesSource(), RecordingSink(), token, now)
        assertEquals(0, report.failedCount)
        assertEquals(0, report.writtenCount)
    }

    // ==================== 进度 ====================

    @Test
    fun `每处理完一卷上报一次进度且失败项也推进`() {
        val seen = mutableListOf<TaskProgress>()
        val plans = listOf(plan("a"), plan("bad", skipped = false), plan("c"))
        val sink = RecordingSink(failOnOpen = setOf("bad.cbz"))
        runPackPlans(plans, bytesSource(), sink, CancellationToken(), now) { progress, _ ->
            seen += progress
        }

        assertEquals(3, seen.size)
        assertEquals(listOf(1, 2, 3), seen.map { it.processed }, "失败的卷也要推进进度,否则进度条卡住")
        assertEquals(1, seen.last().failed)
        assertEquals(100, seen.last().percent)
        assertTrue(seen.last().isComplete)
    }

    @Test
    fun `跳过的卷也算处理完不阻塞进度`() {
        val seen = mutableListOf<TaskProgress>()
        runPackPlans(listOf(plan("skipped", skipped = true)), bytesSource(), RecordingSink(), CancellationToken(), now) { p, _ ->
            seen += p
        }
        assertEquals(100, seen.single().percent, "跳过的卷是「已处理」,不该让进度条停在 0%")
    }

    @Test
    fun `中断时不补发完成进度`() {
        val token = CancellationToken()
        token.cancel()
        val seen = mutableListOf<TaskProgress>()
        runPackPlans(listOf(plan("a")), bytesSource(), RecordingSink(), token, now) { p, _ -> seen += p }
        assertTrue(seen.isEmpty(), "一卷都没处理就中断,不该有进度事件")
    }

    // ==================== 报告汇总 ====================

    @Test
    fun `报告按结果分类计数`() {
        val plans = listOf(plan("ok"), plan("skip", skipped = true), plan("bad"))
        val sink = RecordingSink(failOnOpen = setOf("bad.cbz"))
        val report = runPackPlans(plans, bytesSource(), sink, CancellationToken(), now)

        assertEquals(1, report.writtenCount)
        assertEquals(1, report.skippedCount)
        assertEquals(1, report.failedCount)
        assertEquals(0, report.missingCount)
    }

    @Test
    fun `报告保留每卷的计划以便追溯输出路径`() {
        val p = plan("v1")
        val report = runPackPlans(listOf(p), bytesSource(), RecordingSink(), CancellationToken(), now)
        assertEquals("out/v1.cbz", report.entries[0].plan.outputPath)
    }

    @Test
    fun `时间戳必须显式给定`() {
        // 可复现性:CBZ 内条目的修改时间来自这里,默认取「现在」会让同一份输入产出不同字节。
        val err = kotlin.runCatching {
            runPackPlans(listOf(plan("v1")), bytesSource(), RecordingSink(), CancellationToken(), 0L)
        }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException, "timeMillis = 0 应当立刻拒绝,而不是写到一半")
    }
}
