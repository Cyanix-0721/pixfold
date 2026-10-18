package com.cyanix.pixfold.domain.plan

import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.NameProposal
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.naming.OpStatus
import com.cyanix.pixfold.domain.naming.RenameOp
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey
import com.cyanix.pixfold.domain.workflowa.workflowAStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    private val aot2 = volumes.first { it.id == "aot-2" }
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
    fun `runnable counts what will actually run rather than summing the parts`() {
        // 用真实读数钉死,而不是 `assertEquals(plan.total - plan.skipped, plan.runnable)` ——
        // 后者与实现同构,把 `runnable` 写成 `total + skipped` 也照样通过(变异实测证实)。
        // 5 卷里 op-102 的输出已存在,Skip 策略下跳过 1 卷 ⇒ 可执行 4。
        val plan = operationPlanOf(buildPackagePlans(volumes, OutputConflictPolicy.Skip))
        assertEquals(5, plan.total)
        assertEquals(1, plan.skipped)
        assertEquals(4, plan.runnable, "可执行数必须是 total − skipped")
        assertEquals(plan.runnable, plan.counts.runnable, "两条出口(${""}字段与 counts)不能各算一份")
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
                            proposal = com.cyanix.pixfold.domain.model.NameProposal(
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

    // ---- 报告行与计划行必须同源(label / skipReason 的真实杀伤力) ----

    @Test
    fun `the report label is the planned cbz file name, not the conflict renamed output`() {
        // 这两者在输出冲突时**不相等**:`cbzFileName` 是卷的默认名,
        // `outputDisplay` 才是加了「 (1)」之后真正落盘的名字。
        // 报告行要显示的是"这一卷",若误用 outputDisplay,用户会把「(1)」当成卷名的一部分。
        val plans = buildPackagePlans(listOf(op102), OutputConflictPolicy.Rename)
        val plan = operationPlanOf(plans)
        assertEquals("海贼王 第102卷.cbz", plan.ops.single().label)
        assertEquals("海贼王 第102卷 (1).cbz", (plan.ops.single() as PackagePlannedOp).plan.outputDisplay)
        assertEquals(plan.ops.single().label, executePlan(plan).entries.single().label)
    }

    @Test
    fun `a rename op that did not skip has no skip reason even if a reason was attached`() {
        // 接口契约写明「跳过原因;未跳过时为 null」。若实现直接透传 `op.reason`,
        // 一个"带着警告但状态是 Ok"的项就会在计划页显示成跳过 —— 用户会以为它不会执行。
        val warn = Msg(TextKey.WarnCaseCollision, listOf("a.jpg"))
        val op = RenameOp(
            proposal = NameProposal(image = aot1.pages.first(), proposedName = "x.jpg"),
            status = OpStatus.Ok,
            reason = warn,
        )
        assertNull(RenamePlannedOp(op).skipReason, "未跳过就不该有跳过原因")
        // 反向守卫:真的跳过时必须带上那条原因(否则上一条会退化成"恒为 null")
        val skipped = RenamePlannedOp(op.copy(status = OpStatus.Skipped))
        assertEquals(warn, skipped.skipReason)
    }

    @Test
    fun `a blank final name is reported with the original file name as its label`() {
        // 最终名是空白时若拿它当标签,报告里会出现一个空行 —— 用户看不出是哪张图失败了。
        val op = RenameOp(
            proposal = NameProposal(image = aot1.pages.first(), proposedName = "   "),
            status = OpStatus.Ok,
            reason = null,
        )
        val e = executePlan(operationPlanOfRenames(listOf(op))).entries.single()
        assertEquals(ExecOutcome.Failed, e.outcome)
        assertEquals(aot1.pages.first().fileName, e.label, "失败行必须回落到原文件名")
    }

    @Test
    fun `describe of a unified plan still emits the pack contract lines`() {
        // `OperationPlan.describe()` 是"计划必须可解释"(§5 原则 4)的统一入口。
        // 若它退化成空列表,UI 侧拿到一片空白 —— 而不会有任何编译错误。
        val plan = operationPlanOf(buildPackagePlans(listOf(aot1)))
        assertEquals(buildPackagePlans(listOf(aot1)).single().describe(), plan.describe())
        assertEquals(
            listOf(TextKey.PackCreateCbz, TextKey.PackWriteComicInfo, TextKey.PackPackPages),
            plan.describe().map { it.key },
        )
    }

    @Test
    fun `describe covers every operation in plan order`() {
        val plans = buildPackagePlans(listOf(aot1, aot2))
        val plan = operationPlanOf(plans)
        // 逐项拼接:每项 3 条(无危险动作),顺序与 ops 一致 ——
        // 于是"计划第 N 行 ↔ 报告第 N 行"在 describe() 上也成立。
        assertEquals(plans.flatMap { it.describe() }, plan.describe())
        assertEquals(6, plan.describe().size)
    }

    @Test
    fun `describe of a rename plan spells out the original and the new name`() {
        // 打包侧每项能说清"要做什么"(创建 CBZ → X / 写 ComicInfo / 打包 N 张),
        // 改名侧原先只有一句状态词「将重命名」—— 用户看完一整列也不知道会改成什么。
        // 这里钉的是两个参数的**方向**:先原名,后新名。
        val op = RenameOp(
            proposal = NameProposal(image = aot1.pages.first(), proposedName = "x.jpg"),
            status = OpStatus.Ok,
            reason = null,
        )
        assertEquals(
            listOf(
                Msg(
                    TextKey.RenamePlanRename,
                    listOf(aot1.pages.first().fileName, "x.jpg"),
                ),
            ),
            operationPlanOfRenames(listOf(op)).describe(),
            "未跳过项必须给出「原名 → 新名」,而不是一句状态词",
        )
    }

    @Test
    fun `describe of a skipped rename op reports its reason instead of the status word`() {
        // 跳过项与正常项在计划页上**只有这一句话**能区分开:
        // 若跳过项也回退到「重命名」状态词,用户看到的就是一整列"都会改",
        // 执行后才发现少了一批 —— 这正是"计划页必须可预期"要拦的事。
        val reason = Msg(TextKey.WarnDuplicate, listOf(2, "x.jpg"))
        val op = RenameOp(
            proposal = NameProposal(image = aot1.pages.first(), proposedName = "x.jpg"),
            status = OpStatus.Skipped,
            reason = reason,
        )
        assertEquals(
            listOf(reason),
            operationPlanOfRenames(listOf(op)).describe(),
            "有原因时必须原样给出原因,而不是回退到状态词",
        )
    }

    @Test
    fun `a skipped rename op without a reason falls back to the skip status word`() {
        // 回落方向必须是「将跳过」而不是「将重命名」:
        // 计划页上说"会重命名"、执行时却跳过,是比"没话说"更坏的误导。
        // 构造上 buildRenamePlan 给跳过项必配 reason,故这条只能手工造出来 ——
        // 但它正是 describe() 的穷尽性守卫:新增状态时这里会先红。
        val op = RenameOp(
            proposal = NameProposal(image = aot1.pages.first(), proposedName = "x.jpg"),
            status = OpStatus.Skipped,
            reason = null,
        )
        assertEquals(
            listOf(Msg(TextKey.OpStatusSkip)),
            operationPlanOfRenames(listOf(op)).describe(),
            "无原因的跳过项应回落到「将跳过」,而不是「将重命名」",
        )
    }
}
