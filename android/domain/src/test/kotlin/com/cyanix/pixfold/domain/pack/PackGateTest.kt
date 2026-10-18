package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.SourcePolicy
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.storage.SpaceThreshold
import com.cyanix.pixfold.domain.task.CancellationToken
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 预检 → 执行的闸门。
 *
 * ## 这一层补的是什么缺口
 *
 * `preflightPackPlans` 早就单测通过,但它与真正的写盘之间**没有任何代码联系** ——
 * 「预检拒绝就别写」此前只是注释里的承诺。它的后果不是理论风险:`runPackPlans`
 * 一旦被调用就会逐卷 `openFor` + 写字节,预检结论被无视时用户会在已经知道
 * 空间不足的情况下看着它写到一半失败,留下若干半成品。
 *
 * 故这里把「拒绝」做成**可执行的保证**:拒绝路径上 sink 一次都不打开、字节源一次都不读。
 * 断言「一次都没打开」而不是「打开了但没写」,是因为**打开本身就已经在用户目录里
 * 创建了文件** —— 那正是 `writeOne` 里「先探测后开流」要避免的事。
 */
class PackGateTest {

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
        // 必须显式重算 pageOrder —— copy(pages = …) 不会联动它,旧 pageOrder 会让页数/尺寸
        // 改动全部失效(首轮 2 条测试红就是这个原因:预检按旧页数算,于是既不够大也不缺空间)。
        return base.copy(
            id = id,
            outputDir = "out",
            cbzFileName = "$id.cbz",
            title = id,
            pages = items,
            pageOrder = pageOrderOf(items),
        )
    }

    private fun plan(
        id: String,
        pages: Int = 2,
        pageBytes: Long = 1_000L,
        skipped: Boolean = false,
    ): VolumePackPlan = VolumePackPlan(
        volume = volume(id, pages, pageBytes),
        outputDisplay = "$id.cbz",
        pageCount = pages,
        skipped = skipped,
        skipReason = null,
        conflictPolicy = OutputConflictPolicy.Rename,
        sourcePolicy = SourcePolicy.Keep,
    )

    /** 记录被打开过的输出流 —— 打开就等于在用户目录里创建了文件。 */
    private class RecordingSink : CbzOutputSink {
        val opened = mutableListOf<String>()
        override fun openFor(plan: VolumePackPlan): OutputStream {
            opened += plan.outputDisplay
            return ByteArrayOutputStream()
        }
    }

    /** 记录被读过的 itemId。 */
    private class RecordingBytes : PageByteSource {
        val read = mutableListOf<String>()
        override fun bytesOf(itemId: String): ByteArray {
            read += itemId
            return "PAGE:$itemId".toByteArray()
        }
    }

    private val percent10 = SpaceThreshold.Percentage(marginPercent = 10)
    private val huge = 10L * 1024 * 1024 * 1024

    // ==================== 拒绝路径 ====================

    @Test
    fun `空间不足时拒绝执行且一个输出流都不打开`() {
        val sink = RecordingSink()
        val outcome = runPackPlansChecked(
            plans = listOf(plan("v1", pages = 4, pageBytes = 1_000_000L)),
            availableBytes = 1L,
            threshold = percent10,
            safetyMargin = 0L,
            bytes = RecordingBytes(),
            sink = sink,
            token = CancellationToken(),
            timeMillis = 1_700_000_000_000L,
        )
        assertIs<PackCheckedOutcome.Rejected>(outcome)
        assertEquals(emptyList(), sink.opened, "拒绝路径上一个流都不能打开")
    }

    @Test
    fun `空间不足时连页面字节都不读`() {
        val bytes = RecordingBytes()
        runPackPlansChecked(
            plans = listOf(plan("v1", pages = 4, pageBytes = 1_000_000L)),
            availableBytes = 1L,
            threshold = percent10,
            safetyMargin = 0L,
            bytes = bytes,
            sink = RecordingSink(),
            token = CancellationToken(),
            timeMillis = 1_700_000_000_000L,
        )
        assertEquals(emptyList(), bytes.read, "拒绝在打开任何东西之前发生，不该产生读取")
    }

    @Test
    fun `拒绝结果带着完整预检以便界面说清还差多少`() {
        val outcome = runPackPlansChecked(
            plans = listOf(plan("v1", pages = 4, pageBytes = 1_000_000L)),
            availableBytes = 1L,
            threshold = percent10,
            safetyMargin = 0L,
            bytes = RecordingBytes(),
            sink = RecordingSink(),
            token = CancellationToken(),
            timeMillis = 1_700_000_000_000L,
        )
        val rejected = assertIs<PackCheckedOutcome.Rejected>(outcome)
        assertTrue(rejected.preflight.blockers.isNotEmpty())
        assertTrue(rejected.preflight.estimatedBytes > 0)
    }

    // ==================== 放行路径 ====================

    @Test
    fun `空间充足时正常写出并带回预检`() {
        val sink = RecordingSink()
        val outcome = runPackPlansChecked(
            plans = listOf(plan("v1"), plan("v2")),
            availableBytes = huge,
            threshold = percent10,
            safetyMargin = 0L,
            bytes = RecordingBytes(),
            sink = sink,
            token = CancellationToken(),
            timeMillis = 1_700_000_000_000L,
        )
        val ran = assertIs<PackCheckedOutcome.Ran>(outcome)
        assertEquals(2, ran.report.writtenCount)
        assertEquals(listOf("v1.cbz", "v2.cbz"), sink.opened)
        assertTrue(ran.preflight.blockers.isEmpty())
    }

    @Test
    fun `空间临界只提示不阻止`() {
        val outcome = runPackPlansChecked(
            plans = listOf(plan("v1", pages = 2, pageBytes = 1_000L)),
            // 需求 2512 + 10% = 2763;给 3000 ⇒ 够但离安全余量还远 ⇒ Marginal(不是 Insufficient)。
            availableBytes = 3_000L,
            threshold = percent10,
            safetyMargin = 10_000_000L,
            bytes = RecordingBytes(),
            sink = RecordingSink(),
            token = CancellationToken(),
            timeMillis = 1_700_000_000_000L,
        )
        val ran = assertIs<PackCheckedOutcome.Ran>(outcome)
        assertTrue(ran.preflight.warnings.any { it is PackWarning.SpaceMarginal })
        assertEquals(1, ran.report.writtenCount)
    }

    @Test
    fun `需要 Zip64 只提示不阻止`() {
        val outcome = runPackPlansChecked(
            plans = listOf(plan("v1", pages = 70_000, pageBytes = 1L)),
            availableBytes = huge,
            threshold = percent10,
            safetyMargin = 0L,
            bytes = RecordingBytes(),
            sink = RecordingSink(),
            token = CancellationToken(),
            timeMillis = 1_700_000_000_000L,
        )
        val ran = assertIs<PackCheckedOutcome.Ran>(outcome)
        assertTrue(ran.preflight.warnings.any { it is PackWarning.Zip64Required })
        assertEquals(1, ran.report.writtenCount)
    }

    @Test
    fun `空计划不被空间临界拦住`() {
        val outcome = runPackPlansChecked(
            plans = emptyList(),
            availableBytes = 0L,
            threshold = percent10,
            safetyMargin = 10_000_000L,
            bytes = RecordingBytes(),
            sink = RecordingSink(),
            token = CancellationToken(),
            timeMillis = 1_700_000_000_000L,
        )
        assertIs<PackCheckedOutcome.Ran>(outcome)
    }

    @Test
    fun `预检放行后中断令牌仍然生效`() {
        val token = CancellationToken()
        token.cancel()
        val sink = RecordingSink()
        val outcome = runPackPlansChecked(
            plans = listOf(plan("v1")),
            availableBytes = huge,
            threshold = percent10,
            safetyMargin = 0L,
            bytes = RecordingBytes(),
            sink = sink,
            token = token,
            timeMillis = 1_700_000_000_000L,
        )
        val ran = assertIs<PackCheckedOutcome.Ran>(outcome)
        assertEquals(0, ran.report.interruptedAt)
        assertEquals(emptyList(), sink.opened)
    }
}
