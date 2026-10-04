package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.naming.RenameOp
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey

/**
 * 统一操作词汇(HANGOFF §5 的 `OperationPlan`)。
 *
 * ## 为什么要有这一层
 *
 * §5 把 `OperationPlan` 与 `PackagePlan` 并列列进统一领域模型,但到 D3 为止它**全仓零命中**:
 * 「计划有多少项、跳过几项、会触发哪些危险动作」在打包侧与改名侧各自实现了一遍 ——
 * 打包走 `planCountsOf` / `effectiveDangerActions(plans, …)`,改名走
 * `LibraryRenamePlan.counts` / `workflowADangerActions(clean)`。同一件事两处算,
 * 迟早漂移;而且新增第三种操作(复制/移动)时没有任何结构提示"你还得再补一份"。
 *
 * 这里把两者收进**一个** sealed 词汇:每加一种操作,`executePlan` 的穷尽 `when`
 * 与 `PlannedOp` 的实现都会**编译期**报缺,而不是悄悄漏掉。
 *
 * ## 与既有公开契约的关系
 *
 * **零行为变化**:[executePackagePlans] / [executeRenamePlan] 保留原签名,改为
 * 经由本文件的 [executePlan] 薄委托;既有测试一行不改也必须全绿。
 * 本文件**不新增任何 [TextKey]** —— 计划行文案沿用打包侧既有的 `describe()` 契约。
 */

/**
 * 计划里的一项操作。
 *
 * [id] / [label] / [skipped] / [skipReason] 是执行报告与计划页**共同**需要的四个字段,
 * 提到接口上,于是"报告行与计划行逐行对齐"成为结构性质而不是两处各自小心维护的约定。
 */
sealed interface PlannedOp {
    /** 该项的产生来源 id(卷 id / 图片 id)。 */
    val id: String

    /** 报告行与计划页共用的**标签**(文件名或输出名,不是文案)。 */
    val label: String

    /** 计划是否已判定跳过(跳过优先于一切失败判定)。 */
    val skipped: Boolean

    /** 跳过原因(键 + 参数);未跳过时为 null。 */
    val skipReason: Msg?

    /** 本项属于哪种操作。`OperationPlan` 据此强制同质。 */
    val kind: ExecKind
}

/** 一项打包操作(包装既有 [VolumePackPlan],语义与字段全部沿用)。 */
data class PackagePlannedOp(val plan: VolumePackPlan) : PlannedOp {
    override val id: String get() = plan.volume.id
    override val label: String get() = plan.volume.cbzFileName
    override val skipped: Boolean get() = plan.skipped
    override val skipReason: Msg? get() = plan.skipReason
    override val kind: ExecKind get() = ExecKind.Package

    /** 输出全路径(与 [VolumePackPlan.outputPath] 同源)。 */
    val outputPath: String get() = plan.outputPath

    /**
     * 计划行文案 —— **直接沿用**打包侧既有契约 [VolumePackPlan.describe],
     * 5 条消息的顺序与参数逐字不变(见 `docs/notes/d1-archive-domain-semantics.md:610-617`)。
     */
    fun describe(): List<Msg> = plan.describe()
}

/** 一项改名操作(包装既有 [RenameOp])。 */
data class RenamePlannedOp(val op: RenameOp) : PlannedOp {
    override val id: String get() = op.id
    override val label: String get() = op.finalName
    override val skipped: Boolean get() = op.isSkipped
    override val skipReason: Msg? get() = if (op.isSkipped) op.reason else null
    override val kind: ExecKind get() = ExecKind.Rename

    /**
     * 计划行文案。
     *
     * 改名侧**没有**逐项句子契约:`RenamePlanPage` 渲染的是"原名 → 新名"两列与状态标签,
     * 不是计划句。故这里只能给出该行**为什么在那儿**的那一条:
     *  - 跳过项 → 它自己的冲突原因(与执行报告里 `reason` 同一条,不是另算一份);
     *  - 其余 → [TextKey.OpStatusRename](「将重命名」),它就是该行在计划页上的状态词。
     *
     * **有意不新造文案键**:见本文件头部「不新增任何 TextKey」。
     */
    fun describe(): List<Msg> = listOf(op.reason ?: Msg(TextKey.OpStatusRename))
}

/**
 * 一份待执行的操作计划。
 *
 * [dangerActions] 默认空集 —— 危险动作是**计划级**判定(见 [operationPlanOf]):
 *
 *  - 打包侧的删除源目录 / 覆盖已有输出,由计划里**未跳过**的项推导;
 *  - 改名侧的清理空目录是**批次级开关**,不对应任何一项操作。
 *
 * 故它是计划的字段,不是操作列表的派生属性 —— 否则「清理空目录」会凭空多出一条计划项,
 * 报告与计划的行数就对不上了。
 */
data class OperationPlan(
    val kind: ExecKind,
    val ops: List<PlannedOp>,
    val dangerActions: Set<DangerAction> = emptySet(),
) {
    init {
        // ---- 不变式:一份计划只有一种操作 ----
        //
        // 为什么强制:撤销语义是**按种类**分的 —— 打包回滚是"删掉新建的文件"
        // (`UndoOp.DeleteCreated`),改名回滚是"改回原名"(`UndoOp.RestoreRenamed`)。
        // 混装时 [undoRecordOf] 只能按 `kind` 二选一,另一部分项就会被用**错的**方式回滚。
        // 与其在使用点小心判断,不如让这种状态根本构造不出来。
        //
        // ⚠️ 这条消息是**开发者诊断**(面向堆栈),不是用户文案 ⇒ 保持中文字面量,不建键。
        require(ops.all { it.kind == kind }) {
            "OperationPlan 的操作种类必须与 kind 一致：kind=$kind，实际混入了 " +
                ops.map { it.kind }.distinct().filter { it != kind }
        }
    }

    val total: Int get() = ops.size
    val skipped: Int get() = ops.count { it.skipped }
    val runnable: Int get() = total - skipped
    val counts: PlanCounts get() = PlanCounts(total, skipped)

    /**
     * 整份计划的**可解释**文案,按 [ops] 顺序拼接。
     *
     * 这是"计划必须可解释"(HANGOFF §5 关键原则 4)的统一入口:
     * 打包侧沿用既有 5 条契约,改名侧给出每行状态,两者同序,于是
     * 「计划第 N 行 ↔ 报告第 N 行」在两类操作上都成立。
     */
    fun describe(): List<Msg> = ops.flatMap { op ->
        when (op) {
            is PackagePlannedOp -> op.describe()
            is RenamePlannedOp -> op.describe()
        }
    }
}

/**
 * 从打包计划构建统一操作计划。
 *
 * [dangerActions] 复用 [effectiveDangerActions] —— **唯一**的危险动作入口,
 * 不在此另写一份判定(那正是本文件要消除的重复)。
 */
fun operationPlanOf(
    plans: List<VolumePackPlan>,
    cleanEmptyDirs: Boolean = false,
): OperationPlan = OperationPlan(
    kind = ExecKind.Package,
    ops = plans.map { PackagePlannedOp(it) },
    dangerActions = effectiveDangerActions(plans, cleanEmptyDirs),
)

/**
 * 从改名计划构建统一操作计划。
 *
 * 改名侧的危险动作只有"清理空目录"且是批次级开关,故 [dangerActions] 只可能来自
 * [cleanEmptyDirs](复用 [workflowADangerActions])。
 */
fun operationPlanOfRenames(
    ops: List<RenameOp>,
    cleanEmptyDirs: Boolean = false,
): OperationPlan = OperationPlan(
    kind = ExecKind.Rename,
    ops = ops.map { RenamePlannedOp(it) },
    dangerActions = workflowADangerActions(cleanEmptyDirs),
)

/**
 * 执行一份操作计划(确定性模拟,不触碰文件系统 —— 见 `Execution.kt` 头部)。
 *
 * 分支顺序与失败判定条件**逐字沿用**原先的两个入口:
 * 跳过优先 → 再判各自的可达失败条件(打包:无页 / 输出目录为空;改名:新名为空)。
 */
fun executePlan(plan: OperationPlan): ExecutionReport {
    val entries = plan.ops.map { op ->
        when (op) {
            is PackagePlannedOp -> executePackageOp(op)
            is RenamePlannedOp -> executeRenameOp(op)
        }
    }
    return ExecutionReport(entries, plan.kind)
}

private fun executePackageOp(item: PackagePlannedOp): ExecEntry {
    val plan = item.plan
    val v = plan.volume
    return when {
        plan.skipped -> ExecEntry(
            id = item.id,
            label = item.label,
            outcome = ExecOutcome.Skipped,
            reason = plan.skipReason,
        )

        v.pageCount == 0 -> ExecEntry(
            id = item.id,
            label = item.label,
            outcome = ExecOutcome.Failed,
            reason = Msg(TextKey.PackFailNoPages),
        )

        v.outputDir.isBlank() -> ExecEntry(
            id = item.id,
            label = item.label,
            outcome = ExecOutcome.Failed,
            reason = Msg(TextKey.PackFailEmptyOutputDir),
        )

        else -> ExecEntry(
            id = item.id,
            label = item.label,
            outcome = ExecOutcome.Succeeded,
            createdPath = plan.outputPath,
        )
    }
}

private fun executeRenameOp(item: RenamePlannedOp): ExecEntry {
    val op = item.op
    return when {
        op.isSkipped -> ExecEntry(
            id = item.id,
            label = item.label,
            outcome = ExecOutcome.Skipped,
            reason = op.reason,
        )

        op.finalName.isBlank() -> ExecEntry(
            id = item.id,
            // ⚠️ 这里**刻意不用** item.label(= finalName):最终名是空白,拿它当标签
            // 会在报告里显示一个空行,用户根本看不出是哪张图失败了。故回落到原文件名。
            label = op.proposal.image.fileName,
            outcome = ExecOutcome.Failed,
            reason = Msg(TextKey.RenameFailEmptyName),
        )

        else -> ExecEntry(
            id = item.id,
            label = item.label,
            outcome = ExecOutcome.Succeeded,
            createdPath = op.finalName,
            // 撤销要"改回原名",而原名只存在于计划里(`op.proposal.image.fileName`)。
            // 报告若不带它,从报告推导撤销的那条入口就永远取不到 —— 见 ExecEntry.originalPath。
            originalPath = op.proposal.image.fileName,
        )
    }
}

/**
 * 撤销记录的统一推导:从计划 + 报告得到逆向操作。
 *
 * 与 [undoRecordOf] 的区别只有**判据来源**:这里按 [OperationPlan.kind] 决定回滚语义,
 * 而 [undoRecordOf] 按报告自带的 `kind`。两者共用 [undoOpsOf],因此不可能漂移 ——
 * 若各自实现,"统一词汇"反而会多出一份要同步的重复。
 */
fun undoRecordFrom(plan: OperationPlan, report: ExecutionReport): UndoRecord? =
    undoOpsOf(report, plan.kind)?.let { UndoRecord(it, plan.kind) }
