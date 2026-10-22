package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.storage.SpaceThreshold
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress

/**
 * 预检 → 执行的闸门（D4b 执行链的入口）。
 *
 * ## 为什么需要这一层
 *
 * [preflightPackPlans] 与 [runPackPlans] 各自单测通过，但两者之间**没有任何代码联系** ——
 * 「预检拒绝就别写」在此之前只是注释里的承诺。这不是理论风险：[runPackPlans] 一旦被调用
 * 就会逐卷 `openFor` + 写字节，预检结论被无视时，用户会在**已经知道空间不足**的情况下
 * 看着程序写到一半失败，并在目标目录里留下若干半成品。
 *
 * 故这一层把「拒绝」做成**可执行的保证**：拒绝路径上
 * **一个输出流都不打开、一个页面字节都不读**。断言「一次都没打开」而不是
 * 「打开了但没写」，因为**打开本身就已经在用户目录里创建了文件** ——
 * 那正是 [runPackPlans] 里「先探测后开流」要避免的事。
 *
 * ## 归责边界
 *
 * - **归这里**：把预检结论变成控制流（有阻断项就不进入执行），并把预检结果与实际
 *   执行报告一起带回；
 * - **不归这里**：可用空间从哪来（平台层）、阈值取多少（调用方显式给，
 *   见 `preflightPackPlans` 的 KDoc）、拦截后怎么提示用户（UI 的事）。
 *
 * 零 Android import（`AdapterBoundaryTest` 强制）。
 */

/** 「先预检、后执行」的结果。两种情形都带上预检明细，界面不必自己再算一遍。 */
sealed interface PackCheckedOutcome {
    /**
     * 预检发现阻断项，**没有动任何文件**。
     *
     * [preflight] 必须原样带回：[PackBlocker.SpaceInsufficient] 里有 `shortfallBytes`，
     * 界面据此才能说清「还差 1.2 GB」而不是笼统的「空间不足」。
     */
    data class Rejected(val preflight: PackPreflightResult) : PackCheckedOutcome

    /** 预检放行并已执行（执行本身可能中断，见 `report.interruptedAt`）。 */
    data class Ran(
        val preflight: PackPreflightResult,
        val report: PackRunReport,
    ) : PackCheckedOutcome
}

/**
 * 预检通过才执行；有阻断项则立刻返回 [PackCheckedOutcome.Rejected]。
 *
 * 参数与 [runPackPlans] 一一对应，另加三个预检参数 —— 这样调用方没法「忘了预检」：
 * 想真正写盘就得提供可用空间与阈值，提供它们就意味着预检已经发生了。
 *
 * @param availableBytes 目标存储可用空间（平台层提供）。
 * @param threshold 空间阈值策略。**不设默认值**，理由见 `preflightPackPlans`。
 * @param safetyMargin 临界余量（只产生提示，不阻止）。
 */
fun runPackPlansChecked(
    plans: List<VolumePackPlan>,
    availableBytes: Long,
    threshold: SpaceThreshold,
    safetyMargin: Long,
    bytes: PageByteSource,
    sink: CbzOutputSink,
    token: CancellationToken,
    timeMillis: Long,
    onProgress: (TaskProgress, Int) -> Unit = { _, _ -> },
    onPageWritten: (volumeIndex: Int, pageDone: Int, pageTotal: Int) -> Unit = { _, _, _ -> },
): PackCheckedOutcome {
    val preflight = preflightPackPlans(
        plans = plans,
        availableBytes = availableBytes,
        threshold = threshold,
        safetyMargin = safetyMargin,
    )
    // 拒绝路径：在这里就返回 ⇒ runPackPlans 根本不会被执行 ⇒ sink/bytes 一次都不会被碰。
    if (!preflight.canProceed) return PackCheckedOutcome.Rejected(preflight)

    return PackCheckedOutcome.Ran(
        preflight = preflight,
        report = runPackPlans(
            plans = plans,
            bytes = bytes,
            sink = sink,
            token = token,
            timeMillis = timeMillis,
            onProgress = onProgress,
            onPageWritten = onPageWritten,
        ),
    )
}
