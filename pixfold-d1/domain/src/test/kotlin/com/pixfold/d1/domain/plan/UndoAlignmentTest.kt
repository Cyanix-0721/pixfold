package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.NameProposal
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
    private val orders = workflowAStateOf(collections).orders
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
        // 方向见 `a successful rename undo currently collapses from and to onto the new name`：
        // 当前 from 与 to 都取自报告里的 label / createdPath（同一个值），此处只钉数量与类型。
        assertEquals("新名.jpg", op.from, "from 取自报告标签")
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
    fun `a successful rename undo currently collapses from and to onto the new name`() {
        // ⚠️ 这条断言记录的是**既有缺陷**，不是期望行为 —— 见台账「待裁决 R1」。
        //
        // `UndoOp.RestoreRenamed(from, to)` 的语义是「把改名后的文件改回去」，
        // 故 `from` 应是新名、`to` 应是**原文件名**。但成功项的 `label` 与
        // `createdPath` 都是 `op.finalName`（同一个值），于是推导出的记录是
        // `RestoreRenamed("改后.jpg", "改后.jpg")` —— 一个**空操作**，
        // 真要按它回滚是回不去的。
        //
        // 为什么 D1 一直没暴露：`performUndo` 恒返回 `applied = false`（原型不触碰
        // 真实文件系统），UI 只显示「已记录 N 项」，从不消费 `from`/`to`。
        // 真机核对（HANGOFF:540 第 17 项）看到的也是同一句话。
        //
        // 为什么这里**不改实现**：正确取值需要产品决策 —— 撤销记录该携带"原文件名"
        // （则要扩 `ExecEntry` 或让 `undoRecordFrom` 从计划里取 `op.proposal.image.fileName`），
        // 还是 `from`/`to` 本就该是别的含义。按 §7.1 第 1 步「有歧义先请用户裁决」，
        // 本项目夜间无人值守，故只记录、不动行为（本轮契约是"零行为变化"）。
        //
        // 顺带解释一个变异结果：把 `from`/`to` 对调的变异**存活**（等价变异）——
        // 正因为两者当前相等。缺陷一旦修复，该变异会被本文件的正向断言杀死。
        val op = undoRecordOf(executeRenamePlan(listOf(renameOp("i1", "改后.jpg"))))!!.ops.single()
        assertTrue(op is UndoOp.RestoreRenamed)
        assertEquals("改后.jpg", op.from, "from = 报告里的 label（当前 = 新名）")
        assertEquals(
            op.from,
            op.to,
            "当前 to 也等于新名 ⇒ 撤销是空操作；修复后此断言应改为「to == 原文件名」",
        )
    }

    @Test
    fun `the undo record cannot recover the original name from the report alone`() {
        // 支撑上一条的结构性事实：`ExecEntry` 里**没有任何字段**携带原文件名。
        // 故"从报告推导撤销"这条路径在类型上就取不到原文件名 —— 这不是实现疏忽，
        // 而是记录里缺字段。`undoRecordFrom(plan, report)` 手里有计划，
        // 具备修复条件；这正是两个入口**不该**被强行视作完全等价的地方（见台账 R1）。
        val original = aot1.pages.first().fileName
        val report = executeRenamePlan(listOf(renameOp("i1", "改后.jpg")))
        val e = report.entries.single()
        assertEquals(ExecOutcome.Succeeded, e.outcome)
        assertTrue(
            e.label != original,
            "报告标签是新名；原文件名「$original」在报告里已无处可取",
        )
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
