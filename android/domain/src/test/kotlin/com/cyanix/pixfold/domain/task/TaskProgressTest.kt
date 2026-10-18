package com.cyanix.pixfold.domain.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 任务进度与上报节流（D2b 退出条件第 2 项「后台任务与进度」）。
 *
 * ## 这一层为什么是领域问题而不是界面问题
 *
 * 「进度怎么算」和「多久上报一次」看起来像纯 UI 的事，其实两个都是**产品语义**：
 * - 失败项算不算进度：不算的话，一批里失败 30% 时进度条会卡在 70% 不动，
 *   用户以为任务死了 —— 而它其实跑完了；
 * - 上报多密：每张图都上报一次，千级任务就是上千次状态更新，列表疯狂重组；
 *   但**最后一次必须上报**，否则界面永远停在 99%。
 *
 * 两条都与「有没有 Android」无关，故放在领域层并用纯函数钉死。
 */
class TaskProgressTest {

    @Test
    fun `空任务进度直接视为完成`() {
        // 0/0 不能算 0%（那会让空目录看起来像没开始），也不能除零崩掉。
        val p = TaskProgress(total = 0)
        assertEquals(1f, p.fraction)
        assertEquals(100, p.percent)
        assertTrue(p.isComplete)
    }

    @Test
    fun `已处理数等于成功加失败`() {
        val p = TaskProgress(total = 10, done = 3, failed = 2)
        assertEquals(5, p.processed)
    }

    /** ⭐ 核心语义：失败项**也推进进度**。 */
    @Test
    fun `失败项同样推进进度否则进度条会卡住`() {
        val p = TaskProgress(total = 10, done = 7, failed = 3)
        assertEquals(100, p.percent)
        assertTrue(p.isComplete, "跑完就是跑完，哪怕全都失败了")
    }

    @Test
    fun `百分比按已处理数算而不是按成功数`() {
        val p = TaskProgress(total = 10, done = 2, failed = 3)
        assertEquals(50, p.percent)
    }

    @Test
    fun `进度不会超过百分之百`() {
        // 平台层重复回调是真实会发生的（重试、事件重放），不能因此显示 130%。
        val p = TaskProgress(total = 10, done = 8, failed = 8)
        assertEquals(100, p.percent)
        assertEquals(1f, p.fraction)
    }

    @Test
    fun `负数输入被夹到零`() {
        val p = TaskProgress(total = 10, done = -3, failed = -2)
        assertEquals(0, p.processed)
        assertEquals(0, p.percent)
    }

    @Test
    fun `还没开始的任务进度是零`() {
        val p = TaskProgress(total = 10)
        assertEquals(0, p.percent)
        assertFalse(p.isComplete)
        assertEquals(0f, p.fraction)
    }

    // ==================== 上报节流 ====================

    @Test
    fun `第一次进度必须上报`() {
        assertTrue(
            shouldEmitProgress(
                last = null,
                next = TaskProgress(total = 100, done = 1),
                lastEmitAtMillis = 0,
                nowMillis = 0,
            ),
        )
    }

    /** ⭐ 核心语义：最后一次必须上报，否则界面停在 99%。 */
    @Test
    fun `完成时必须上报哪怕离上次只过了一毫秒`() {
        assertTrue(
            shouldEmitProgress(
                last = TaskProgress(total = 100, done = 99),
                next = TaskProgress(total = 100, done = 100),
                lastEmitAtMillis = 1_000,
                nowMillis = 1_001,
            ),
            "完成事件被节流掉 = 进度条永远停在 99%",
        )
    }

    @Test
    fun `刚上报过且百分比没变就不再上报`() {
        assertFalse(
            shouldEmitProgress(
                last = TaskProgress(total = 1_000, done = 500),
                next = TaskProgress(total = 1_000, done = 500),
                lastEmitAtMillis = 1_000,
                nowMillis = 1_050,
            ),
        )
    }

    @Test
    fun `间隔太短且百分比没跨档就不上报`() {
        // 千级任务每张图都上报 = 上千次状态更新，列表疯狂重组。
        assertFalse(
            shouldEmitProgress(
                last = TaskProgress(total = 1_000, done = 500),
                next = TaskProgress(total = 1_000, done = 501),
                lastEmitAtMillis = 1_000,
                nowMillis = 1_010,
            ),
        )
    }

    @Test
    fun `百分比跨过一档就上报哪怕间隔很短`() {
        // 50% → 56%，跨过 PROGRESS_EMIT_STEP_PERCENT 的 5 个点。
        assertTrue(
            shouldEmitProgress(
                last = TaskProgress(total = 1_000, done = 500),
                next = TaskProgress(total = 1_000, done = 560),
                lastEmitAtMillis = 1_000,
                nowMillis = 1_001,
            ),
        )
    }

    @Test
    fun `没跨满一档且间隔不够就不上报`() {
        // 50% → 51% 只差 1 个点，再报一次纯属浪费。
        assertFalse(
            shouldEmitProgress(
                last = TaskProgress(total = 1_000, done = 500),
                next = TaskProgress(total = 1_000, done = 511),
                lastEmitAtMillis = 1_000,
                nowMillis = 1_050,
            ),
        )
    }

    @Test
    fun `间隔够久就算百分比没变也要上报`() {
        // 长时间没有进展时界面需要「我还活着」的信号；否则用户以为卡死。
        assertTrue(
            shouldEmitProgress(
                last = TaskProgress(total = 1_000, done = 500),
                next = TaskProgress(total = 1_000, done = 500),
                lastEmitAtMillis = 1_000,
                nowMillis = 2_000,
            ),
        )
    }

    /** ⭐ 失败数变化必须上报，否则用户不知道有东西失败了。 */
    @Test
    fun `失败数变化必须上报`() {
        assertTrue(
            shouldEmitProgress(
                last = TaskProgress(total = 1_000, done = 500, failed = 1),
                next = TaskProgress(total = 1_000, done = 500, failed = 2),
                lastEmitAtMillis = 1_000,
                nowMillis = 1_001,
            ),
        )
    }

    @Test
    fun `进度倒退必须上报`() {
        // 重新扫描 / 换工作区时 total 会变，节流不能把这种重置吃掉。
        assertTrue(
            shouldEmitProgress(
                last = TaskProgress(total = 1_000, done = 800),
                next = TaskProgress(total = 1_000, done = 0),
                lastEmitAtMillis = 1_000,
                nowMillis = 1_001,
            ),
        )
    }

    @Test
    fun `总数变化必须上报`() {
        assertTrue(
            shouldEmitProgress(
                last = TaskProgress(total = 1_000, done = 500),
                next = TaskProgress(total = 1_200, done = 500),
                lastEmitAtMillis = 1_000,
                nowMillis = 1_001,
            ),
        )
    }
}
