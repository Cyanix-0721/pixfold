package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.plan.ExecKind
import com.cyanix.pixfold.domain.plan.ExecOutcome
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.SourcePolicy
import com.cyanix.pixfold.domain.plan.UndoScope
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.plan.undoRecordOf
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.text.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 真实写盘报告 → 界面执行报告的桥接。
 *
 * 断言口径:只断**转换规则**(哪类 outcome 变成哪类 ExecEntry、路径带不带,
 * 未处理的卷补不补),不断「ZIP 内容」—— 那归 `PackEndToEndTest`。
 */
class PackReportBridgeTest {

    private val base: ComicVolume = MockDataB.library.volumes.first()

    /** 造一卷;`outputExists` 决定它会不会覆盖掉已有文件。 */
    private fun volume(
        id: String,
        pages: Int = 2,
        outputExists: Boolean = false,
    ): ComicVolume {
        val items = (0 until pages).map { i ->
            base.pages[i % base.pages.size].copy(
                id = "$id-$i",
                sizeBytes = 1_000L,
                baseName = "${id}_$i",
            )
        }
        return base.copy(
            id = id,
            cbzFileName = "$id.cbz",
            pages = items,
            pageOrder = pageOrderOf(items),
            outputExists = outputExists,
        )
    }

    /** 用「输出已存在 + Rename」以外的路径造不跳过的计划。 */
    private fun plan(
        id: String,
        pages: Int = 2,
        outputExists: Boolean = false,
        skippedPlan: Boolean = false,
    ): VolumePackPlan {
        val v = volume(id, pages, outputExists)
        return VolumePackPlan(
            volume = v,
            outputDisplay = v.cbzFileName,
            pageCount = v.pageCount,
            skipped = skippedPlan,
            skipReason = v.cbzFileName.takeIf { skippedPlan }?.let {
                com.cyanix.pixfold.domain.text.Msg(TextKey.PackSkipOutputExists, listOf(it))
            },
            // overwritesExisting 的唯一判据要求 conflictPolicy == Overwrite 且 outputExists,
            // 故覆盖用例必须用 Overwrite 策略 —— 用 Rename 的话它本来就不算覆盖。
            conflictPolicy = if (outputExists) OutputConflictPolicy.Overwrite else OutputConflictPolicy.Rename,
            sourcePolicy = SourcePolicy.Keep,
        )
    }

    private fun written(plan: VolumePackPlan) = PackRunEntry(plan, PackRunOutcome.Written(plan.pageCount))

    // ---- 规则 1:只有 Written 算成功且带 createdPath ----

    @Test
    fun `写入成功的卷变成成功项并带输出路径`() {
        val p = plan("aot-1")
        val report = packRunAsExecutionReport(listOf(p), PackRunReport(listOf(written(p))))

        val e = report.entries.single()
        assertEquals(ExecOutcome.Succeeded, e.outcome)
        assertEquals(p.outputPath, e.createdPath)
        assertEquals(ExecKind.Package, report.kind)
    }

    @Test
    fun `缺页的卷是失败项且不带输出路径`() {
        val p = plan("aot-1")
        val outcome = PackRunOutcome.MissingPages(emptyList())
        val report = packRunAsExecutionReport(listOf(p), PackRunReport(listOf(PackRunEntry(p, outcome))))

        val e = report.entries.single()
        assertEquals(ExecOutcome.Failed, e.outcome)
        assertNull(e.createdPath, "缺页时一个字节都没写,不能带 createdPath")
    }

    @Test
    fun `写盘失败的卷是失败项且原因带出平台详情`() {
        val p = plan("aot-1")
        val outcome = PackRunOutcome.Failed("No space left on device")
        val report = packRunAsExecutionReport(listOf(p), PackRunReport(listOf(PackRunEntry(p, outcome))))

        val e = report.entries.single()
        assertEquals(ExecOutcome.Failed, e.outcome)
        assertNull(e.createdPath)
        assertEquals(TextKey.PackFailWrite, e.reason?.key)
        assertEquals(listOf<Any>("No space left on device"), e.reason?.args)
    }

    @Test
    fun `计划跳过的卷是跳过项并沿用计划原因`() {
        val p = plan("aot-1", skippedPlan = true)
        val report = packRunAsExecutionReport(listOf(p), PackRunReport(listOf(PackRunEntry(p, PackRunOutcome.Skipped))))

        val e = report.entries.single()
        assertEquals(ExecOutcome.Skipped, e.outcome)
        assertNull(e.createdPath)
        assertEquals(TextKey.PackSkipOutputExists, e.reason?.key)
    }

    @Test
    fun `缺页失败项的原因里带缺了几页`() {
        val p = plan("aot-1")
        val missing = p.volume.pageOrder.order.take(2).map {
            CbzPageEntry(itemId = it.id, fileName = it.fileName)
        }
        val report = packRunAsExecutionReport(
            listOf(p),
            PackRunReport(listOf(PackRunEntry(p, PackRunOutcome.MissingPages(missing)))),
        )

        val e = report.entries.single()
        assertEquals(TextKey.PackFailMissingPages, e.reason?.key)
        assertEquals(listOf<Any>(2), e.reason?.args)
    }

    // ---- 规则 2:覆盖项的 originalPath ----

    @Test
    fun `覆盖已有文件的成功项带 originalPath 使撤销需要警告`() {
        val p = plan("aot-1", outputExists = true)
        val report = packRunAsExecutionReport(listOf(p), PackRunReport(listOf(written(p))))

        assertNotNull(report.entries.single().originalPath, "覆盖项必须记录原位置")
        assertEquals(1, report.overwrittenCount)
        assertTrue(report.needsUndoWarning, "覆盖过用户文件,撤销前必须警告")
    }

    @Test
    fun `新建文件的成功项不带 originalPath 撤销无需警告`() {
        val p = plan("aot-1", outputExists = false)
        val report = packRunAsExecutionReport(listOf(p), PackRunReport(listOf(written(p))))

        assertNull(report.entries.single().originalPath)
        assertEquals(0, report.overwrittenCount)
        assertTrue(!report.needsUndoWarning)
    }

    @Test
    fun `覆盖项在撤销安全档下不产生删除项`() {
        val p = plan("aot-1", outputExists = true)
        val report = packRunAsExecutionReport(listOf(p), PackRunReport(listOf(written(p))))

        // 安全档:这一删会删掉用户原有的文件,故不产生撤销项。
        assertNull(undoRecordOf(report, UndoScope.ThisRun))
    }

    @Test
    fun `覆盖项在撤销危险档下产生覆盖删除项`() {
        val p = plan("aot-1", outputExists = true)
        val report = packRunAsExecutionReport(listOf(p), PackRunReport(listOf(written(p))))

        val record = assertNotNull(undoRecordOf(report, UndoScope.Everything))
        assertEquals(1, record.opCount)
        assertTrue(record.ops.single() is com.cyanix.pixfold.domain.plan.UndoOp.DeleteOverwritten)
    }

    @Test
    fun `失败项不进撤销记录`() {
        val p = plan("aot-1")
        val outcome = PackRunOutcome.Failed("boom")
        val report = packRunAsExecutionReport(listOf(p), PackRunReport(listOf(PackRunEntry(p, outcome))))

        assertNull(undoRecordOf(report, UndoScope.Everything), "失败的卷什么都没写,无可撤销")
    }

    // ---- 规则 3:中断后未处理的卷补行 ----

    @Test
    fun `中断后报告行数与计划行数一致`() {
        val plans = listOf(plan("v1"), plan("v2"), plan("v3"))
        // 只处理了第 0 卷,在第 1 卷开始前中断。
        val report = packRunAsExecutionReport(
            plans,
            PackRunReport(entries = listOf(written(plans[0])), interruptedAt = 1),
        )

        assertEquals(plans.size, report.entries.size)
    }

    @Test
    fun `中断后未处理的卷是跳过项且原因写明尚未开始`() {
        val plans = listOf(plan("v1"), plan("v2"), plan("v3"))
        val report = packRunAsExecutionReport(
            plans,
            PackRunReport(entries = listOf(written(plans[0])), interruptedAt = 1),
        )

        val notStarted = report.entries.drop(1)
        assertEquals(2, notStarted.size)
        for (e in notStarted) {
            assertEquals(ExecOutcome.Skipped, e.outcome)
            assertEquals(TextKey.PackFailNotStarted, e.reason?.key)
            assertNull(e.createdPath)
        }
    }

    @Test
    fun `中断后补的行用的是原计划的内容`() {
        val plans = listOf(plan("v1"), plan("v2"))
        val report = packRunAsExecutionReport(
            plans,
            PackRunReport(entries = emptyList(), interruptedAt = 0),
        )

        assertEquals(listOf("v1.cbz", "v2.cbz"), report.entries.map { it.label })
    }

    @Test
    fun `没有中断时不补任何行`() {
        val plans = listOf(plan("v1"), plan("v2"))
        val report = packRunAsExecutionReport(
            plans,
            PackRunReport(entries = listOf(written(plans[0]), written(plans[1]))),
        )

        assertEquals(plans.size, report.entries.size)
        assertTrue(report.entries.all { it.outcome == ExecOutcome.Succeeded })
    }

    // ---- 报告计数与顺序 ----

    @Test
    fun `报告保留计划的原始顺序`() {
        val plans = listOf(plan("v1"), plan("v2"), plan("v3"))
        val entries = listOf(written(plans[0]), PackRunEntry(plans[1], PackRunOutcome.Skipped), written(plans[2]))
        val report = packRunAsExecutionReport(plans, PackRunReport(entries))

        assertEquals(listOf("v1.cbz", "v2.cbz", "v3.cbz"), report.entries.map { it.label })
    }

    @Test
    fun `成功与跳过与失败三类计数正确`() {
        val plans = listOf(plan("v1"), plan("v2"), plan("v3"), plan("v4"))
        val report = packRunAsExecutionReport(
            plans,
            PackRunReport(
                listOf(
                    written(plans[0]),
                    PackRunEntry(plans[1], PackRunOutcome.Skipped),
                    PackRunEntry(plans[2], PackRunOutcome.Failed("boom")),
                    written(plans[3]),
                ),
            ),
        )

        assertEquals(2, report.succeededCount)
        assertEquals(1, report.skippedCount)
        assertEquals(1, report.failedCount)
        assertTrue(report.canUndo)
    }

    @Test
    fun `空计划转换出空报告`() {
        val report = packRunAsExecutionReport(emptyList(), PackRunReport(emptyList()))

        assertTrue(report.entries.isEmpty())
        assertEquals(0, report.succeededCount)
        assertTrue(!report.canUndo)
    }

    @Test
    fun `用真实的打包计划构造时不崩且行数一致`() {
        // buildPackagePlans 走的是生产路径的计划构造,顺带验证桥接对它的字段都读得通。
        val volumes = MockDataB.library.volumes
        val plans = buildPackagePlans(volumes, OutputConflictPolicy.Rename, SourcePolicy.Keep)
        val entries = plans.mapNotNull { p -> p.takeUnless { it.skipped }?.let { written(it) } }
        val report = packRunAsExecutionReport(plans, PackRunReport(entries))

        assertEquals(plans.size, report.entries.size)
    }
}
