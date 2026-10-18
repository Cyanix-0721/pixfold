package com.cyanix.pixfold.domain.source

/**
 * 工作区授权失效后的恢复决策（D2b 退出条件第 3 项）。
 *
 * ## 归责边界
 *
 * **归这里**：给定「当前状态 + 发生了什么」应当进入哪个状态、给用户什么处置选项。
 * **不归这里**：什么时候去 `takePersistableUriPermission`、什么时候重新 `query`、
 * 怎么弹系统目录选择器 —— 那些是平台层与界面层的事。
 *
 * ## 为什么值得单独一个状态机
 *
 * SAF 的授权失效**有多种成因**，而它们的正确处置**不同**：
 *
 * | 成因 | 正确的处置 |
 * | --- | --- |
 * | 用户在看系统设置里撤销了授权 | 引导**重选目录**（同一个 URI 再 `takePersistableUriPermission` 不会成功） |
 * | 目录被删除 / 存储卡拔出 | 引导**重选目录**，并说明原因（不是用户的错） |
 * | 进程被杀后重启，持久授权丢失 | 引导**重新确认**即可（多数情况下 URI 仍可用） |
 * | 权限策略临时抖动（少数 ROM） | **重试**，不要打扰用户 |
 *
 * 把「重试 / 重新确认 / 重选」这三种处置混成一句「扫描失败」，用户就无从判断
 * 该点哪个按钮；而如果一律引导重选，进程重启这种最常见的情况会让用户以为自己
 * 的目录出了问题。**故本状态机把成因显式建模**。
 *
 * ## 为什么不直接复用 [LoadResult]
 *
 * [LoadResult] 描述的是**一次扫描**的结果；本状态机描述的是**工作区在时间上的命运** ——
 * 它会跨越多轮「重试 → 失败 → 重选 → 成功」。两者的生命周期不同，
 * 硬塞进一个 sealed interface 会让「扫描失败但工作区仍健康（重试即可）」这种
 * 中间态无处安放。
 */
sealed interface WorkspaceRecovery {
    /**
     * 工作区可用，无需恢复动作。
     *
     * @property autoRetried 本次是第几次自动重试后成功的（0 = 一次成功）。
     *   用于「曾经短暂失效过」的透明化 —— 用户有权知道刚才那次卡顿是真的出了事
     *   还是只是个抖动。
     */
    data class Healthy(val autoRetried: Int = 0) : WorkspaceRecovery

    /**
     * 临时失效，**应当自动重试**，不要立刻打扰用户。
     *
     * 唯一会走进这里的成因是 [FailureCause.Transient]：多数情况下一次就好，
     * 此时弹「授权已失效，请重选目录」是纯粹的惊吓。
     *
     * @property attempt 已经重试过几次（从 0 开始）。
     */
    data class Retrying(val cause: FailureCause, val attempt: Int) : WorkspaceRecovery

    /**
     * 确定失效，**只能重选目录**。
     *
     * @property cause 具体成因（决定提示文案）。
     * @property keptName 原目录的显示名。**失效后仍要显示它** —— 用户需要知道
     *   「是哪个目录」失效了才能判断重选哪个；丢了名字只剩一句「授权失效」是没用的提示。
     */
    data class NeedsReselect(
        val cause: FailureCause,
        val keptName: String,
    ) : WorkspaceRecovery

    /**
     * 用户主动清空了工作区（点了「更换」却取消、或明确取消授权）。
     *
     * 与 [NeedsReselect] 的区别：**没有出错**，不该显示错误样式，也不该劝用户重选。
     */
    data object Cleared : WorkspaceRecovery
}

/**
 * 失效成因。**决定处置**，不是决定文案 —— 文案由界面层按 `TextKey` 解析（全量 i18n 约束）。
 */
enum class FailureCause {
    /** 授权被撤销，或目录已不存在：重选是唯一出路。 */
    Revoked,

    /** 临时性故障（provider 一时不可用、进程重启后持久授权尚未就绪）：应当重试。 */
    Transient,

    /** 其他 IO 错误：重试一次，仍失败则引导重选。 */
    Io,
}

/**
 * 一次故障事件 —— 状态机的输入。
 *
 * **刻意不复用 [LoadResult]**：`LoadResult` 是平台层的返回值，而这里需要的是
 * 「这次失败发生在什么上下文里」（是否已重试过、是自动恢复还是用户主动操作）。
 */
sealed interface RecoveryEvent {
    /** 扫描成功。 */
    data object Loaded : RecoveryEvent

    /** 扫描失败。 */
    data class LoadFailed(val cause: FailureCause) : RecoveryEvent

    /** 自动重试（平台层决定何时发起，状态机只记录次数）。 */
    data object Retried : RecoveryEvent

    /** 用户主动清空 / 取消了选择。 */
    data object Cleared : RecoveryEvent

    /** 用户重新选了一个目录（可能和原来同一个）。 */
    data object Reselected : RecoveryEvent
}

/**
 * 状态机转移规则（唯一权威）。
 *
 * ## 三条规则值得单独说明
 *
 * 1. **`Transient` 最多自动重试 [MAX_AUTO_RETRIES] 次，之后降级成 `NeedsReselect`。**
 *    无限自动重试会让用户面对一个永远转圈、永不结束的界面 —— 那比直接报错更糟。
 *
 * 2. **`Revoked` / `Io` 不做无谓的自动重试。** 授权被撤销时重试一百次也不会成功，
 *    只会让用户多等；`Io` 允许重试一次（磁盘抖动是真的），但也就一次。
 *
 * 3. **成功时保留 `autoRetried` 计数。** 界面可以据此决定是否显示一句
 *    「刚才短暂失去访问权限，已自动恢复」—— 静默恢复比反复弹窗好，但完全不告知
 *    会让用户把偶发卡顿归因成「这应用有 bug」。
 *
 * @param current 当前状态。
 * @param event 发生了什么。
 * @param keptName 当前工作区的显示名（`Cleared` 后为空）。失效状态必须保留它。
 */
fun recoverWorkspace(
    current: WorkspaceRecovery,
    event: RecoveryEvent,
    keptName: String = "",
): WorkspaceRecovery = when (event) {
    is RecoveryEvent.Loaded -> {
        // 重试过几次就从哪个状态里读回来；从 NeedsReselect 直接选成功则计数归零。
        val retried = when (current) {
            is WorkspaceRecovery.Retrying -> current.attempt + 1
            is WorkspaceRecovery.Healthy -> current.autoRetried
            else -> 0
        }
        WorkspaceRecovery.Healthy(autoRetried = retried)
    }

    is RecoveryEvent.LoadFailed -> when (current) {
        // 已经在等用户重选了，此时又失败一次 —— 不改状态（避免提示重复堆叠）。
        is WorkspaceRecovery.NeedsReselect -> current
        // 已经重试过，仍失败：不再自动重试，交给人。
        is WorkspaceRecovery.Retrying ->
            WorkspaceRecovery.NeedsReselect(event.cause, keptName)
        else -> when (event.cause) {
            FailureCause.Transient -> WorkspaceRecovery.Retrying(event.cause, attempt = 0)
            FailureCause.Revoked -> WorkspaceRecovery.NeedsReselect(event.cause, keptName)
            FailureCause.Io -> WorkspaceRecovery.Retrying(event.cause, attempt = 0)
        }
    }

    is RecoveryEvent.Retried -> when (current) {
        is WorkspaceRecovery.Retrying ->
            if (current.attempt + 1 >= MAX_AUTO_RETRIES) {
                // 用尽自动重试：降级成需要人介入。
                WorkspaceRecovery.NeedsReselect(current.cause, keptName)
            } else {
                current.copy(attempt = current.attempt + 1)
            }
        // 非重试态收到「已重试」说明平台层有 bug，但不因此丢掉已知状态。
        else -> current
    }

    is RecoveryEvent.Cleared -> WorkspaceRecovery.Cleared

    is RecoveryEvent.Reselected -> WorkspaceRecovery.Healthy()
}

/**
 * 自动重试的上限。
 *
 * 为什么是 3：`Transient` 的典型成因（进程重启后持久授权尚未就绪）在真机上
 * 通常 1 次内恢复；给到 3 次是留余量，再多就只是让用户多等。
 */
const val MAX_AUTO_RETRIES: Int = 3

/**
 * 给定当前状态，界面应当提供哪些处置动作。
 *
 * 抽成领域函数而不是写在 Composable 里，是为了让「失效时到底该出现哪些按钮」
 * 可以被离线断言 —— 这恰恰是这类流程最容易写错的地方（比如失效时忘了给重选入口，
 * 用户就被卡死了）。
 */
enum class RecoveryAction {
    /** 重试当前工作区（不重选目录）。 */
    Retry,

    /** 重新选择目录。 */
    Reselect,

    /** 什么都不用做。 */
    None,
}

fun recoveryActionsFor(state: WorkspaceRecovery): List<RecoveryAction> = when (state) {
    is WorkspaceRecovery.Healthy -> listOf(RecoveryAction.None)
    is WorkspaceRecovery.Retrying -> listOf(RecoveryAction.Retry, RecoveryAction.Reselect)
    // 关键：NeedsReselect **必须**同时给出重试与重选。只给重选的话，
    // 那些其实只是抖动、重试就好的用户就被迫重走一遍系统选择器。
    is WorkspaceRecovery.NeedsReselect -> listOf(RecoveryAction.Reselect, RecoveryAction.Retry)
    is WorkspaceRecovery.Cleared -> listOf(RecoveryAction.Reselect)
}
