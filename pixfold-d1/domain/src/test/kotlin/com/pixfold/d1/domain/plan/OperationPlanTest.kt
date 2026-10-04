package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.naming.OpStatus
import com.pixfold.d1.domain.naming.RenameOp
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey
import com.pixfold.d1.domain.workflowa.workflowAStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 统一操作词汇(HANGOFF §5 的 `OperationPlan`,D3 退出条件 1)。
 *
 * **为什么需要它**:在此之前,"一份计划有多少项、跳过几项""这次会触发哪些危险动作"
 * 有**两套互不相干的实现** —— 打包侧走 `planCountsOf` / `effectiveDangerActions(plans, …)`,
 * 改名侧走 `LibraryRenamePlan.counts` / `workflowADangerActions(clean)`。同一件事两处算,
 * 迟早漂移。本文件钉死统一后的词汇,并**交叉核对**它与既有两条实现给出相同结果 ——
 * 等价是这一步唯一可接受的验收标准(既有公开契约与可观测行为逐字不变)。
 */
class OperationPlanTest {

    private val volumes = MockDataB.library.volumes
    private val aot1 = volumes.first { it.id == "aot-1" }
    private val op102 = volumes.first { it.id == "op-102" }

    private val collections = MockData.workspaceA.collections
    private val orders = workflowAStateOf(collections).orders
    private val naming = NamingState(NamingScheme(rootDirName = "任意"))

    // ---- 种类与计数 ----

    @Test
    fun `a pack plan carries the package kind and the pack counts`() {
        val plans = buildPackagePlans(volumes, conflictPolicy = OutputConflictPolicy.Skip)
        val plan = operationPlanOf(plans)
        assertEquals(ExecKind.Package, plan.kind)
        assertEquals(plans.size, plan.total)
        assertEquals(plans.count { it.skipped }, plan.skipped)
        assertEquals(PlanCounts(plans.size, plans.count { it.skipped }), plan.counts)
    }

    @Test
    fun `a rename plan carries the rename kind`() {
        val library = buildLibraryRenamePlan(collections, orders, naming)
        val plan = operationPlanOfRenames(library.ops)
        assertEquals(ExecKind.Rename, plan.kind)
        assertEquals(library.total, plan.total)
        assertEquals(library.skipped, plan.skipped)
    }

    @Test
    fun `an empty pack plan still reports the package kind`() {
        // 这是"种类必须是计划自己的字段、不能从第一条操作推导"的守卫:
        // `executePackagePlans(emptyList())` 今天返回的就是 ExecKind.Package 的空报告,
        // 若 kind 取自 ops.first(),空计划会在执行时报 NoSuchElement 或退化成别的种类。
        assertEquals(ExecKind.Package, operationPlanOf(emptyList()).kind)
        assertEquals(0, operationPlanOf(emptyList()).total)
    }

    @Test
    fun `operations keep plan order so report rows line up with plan rows`() {
        val plans = buildPackagePlans(volumes)
        assertEquals(plans.map { it.volume.id }, operationPlanOf(plans).ops.map { it.id })
    }

    @Test
    fun `a skipped operation carries its skip reason`() {
        val plans = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Skip)
        val op = operationPlanOf(plans).ops.single()
        assertTrue(op.skipped)
        assertEquals(plans.single().skipReason, op.skipReason)
    }

    // ---- 危险动作:与既有推导交叉核对 ----

    @Test
    fun `danger actions of a pack plan agree with the existing derivation for every policy pair`() {
        // 全矩阵:冲突策略 × 源策略 × 是否勾选清理空目录,逐格比对两条实现
        OutputConflictPolicy.entries.forEach { conflict ->
            SourcePolicy.entries.forEach { source ->
                listOf(false, true).forEach { clean ->
                    val plans = buildPackagePlans(volumes, conflict, source)
                    assertEquals(
                        effectiveDangerActions(plans, clean),
                        operationPlanOf(plans, clean).dangerActions,
                        "冲突=$conflict 源=$source 清理空目录=$clean 下两条实现必须一致",
                    )
                }
            }
        }
    }

    @Test
    fun `a skipped delete-source operation does not raise the danger`() {
        // 计划说跳过的卷根本不会执行,它的"删除源目录"不该让用户白确认一次。
        val plans = buildPackagePlans(listOf(op102), OutputConflictPolicy.Skip, SourcePolicy.Delete)
        assertTrue(plans.single().skipped, "前置:该卷确已跳过")
        assertEquals(emptySet(), operationPlanOf(plans).dangerActions)
    }

    @Test
    fun `overwrite is only dangerous when the output actually exists`() {
        val noConflict = buildPackagePlans(listOf(aot1), OutputConflictPolicy.Overwrite, SourcePolicy.Keep)
        val conflict = buildPackagePlans(listOf(op102), OutputConflictPolicy.Overwrite, SourcePolicy.Keep)
        assertEquals(emptySet(), operationPlanOf(noConflict).dangerActions, "没有冲突就不存在覆盖")
        assertEquals(setOf(DangerAction.OverwriteExisting), operationPlanOf(conflict).dangerActions)
    }

    @Test
    fun `clean empty dirs is a plan-level danger and never becomes an operation`() {
        // 它不是"对某个文件"的操作 —— 若给它造一条 PlannedOp,计划项数会凭空 +1,
        // 报告与计划的行数就对不上了。故它是计划级开关。
        val plan = operationPlanOfRenames(emptyList(), cleanEmptyDirs = true)
        assertEquals(setOf(DangerAction.CleanEmptyDirs), plan.dangerActions)
        assertEquals(0, plan.total)
    }

    @Test
    fun `rename danger actions agree with the existing derivation`() {
        listOf(false, true).forEach { clean ->
            assertEquals(
                workflowADangerActions(clean),
                operationPlanOfRenames(emptyList(), clean).dangerActions,
            )
        }
    }

    // ---- 同质不变式 ----

    @Test
    fun `a plan mixing operation kinds is rejected`() {
        // 一份报告只有一种撤销语义(DeleteCreated / RestoreRenamed),混装会让撤销无从下手。
        val ex = assertFailsWith<IllegalArgumentException> {
            OperationPlan(
                kind = ExecKind.Package,
                ops = listOf(
                    PackagePlannedOp(buildPackagePlans(listOf(aot1)).single()),
                    RenamePlannedOp(
                        RenameOp(
                            proposal = com.pixfold.d1.domain.model.NameProposal(
                                image = aot1.pages.first(),
                                proposedName = "x.jpg",
                            ),
                            status = OpStatus.Ok,
                            reason = null,
                        ),
                    ),
                ),
            )
        }
        assertTrue("种类" in ex.message.orEmpty(), "报错应点明问题所在，实际 ${ex.message}")
    }

    @Test
    fun `a homogeneous plan is accepted`() {
        // 反向守卫:上面的拒绝不能把合法用法一起挡掉(否则"同质校验"就成了"永远抛异常")
        val library = buildLibraryRenamePlan(collections, orders, naming)
        assertEquals(library.total, OperationPlan(ExecKind.Rename, operationPlanOfRenames(library.ops).ops).total)
        assertEquals(volumes.size, OperationPlan(ExecKind.Package, operationPlanOf(buildPackagePlans(volumes)).ops).total)
        // 空计划也必须合法(空报告是既有行为)
        OperationPlan(ExecKind.Package, emptyList())
        OperationPlan(ExecKind.Rename, emptyList())
    }

    // ---- 单一执行入口:与既有两个入口逐项等价 ----

    @Test
    fun `executePlan produces the same report as the legacy package entry point`() {
        OutputConflictPolicy.entries.forEach { conflict ->
            val plans = buildPackagePlans(volumes, conflict, SourcePolicy.Keep)
            assertEquals(executePackagePlans(plans), executePlan(operationPlanOf(plans)))
        }
    }

    @Test
    fun `executePlan produces the same report as the legacy rename entry point`() {
        val library = buildLibraryRenamePlan(collections, orders, naming)
        assertEquals(executeRenamePlan(library.ops), executePlan(operationPlanOfRenames(library.ops)))
    }

    @Test
    fun `executePlan on an empty pack plan matches the legacy result`() {
        assertEquals(executePackagePlans(emptyList()), executePlan(operationPlanOf(emptyList())))
    }

    @Test
    fun `undo records derived from both entry points are identical`() {
        val plans = buildPackagePlans(volumes, OutputConflictPolicy.Rename, SourcePolicy.Keep)
        assertEquals(
            undoRecordOf(executePackagePlans(plans)),
            undoRecordOf(executePlan(operationPlanOf(plans))),
        )
        val library = buildLibraryRenamePlan(collections, orders, naming)
        assertEquals(
            undoRecordOf(executeRenamePlan(library.ops)),
            undoRecordOf(executePlan(operationPlanOfRenames(library.ops))),
        )
    }

    @Test
    fun `a skip reason survives the round trip through the unified vocabulary`() {
        val plans = buildPackagePlans(listOf(op102), OutputConflictPolicy.Skip)
        val e = executePlan(operationPlanOf(plans)).entries.single()
        assertEquals(Msg(TextKey.PackSkipOutputExists, listOf("E:\\漫画库\\_output/海贼王 第102卷.cbz")), e.reason)
    }
}
