package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.naming.RenameOp

/**
 * 执行报告与撤销(规格 §9 / 验收第 17 项)。
 *
 * **D1 原型不触碰真实文件系统**(HANGOFF §9 D1 范围),故"执行"是**确定性模拟**:
 * 不产生副作用,只把"本会发生什么、结果如何"算成数据。
 *
 * **为什么仍要算得这么细**:验收第 17 项要的是"报告含成功/跳过/失败 **三类**"。
 * 若执行层只会返回"成功",UI 就没有可展示的失败态,报告也就无法回答
 * "哪些没做成、为什么" —— 这正是归档原型被记为薄弱的地方。故这里把
 * **失败判定条件**也做进领域层,并让它们是**用户可达**的真实条件
 * (页数为 0、输出目录为空),而不是随机注入的假失败。
 */

/** 单项执行结果。 */
enum class ExecOutcome(val label: String) {
    Succeeded("成功"),
    Skipped("跳过"),
    Failed("失败"),
}

/**
 * 报告里的一行。
 *
 * [createdPath] 仅在该项**真的产生了输出**时非空 —— 撤销只回滚这些,
 * 不会去动原本就没动过的东西。
 */
data class ExecEntry(
    val id: String,
    val label: String,
    val outcome: ExecOutcome,
    val reason: String? = null,
    val createdPath: String? = null,
)

/**
 * 执行报告。
 *
 * [entries] 的顺序 = 计划顺序(不重排),这样"报告第 3 行"与"计划第 3 行"对得上,
 * 用户才能拿报告去核对计划。
 */
data class ExecutionReport(val entries: List<ExecEntry>, val kind: ExecKind) {
    val succeededCount: Int get() = entries.count { it.outcome == ExecOutcome.Succeeded }
    val skippedCount: Int get() = entries.count { it.outcome == ExecOutcome.Skipped }
    val failedCount: Int get() = entries.count { it.outcome == ExecOutcome.Failed }

    /** 一句话结论(报告页顶部)。 */
    val summaryLine: String
        get() = "共 ${entries.size} 项：成功 $succeededCount · 跳过 $skippedCount · 失败 $failedCount"

    /** 是否有可撤销的动作(没有成功项就没什么可撤销的)。 */
    val canUndo: Boolean get() = succeededCount > 0
}

/** 报告属于哪条工作流(文案与撤销语义不同)。 */
enum class ExecKind(val title: String) {
    Rename("重命名执行报告"),
    Package("打包执行报告"),
}

/**
 * 执行打包计划(模拟)。
 *
 * **失败判定**(用户可达的真实条件,不是随机失败):
 *  - 该卷**没有任何页** → 失败「没有任何页，无法打包」;
 *  - **输出目录为空** → 失败「输出目录为空」;
 *  - 计划已标记 skipped → 跳过,并沿用计划的 skipReason。
 *
 * 先判"计划跳过"再判失败:计划说要跳过的卷,其失败条件无意义,报"失败"会误导。
 */
fun executePackagePlans(plans: List<VolumePackPlan>): ExecutionReport {
    val entries = plans.map { plan ->
        val v = plan.volume
        when {
            plan.skipped -> ExecEntry(
                id = v.id,
                label = v.cbzFileName,
                outcome = ExecOutcome.Skipped,
                reason = plan.skipReason,
            )

            v.pageCount == 0 -> ExecEntry(
                id = v.id,
                label = v.cbzFileName,
                outcome = ExecOutcome.Failed,
                reason = "没有任何页，无法打包",
            )

            v.outputDir.isBlank() -> ExecEntry(
                id = v.id,
                label = v.cbzFileName,
                outcome = ExecOutcome.Failed,
                reason = "输出目录为空",
            )

            else -> ExecEntry(
                id = v.id,
                label = v.cbzFileName,
                outcome = ExecOutcome.Succeeded,
                createdPath = plan.outputPath,
            )
        }
    }
    return ExecutionReport(entries, ExecKind.Package)
}

/**
 * 执行重命名计划(模拟)。
 *
 * 失败判定:目标文件名为**空** → 失败「新名称为空」(用户清空覆盖框即可触发,
 * 领域层另有"空 = 撤销覆盖"的语义,但若最终名仍为空则无法执行)。
 */
fun executeRenamePlan(ops: List<RenameOp>): ExecutionReport {
    val entries = ops.map { op ->
        when {
            op.isSkipped -> ExecEntry(
                id = op.id,
                label = op.finalName,
                outcome = ExecOutcome.Skipped,
                reason = op.reason,
            )

            op.finalName.isBlank() -> ExecEntry(
                id = op.id,
                label = op.proposal.image.fileName,
                outcome = ExecOutcome.Failed,
                reason = "新名称为空",
            )

            else -> ExecEntry(
                id = op.id,
                label = op.finalName,
                outcome = ExecOutcome.Succeeded,
                createdPath = op.finalName,
            )
        }
    }
    return ExecutionReport(entries, ExecKind.Rename)
}

// ---- 撤销(规格 §9.2:提供但为 mock) ----

/** 逆向操作。正式版据此真正回滚;D1 只记录与展示。 */
sealed interface UndoOp {
    /** 删除本次新建的文件。 */
    data class DeleteCreated(val path: String) : UndoOp

    /** 把改名后的文件改回去。 */
    data class RestoreRenamed(val from: String, val to: String) : UndoOp
}

/**
 * 一次执行的撤销记录。
 *
 * **只记录成功项**:失败项没有产生副作用,跳过项也没有 —— 把它们放进撤销记录
 * 会让"撤销 N 项"的数字虚高。
 */
data class UndoRecord(val ops: List<UndoOp>, val kind: ExecKind) {
    val opCount: Int get() = ops.size
}

/** 从报告推导撤销记录;没有成功项时返回 null(没有可撤销的东西)。 */
fun undoRecordOf(report: ExecutionReport): UndoRecord? {
    if (!report.canUndo) return null
    val ops = report.entries
        .filter { it.outcome == ExecOutcome.Succeeded }
        .mapNotNull { e ->
            val path = e.createdPath ?: return@mapNotNull null
            when (report.kind) {
                ExecKind.Package -> UndoOp.DeleteCreated(path)
                ExecKind.Rename -> UndoOp.RestoreRenamed(from = e.label, to = path)
            }
        }
    return UndoRecord(ops, report.kind)
}

/** 撤销结果。D1 为 mock,故 [applied] 恒为 false,由 [message] 说明原因。 */
data class UndoResult(val applied: Boolean, val message: String)

/**
 * 执行撤销(D1:mock)。
 *
 * **为什么不假装撤销成功**:D1 没有真实文件系统,若返回"已撤销",
 * 界面就会显示一个**不存在的**回滚结论 —— 那是在骗用户。
 * 故这里明确返回 `applied = false` 并说明"正式版将基于 UndoRecord 记录逆向操作",
 * 同时把**具体要回滚哪些东西**列出来(`opCount`),让用户知道撤销是有依据的。
 */
fun performUndo(record: UndoRecord?): UndoResult {
    if (record == null || record.ops.isEmpty()) {
        return UndoResult(applied = false, message = "没有可撤销的操作")
    }
    return UndoResult(
        applied = false,
        message = "已记录 ${record.opCount} 项逆向操作；正式版将基于 UndoRecord 逐项回滚（原型不触碰真实文件系统）",
    )
}
