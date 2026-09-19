package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.NameProposal
import com.pixfold.d1.domain.naming.OpStatus
import com.pixfold.d1.domain.naming.RenameOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 执行报告与撤销(规格 §9 / 验收第 17 项)。
 *
 * 第 17 项要「成功 / 跳过 / 失败」**三类**。故测试的重点是:
 * 三类都**可达**(不是只有成功),且每类都带**可读原因**。
 */
class ExecutionReportTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val op102 = library.volumes.first { it.id == "op-102" }

    // ---- 三类结果 ----

    @Test
    fun `successful packing reports the created path`() {
        val report = executePackagePlans(buildPackagePlans(listOf(aot1)))
        val e = report.entries.single()
        assertEquals(ExecOutcome.Succeeded, e.outcome)
        assertEquals("E:\\漫画库\\_output/进击的巨人 第01卷.cbz", e.createdPath)
        assertNull(e.reason, "成功项不需要原因")
        assertEquals(ExecKind.Package, report.kind)
    }

    @Test
    fun `skipped plans are reported as skipped with the plan reason`() {
        val plans = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Skip)
        val e = executePackagePlans(plans).entries.single()
        assertEquals(ExecOutcome.Skipped, e.outcome)
        assertEquals("输出已存在 E:\\漫画库\\_output/海贼王 第102卷.cbz", e.reason)
        assertNull(e.createdPath, "跳过项没有产生输出")
    }

    @Test
    fun `a volume with no pages fails with a readable reason`() {
        val empty = aot1.copy(pageOrder = aot1.pageOrder.copy(order = emptyList()))
        val e = executePackagePlans(buildPackagePlans(listOf(empty))).entries.single()
        assertEquals(ExecOutcome.Failed, e.outcome)
        assertEquals("没有任何页，无法打包", e.reason)
        assertNull(e.createdPath, "失败项没有产生输出")
    }

    @Test
    fun `a volume with a blank output dir fails with a readable reason`() {
        val e = executePackagePlans(buildPackagePlans(listOf(aot1.copy(outputDir = "")))).entries.single()
        assertEquals(ExecOutcome.Failed, e.outcome)
        assertEquals("输出目录为空", e.reason)
    }

    @Test
    fun `skipped takes precedence over the failure conditions`() {
        // 计划说跳过 + 同时没有页 -> 应报"跳过"而不是"失败"(失败原因在此无意义)
        val broken = op102.copy(pageOrder = op102.pageOrder.copy(order = emptyList()))
        val plans = buildPackagePlans(listOf(broken), conflictPolicy = OutputConflictPolicy.Skip)
        val e = executePackagePlans(plans).entries.single()
        assertEquals(ExecOutcome.Skipped, e.outcome, "已计划跳过时不该报失败")
    }

    // ---- 三类可同时出现 ----

    @Test
    fun `all three outcomes can appear in one report`() {
        val noPages = aot1.copy(pageOrder = aot1.pageOrder.copy(order = emptyList()))
        val plans = buildPackagePlans(
            listOf(library.volumes[1], op102, noPages), // 正常 / 冲突跳过 / 无页失败
            conflictPolicy = OutputConflictPolicy.Skip,
        )
        val report = executePackagePlans(plans)
        assertEquals(1, report.succeededCount)
        assertEquals(1, report.skippedCount)
        assertEquals(1, report.failedCount)
        assertEquals("共 3 项：成功 1 · 跳过 1 · 失败 1", report.summaryLine)
    }

    // ---- 顺序与计数 ----

    @Test
    fun `report preserves plan order so rows line up with the plan page`() {
        val plans = buildPackagePlans(library.volumes)
        val report = executePackagePlans(plans)
        assertEquals(plans.map { it.volume.id }, report.entries.map { it.id })
    }

    @Test
    fun `summary counts add up to the number of entries`() {
        val report = executePackagePlans(buildPackagePlans(library.volumes))
        assertEquals(report.entries.size, report.succeededCount + report.skippedCount + report.failedCount)
    }

    // ---- 重命名报告 ----

    private fun op(id: String, finalName: String, status: OpStatus = OpStatus.Ok, reason: String? = null) =
        RenameOp(
            proposal = NameProposal(
                image = aot1.pages.first().copy(id = id),
                // finalName 是 proposedName/overrideName 的派生属性,不可直接赋值
                proposedName = finalName,
                overrideName = null,
                warnings = emptyList(),
            ),
            status = status,
            reason = reason,
        )

    @Test
    fun `rename report has kind Rename and reports the new name as the created path`() {
        val report = executeRenamePlan(listOf(op("i1", "新名.jpg")))
        assertEquals(ExecKind.Rename, report.kind)
        val e = report.entries.single()
        assertEquals(ExecOutcome.Succeeded, e.outcome)
        assertEquals("新名.jpg", e.createdPath)
    }

    @Test
    fun `rename report marks skipped ops with their conflict reason`() {
        val report = executeRenamePlan(listOf(op("i1", "撞名.jpg", OpStatus.Skipped, "重名：有 2 项同名")))
        val e = report.entries.single()
        assertEquals(ExecOutcome.Skipped, e.outcome)
        assertEquals("重名：有 2 项同名", e.reason)
    }

    @Test
    fun `rename report fails on a blank final name`() {
        val report = executeRenamePlan(listOf(op("i1", "   ")))
        assertEquals(ExecOutcome.Failed, report.entries.single().outcome)
        assertEquals("新名称为空", report.entries.single().reason)
    }

    // ---- 撤销 ----

    @Test
    fun `undo record only contains successful entries`() {
        val plans = buildPackagePlans(
            listOf(library.volumes[1], op102),
            conflictPolicy = OutputConflictPolicy.Skip,
        )
        val report = executePackagePlans(plans)
        val record = undoRecordOf(report)
        assertNotNull(record)
        assertEquals(1, record.opCount, "只有成功的那 1 卷产生副作用，撤销记录应只含它")
    }

    @Test
    fun `undo record is null when nothing succeeded`() {
        val plans = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Skip)
        assertNull(undoRecordOf(executePackagePlans(plans)), "全部跳过 = 没有可撤销的操作")
        assertFalse(executePackagePlans(plans).canUndo)
    }

    @Test
    fun `package undo maps to deleting the created files`() {
        val record = undoRecordOf(executePackagePlans(buildPackagePlans(listOf(aot1))))
        val op = record!!.ops.single()
        assertTrue(op is UndoOp.DeleteCreated, "打包的撤销是删除新建文件，实际 $op")
        assertEquals("E:\\漫画库\\_output/进击的巨人 第01卷.cbz", (op as UndoOp.DeleteCreated).path)
    }

    @Test
    fun `rename undo maps to restoring the original name`() {
        val record = undoRecordOf(executeRenamePlan(listOf(op("i1", "新名.jpg"))))
        val undo = record!!.ops.single()
        assertTrue(undo is UndoOp.RestoreRenamed)
        assertEquals("新名.jpg", (undo as UndoOp.RestoreRenamed).from)
    }

    @Test
    fun `undo is honest about being a mock and names how many ops are recorded`() {
        val record = undoRecordOf(executePackagePlans(buildPackagePlans(listOf(aot1))))
        val result = performUndo(record)
        assertFalse(result.applied, "D1 不触碰真实文件系统，不得谎称已撤销")
        assertTrue("1 项" in result.message, "应说明记录了几项逆向操作，实际 ${result.message}")
    }

    @Test
    fun `undo on an empty record reports that there is nothing to undo`() {
        val result = performUndo(null)
        assertFalse(result.applied)
        assertEquals("没有可撤销的操作", result.message)
    }
}
