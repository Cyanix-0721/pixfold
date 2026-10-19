package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.SourcePolicy
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.storage.SpaceThreshold
import com.cyanix.pixfold.domain.task.CancellationToken
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [executePackPlans] 的分叉守卫 —— 全部围绕**「空间未知」不能伪装成「空间充足」**。
 *
 * 这里刻意不复测 [runPackPlans] / [runPackPlansChecked] 已覆盖的东西(先探测后开流、
 * 中断、清理半成品),那些在 `PackRunnerTest` / `PackGateTest` 里。本文件只盯分叉:
 * 走了哪条路、[PackExecutionOutcome.preflight] 是 `null` 还是实例、以及两条路上
 * 其余语义是否完全一致。
 */
class PackExecutorTest {

    // ==================== 夹具 ====================

    private val base = com.cyanix.pixfold.domain.mock.MockDataB.library.volumes.first()

    private fun volume(id: String, pages: Int = 2, pageBytes: Long = 1_000L): ComicVolume {
        val items = (0 until pages).map { i ->
            base.pages[i % base.pages.size].copy(
                id = "$id-$i",
                sizeBytes = pageBytes,
                baseName = "${id}_$i",
            )
        }
        // 必须显式重算 pageOrder —— copy(pages = …) 不联动它。
        return base.copy(
            id = id,
            outputDir = "out",
            cbzFileName = "$id.cbz",
            title = id,
            pages = items,
            pageOrder = pageOrderOf(items),
        )
    }

    private fun plans(vararg volumes: ComicVolume): List<VolumePackPlan> =
        buildPackagePlans(volumes.toList(), OutputConflictPolicy.Rename, SourcePolicy.Keep)

    /** 只记调用、只进内存的 sink(不碰磁盘)。 */
    private class RecordingSink : CbzOutputSink {
        val opened = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        override fun openFor(plan: VolumePackPlan): OutputStream {
            opened += plan.volume.id
            return ByteArrayOutputStream()
        }

        override fun discardFor(plan: VolumePackPlan) {
            discarded += plan.volume.id
        }
    }

    private val threshold = SpaceThreshold.Percentage(10)

    private fun bytesOf(ok: Boolean = true) = PageByteSource { if (ok) ByteArray(16) { 7 } else null }

    private fun run(
        plans: List<VolumePackPlan>,
        availableBytes: Long?,
        sink: CbzOutputSink = RecordingSink(),
        token: CancellationToken = CancellationToken(),
        bytes: PageByteSource = bytesOf(),
        onProgress: (com.cyanix.pixfold.domain.task.TaskProgress, Int) -> Unit = { _, _ -> },
    ) = executePackPlans(
        plans = plans,
        availableBytes = availableBytes,
        threshold = threshold,
        safetyMargin = 0L,
        bytes = bytes,
        sink = sink,
        token = token,
        timeMillis = 1_000L,
        onProgress = onProgress,
    )

    // ==================== 空间未知:不做预检、不阻断 ====================

    @Test
    fun `空间未知时不做预检 preflight 为 null`() {
        val ran = assertIs<PackExecutionOutcome.Ran>(run(plans(volume("v1")), availableBytes = null))
        assertNull(ran.preflight, "空间未知时 preflight 必须是 null —— 不能冒充「预检通过」")
        assertEquals(1, ran.report.writtenCount)
    }

    @Test
    fun `空间未知时仍然真的写出而不是跳过`() {
        val sink = RecordingSink()
        run(plans(volume("v1")), availableBytes = null, sink = sink)
        assertEquals(listOf("v1"), sink.opened, "空间未知也要打开流 —— 不能当成空间不足")
        assertTrue(sink.discarded.isEmpty())
    }

    @Test
    fun `空间未知时缺页仍不打开流`() {
        // 这条保证属于 runPackPlans;null 分支复用它,此处断一次防止将来改成自己实现。
        val sink = RecordingSink()
        val ran = assertIs<PackExecutionOutcome.Ran>(
            run(plans(volume("v1")), availableBytes = null, sink = sink, bytes = bytesOf(ok = false)),
        )
        assertEquals(1, ran.report.missingCount)
        assertTrue(sink.opened.isEmpty(), "缺页时一个流都不该打开")
    }

    // ==================== 空间已知:走预检 ====================

    @Test
    fun `空间充足时走预检并执行 preflight 非 null`() {
        val ran = assertIs<PackExecutionOutcome.Ran>(run(plans(volume("v1")), availableBytes = 1_000_000_000L))
        val preflight = assertNotNull(ran.preflight, "空间已知时必须带预检结果")
        assertTrue(preflight.canProceed)
        assertEquals(1, ran.report.writtenCount)
    }

    @Test
    fun `空间不足时拒绝且一个流都不打开`() {
        val sink = RecordingSink()
        val rejected = assertIs<PackExecutionOutcome.Rejected>(run(plans(volume("v1")), availableBytes = 1L, sink = sink))
        assertTrue(rejected.preflight.blockers.isNotEmpty())
        assertTrue(sink.opened.isEmpty(), "拒绝时不允许打开任何输出流")
    }

    @Test
    fun `availableBytes 为 0 是已知且不足 不是未知`() {
        // 0 与 null 语义完全不同:0 走预检必被阻断。
        assertIs<PackExecutionOutcome.Rejected>(run(plans(volume("v1")), availableBytes = 0L))
    }

    // ==================== 两条路语义一致 ====================

    @Test
    fun `中断在两条路上都在卷间生效`() {
        val token = CancellationToken()
        token.cancel()
        for (available in listOf(1_000_000_000L, null)) {
            val ran = assertIs<PackExecutionOutcome.Ran>(
                run(plans(volume("a"), volume("b")), available, token = token),
            )
            assertFalse(ran.report.isComplete, "availableBytes=$available 应中断")
            assertEquals(0, ran.report.processedCount)
        }
    }

    @Test
    fun `进度回调在两条路上都被调用且序列相同`() {
        for (available in listOf(1_000_000_000L, null)) {
            val seen = mutableListOf<Int>()
            run(plans(volume("a"), volume("b")), available, onProgress = { _, index -> seen += index })
            assertEquals(listOf(0, 1), seen, "availableBytes=$available 的进度序列")
        }
    }

    @Test
    fun `空计划在两条路上都不产生文件也不崩`() {
        for (available in listOf(1_000_000_000L, null)) {
            val sink = RecordingSink()
            val outcome = run(emptyList(), available, sink = sink)
            val report = when (outcome) {
                is PackExecutionOutcome.Ran -> outcome.report
                is PackExecutionOutcome.Rejected -> error("空计划不该被拒绝")
            }
            assertEquals(0, report.processedCount)
            assertTrue(sink.opened.isEmpty())
        }
    }
}
