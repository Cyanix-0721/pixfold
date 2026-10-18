package com.cyanix.pixfold.domain.plan

import com.cyanix.pixfold.domain.comic.ComicInfo
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.NameProposal
import com.cyanix.pixfold.domain.naming.OpStatus
import com.cyanix.pixfold.domain.naming.RenameOp
import com.cyanix.pixfold.domain.pack.CbzWriter
import com.cyanix.pixfold.domain.pack.PageByteSource
import com.cyanix.pixfold.domain.pack.buildCbzWritePlan
import com.cyanix.pixfold.domain.pack.writeCbz
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 打包流水线的**跨层不变式**(W8 边界加固)。
 *
 * ## 为什么单层测试不够
 *
 * 到 W7 为止,每一层各自被钉死了:
 *  - `PackagePlanTest` 管「计划说什么」(`PackPackPages(16)`);
 *  - `ExecutionReportTest` 管「报告说什么」(`Succeeded` + `createdPath`);
 *  - `CbzWritePlanTest` / `CbzWriterTest` 管「字节写成什么」(16 条页条目)。
 *
 * 但**没有任何一条用例把三层连起来**。于是下面这种事故可以全绿通过:计划宣称打包 16 页、
 * 报告说成功、归档里实际只有 15 张 —— 每一层各自都"对",成品却是坏的,而阅读器不会报错。
 * 本文件断言的是**层与层之间的等式**,专门堵这一类。
 *
 * ## 与既有用例的分工(刻意不重复)
 *
 *  - 单卷的空卷 / 无页 / 空输出目录已在 `ExecutionReportTest` 覆盖,这里只补**它漏掉的边界**
 *    (纯空白输出目录 —— 见下);
 *  - 3×2×2 的危险动作矩阵已在 `OperationPlanTest` 覆盖,这里改用**行数与计数**做矩阵;
 *  - 归档的字节顺序 / 压缩方式已在 `CbzWriterTest` 覆盖,这里只数**条目条数**是否等于宣称页数。
 */
class PackingPipelineTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val aot2 = library.volumes.first { it.id == "aot-2" }
    private val op102 = library.volumes.first { it.id == "op-102" }
    private val time = 1_700_000_000_000L
    private val allVolumes = listOf(aot1, aot2, op102)

    /** 一个对任何 id 都给得出字节的源(本文件只关心条数,不关心内容)。 */
    private val anyBytes = PageByteSource { id -> "bytes-of-$id".toByteArray(Charsets.UTF_8) }

    private fun List<Msg>.line(key: TextKey): Msg =
        firstOrNull { it.key == key } ?: error("计划里没有 $key 这一行：${map { it.key }}")

    private fun readBack(bytes: ByteArray): List<Pair<ZipEntry, ByteArray>> {
        val result = mutableListOf<Pair<ZipEntry, ByteArray>>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                result += e to zip.readBytes()
            }
        }
        return result
    }

    // ---- 不变式 1:计划宣称的页数 == 归档里实际的页条目数 ----

    @Test
    fun `the page count the plan announces equals the number of page entries actually written`() {
        // 这是本文件存在的首要理由。计划页对用户说"打包 16 页";归档里就必须有 16 条图,
        // 多一条少一条都是坏成品,而**三层各自的单测都不会发现**。
        val volume = aot1
        val promised = buildPackagePlans(listOf(volume))
            .single()
            .describe()
            .line(TextKey.PackPackPages)
            .args
            .single() as Int

        val plan = buildCbzWritePlan(volume)
        val out = ByteArrayOutputStream()
        val result = writeCbz(plan, anyBytes, out, time)
        val entries = readBack(out.toByteArray())

        assertEquals(promised, entries.size - 1, "归档里除 ComicInfo.xml 外应有 $promised 条图")
        assertEquals(listOf(CbzWriter.COMIC_INFO_ENTRY) + plan.pages.map { it.fileName }, result.writtenEntries)
        assertEquals(CbzWriter.COMIC_INFO_ENTRY, entries.first().first.name)
    }

    @Test
    fun `the pack plan and the write plan agree on the page count for every mock volume`() {
        // 两个"页数"来自不同计算:`VolumePackPlan.pageCount` 抄自 `ComicVolume.pageCount`
        // (= 页序长度),`CbzWritePlan.pageCount` 数的是 `renumberPages(volume)` 的结果。
        // 只要其中一处改成读 `pages` 字段(而非页序),人工调过页序的卷就会两处不一致。
        library.volumes.forEach { v ->
            assertEquals(
                buildPackagePlans(listOf(v)).single().pageCount,
                buildCbzWritePlan(v).pageCount,
                "卷 ${v.id} 的计划页数与写入计划页数必须同源",
            )
        }
    }

    @Test
    fun `a volume whose page order was edited still announces the edited count`() {
        // 反向守卫:两个页数不能是"都恒等于 pages.size"而碰巧相等 —— 页序删掉一张后,
        // 计划宣称的页数必须跟着变,归档也必须跟着少一条。
        val trimmed = aot1.copy(pageOrder = aot1.pageOrder.copy(order = aot1.pageOrder.order.drop(1)))
        val promised = buildPackagePlans(listOf(trimmed))
            .single()
            .describe()
            .line(TextKey.PackPackPages)
            .args
            .single() as Int
        assertEquals(aot1.pageCount - 1, promised)

        val out = ByteArrayOutputStream()
        writeCbz(buildCbzWritePlan(trimmed), anyBytes, out, time)
        assertEquals(promised, readBack(out.toByteArray()).size - 1)
    }

    // ---- 不变式 2:能执行成功的项,字节层真的写得出来 ----

    @Test
    fun `every operation reported as succeeded can actually be written byte for byte`() {
        val plans = buildPackagePlans(allVolumes, OutputConflictPolicy.Rename, SourcePolicy.Keep)
        val report = executePlan(operationPlanOf(plans))
        val packPlanById = plans.associateBy { it.volume.id }

        val succeeded = report.entries.filter { it.outcome == ExecOutcome.Succeeded }
        assertTrue(succeeded.isNotEmpty(), "前置:这组卷里应至少有一项成功")

        succeeded.forEach { entry ->
            val packPlan = assertNotNull(packPlanById[entry.id], "成功项 ${entry.id} 必须对得上一个计划项")
            val volume = packPlan.volume
            val written = writeCbz(buildCbzWritePlan(volume), anyBytes, ByteArrayOutputStream(), time)
            assertTrue(written.ok, "报告说成功的卷 ${volume.id} 却写不出完整归档:缺 ${written.missing}")
            // createdPath 走的是 outputPath(= 冲突改名后的名字),不是 volume.cbzFileName。
            assertEquals(packPlan.outputPath, entry.createdPath)
        }
    }

    @Test
    fun `a volume that failed to execute is one that cannot satisfy the write plan either`() {
        // 失败项不得与"可写"共存:否则页面会同时显示"失败"与"已写入"。
        val noPages = aot1.copy(pageOrder = aot1.pageOrder.copy(order = emptyList()))
        val report = executePlan(operationPlanOf(buildPackagePlans(listOf(noPages, aot2))))

        val failed = report.entries.single { it.id == noPages.id }
        assertEquals(ExecOutcome.Failed, failed.outcome)
        assertNull(failed.createdPath)
        assertEquals(0, buildCbzWritePlan(noPages).pageCount, "失败卷的写入计划确实是空的")

        val ok = report.entries.single { it.id == aot2.id }
        assertEquals(ExecOutcome.Succeeded, ok.outcome)
        assertNotNull(ok.createdPath)
    }

    // ---- 不变式 3:计划行 ↔ 报告行逐行对齐(全策略矩阵) ----

    @Test
    fun `plan rows and report rows line up for every policy pair`() {
        OutputConflictPolicy.entries.forEach { conflict ->
            SourcePolicy.entries.forEach { source ->
                val plan = operationPlanOf(buildPackagePlans(allVolumes, conflict, source))
                val report = executePlan(plan)
                assertEquals(plan.ops.size, report.entries.size, "冲突=$conflict 源=$source:行数必须相等")
                assertEquals(
                    plan.ops.map { it.id },
                    report.entries.map { it.id },
                    "冲突=$conflict 源=$source:报告行必须与计划行同序同 id",
                )
                assertEquals(
                    plan.ops.map { it.label },
                    report.entries.map { it.label },
                    "冲突=$conflict 源=$source:标签必须逐行一致(用户靠它认行)",
                )
            }
        }
    }

    @Test
    fun `the three outcome counts add up to the plan total for every policy pair`() {
        // 计数是页面上"成功 N / 跳过 N / 失败 N"的唯一来源。若某个分支漏加,
        // 用户看到的三个数加起来对不上总数,却不会触发任何单层断言。
        OutputConflictPolicy.entries.forEach { conflict ->
            SourcePolicy.entries.forEach { source ->
                listOf(false, true).forEach { clean ->
                    val plan = operationPlanOf(buildPackagePlans(allVolumes, conflict, source), clean)
                    val report = executePlan(plan)
                    assertEquals(
                        plan.total,
                        report.succeededCount + report.skippedCount + report.failedCount,
                        "冲突=$conflict 源=$source 清理=$clean:三类计数之和必须等于计划总数",
                    )
                    assertEquals(plan.skipped, report.skippedCount, "跳过数的两条来源必须一致")
                }
            }
        }
    }

    @Test
    fun `skipping every conflicting volume leaves only the writable ones runnable`() {
        // 边界:跳过策略下 op-102(outputExists)退出执行,但**不得**连带影响没有冲突的卷。
        val plan = operationPlanOf(buildPackagePlans(allVolumes, OutputConflictPolicy.Skip))
        val report = executePlan(plan)
        assertEquals(1, plan.skipped, "只有 op-102 有冲突")
        assertEquals(1, report.skippedCount)
        assertEquals(2, report.succeededCount)
        assertEquals(0, report.failedCount, "跳过不该被算成失败")
        assertEquals(op102.id, report.entries.single { it.outcome == ExecOutcome.Skipped }.id)
    }

    // ---- 不变式 4:撤销记录只指向报告说"已创建"的那些路径 ----

    @Test
    fun `package undo targets exactly the paths the report says were created`() {
        val plans = buildPackagePlans(allVolumes, OutputConflictPolicy.Skip)
        val plan = operationPlanOf(plans)
        val report = executePlan(plan)
        val record = assertNotNull(undoRecordFrom(plan, report), "有成功项就必须有撤销记录")

        val created = report.entries.mapNotNull { it.createdPath }.toSet()
        val targeted = record.ops.filterIsInstance<UndoOp.DeleteCreated>().map { it.path }.toSet()
        assertEquals(created, targeted, "撤销要删的必须**恰好**是报告里已创建的,不多不少")
        assertEquals(report.succeededCount, record.ops.size)
    }

    @Test
    fun `rename undo targets exactly the names the report says were created`() {
        val ops = listOf(
            renameOp("r1", "改后-1.jpg", OpStatus.Ok),
            renameOp("r2", "改后-2.jpg", OpStatus.Skipped),
            renameOp("r3", "改后-3.jpg", OpStatus.Ok),
        )
        val plan = operationPlanOfRenames(ops)
        val report = executePlan(plan)
        val record = assertNotNull(undoRecordFrom(plan, report))

        val created = report.entries.mapNotNull { it.createdPath }.toSet()
        // 只看 from:它恒为"改名后的名字"(= createdPath),是回滚的**起点**。
        // to 是回滚的**终点**(原文件名),与 from 不同源,故由 UndoAlignmentTest 单独钉死
        // (R1 修复前两者相等,那条用例当时只能如实记录缺陷)。
        val targeted = record.ops.filterIsInstance<UndoOp.RestoreRenamed>().map { it.from }.toSet()
        assertEquals(created, targeted)
        assertEquals(2, record.ops.size, "跳过项不进撤销记录")
    }

    @Test
    fun `a plan whose every operation fails yields no undo record at all`() {
        // 边界:全部失败 ⇒ 没有可回滚的东西 ⇒ null(而不是空记录)。页面据此显示"无可撤销"。
        val plan = operationPlanOfRenames(listOf(renameOp("r1", "", OpStatus.Ok)))
        val report = executePlan(plan)
        assertEquals(ExecOutcome.Failed, report.entries.single().outcome)
        assertNull(undoRecordFrom(plan, report))
        assertNull(undoRecordOf(report))
    }

    @Test
    fun `an emptied plan produces an empty report and no undo record`() {
        // 边界:全部卷被取消勾选 ⇒ 计划为空。空计划不该产出空撤销记录。
        val excluded = library.volumes.map { it.copy(included = false) }
        val plans = buildPackagePlans(excluded)
        assertTrue(plans.isEmpty(), "未勾选的卷不进计划")
        val plan = operationPlanOf(plans)
        val report = executePlan(plan)
        assertEquals(0, plan.total)
        assertEquals(0, report.entries.size)
        assertNull(undoRecordFrom(plan, report))
    }

    // ---- 边界:纯空白与空串必须同等对待 ----

    @Test
    fun `a whitespace only output dir fails exactly like an empty one`() {
        // `ExecutionReportTest` 只测了 `""` —— 而 `isBlank()` 与 `isEmpty()` 在 `""` 上**同解**,
        // 于是把实现改成 `isEmpty()` 不会有任何用例报警。用户手滑敲进几个空格时,
        // 路径会拼成 `"   /进击的巨人 第01卷.cbz"`,报告却显示成功。
        listOf("", " ", "   ", "\t", "\n").forEach { blank ->
            val e = executePackagePlans(buildPackagePlans(listOf(aot1.copy(outputDir = blank))))
                .entries
                .single()
            assertEquals(ExecOutcome.Failed, e.outcome, "输出目录=${blank.length} 个空白字符时应判失败")
            assertEquals(Msg(TextKey.PackFailEmptyOutputDir), e.reason)
            assertNull(e.createdPath, "失败项不得声称创建了输出")
        }
    }

    @Test
    fun `an empty writer is not written in either the xml or the plan line`() {
        // 两侧必须对"什么算没写"给出一致答案:XML 给自闭合空标签,计划行给占位词。
        // 若一侧改成 `isBlank`、另一侧仍是 `isEmpty`,用户会看到"计划说写了、XML 里是空的"。
        val v = aot1.copy(writer = "")
        assertTrue(ComicInfo.build(v).contains("<Writer />"), "空作者应产出自闭合标签")
        assertEquals(
            Msg(TextKey.PackValueEmpty),
            buildPackagePlans(listOf(v)).single().describe().line(TextKey.PackWriteComicInfo).args[3],
        )
    }

    @Test
    fun `a whitespace only writer is written verbatim on both sides`() {
        // 反向边界:空白**是**用户输入的值,不是"没填"。两侧都按"有值"处理 ——
        // 这是 `isNullOrEmpty` 而非 `isBlank` 的刻意选择,钉住它以免被顺手"优化"掉。
        val v = aot1.copy(writer = "   ")
        assertTrue(ComicInfo.build(v).contains("<Writer>   </Writer>"), "空白作者应原样写入")
        assertEquals(
            "   ",
            buildPackagePlans(listOf(v)).single().describe().line(TextKey.PackWriteComicInfo).args[3],
            "计划行也必须原样显示该值,而不是占位词",
        )
    }

    // ---- 边界:单页卷 ----

    @Test
    fun `a single page volume packs into exactly one page entry`() {
        val pages = listOf(aot1.pages.first())
        val single = aot1.copy(pages = pages, pageOrder = pageOrderOf(pages))
        val plan = buildPackagePlans(listOf(single)).single()
        assertEquals(1, plan.pageCount)

        val out = ByteArrayOutputStream()
        val written = writeCbz(buildCbzWritePlan(single), anyBytes, out, time)
        val entries = readBack(out.toByteArray())
        assertEquals(2, entries.size, "ComicInfo.xml + 唯一一页")
        assertEquals("001.jpg", entries[1].first.name)
        assertEquals(1, written.writtenEntries.size - 1)
        assertEquals(plan.pageCount, written.writtenEntries.size - 1)
    }

    @Test
    fun `a one volume plan is not confused with a one page volume`() {
        // 边界反向守卫:计划项数与页数是两个量,不该在只有一卷时被当成同一个。
        val plan = operationPlanOf(buildPackagePlans(listOf(aot1)))
        assertEquals(1, plan.total)
        assertTrue(aot1.pageCount > 1, "前置:这一卷不止一页")
    }

    private fun renameOp(id: String, finalName: String, status: OpStatus): RenameOp = RenameOp(
        proposal = NameProposal(image = aot1.pages.first().copy(id = id), proposedName = finalName),
        status = status,
        reason = if (status == OpStatus.Skipped) Msg(TextKey.WarnDuplicate) else null,
    )
}
