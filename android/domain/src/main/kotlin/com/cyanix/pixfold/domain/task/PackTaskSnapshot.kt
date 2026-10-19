package com.cyanix.pixfold.domain.task

/**
 * 打包任务的生命周期状态（D2b「后台任务与进度 · 切后台恢复」）。
 *
 * ## 为什么是五个状态而不是「跑着 / 跑完了」
 *
 * 恢复逻辑要回答的问题不止「完没完」，还有「**要不要担心磁盘上有半成品**」：
 *
 * - [Running]   —— 还在写。若进程已不在，说明被系统回收 ⇒ 大概率有半成品。
 * - [Finished]  —— 跑完了。**可能含失败项**（失败是"处理过了没成功"，不是"没处理"，
 *   与 `PackRunReport.isComplete` 的语义一致）。一个字节都不用担心。
 * - [Cancelled] —— 用户主动停的。中断只在**卷间**生效，正在写的那一卷会写完，
 *   故已完成的部分都是完整文件；剩余卷根本没碰。
 * - [Rejected]  —— 预检拒绝（空间不足 / 输出名撞车）。**一个输出流都没打开**，
 *   磁盘上什么都没有，恢复时不该吓唬用户。
 * - [Aborted]   —— 执行器自身故障（授权失效 / 输出目录不见了）。写没写完不确定，
 *   按 [Running] 同等对待。
 *
 * 把 [Rejected] 与 [Aborted] 混成一个「Failed」会让恢复提示分不清
 * 「什么都没发生」与「可能写到一半」—— 前者不该提示，后者必须提示。
 */
enum class PackTaskPhase {
    Running,
    Finished,
    Cancelled,
    Rejected,
    Aborted,
}

/**
 * 一次打包任务的可持久化快照。
 *
 * ## 为什么需要快照（而不是只靠内存里的 StateFlow）
 *
 * 前台服务能在切后台时继续跑，但**进程被系统回收时服务也会死**。那一刻：
 * 磁盘上可能留着一个没写完的 CBZ，而内存里的一切都消失了。用户下次打开应用
 * 只会看到「什么都没有」，然后自己发现目录里有个打不开的文件。
 *
 * 所以每次进度推进都要把「我正在写这些文件」落到磁盘上，下次启动据此提示。
 * 这**不是**为了自动续跑（见 [PackTaskRecovery.Interrupted] 的说明）。
 *
 * ## 字段为什么是这几个
 *
 * - [taskId] 区分「同一个任务」与「新任务」。恢复提示只对**同一次任务**有意义。
 * - [outputTargets] 是恢复提示的核心：告诉用户「上次要写这 3 个文件」，
 *   平台层再逐个检查完整性。没有它，恢复只能干说一句「上次可能没写完」。
 * - [currentVolume] 只用于**通知文案**（「正在处理 xxx」）。它不参与恢复判定 ——
 *   被杀时正在写的那一卷，与 [outputTargets] 里任意一卷都有可能不完整。
 * - [progress] 复用 [TaskProgress]：`total`/`processed` 的语义已经定好，
 *   自建一套计数只会让两处慢慢漂移。
 *
 * @param taskId 任务标识。由调用方给（同一批次内唯一），恢复时用来判断是不是同一次。
 * @param phase 生命周期状态，见 [PackTaskPhase]。
 * @param startedAtMillis 开始时间（epoch millis）。用于「上次是多久以前」的人类文案。
 * @param updatedAtMillis 最近一次更新。恢复提示用「快要完成」的判断只应看 [progress]，
 *   不看时间 —— 用户可能中途锁屏几小时，时间差不代表异常。
 * @param progress 进度快照。
 * @param currentVolume 正在处理的卷显示名；`null` = 不在处理某一卷（例如刚开始）。
 * @param outputTargets 本次计划写出的**完整显示路径**，用于恢复时逐个查完整性。
 * @param failureDetail 失败详情（[PackTaskPhase.Aborted] 时有值），给用户看的原始信息。
 */
data class PackTaskSnapshot(
    val taskId: String,
    val phase: PackTaskPhase,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val progress: TaskProgress,
    val currentVolume: String? = null,
    val outputTargets: List<String> = emptyList(),
    val failureDetail: String? = null,
) {
    /** 是否处于「磁盘上可能有不完整文件」的状态。恢复提示的开关。 */
    val mayLeavePartialFiles: Boolean
        get() = phase == PackTaskPhase.Running || phase == PackTaskPhase.Aborted

    /** 已处理卷数（成功 + 失败 + 跳过）。 */
    val processedVolumes: Int get() = progress.processed

    /** 还剩多少卷没处理。 */
    val remainingVolumes: Int get() = progress.remaining

    /** 进度推进（只改计数与时间戳，不动任务身份与目标列表）。 */
    fun withProgress(
        next: TaskProgress,
        currentVolume: String? = this.currentVolume,
        nowMillis: Long,
    ): PackTaskSnapshot = copy(
        progress = next,
        currentVolume = currentVolume,
        updatedAtMillis = nowMillis,
    )

    /** 收尾：落到终态，清掉「正在处理某卷」。 */
    fun finished(
        phase: PackTaskPhase,
        nowMillis: Long,
        failureDetail: String? = null,
    ): PackTaskSnapshot {
        require(phase != PackTaskPhase.Running) { "收尾不能用 Running，那是进行中的状态：$phase" }
        return copy(
            phase = phase,
            currentVolume = null,
            updatedAtMillis = nowMillis,
            failureDetail = failureDetail,
        )
    }
}

/**
 * 恢复判定的结果。
 *
 * 由 [recoverPackTask] 根据「快照说什么」+「服务还活着吗」两个输入得出。
 */
sealed interface PackTaskRecovery {

    /** 没有遗留任务。正常启动路径。 */
    data object None : PackTaskRecovery

    /**
     * 任务还在跑（服务活着，界面重新连上）。
     *
     * 不需要提示也不需要修复 —— UI 订阅进度继续显示即可。
     */
    data class StillRunning(val snapshot: PackTaskSnapshot) : PackTaskRecovery

    /**
     * 上次任务被中断，磁盘上**可能**有半成品。
     *
     * ## 为什么刻意不做「自动续跑」
     *
     * 中断时刻的真实状态是不可知的：进程可能死在写完某一卷之后、也可能死在
     * `openOutputStream` 刚创建文件、还没写第一个字节时。自动续跑会在这两种情况下
     * 做出不同的错事 —— 前者重复写一遍（把已完成的卷当成没完成），
     * 后者会从一个半截文件继续追加（产出一个连 EOCD 都没有的坏 ZIP）。
     *
     * 正确做法是**让用户确认**：告诉他上次要写哪几个文件、我们查到哪几个不完整。
     * 用户自己看一眼就知道该删掉重打还是保留。
     *
     * @param snapshot 被中断的那次任务的快照。
     * @param outputsToVerify 需要平台层逐个检查完整性的输出路径。
     *   刻意不在这里判 —— 领域层不读文件。
     */
    data class Interrupted(
        val snapshot: PackTaskSnapshot,
        val outputsToVerify: List<String>,
    ) : PackTaskRecovery

    /**
     * 上次任务正常结束（成功/取消/被拒/故障），**无需提示**。
     *
     * 带快照是因为 `Finished` 的报告可能在界面重建后仍需展示；
     * 调用方可以不理它（多数情况直接丢弃即可）。
     */
    data class Settled(val snapshot: PackTaskSnapshot) : PackTaskRecovery
}

/**
 * 根据快照与服务存活情况判定该怎么处理上次的打包任务。
 *
 * ## 两个输入缺一不可
 *
 * 只有快照时，`phase == Running` 有两种完全不同的含义：
 * 服务还在跑（界面刚重建），或服务已经死了（进程被回收）。
 * 前者不该打扰用户，后者必须提示。所以 [serviceAlive] 是必填参数，
 * 而不是给一个「猜」的默认值。
 *
 * ## 为什么 [PackTaskPhase.Rejected] 不算中断
 *
 * 预检拒绝意味着**一个输出流都没打开**（`runPackPlansChecked` 的契约）。
 * 磁盘上不可能有半成品，提示用户反而制造恐慌：「上次打包可能没完成」而实际上
 * 什么都没写。这类任务按 [PackTaskRecovery.Settled] 处理。
 *
 * @param snapshot 上次落盘的快照；`null` = 没有遗留。
 * @param serviceAlive 打包前台服务此刻是否仍在运行。
 */
fun recoverPackTask(
    snapshot: PackTaskSnapshot?,
    serviceAlive: Boolean,
): PackTaskRecovery {
    if (snapshot == null) return PackTaskRecovery.None

    return when (snapshot.phase) {
        PackTaskPhase.Running ->
            if (serviceAlive) {
                PackTaskRecovery.StillRunning(snapshot)
            } else {
                // 服务没了但快照停在 Running ⇒ 进程被回收 / 系统杀服务 / 崩溃。
                // 这不是「失败」，是「没来得及收尾」—— 按可能留半成品处理。
                PackTaskRecovery.Interrupted(snapshot, snapshot.outputTargets)
            }

        // 执行器故障时写没写完不确定 ⇒ 与 Running 同等对待。
        PackTaskPhase.Aborted ->
            PackTaskRecovery.Interrupted(snapshot, snapshot.outputTargets)

        // 跑完 / 用户取消 / 预检拒绝：磁盘状态是确定的。
        PackTaskPhase.Finished,
        PackTaskPhase.Cancelled,
        PackTaskPhase.Rejected,
        -> PackTaskRecovery.Settled(snapshot)
    }
}
