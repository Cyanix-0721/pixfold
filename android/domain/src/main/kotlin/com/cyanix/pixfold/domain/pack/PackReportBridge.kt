package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.plan.ExecEntry
import com.cyanix.pixfold.domain.plan.ExecKind
import com.cyanix.pixfold.domain.plan.ExecOutcome
import com.cyanix.pixfold.domain.plan.ExecutionReport
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey

/**
 * 把**真实写盘**的结果 [PackRunReport] 转成界面已在用的 [ExecutionReport]。
 *
 * ## 为什么需要这个转换(两套报告为什么不能只留一套)
 *
 * 它们是两个不同阶段的真话:
 *
 * - [ExecutionReport] 是 **D3 的执行报告**,KDoc 原文「D1 原型不触碰真实文件系统,
 *   故"执行"是**确定性模拟**」。它描述"按计划会发生什么",用来在**执行前**给用户确认。
 * - [PackRunReport] 是 **D4b 的真实写盘结果**,描述"刚才到底写出了什么"。
 *
 * 与其把 UI 改成同时认识两套报告(报告页、撤销入口、文案解析全都要分叉),
 * 不如做一个**单向转换**:真实执行完之后,把结果表达成 UI 已经会渲染的那一种。
 * 这样报告页、存档表格、撤销逻辑全部**零改动**就吃到真实数据。
 *
 * ## 为什么要传 [plans]
 *
 * [PackRunReport.entries] 只含**已处理**的卷;中断后剩下的卷**不在里面**。
 * 报告行数若比计划少几行,用户拿报告核对计划时会以为"计划里那几卷凭空消失了"。
 * 故把完整 [plans] 一并传入,才能把未处理的卷补成如实的一行。
 * (不从 `interruptedAt` 反推:计划内容在 [PackRunReport] 里没有,只在调用方手里。)
 *
 * ## 转换的三条硬规则
 *
 * 1. **只有 `Written` 才算 `Succeeded` 且带 `createdPath`** —— 这是 [ExecEntry]
 *    的 init 强制的不变式(`Succeeded` ⟺ `createdPath != null`)。其余一律 `createdPath = null`,
 *    于是它们**自动不进撤销记录**(`undoOpsOf` 只收带 createdPath 的项)。
 * 2. **`originalPath` 取 [VolumePackPlan.overwritesExisting] 的结果** —— 覆盖的场景下输出位置上
 *    原本就有用户的文件。这一项一旦填上,`ExecutionReport.overwrittenCount` 与
 *    `needsUndoWarning` 就自动正确,界面会据此在执行后提示"撤销有风险"。
 *    ⚠️ 判据必须复用 [VolumePackPlan.overwritesExisting] 这**唯一**一处实现,不许在这里
 *    再写一遍 `conflictPolicy == Overwrite && outputExists` —— 那正是缺陷 R3 最贵的部分
 *    (界面警告了覆盖、撤销却按新建删)。
 * 3. **中断后未处理的卷要如实补进报告**(`Skipped` + 「尚未开始」原因),理由见上。
 *
 * ## 归责边界
 *
 * **归这里**:把机器结果翻译成报告词汇(哪种 outcome 对应哪类 ExecEntry、哪些卷要补行)。
 * **不归这里**:决定"要不要执行"(那是 [runPackPlansChecked] / [PackPreflightResult])、
 * 真的去写(ZIP 是 [runPackPlans])、撤销怎么做(那是 `undoRecordOf`)。
 * 本文件**没有任何副作用**,纯函数。
 */
fun packRunAsExecutionReport(
    plans: List<VolumePackPlan>,
    report: PackRunReport,
): ExecutionReport {
    val entries = ArrayList<ExecEntry>(plans.size)
    for (entry in report.entries) {
        val plan = entry.plan
        val createdPath = when (entry.outcome) {
            is PackRunOutcome.Written -> plan.outputPath
            else -> null
        }
        // 只有真的写出去了才算"改动了那个位置";没写出去的项不许带 originalPath
        // (ExecEntry 的 init 会拒绝非成功项携带它)。
        val originalPath = if (createdPath != null && plan.overwritesExisting) plan.outputPath else null
        val (outcome, reason) = when (val o = entry.outcome) {
            is PackRunOutcome.Written -> ExecOutcome.Succeeded to null
            PackRunOutcome.Skipped -> ExecOutcome.Skipped to plan.skipReason
            is PackRunOutcome.MissingPages -> ExecOutcome.Failed to Msg(
                TextKey.PackFailMissingPages,
                listOf(o.missing.size),
            )
            // 写盘失败详情原样带出:ENOSPC / EPERM / provider 抛错 这些原因,
            // 用户与开发者都无法从「写入失败」四个字里猜到,而它是唯一线索。
            is PackRunOutcome.Failed -> ExecOutcome.Failed to Msg(
                TextKey.PackFailWrite,
                listOf(o.detail),
            )
        }
        entries += ExecEntry(
            id = plan.volume.id,
            label = plan.outputDisplay,
            outcome = outcome,
            reason = reason,
            createdPath = createdPath,
            originalPath = originalPath,
        )
    }
    // 中断后没轮到的卷补成 Skipped,保证报告行数 == 计划行数。
    val processedIds = report.entries.mapTo(HashSet()) { it.plan.volume.id }
    for (plan in plans) {
        if (plan.volume.id in processedIds) continue
        entries += ExecEntry(
            id = plan.volume.id,
            label = plan.outputDisplay,
            outcome = ExecOutcome.Skipped,
            reason = Msg(TextKey.PackFailNotStarted),
        )
    }
    return ExecutionReport(entries = entries, kind = ExecKind.Package)
}
