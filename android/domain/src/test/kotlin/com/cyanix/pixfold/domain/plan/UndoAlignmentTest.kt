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
import com.cyanix.pixfold.domain.organize.organizeStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 撤销记录与执行报告在新统一词汇下的**全路径**对齐(W6,对应 D3 退出条件 1 的
 * 「执行报告 / 撤销记录」)。
 *
 * ## 这份文件在守什么
 *
 * `undoRecordFrom(plan, report)` 是 W1–W3 引入的**新**入口,它与既有的
 * `undoRecordOf(report)` 只差一件事:**撤销语义按谁的 `kind` 走**。
 * 两者共用 `undoOpsOf`,所以"不可能漂移"—— 但**这是实现事实,不是被断言的性质**。
 * 若哪天有人把其中一个改成各自实现,`OperationPlanTest` 里那条
 * "两条入口产出的撤销记录相同"仍会在**当前数据**上通过(因为两边恰好都没混装)。
 *
 * 故这里把**所有组合**列出来交叉核对:成功 / 跳过 / 失败 × 打包 / 改名,
 * 每一种都同时问两个入口,并要求逐字相同。
 *
 * ## 为什么「失败项不进撤销记录」值得单独钉
 *
 * 「撤销 N 项」这个数字会显示给用户(HANGOFF §5 关键原则 5「结果可追溯」)。
 * 失败项**没有产生任何副作用**,把它算进去会让数字虚高,用户据此以为"多做了几件事
 * 需要回滚" —— 那是在骗人。跳过项同理。
 */
class UndoAlignmentTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val op102 = library.volumes.first { it.id == "op-102" }

    private val collections = MockData.workspaceA.collections
    private val orders = organizeStateOf(collections).orders
    private val naming = NamingState(NamingScheme(rootDirName = "任意"))

    private fun renameOp(
        id: String,
        finalName: String,
        status: OpStatus = OpStatus.Ok,
        reason: Msg? = null,
    ) = RenameOp(
        proposal = NameProposal(
            image = aot1.pages.first().copy(id = id),
            proposedName = finalName,
        ),
        status = status,
        reason = reason,
    )

    /** 打包三种结果的混合批:1 成功(aot-1)、1 跳过(op-102 已有输出 + Skip 策略)。 */
    private fun mixedPackPlans() = buildPackagePlans(
        listOf(aot1, op102),
        conflictPolicy = OutputConflictPolicy.Skip,
    )

    // ---- 全路径:三种结果 × 两种入口 ----

    @Test
    fun `both entry points agree on a mixed package batch`() {
        val plans = mixedPackPlans()
        val report = executePackagePlans(plans)
        // 混合批必须真的含两类结果,否则这条测试会退化成"两条入口都不返回东西"
        assertEquals(1, report.succeededCount, "前置:aot-1 应成功")
        assertEquals(1, report.skippedCount, "前置:op-102 应跳过")

        val fromReport = undoRecordOf(report)
        val fromPlan = undoRecordFrom(operationPlanOf(plans), report)
        assertEquals(fromReport, fromPlan, "两条入口的撤销记录必须逐字相同")
        assertEquals(ExecKind.Package, fromPlan!!.kind)
    }

    @Test
    fun `both entry points agree on a mixed rename batch`() {
        val conflict = Msg(TextKey.WarnDuplicate, listOf(2, "撞名.jpg"))
        val ops = listOf(
            renameOp("i1", "新名.jpg"),
            renameOp("i2", "撞名.jpg", OpStatus.Skipped, conflict),
            renameOp("i3", "   "),
        )
        val report = executeRenamePlan(ops)
        assertEquals(1, report.succeededCount)
        assertEquals(1, report.skippedCount)
        assertEquals(1, report.failedCount, "前置:空名应失败")

        assertEquals(undoRecordOf(report), undoRecordFrom(operationPlanOfRenames(ops), report))
    }

    // ---- 哪一类结果会进撤销记录 ----

    @Test
    fun `only the succeeded entry of a mixed package batch is undoable`() {
        val plans = mixedPackPlans()
        val report = executePackagePlans(plans)
        val record = assertNotNull(undoRecordOf(report))
        assertEquals(1, record.opCount, "只有成功的那 1 卷产生副作用")
        val op = record.ops.single()
        assertTrue(op is UndoOp.DeleteCreated, "打包的撤销是删除新建文件，实际 $op")
        assertEquals("E:\\漫画库\\_output/进击的巨人 第01卷.cbz", op.path)
    }

    @Test
    fun `only the succeeded entry of a mixed rename batch is undoable`() {
        val ops = listOf(
            renameOp("i1", "新名.jpg"),
            renameOp("i2", "撞名.jpg", OpStatus.Skipped, Msg(TextKey.WarnDuplicate, listOf(2, "撞名.jpg"))),
            renameOp("i3", "   "),
        )
        val record = assertNotNull(undoRecordOf(executeRenamePlan(ops)))
        assertEquals(1, record.opCount, "跳过与失败都不产生副作用")
        val op = record.ops.single()
        assertTrue(op is UndoOp.RestoreRenamed, "改名的撤销是改回原名，实际 $op")
        assertEquals("新名.jpg", op.from, "from = 改后的名字（回滚的起点）")
        assertEquals(
            aot1.pages.first().fileName,
            op.to,
            "to = 原文件名 —— 跳过与失败项都不该污染它（它们压根不进记录）",
        )
    }

    /** 失败项的 `createdPath` 必须为空 —— 否则撤销会去删一个不存在的东西。 */
    @Test
    fun `a failed entry carries no created path and so cannot enter an undo record`() {
        val empty = aot1.copy(pageOrder = aot1.pageOrder.copy(order = emptyList()))
        val report = executePackagePlans(buildPackagePlans(listOf(empty)))
        val e = report.entries.single()
        assertEquals(ExecOutcome.Failed, e.outcome)
        assertNull(e.createdPath)
        assertNull(undoRecordOf(report), "全失败 = 没有可撤销的操作")
    }

    @Test
    fun `an empty batch has no undo record from either entry point`() {
        val packReport = executePackagePlans(emptyList())
        assertNull(undoRecordOf(packReport))
        assertNull(undoRecordFrom(operationPlanOf(emptyList()), packReport))

        val renameReport = executeRenamePlan(emptyList())
        assertNull(undoRecordOf(renameReport))
        assertNull(undoRecordFrom(operationPlanOfRenames(emptyList()), renameReport))
    }

    // ---- 撤销语义按 kind 分:两类操作的逆向动作类型不同 ----

    @Test
    fun `the same report shape yields different undo op types depending on kind`() {
        // 这是"撤销语义必须按 kind 分"的**唯一**直接证据。
        // 若两个 kind 都用 DeleteCreated,Package 的撤销就会去"删除"一个改过名的文件
        // (其实应该改回原名),而且不会有任何编译错误提示。
        val packOp = undoRecordOf(executePackagePlans(buildPackagePlans(listOf(aot1))))!!.ops.single()
        val renameOp = undoRecordOf(executeRenamePlan(listOf(renameOp("i1", "新名.jpg"))))!!.ops.single()
        assertTrue(packOp is UndoOp.DeleteCreated, "打包 ⇒ 删除新建的 CBZ，实际 $packOp")
        assertTrue(renameOp is UndoOp.RestoreRenamed, "改名 ⇒ 改回原名，实际 $renameOp")
    }

    @Test
    fun `a successful rename undo restores the original file name`() {
        // 这条用例原名 `a successful rename undo currently collapses from and to onto
        // the new name`，**如实记录既有缺陷**（台账「待裁决 R1」）。用户已裁决修复，
        // 故此处翻成**正向断言**。
        //
        // 缺陷是什么：`UndoOp.RestoreRenamed(from, to)` 的语义是「把改名后的文件改回去」，
        // 故 `from` 应是新名、`to` 应是**原文件名**。但成功项的 `label` 与 `createdPath`
        // 都是 `op.finalName`（同一个值），于是推导出的记录是
        // `RestoreRenamed("改后.jpg", "改后.jpg")` —— 一个**空操作**，真要按它回滚是回不去的。
        //
        // 为什么 D1 一直没暴露：`performUndo` 恒返回 `applied = false`（原型不触碰
        // 真实文件系统），UI 只显示「已记录 N 项」，从不消费 `from`/`to`。
        // 真机核对（HANGOFF:540 第 17 项）看到的也是同一句话。
        //
        // 修复分两步：① `ExecEntry` 补 `originalPath`（本项动手前该位置原本的名字），
        // 由 `executeRenameOp` 从 `op.proposal.image.fileName` 填；② `undoOpsOf` 的
        // Rename 分支改从它取 `to`。于是 `from` = 新名（起点）、`to` = 原文件名（终点）。
        //
        // 顺带兑现一条变异预测：`from`/`to` 对调的变异在缺陷期**存活**（两者相等 ⇒
        // 等价变异），修复后会被下面的 `to` 断言杀死。
        val original = aot1.pages.first().fileName
        val op = undoRecordOf(executeRenamePlan(listOf(renameOp("i1", "改后.jpg"))))!!.ops.single()
        assertTrue(op is UndoOp.RestoreRenamed)
        assertEquals("改后.jpg", op.from, "from = 改后的名字（回滚的起点）")
        assertEquals(original, op.to, "to = 原文件名（回滚的终点）—— 否则这条撤销是空操作")
    }

    @Test
    fun `the report carries the original name so undo no longer needs the plan`() {
        // 这条用例原名 `the undo record cannot recover the original name from the report alone`，
        // 记录的是**结构性事实**：`ExecEntry` 里没有任何字段携带原文件名，于是
        // "从报告推导撤销"在类型上就取不到原文件名（见台账 R1）。现在补上了 `originalPath`。
        //
        // 为什么 `originalPath` 落在 `ExecEntry` 而不是让 `undoRecordFrom(plan, report)`
        // 从计划里现取：两个入口（`undoRecordOf(report)` / `undoRecordFrom(plan, report)`）
        // 必须产出**同一份**记录 —— 这是本文件开头声明的不变量。若只有拿到计划的那个
        // 入口能取到原名，两个入口就会给出不同的 `to`，"不可能漂移"就成了空话。
        // 报告自己带上原名，两条路径才真正等价。
        val original = aot1.pages.first().fileName
        val report = executeRenamePlan(listOf(renameOp("i1", "改后.jpg")))
        val e = report.entries.single()
        assertEquals(ExecOutcome.Succeeded, e.outcome)
        assertTrue(e.label != original, "报告标签仍是新名（用户要看的是改成了什么）")
        assertEquals(original, e.originalPath, "原文件名必须能从报告本身取回")
    }

    @Test
    fun `an overwritten file is not something this run created and safe undo must not delete it`() {
        // 这条用例原名 `an overwritten file is recorded as created and so undo would delete it`，
        // 记录的是**既有缺陷 R3**：`Overwrite` 策略 + 输出已存在时，报告报 `Succeeded`，
        // `createdPath` 取的却是**本该被覆盖的那个旧文件**的路径。撤销据此推导出
        // `DeleteCreated(该路径)` —— 可那个文件**不是本次创建的**，是被**替换**的。
        // 真要执行撤销，会把用户原有的 CBZ 删掉，而被覆盖掉的内容已经找不回来了。
        //
        // 现在的语义（用户 m04079 第③项裁决）：撤销前先警告，由用户选
        // 「只回滚本次新建的」还是「连被覆盖的一起删」。**默认（安全）档只回滚本次新建的**，
        // 而"这个位置上原本有没有东西"由 `ExecEntry.originalPath` 承载 ——
        // 它是**报告自己**的事实，不需要回头去查计划（两个撤销入口共用 `undoOpsOf`，
        // 只有一个入口拿得到计划，靠计划就意味着两条路径会漂移）。
        val plans = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite)
        val report = executePackagePlans(plans)
        val e = report.entries.single()
        assertEquals(ExecOutcome.Succeeded, e.outcome, "前置:Overwrite 下照常执行")
        assertEquals(plans.single().outputPath, e.createdPath)
        assertEquals(
            plans.single().outputPath,
            e.originalPath,
            "覆盖项必须在报告里留下「这个位置原本有东西」的痕迹 —— " +
                "否则撤销无从区分「本次新建」与「替换掉别人的」",
        )
        // 成功率没有变：这一项**确实成功执行了**，界面照常报成功、撤销按钮照常可用。
        assertTrue(report.canUndo, "覆盖也是一次成功的执行,不能因此把撤销入口关掉")
        // 但**安全档**没有任何可回滚的东西：本次没有新建任何文件。
        assertNull(
            undoRecordOf(report),
            "安全档只回滚本次新建的东西;这里唯一的成功项是「替换」,不属于本次新建",
        )
        // 报告必须自己说得出"有几项是覆盖",界面据此决定要不要弹警告。
        assertEquals(1, report.overwrittenCount, "报告要能报出被覆盖的项数")
        assertTrue(report.needsUndoWarning, "有覆盖项 ⇒ 撤销前必须先警告(用户 m04079 第③项)")
        // 显式选了「全部删除」才产生删除项,且**类型上**与"删除本次新建"分开 ——
        // 这一删会连用户原有的内容一起抹掉,不能混成同一个 UndoOp。
        val everything = assertNotNull(
            undoRecordOf(report, UndoScope.Everything),
            "危险档下覆盖项是可删的",
        )
        assertEquals(
            listOf(UndoOp.DeleteOverwritten(e.createdPath!!)),
            everything.ops,
            "危险档删的是「被覆盖掉的那个路径」,与删除本次新建分开成两个类型",
        )
        assertEquals(1, everything.overwrittenOpCount, "记录要能报出其中几项是危险的")
        // 反向守卫一:同一卷换成 Skip 策略就什么都不产生、不进撤销记录 ——
        // 说明问题只出在 Overwrite 这一个分支上,不是"凡是已存在的输出都记错"。
        val skipReport = executePackagePlans(
            buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Skip),
        )
        assertNull(undoRecordOf(skipReport), "Skip 策略下没有创建任何东西，不该有撤销记录")
        // 反向守卫二:**Rename** 策略 + 输出已存在 ⇒ 新文件**另起一名**(`… (1).cbz`),
        // 谁也没被覆盖 ⇒ 不是覆盖项,是普通新增,安全档照常回滚,界面也不该弹警告。
        // 这一条防的是"凡 `outputExists` 就算覆盖"这种偷懒判据。
        val renamePlans = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Rename)
        val renameReport = executePackagePlans(renamePlans)
        val renamed = renameReport.entries.single()
        assertNull(renamed.originalPath, "Rename 另起一名,没有覆盖任何东西")
        assertEquals(
            listOf(UndoOp.DeleteCreated(renamePlans.single().outputPath)),
            undoRecordOf(renameReport)!!.ops,
            "Rename 策略下这就是一次纯新建,安全档照常删它",
        )
    }

    @Test
    fun `renaming never counts as overwriting`() {
        // `ExecKind.Rename` 的撤销语义是「把改名后的文件改回去」(移动),不是"删除"。
        // 改名到已存在的名字属于**冲突/警告**(`ProposalWarningType.Duplicate`),
        // 不是覆盖 —— 计划层根本不会执行它。故改名侧**恒无覆盖项**,
        // 界面也就永远不该为改名执行报弹撤销警告。
        val report = executeRenamePlan(listOf(renameOp("i1", "新名.jpg")))
        assertEquals(ExecKind.Rename, report.kind)
        assertEquals(1, report.succeededCount, "前置:应有一项成功")
        // 原来的"把原名改回去"照旧;改名是**移动**,没有"被覆盖的旧文件"可删,
        // 所以两个档位在这里必须给出同一份记录。
        val op = undoRecordOf(report)!!.ops.single()
        assertTrue(op is UndoOp.RestoreRenamed, "改名的撤销是改回原名,实际 $op")
        assertEquals(renameOp("i1", "新名.jpg").proposal.image.fileName, op.to, "终点是原名")
        // ⚠️ 改名的 `originalPath` 是**本文件原来的名字**(撤销靠它改回去,R1),
        // 不是"被覆盖"的痕迹 —— 同一个字段在两种工作流下含义不同,故"是否覆盖"
        // 必须按 kind 分叉判断,不能只看字段非空。
        assertEquals(0, report.overwrittenCount, "改名不产生覆盖项")
        assertTrue(!report.needsUndoWarning, "改名执行不该弹撤销警告")
        // 两档在这条路径上必须给出**同一份**记录:改名没有"被覆盖的旧文件"可删。
        assertEquals(undoRecordOf(report), undoRecordOf(report, UndoScope.Everything))
    }

    // ---- UndoRecord 自身的计数与可执行性 ----

    @Test
    fun `op count matches the number of successful entries for both kinds`() {
        val packReport = executePackagePlans(buildPackagePlans(library.volumes, OutputConflictPolicy.Rename))
        assertNotNull(undoRecordOf(packReport))
        assertEquals(packReport.succeededCount, undoRecordOf(packReport)!!.opCount)

        val renameReport = executeRenamePlan(listOf(renameOp("i1", "a.jpg"), renameOp("i2", "b.jpg")))
        assertNotNull(undoRecordOf(renameReport))
        assertEquals(renameReport.succeededCount, undoRecordOf(renameReport)!!.opCount)
    }

    @Test
    fun `performUndo reports the op count derived from the unified plan`() {
        // 撤销消息里的数字必须与"计划驱动"的那份记录一致 —— 否则用户看到的
        // 「已记录 N 项」与计划页显示的项数会互相矛盾。
        val plans = buildPackagePlans(library.volumes, OutputConflictPolicy.Skip)
        val report = executePlan(operationPlanOf(plans))
        val record = assertNotNull(undoRecordFrom(operationPlanOf(plans), report))
        val result = performUndo(record)
        assertEquals(Msg(TextKey.UndoRecorded, listOf(record.opCount)), result.message)
        assertTrue(!result.applied, "D1 是 mock,不得谎称已撤销")
    }

    // ---- 既有公开契约未被本轮改动 ----

    @Test
    fun `the legacy rename plan still feeds the unified vocabulary without drift`() {
        // 端到端:走一遍既有 LibraryRenamePlan 的公开路径,再与统一词汇比对。
        val plan = buildLibraryRenamePlan(collections, orders, naming)
        val report = executeRenamePlan(plan.ops)
        val unified = executePlan(operationPlanOfRenames(plan.ops))
        assertEquals(report, unified)
        assertEquals(undoRecordOf(report), undoRecordFrom(operationPlanOfRenames(plan.ops), unified))
    }

    @Test
    fun `a report kind that disagrees with the plan kind is visible in the undo record`() {
        // 反向守卫:undoRecordFrom 从**计划**取 kind(参数即断言)。
        // 若它偷偷改成读 report.kind,这里就会相等 —— 于是这条会红。
        // 用一份 Rename 计划配一份 Package 报告(现实中不会这样调用,但能暴露实现取值来源)。
        val renameOps = listOf(renameOp("i1", "新名.jpg"))
        val renamePlan = operationPlanOfRenames(renameOps)
        val packReport = executePackagePlans(buildPackagePlans(listOf(aot1)))
        val record = undoRecordFrom(renamePlan, packReport)!!
        assertEquals(ExecKind.Rename, record.kind, "kind 必须来自计划参数")
        assertTrue(record.ops.single() is UndoOp.RestoreRenamed, "语义按计划的 Rename 走")
    }

    @Test
    fun `mixed package and rename results never share one record`() {
        // undoOpsOf 按单一 kind 分派,故"混装"在类型上就不该发生 —— 计划层的
        // require 已经堵死(见 OperationPlanTest)。这里补一条:两条独立报告
        // 各自的撤销项数量互不干扰。
        val packRecord = undoRecordOf(executePackagePlans(buildPackagePlans(listOf(aot1))))!!
        val renameRecord = undoRecordOf(executeRenamePlan(listOf(renameOp("i1", "新名.jpg"))))!!
        assertEquals(1, packRecord.opCount)
        assertEquals(1, renameRecord.opCount)
        assertEquals(ExecKind.Package, packRecord.kind)
        assertEquals(ExecKind.Rename, renameRecord.kind)
    }

    // ---- 不变式:撤销记录不会凭空多出项 ----

    @Test
    fun `a record only ever contains ops for entries that created something`() {
        // 遍历一个真实的混合批次,逐项核对:每个撤销项都能在报告里找到一条
        // "成功 + createdPath 非空"的行,且数量相等。
        val plans = buildPackagePlans(library.volumes, OutputConflictPolicy.Skip)
        val report = executePlan(operationPlanOf(plans))
        val record = undoRecordOf(report)
        val created = report.entries.filter { it.createdPath != null }
        assertEquals(created.size, record?.opCount ?: 0)
        assertEquals(created.size, report.succeededCount, "createdPath 非空 ⟺ Succeeded")
    }

    @Test
    fun `an illegal entry combination is still rejected after the refactor`() {
        // ExecEntry 的两条 require 是本轮重构的**承重墙**:undoOpsOf 只留一道判据
        // (createdPath != null),前提正是"非法组合造不出来"。这里确认它没被顺手删掉。
        val skippedWithPath = assertFailsWith<IllegalArgumentException> {
            ExecEntry(id = "x", label = "a.cbz", outcome = ExecOutcome.Skipped, createdPath = "out/a.cbz")
        }
        assertTrue("createdPath" in skippedWithPath.message.orEmpty())
        val succeededWithoutPath = assertFailsWith<IllegalArgumentException> {
            ExecEntry(id = "x", label = "a.cbz", outcome = ExecOutcome.Succeeded)
        }
        assertTrue("createdPath" in succeededWithoutPath.message.orEmpty())
    }
}
