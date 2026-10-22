package com.cyanix.pixfold.ui.library

import com.cyanix.pixfold.domain.pack.PackExecutionOutcome
import com.cyanix.pixfold.domain.pack.PackPreflightResult
import com.cyanix.pixfold.domain.plan.ExecutionReport
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress

/**
 * 打包执行的 UI 端口（D2b「大归档写入」）。
 *
 * ### 为什么 UI 层不直接调 `executePackPlans`
 * `android/domain/.../pack/PackExecutor.kt` 的 `executePackPlans` 是**阻塞**的：
 * 它逐页 `readBytes`、逐卷 `writeCbz`，一个 30 页的卷在慢存储上要几百毫秒。
 * 直接放在组合线程上会 ANR。真正的执行必须：
 *
 * 1. 切到 `Dispatchers.IO`；
 * 2. 构造平台实现（`SafCbzOutputSink` / `SafPageBytes` / `StatFs` 空间查询）
 *    —— 这些都需要 `Context`，领域层刻意不知道它们的存在；
 * 3. 把领域结果（`PackRunReport`）转成界面认的 `ExecutionReport`（`packRunAsExecutionReport`）。
 *
 * 这三件事全部属于「平台 + 编排」，故收进一个端口。领域层保持纯函数、可单测；
 * UI 层只依赖这个接口，测试里给一个假实现就能验证「点了确认会走到执行」。
 *
 * ### 为什么签名是 suspend 而不是回调
 * 调用点本身就是协程（`rememberCoroutineScope().launch`）。做成回调会迫使调用方
 * 自己管「在哪个线程」和「完成后谁收尾」，而这两件事在每个调用点都要重复一遍。
 *
 * ### 为什么返回 `PackExecutionOutcome` 而不是 `ExecutionReport`
 * 执行可能**根本没开始**：空间不足时 `executePackPlans` 返回
 * [PackExecutionOutcome.Rejected]、一个字节都不写。界面必须能把「拒绝」与
 * 「跑了但全失败」区分开 —— 前者该引导用户清理空间，后者该看报告里的失败原因。
 * 若端口只回 `ExecutionReport`，拒绝就只能伪装成一个空报告。
 */
interface PackExecutorPort {

    /**
     * 执行打包计划（**挂起直到全部卷处理完或被取消**）。
     *
     * @param plans 待执行的卷计划，来自 `buildPackagePlans(volumes, conflictPolicy, sourcePolicy)`。
     * @param token 取消令牌。中断只在**卷间**生效（见 `runPackPlans` 的 KDoc）。
     * @param onProgress 进度回调。**在 IO 线程上被调用**，实现方负责切回主线程；
     *   平台实现应据此节流（领域层给了 `shouldEmitProgress`），否则每页一次重组。
     * @param onPageWritten **当前卷的页级进度**：`(volumeIndex, pageDone, pageTotal)`。
     *   双层圆环的内环靠它 —— 只有卷进度时，一卷 1600 页的内圈会从 0 直接跳到 100%，
     *   看起来像卡死。同样在 IO 线程回调。
     *
     * @return 拒绝执行 / 已执行的报告 / 执行器自身故障（如拿不到输出目录）。
     */
    suspend fun execute(
        plans: List<VolumePackPlan>,
        token: CancellationToken,
        onProgress: (TaskProgress) -> Unit,
        onPageWritten: (volumeIndex: Int, pageDone: Int, pageTotal: Int) -> Unit = { _, _, _ -> },
    ): PackUiOutcome

    /**
     * **只读预检**：算出「能不能打」和「打之前该知道什么」，**一个字节都不写、一个流都不开**。
     *
     * 为什么需要它：预检原先只在 [execute] 内部、真正写盘前那一刻跑 ⇒ 用户站在
     * 「打包计划」页时看不到任何结论，`PackWarning` 的三类告知项（空间临界 /
     * 估算只是下界 / 需要 Zip64）**全都没有出口**。告知发生在用户按下「执行」之后，
     * 那时再想改方案已经来不及 —— 大卷用户会一直以为一切正常。
     *
     * 它与 [execute] 内部的预检**不是两套判据**：实现方必须复用它自己的阈值常量，
     * 否则「准备页说能打、执行时被拒绝」会出现（反之亦然）。[execute] 仍保留内部预检 ——
     * 计划可能在预检与执行之间被改动，而且执行方不能信任调用方「我已经预检过了」。
     *
     * @return 预检结果；**空间无法判定时 `availableBytes` 为 `null`** —— 见
     *   [com.cyanix.pixfold.domain.pack.PackPreflightResult] 的说明：那时不做空间阻断，
     *   但仍会给出 Zip64 等与空间无关的告知项。
     */
    suspend fun preflight(plans: List<VolumePackPlan>): PackUiPreflight
}

/**
 * 计划页预检的两种结局。
 *
 * 与领域层 [PackPreflightResult] 的区别：**多一个 [Unavailable]**。
 * 「空间未知」不是领域层能表达的状态（它要求一个 `availableBytes: Long`），
 * 但界面必须能区分「问不到空间」与「空间充足」—— 前者不该显示「空间充足」这种
 * 会被用户当承诺的结论，也不该显示「空间不足」（那是假的）。
 */
sealed interface PackUiPreflight {

    /**
     * 拿到了预检结论。
     *
     * `availableBytes == null` ⇒ 空间无法判定（非 external-storage 树 / StatFs 失败）。
     * 此时 `result` 里**不会有**空间类阻断与提示，但 Zip64、估算下界等与空间无关的
     * 告知项仍然有效 —— 它们不依赖可用空间。
     */
    data class Ready(
        val result: PackPreflightResult,
        val availableBytes: Long?,
    ) : PackUiPreflight

    /** 预检本身失败（授权失效 / 无法查询空间等）。界面据此保守处理：不显示「可以打包」。 */
    data class Unavailable(val message: String) : PackUiPreflight
}

/**
 * 打包执行在界面层的三种结局。
 *
 * 与领域层 [PackExecutionOutcome] 的区别：**多一个 [Error]**。
 * 领域层的 `executePackPlans` 只能表达「预检拒绝」与「跑了」两种 —— 它不知道
 * 「拿不到工作区授权」「输出目录不存在」这类平台故障（那些在构造 sink 之前就发生了，
 * 根本进不到 `executePackPlans`）。界面需要把这类故障与「跑完全失败」分开说，
 * 故在端口边界补一个分支。
 */
sealed interface PackUiOutcome {

    /**
     * 真的跑了（可能有失败项）。`report` **可能 `isComplete == false`**（用户中途取消）。
     *
     * 注意 `report` 的类型是 `ExecutionReport` 而不是 `PackRunReport`：
     * 界面（`ExecutionReportPage`）与撤销记录只认前者，且
     * `packRunAsExecutionReport` 已经保证「只有真正写出的卷才带 `createdPath`」
     * （这是撤销记录不会误删用户原有文件的唯一保证）。
     */
    data class Done(val report: ExecutionReport) : PackUiOutcome

    /**
     * 空间预检拒绝，**一个字节都没写**。
     *
     * `preflight.preflight.blockers` 里是拒绝理由（目前只有 `SpaceInsufficient`），
     * `warnings` 里是「估算只是下界」「Zip64」这类告知项 —— 拒绝时也要显示，
     * 因为用户可能需要知道「即便腾出空间，这个卷也超出 ZIP64 边界」。
     */
    data class Rejected(val preflight: PackPreflightResult) : PackUiOutcome

    /** 执行器自身故障（授权失效 / 输出目录不存在 / 无法查询空间等）。 */
    data class Error(val message: String) : PackUiOutcome
}
