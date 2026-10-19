package com.cyanix.pixfold.domain.plan

import com.cyanix.pixfold.domain.naming.RenameOp
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey

/**
 * 真实改名的平台端口。
 *
 * ## 为什么返回**新 uri** 而不是 `Boolean`
 *
 * SAF 的 `DocumentsContract.renameDocument` 会返回**改名后的 document uri** ——
 * 在 `ExternalStorageProvider` 上它和原 uri 不同(其 document id 由路径派生)。
 * 撤销要改回去,必须拿得到改名后的那个 uri;先返回 `Boolean` 再让调用方去猜,
 * 猜错的后果是「撤销点了没反应」或「撤到别的文件上」。
 *
 * 返回 `null` = 失败。失败是**正常业务分支**(目标名被占、介质被移除、授权失效、
 * ROM 拒绝),与 `CbzOutputSink.openFor` 返回 null 同一取舍:调用方要把它变成
 * 报告里一行「失败 + 原因」,而不是让整个执行崩掉。平台实现**不得抛**。
 */
fun interface RenameActions {
    /**
     * 把 [uri] 指向的文件改名为 [newName]。
     *
     * [newName] 是**完整文件名**(含扩展名),与 [RenameOp.finalName] 同义。
     * @return 改名后的 uri;失败返回 `null`
     */
    fun rename(uri: String, newName: String): String?
}

/**
 * 真实执行改名计划。
 *
 * ## 与 `executeRenamePlan` 的关系
 *
 * `executeRenamePlan` 是 D1 的**确定性模拟**:只按领域规则算「本会成功/跳过/失败」,
 * 一个字节都不动(KDoc 原文「执行重命名计划(模拟)」)。本函数把同一条判定搬进真实路径:
 *
 * - **跳过判定完全复用** `RenameOp.isSkipped`(计划层已算好冲突,不许在此另算一份);
 * - **空名判定同样保留** —— 它是领域规则,与有没有文件系统无关;
 * - 前两种之外,才真正问平台要结果。
 *
 * 分支顺序刻意与 `executeRenameOp` 逐条对齐:两份实现若顺序不同,会出现
 * 「模拟说跳过、真实路径却去改名」这种谁也说不清的行为。
 *
 * ## 为什么 `uri == null` 算失败而不是跳过
 *
 * 「跳过」在报告里的意思是**计划定的**(用户能看到原因),而「这张图没有 uri」
 * 是**执行环境缺信息** —— 用户无从预期。混成跳过会让人以为是自己选的。
 *
 * @param ops 改名计划(与 `executeRenamePlan` 同一入参)
 * @param actions 平台端口;测试可传内存实现
 * @return 与 `executeRenamePlan` 同构的报告,可直接交给既有报告页与撤销入口
 */
fun executeRenamePlanReal(
    ops: List<RenameOp>,
    actions: RenameActions,
    token: CancellationToken = CancellationToken(),
    onProgress: (TaskProgress) -> Unit = {},
): ExecutionReport {
    // 进度按项上报(不是按时间),因为一项就是一次 Binder 往返 —— 界面上
    // 的"动一格"必须对应用户能看到的一次实际变化,否则进度条会自己跑完
    // 而磁盘还没动几个文件。
    var done = 0
    var failed = 0
    var skipped = 0
    val total = ops.size
    val entries = buildList {
        for ((index, op) in ops.withIndex()) {
            // 中断只在**项之间**检查:半途停在一次 renameDocument 中间没有意义,
            // 而且可能在目标目录留下"改了名但没记账"的状态(与 PackRunner 同一取舍)。
            if (token.shouldStop(beforeStartingItem = index)) {
                // 未处理的项补成 Skipped + 「尚未开始」—— 与 PackReportBridge 同一口径:
                // 用户取消 ≠ 计划跳过,两者文案必须能区分开。
                for (rest in ops.subList(index, ops.size)) {
                    add(
                        ExecEntry(
                            id = rest.id,
                            label = rest.proposal.image.fileName,
                            outcome = ExecOutcome.Skipped,
                            reason = Msg(TextKey.PackFailNotStarted),
                        ),
                    )
                }
                skipped += ops.size - index
                onProgress(TaskProgress(total = total, done = done, failed = failed, skipped = skipped))
                break
            }
            val entry = when {
                op.isSkipped -> ExecEntry(
                    id = op.id,
                    label = op.finalName,
                    outcome = ExecOutcome.Skipped,
                    reason = op.reason,
                )

                op.finalName.isBlank() -> ExecEntry(
                    id = op.id,
                    // 与 executeRenameOp 同一理由:最终名是空白,拿它当标签会让报告出现空行,
                    // 用户看不出是哪张图失败。回落原文件名。
                    label = op.proposal.image.fileName,
                    outcome = ExecOutcome.Failed,
                    reason = Msg(TextKey.RenameFailEmptyName),
                )

                op.proposal.image.uri == null -> ExecEntry(
                    id = op.id,
                    label = op.proposal.image.fileName,
                    outcome = ExecOutcome.Failed,
                    reason = Msg(TextKey.RenameFailNoUri),
                )

                else -> {
                    val uri = op.proposal.image.uri
                    val renamed = runCatching { actions.rename(uri, op.finalName) }.getOrNull()
                    if (renamed != null) {
                        ExecEntry(
                            id = op.id,
                            label = op.finalName,
                            outcome = ExecOutcome.Succeeded,
                            createdPath = op.finalName,
                            originalPath = op.proposal.image.fileName,
                            uri = renamed,
                        )
                    } else {
                        ExecEntry(
                            id = op.id,
                            label = op.proposal.image.fileName,
                            outcome = ExecOutcome.Failed,
                            // 失败行用**原名**做标签:此刻磁盘上还是旧名字,
                            // 用户要按旧名去文件管理器里找那张图。
                            reason = Msg(TextKey.RenameFailIo),
                        )
                    }
                }
            }
            add(entry)
            // 计数与 entry.outcome 严格同源 —— 不能各算各的,否则进度条与报告
            // 会给出两个数(库侧 PackRunner 踩过同类:跳过曾被并进 failed)。
            when (entry.outcome) {
                ExecOutcome.Succeeded -> done++
                ExecOutcome.Failed -> failed++
                ExecOutcome.Skipped -> skipped++
            }
            onProgress(TaskProgress(total = total, done = done, failed = failed, skipped = skipped))
        }
    }
    return ExecutionReport(entries, ExecKind.Rename)
}

/**
 * 真实撤销的平台端口。
 *
 * 两个方法都**不得抛**:撤销是用户点「撤销」之后的补救动作,半路抛异常会让
 * 「已经回滚了几项」这件事实永远传不回界面。
 */
interface UndoActions {
    /** 删除 [uri] 指向的文件。返回是否成功。 */
    fun delete(uri: String): Boolean

    /** 把 [uri] 改名为 [newName]。返回是否成功。 */
    fun rename(uri: String, newName: String): Boolean
}

/**
 * 真实执行撤销。
 *
 * ## 与 `performUndo` 的关系
 *
 * `performUndo` 是 D1 的 mock:恒返回 `applied = false` 并说明"正式版将基于
 * UndoRecord 回滚"(KDoc 原文)。本函数真的动手,并保持**同一个返回类型**
 * [UndoResult],于是报告页与两处工作流接线零改动。
 *
 * ## 顺序:逐个执行,不因单点失败而整体中断
 *
 * 撤销的一项失败不影响其余项 —— 已经删掉的文件不会因为后面一项失败而"回来",
 * 停在中途只会让状态更难解释。结果按 [UndoReport] 逐项记录,界面据此说清
 * "N 项里成功了几项"。
 *
 * ## 缺 uri 的项
 *
 * [UndoOp] 的 `uri` 为 null ⇒ 这一项来自于**模拟报告**(D1 路径)或旧数据。
 * 无从下手,记失败并给出 [TextKey.UndoFailedNoUri]。**不静默跳过** ——
 * 跳过会让"撤销 3 项"的数字虚高,与 `UndoRecord` 只收成功项同一取舍。
 */
fun performUndoReal(record: UndoRecord?, actions: UndoActions): UndoResult {
    if (record == null || record.ops.isEmpty()) {
        return UndoResult(applied = false, message = Msg(TextKey.UndoNothing))
    }
    var okCount = 0
    for (op in record.ops) {
        val ok = runCatching { applyOneUndo(op, actions) }.getOrDefault(false)
        if (ok) okCount++
    }
    val total = record.ops.size
    return if (okCount == total) {
        UndoResult(applied = true, message = Msg(TextKey.UndoApplied, listOf(total)))
    } else {
        // 一项都没成也是「部分失败」的一个特例 —— 用同一条键、同一种措辞,
        // 免得界面出现两套完全不同的话术去描述同一件事。
        UndoResult(applied = false, message = Msg(TextKey.UndoAppliedPartly, listOf(okCount, total)))
    }
}

/** 单条逆向操作的执行。返回是否真的动了文件。 */
private fun applyOneUndo(op: UndoOp, actions: UndoActions): Boolean = when (op) {
    is UndoOp.DeleteCreated -> op.uri?.let { actions.delete(it) } ?: false
    is UndoOp.DeleteOverwritten -> op.uri?.let { actions.delete(it) } ?: false
    is UndoOp.RestoreRenamed -> op.uri?.let { actions.rename(it, op.to) } ?: false
}
