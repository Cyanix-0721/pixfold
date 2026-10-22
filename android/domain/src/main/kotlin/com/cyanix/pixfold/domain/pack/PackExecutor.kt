package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.storage.SpaceThreshold
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress

/**
 * 打包执行的**唯一入口** —— 把「预检」与「执行」按可用空间是否已知分成两条路。
 *
 * ## 为什么空间未知要单独一条路
 *
 * [runPackPlansChecked] 要求 `availableBytes` 是个真实数字(它要跑
 * [preflightPackPlans] 做阻断判定)。但平台层**并非总能拿到这个数字**:
 *
 * - `FilesystemSpaceSource` 只对主存储(`com.android.externalstorage.documents` 的
 *   `primary:` 卷)给出确定答案;
 * - 第三方 SAF provider(网盘、文件管理器的虚拟目录)**没有**任何标准 API 暴露剩余空间;
 * - 外置 SD / U 盘的 `StatFs` 需要真实路径,而从 tree URI 反推路径在 scoped storage 下不可靠。
 *
 * 此时有两种做法,另一种是错的:
 *
 * - ❌ **拿一个假的"很大"的值**(如 `Long.MAX_VALUE`)⇒ 预检一定判「空间充足」,
 *   界面还会把那个假数字当成真实可用空间展示出来。这是把"不知道"伪装成"够用",
 *   用户真写满磁盘时不可能理解为什么界面说够;
 * - ❌ **拒绝执行** ⇒ 用户在有空间的情况下完全无法打包,而"无法判定"不等于"不够"。
 *
 * 故本函数走第三条:空间未知时**跳过空间阻断**,直接执行,把"能不能判定"如实
 * 通过 [PackExecutionOutcome.Ran.preflight] 为 `null` 交给上层 —— 界面据此显示
 * 「无法判定可用空间」而不是「空间充足」。
 *
 * Zip64 提示与"估算只是下界"提示同样**只在空间已知时**产出:它们都是
 * [PackPreflightResult] 的字段,没有预检就没有它们。空间未知时界面应额外提示
 * 「本次未做空间预检」,这一条由 UI 侧补,不伪造进领域结果。
 *
 * @param availableBytes 目标存储可用字节;**`null` = 无法判定**(不是 0、不是"很少");
 *   非 `null` 时必须 `>= 0`。
 * @param onProgress 进度回调,语义与 [runPackPlans] 相同。
 */
sealed interface PackExecutionOutcome {
    /** 预检结果;`null` 表示**没有做预检**(空间未知),不是"预检通过"。 */
    val preflight: PackPreflightResult?

    /** 已执行(含中途取消 —— 取消由 [PackRunReport.interruptedAt] 表达)。 */
    data class Ran(
        override val preflight: PackPreflightResult?,
        val report: PackRunReport,
    ) : PackExecutionOutcome

    /** 预检阻断,**一个输出流都没打开、一个页面字节都没读**([runPackPlansChecked] 保证)。 */
    data class Rejected(
        override val preflight: PackPreflightResult,
    ) : PackExecutionOutcome
}

/**
 * 按可用空间是否已知选择执行路径,见 [PackExecutionOutcome] 的说明。
 *
 * 这是「点下打包按钮之后发生什么」的完整答案:
 *
 * ```
 * 空间已知 → 预检 → (阻断) Rejected  /  (放行) 执行 → Ran(preflight, report)
 * 空间未知 →                       直接执行 → Ran(null, report)
 * ```
 *
 * @param threshold 空间阈值策略。**刻意不给默认值** —— 它是产品参数
 *   (§11 第 12 条「按预估的百分比?绝对值?」),由真机实测后确定,
 *   给默认值等于让调用方在不知情的情况下用上一份拍脑袋的数。
 */
fun executePackPlans(
    plans: List<VolumePackPlan>,
    availableBytes: Long?,
    threshold: SpaceThreshold,
    safetyMargin: Long,
    bytes: PageByteSource,
    sink: CbzOutputSink,
    token: CancellationToken,
    timeMillis: Long,
    onProgress: (TaskProgress, Int) -> Unit = { _, _ -> },
    onPageWritten: (volumeIndex: Int, pageDone: Int, pageTotal: Int) -> Unit = { _, _, _ -> },
): PackExecutionOutcome {
    if (availableBytes == null) {
        // 无法判定空间 ⇒ 不做空间阻断。仍走 runPackPlans,故「先探测后开流」
        // 「中断只在卷间」「写失败清理半成品」这些保证一个不少。
        return PackExecutionOutcome.Ran(
            preflight = null,
            report = runPackPlans(plans, bytes, sink, token, timeMillis, onProgress, onPageWritten),
        )
    }
    return when (
        val checked = runPackPlansChecked(
            plans = plans,
            availableBytes = availableBytes,
            threshold = threshold,
            safetyMargin = safetyMargin,
            bytes = bytes,
            sink = sink,
            token = token,
            timeMillis = timeMillis,
            onProgress = onProgress,
            onPageWritten = onPageWritten,
        )
    ) {
        is PackCheckedOutcome.Rejected ->
            PackExecutionOutcome.Rejected(checked.preflight)

        is PackCheckedOutcome.Ran ->
            PackExecutionOutcome.Ran(checked.preflight, checked.report)
    }
}
