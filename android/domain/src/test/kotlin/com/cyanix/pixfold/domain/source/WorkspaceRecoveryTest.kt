package com.cyanix.pixfold.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [recoverWorkspace] 与 [recoveryActionsFor] 的单测（D2b 退出条件第 3 项）。
 *
 * 核心意图：**不同成因的正确处置不同**。把「重试」和「重选」混为一谈时，
 * 最常见的情况（进程重启后持久授权未就绪）会让用户误以为自己的目录坏了。
 */
class WorkspaceRecoveryTest {

    private fun fail(cause: FailureCause) = RecoveryEvent.LoadFailed(cause)

    // ==================== 首次失败：按成因分流 ====================

    @Test
    fun `临时性故障进入自动重试而不是立刻打扰用户`() {
        val next = recoverWorkspace(WorkspaceRecovery.Healthy(), fail(FailureCause.Transient))

        val retrying = assertIs<WorkspaceRecovery.Retrying>(next)
        assertEquals(FailureCause.Transient, retrying.cause)
        assertEquals(0, retrying.attempt, "首次失败时还没重试过")
    }

    @Test
    fun `授权被撤销直接要求重选不做无谓重试`() {
        // 撤销后重试一百次也不会成功，只会让用户多等。
        val next = recoverWorkspace(WorkspaceRecovery.Healthy(), fail(FailureCause.Revoked), keptName = "旅行照片")

        val needs = assertIs<WorkspaceRecovery.NeedsReselect>(next)
        assertEquals(FailureCause.Revoked, needs.cause)
    }

    @Test
    fun `IO 错误先给一次自动重试`() {
        // 磁盘抖动是真的，但也就给一次。
        val next = recoverWorkspace(WorkspaceRecovery.Healthy(), fail(FailureCause.Io))
        assertIs<WorkspaceRecovery.Retrying>(next)
    }

    @Test
    fun `失效时必须保留原目录名`() {
        // 丢了名字只剩「授权失效」，用户无从判断该重选哪个目录。
        val next = recoverWorkspace(WorkspaceRecovery.Healthy(), fail(FailureCause.Revoked), keptName = "旅行照片")

        val needs = assertIs<WorkspaceRecovery.NeedsReselect>(next)
        assertEquals("旅行照片", needs.keptName)
    }

    // ==================== 重试上限 ====================

    @Test
    fun `临时性故障重试到上限后降级为需要重选`() {
        // 无限自动重试会留下一个永远转圈、永不结束的界面 —— 比直接报错更糟。
        var state: WorkspaceRecovery = recoverWorkspace(WorkspaceRecovery.Healthy(), fail(FailureCause.Transient))

        repeat(MAX_AUTO_RETRIES) { state = recoverWorkspace(state, RecoveryEvent.Retried, keptName = "相册") }

        val needs = assertIs<WorkspaceRecovery.NeedsReselect>(state)
        assertEquals(FailureCause.Transient, needs.cause)
        assertEquals("相册", needs.keptName)
    }

    @Test
    fun `上限之内重试不会提前降级`() {
        var state: WorkspaceRecovery = recoverWorkspace(WorkspaceRecovery.Healthy(), fail(FailureCause.Transient))

        repeat(MAX_AUTO_RETRIES - 1) { state = recoverWorkspace(state, RecoveryEvent.Retried) }

        assertIs<WorkspaceRecovery.Retrying>(state)
    }

    @Test
    fun `用尽重试后再失败仍然维持需要重选`() {
        // 已经在等用户处置时又失败一次：不该把状态弹回重试，也不该清掉成因。
        var state: WorkspaceRecovery = recoverWorkspace(WorkspaceRecovery.Healthy(), fail(FailureCause.Revoked), keptName = "相册")
        assertIs<WorkspaceRecovery.NeedsReselect>(state)

        state = recoverWorkspace(state, fail(FailureCause.Io), keptName = "相册")

        val needs = assertIs<WorkspaceRecovery.NeedsReselect>(state)
        assertEquals(FailureCause.Revoked, needs.cause, "已确定的成因不该被后续杂音覆盖")
    }

    // ==================== 恢复 ====================

    @Test
    fun `重试成功后记录重试次数`() {
        // 用户有权知道刚才那次卡顿是真的出了事还是只是个抖动。
        var state: WorkspaceRecovery = recoverWorkspace(WorkspaceRecovery.Healthy(), fail(FailureCause.Transient))
        state = recoverWorkspace(state, RecoveryEvent.Retried)
        state = recoverWorkspace(state, RecoveryEvent.Loaded)

        val healthy = assertIs<WorkspaceRecovery.Healthy>(state)
        assertEquals(2, healthy.autoRetried, "重试了 2 次才成功")
    }

    @Test
    fun `一次成功时重试次数为零`() {
        val next = recoverWorkspace(WorkspaceRecovery.Healthy(), RecoveryEvent.Loaded)
        assertEquals(0, assertIs<WorkspaceRecovery.Healthy>(next).autoRetried)
    }

    @Test
    fun `重选成功后计数归零`() {
        // 换了目录，旧的抖动历史与它无关了。
        var state: WorkspaceRecovery = recoverWorkspace(WorkspaceRecovery.Healthy(), fail(FailureCause.Revoked), keptName = "旧目录")
        state = recoverWorkspace(state, RecoveryEvent.Reselected)

        assertEquals(0, assertIs<WorkspaceRecovery.Healthy>(state).autoRetried)
    }

    @Test
    fun `健康状态收到已重试事件保持不变`() {
        // 非重试态收到「已重试」说明平台层有 bug，但不该因此丢掉已知状态。
        val healthy = WorkspaceRecovery.Healthy(autoRetried = 1)
        assertEquals(healthy, recoverWorkspace(healthy, RecoveryEvent.Retried))
    }

    // ==================== 主动清空 ====================

    @Test
    fun `用户主动清空进入 Cleared 而不是错误态`() {
        // 没有出错，不该显示错误样式，也不该劝用户重选。
        val next = recoverWorkspace(WorkspaceRecovery.Healthy(), RecoveryEvent.Cleared)
        assertEquals(WorkspaceRecovery.Cleared, next)
    }

    // ==================== 处置动作 ====================

    @Test
    fun `健康时不需要任何处置动作`() {
        assertEquals(listOf(RecoveryAction.None), recoveryActionsFor(WorkspaceRecovery.Healthy()))
    }

    @Test
    fun `失效时必须同时给重试与重选`() {
        // 只给重选的话，那些其实只是抖动、重试就好的用户被迫重走系统选择器。
        val actions = recoveryActionsFor(
            WorkspaceRecovery.NeedsReselect(FailureCause.Revoked, "相册"),
        )

        assertEquals(listOf(RecoveryAction.Reselect, RecoveryAction.Retry), actions)
        // 顺序也有意义：主按钮是重选（确定的出路），次要是重试。
        assertEquals(RecoveryAction.Reselect, actions.first())
    }

    @Test
    fun `重试中同时给重试与重选`() {
        val actions = recoveryActionsFor(WorkspaceRecovery.Retrying(FailureCause.Transient, 1))
        assertEquals(listOf(RecoveryAction.Retry, RecoveryAction.Reselect), actions)
    }

    @Test
    fun `清空后只给重选`() {
        // 清空是用户自己的选择，此时给「重试」是荒谬的 —— 没有东西可重试。
        assertEquals(listOf(RecoveryAction.Reselect), recoveryActionsFor(WorkspaceRecovery.Cleared))
    }

    @Test
    fun `任何失效状态都至少有一条出路`() {
        // 终极兜底：用户永远不该被卡在一个无路可走的界面里。
        // 刻意不含 Healthy —— 它本就不需要动作（见上一条用例）。
        val all: List<WorkspaceRecovery> = listOf(
            WorkspaceRecovery.Retrying(FailureCause.Transient, 0),
            WorkspaceRecovery.Retrying(FailureCause.Io, 2),
            WorkspaceRecovery.NeedsReselect(FailureCause.Revoked, ""),
            WorkspaceRecovery.NeedsReselect(FailureCause.Io, "x"),
            WorkspaceRecovery.Cleared,
        )

        all.forEach { state ->
            val actions = recoveryActionsFor(state)
            assertEquals(
                true,
                actions.any { it != RecoveryAction.None },
                "$state 没有任何可用动作",
            )
        }
    }
}
