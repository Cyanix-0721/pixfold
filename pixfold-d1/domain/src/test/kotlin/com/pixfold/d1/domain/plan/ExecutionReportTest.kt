package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.NameProposal
import com.pixfold.d1.domain.naming.OpStatus
import com.pixfold.d1.domain.naming.RenameOp
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
 *
 * **断言的是 [Msg] 结构(key + args)而非成品文案**:领域层不产出语言。
 * 逐字文案由 app 侧资源守卫;这里保证"原因被带上了、且是正确的那一条"。
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
        assertEquals(
            Msg(TextKey.PackSkipOutputExists, listOf("E:\\漫画库\\_output/海贼王 第102卷.cbz")),
            e.reason,
            "跳过原因必须原样沿用计划里的那一条(键+参数)",
        )
        assertNull(e.createdPath, "跳过项没有产生输出")
    }

    @Test
    fun `a volume with no pages fails with a readable reason`() {
        val empty = aot1.copy(pageOrder = aot1.pageOrder.copy(order = emptyList()))
        val e = executePackagePlans(buildPackagePlans(listOf(empty))).entries.single()
        assertEquals(ExecOutcome.Failed, e.outcome)
        assertEquals(Msg(TextKey.PackFailNoPages), e.reason)
        assertNull(e.createdPath, "失败项没有产生输出")
    }

    @Test
    fun `a volume with a blank output dir fails with a readable reason`() {
        val e = executePackagePlans(buildPackagePlans(listOf(aot1.copy(outputDir = "")))).entries.single()
        assertEquals(ExecOutcome.Failed, e.outcome)
        assertEquals(Msg(TextKey.PackFailEmptyOutputDir), e.reason)
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
        assertEquals(
            Msg(TextKey.ExecSummary, listOf(3, 1, 1, 1)),
            report.summary,
            "结论行必须带真实四元组(总数·成功·跳过·失败)",
        )
    }

    @Test
    fun `summary keeps the four counts in their own positions`() {
        // 回归:上一条用例里成功数与跳过数**都是 1**,于是「成功/跳过两个实参对调」
        // 这个变异在四元组上完全不可见 —— 实测存活。这里让四个数**两两不等**,
        // 任何一次位置对调都会改变消息内容。
        val aot2 = library.volumes.first { it.id == "aot-2" }
        val op101 = library.volumes.first { it.id == "op-101" }
        val alsoConflicting = library.volumes.first { it.id == "aot-0" }.copy(outputExists = true)
        val noPages = aot1.copy(pageOrder = aot1.pageOrder.copy(order = emptyList()))
        val plans = buildPackagePlans(
            // 3 卷正常 / 2 卷冲突跳过(含 mock 里本就 outputExists 的 op-102)/ 1 卷无页失败
            listOf(aot1, aot2, op101, alsoConflicting, op102, noPages),
            conflictPolicy = OutputConflictPolicy.Skip,
        )
        val report = executePackagePlans(plans)
        assertEquals(6, report.entries.size)
        assertEquals(3, report.succeededCount)
        assertEquals(2, report.skippedCount)
        assertEquals(1, report.failedCount)
        assertEquals(
            Msg(TextKey.ExecSummary, listOf(6, 3, 2, 1)),
            report.summary,
            "四元组顺序是（总数·成功·跳过·失败）—— 四个数两两不等才能发现位置错了",
        )
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

    private fun op(
        id: String,
        finalName: String,
        status: OpStatus = OpStatus.Ok,
        reason: Msg? = null,
    ) = RenameOp(
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
        val conflict = Msg(TextKey.WarnDuplicate, listOf(2, "撞名.jpg"))
        val report = executeRenamePlan(listOf(op("i1", "撞名.jpg", OpStatus.Skipped, conflict)))
        val e = report.entries.single()
        assertEquals(ExecOutcome.Skipped, e.outcome)
        assertEquals(conflict, e.reason)
    }

    @Test
    fun `rename report fails on a blank final name`() {
        val report = executeRenamePlan(listOf(op("i1", "   ")))
        assertEquals(ExecOutcome.Failed, report.entries.single().outcome)
        assertEquals(Msg(TextKey.RenameFailEmptyName), report.entries.single().reason)
    }

    // ---- 不变式:只有成功项才产生输出 ----
    //
    // P6 变异验证实测:"把跳过也算进撤销记录"这个变异**未能被任何测试捕获** ——
    // 因为 undorecordOf 里并列了 outcome 与 createdPath 两道**等价**判据,去掉一道
    // 另一道仍挡住了它。根因是不变量没被强制。以下用例钉死该不变量。

    @Test
    fun `a skipped entry cannot carry a created path`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ExecEntry(id = "x", label = "a.cbz", outcome = ExecOutcome.Skipped, createdPath = "out/a.cbz")
        }
        assertTrue("createdPath" in ex.message.orEmpty(), "报错应点明问题所在，实际 ${ex.message}")
    }

    @Test
    fun `a failed entry cannot carry a created path`() {
        assertFailsWith<IllegalArgumentException> {
            ExecEntry(id = "x", label = "a.cbz", outcome = ExecOutcome.Failed, createdPath = "out/a.cbz")
        }
    }

    @Test
    fun `a successful entry must carry a created path`() {
        // 反向也堵死:成功项没有 path,撤销就不知道回滚什么 —— 属非法状态
        assertFailsWith<IllegalArgumentException> {
            ExecEntry(id = "x", label = "a.cbz", outcome = ExecOutcome.Succeeded)
        }
    }

    @Test
    fun `skipped and failed entries without a path are legal`() {
        // 正常的跳过/失败项必须能构造(否则前面的用例会把合法用法一起挡掉)
        ExecEntry(
            id = "x",
            label = "a.cbz",
            outcome = ExecOutcome.Skipped,
            reason = Msg(TextKey.WarnOverridden),
        )
        ExecEntry(
            id = "y",
            label = "b.cbz",
            outcome = ExecOutcome.Failed,
            reason = Msg(TextKey.PackFailNoPages),
        )
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
        // assertTrue 带 contract(returns() implies actual)，上一行之后已智能转型
        assertEquals("E:\\漫画库\\_output/进击的巨人 第01卷.cbz", op.path)
    }

    @Test
    fun `rename undo maps to restoring the original name`() {
        val record = undoRecordOf(executeRenamePlan(listOf(op("i1", "新名.jpg"))))
        val undo = record!!.ops.single()
        assertTrue(undo is UndoOp.RestoreRenamed)
        assertEquals("新名.jpg", undo.from)
    }

    @Test
    fun `undo is honest about being a mock and names how many ops are recorded`() {
        val record = undoRecordOf(executePackagePlans(buildPackagePlans(listOf(aot1))))
        val result = performUndo(record)
        assertFalse(result.applied, "D1 不触碰真实文件系统，不得谎称已撤销")
        assertEquals(
            Msg(TextKey.UndoRecorded, listOf(1)),
            result.message,
            "应说明记录了几项逆向操作",
        )
    }

    @Test
    fun `undo on an empty record reports that there is nothing to undo`() {
        val result = performUndo(null)
        assertFalse(result.applied)
        assertEquals(Msg(TextKey.UndoNothing), result.message)
    }
}
