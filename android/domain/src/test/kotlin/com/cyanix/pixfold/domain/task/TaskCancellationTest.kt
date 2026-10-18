package com.cyanix.pixfold.domain.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 批量任务的**中断请求**语义（D2b 退出条件第 2 项「后台任务与进度」）。
 *
 * ## 为什么中断是领域问题
 *
 * 「用户按了取消」不是一个布尔开关那么简单。真机上取消有四个必须说清的问题：
 * 1. **取消不是立刻生效**：正在写的那一项通常已经开始了，硬停会留下半成品；
 * 2. **取消后要能说清停在哪**：报告里必须有「已完成 N 项、剩余 M 项」；
 * 3. **取消与完成可能同时到达**：用户点取消的瞬间任务恰好跑完 —— 这时
 *    报「已取消」是骗人的，实际是完成了；
 * 4. **取消后不能回滚已完成的部分**：已落盘的就是已落盘（撤回另走撤销路径）。
 *
 * 这四条与 Android 无关，故在领域层用纯函数钉死。
 */
class TaskCancellationTest {

    @Test
    fun `未请求中断时任务正常运行`() {
        val token = CancellationToken()
        assertFalse(token.isCancelled)
        assertFalse(token.shouldStop(beforeStartingItem = 0))
    }

    @Test
    fun `请求中断后下一项开始前必须停`() {
        val token = CancellationToken()
        token.cancel()
        assertTrue(token.isCancelled)
        assertTrue(token.shouldStop(beforeStartingItem = 5))
    }

    @Test
    fun `已经开始的那一项允许跑完不做半成品`() {
        // 判据是「开始下一项之前」检查，不是「每写一个字节检查一次」。
        // 半途硬停会在目标目录留下半个文件，比多花一秒钟糟得多。
        val token = CancellationToken()
        assertTrue(
            token.shouldStop(beforeStartingItem = 0).not(),
            "还没开始第一项时若没取消就不该停",
        )
        token.cancel()
        assertTrue(token.shouldStop(beforeStartingItem = 1), "第 2 项开始前应当停下")
    }

    @Test
    fun `重复请求中断无害`() {
        val token = CancellationToken()
        token.cancel()
        token.cancel()
        assertTrue(token.isCancelled)
    }

    /** ⭐ 取消与完成同时到达：结果必须如实说「完成」，不能骗人说「取消了」。 */
    @Test
    fun `取消请求晚于实际完成时结果是完成而不是取消`() {
        val token = CancellationToken()
        val progress = TaskProgress(total = 3, done = 3)
        token.cancel()

        val outcome = taskOutcomeOf(progress = progress, token = token)

        assertIs<TaskOutcome.Completed>(outcome)
        assertEquals(3, outcome.progress.processed)
    }

    @Test
    fun `取消后未跑完的结果是已取消并带已完成数`() {
        val token = CancellationToken()
        token.cancel()
        val progress = TaskProgress(total = 10, done = 4, failed = 1)

        val outcome = taskOutcomeOf(progress = progress, token = token)

        val cancelled = assertIs<TaskOutcome.Cancelled>(outcome)
        assertEquals(TaskProgress(total = 10, done = 4, failed = 1), cancelled.progress)
        assertEquals(5, cancelled.progress.processed, "取消后要能说清完成了多少")
        assertEquals(5, cancelled.remaining, "取消后要能说清还剩多少")
    }

    @Test
    fun `没取消且跑完的结果是完成`() {
        val outcome = taskOutcomeOf(progress = TaskProgress(total = 3, done = 3), token = CancellationToken())
        assertIs<TaskOutcome.Completed>(outcome)
    }

    @Test
    fun `没取消且没跑完的结果是进行中`() {
        val outcome = taskOutcomeOf(progress = TaskProgress(total = 10, done = 3), token = CancellationToken())
        assertEquals(7, assertIs<TaskOutcome.Running>(outcome).remaining)
    }

    @Test
    fun `空任务即使被取消也算完成`() {
        // 空目录：用户点了取消，但其实无事可做。报「已取消」会让人以为出了岔子。
        val token = CancellationToken()
        token.cancel()
        assertIs<TaskOutcome.Completed>(taskOutcomeOf(TaskProgress(total = 0), token))
    }
}
