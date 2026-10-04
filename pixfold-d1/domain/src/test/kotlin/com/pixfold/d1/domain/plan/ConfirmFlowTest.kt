package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 确认流程状态机(验收第 16 项)。
 *
 * **本测试是第 16 项"需要两次确认"的主要证据**:它逐帧断言流程,
 * 证明"恰好两次""先通用后危险""取消回到原点""没危险动作就不多弹"。
 */
class ConfirmFlowTest {

    private val safe = listOf(ConfirmStep.General(listOf(Msg(TextKey.RenameSummary, listOf(3, 0)))))
    private val oneDanger = listOf(
        ConfirmStep.General(listOf(Msg(TextKey.SummaryProcessingVolumes, listOf(1, 0)))),
        ConfirmStep.Danger(DangerAction.DeleteSourceDir),
    )

    @Test
    fun `empty sequence means nothing to confirm`() {
        val flow = startConfirmFlow(emptyList())
        assertFalse(flow.isActive)
        assertNull(flow.currentStep)
        assertNull(flow.progressLabel)
    }

    @Test
    fun `a safe operation needs exactly one confirmation to proceed`() {
        var flow = startConfirmFlow(safe)
        assertTrue(flow.isActive)
        val (next, done) = flow.confirmCurrent()
        assertTrue(done, "安全操作一次确认后即可执行")
        assertFalse(next.isActive)
    }

    @Test
    fun `a dangerous operation needs exactly two confirmations`() {
        var flow = startConfirmFlow(oneDanger)

        // 第 1 次(通用确认)
        val (afterFirst, done1) = flow.confirmCurrent()
        assertFalse(done1, "只确认一次不得执行")
        assertTrue(afterFirst.isActive, "还应停在危险确认上")

        // 第 2 次(独立危险确认)
        val (afterSecond, done2) = afterFirst.confirmCurrent()
        assertTrue(done2, "两次确认后才可执行")
        assertFalse(afterSecond.isActive)
    }

    @Test
    fun `the general step comes first and the danger step second`() {
        val flow = startConfirmFlow(oneDanger)
        assertTrue(flow.currentStep is ConfirmStep.General, "必须先弹通用确认")
        val (second, _) = flow.confirmCurrent()
        val step = second.currentStep
        assertTrue(step is ConfirmStep.Danger, "第二步必须是独立危险确认")
        assertEquals(DangerAction.DeleteSourceDir, step.action)
    }

    @Test
    fun `progress label counts from one`() {
        val flow = startConfirmFlow(oneDanger)
        assertEquals(Msg(TextKey.ConfirmProgress, listOf(1, 2)), flow.progressLabel)
        assertEquals(Msg(TextKey.ConfirmProgress, listOf(2, 2)), flow.confirmCurrent().first.progressLabel)
    }

    @Test
    fun `cancelling at any step returns to the very beginning`() {
        // 在第一步取消
        assertFalse(startConfirmFlow(oneDanger).cancel().isActive)
        // 在第二步取消
        val second = startConfirmFlow(oneDanger).confirmCurrent().first
        assertTrue(second.isActive)
        val cancelled = second.cancel()
        assertFalse(cancelled.isActive, "取消应整条流程归零")
        assertEquals(0, cancelled.stepIndex)
    }

    @Test
    fun `confirming an inactive flow does nothing and never reports done`() {
        val (next, done) = ConfirmFlowState().confirmCurrent()
        assertFalse(done, "不在流程中时绝不允许执行")
        assertFalse(next.isActive)
    }

    @Test
    fun `three danger actions require four confirmations total`() {
        val dangers = setOf(
            DangerAction.DeleteSourceDir,
            DangerAction.OverwriteExisting,
            DangerAction.CleanEmptyDirs,
        )
        var flow = startConfirmFlow(buildConfirmSequence(dangers, listOf(Msg(TextKey.SummaryProcessingVolumes, listOf(5, 0)))))
        assertEquals(4, flow.totalSteps, "1 通用 + 3 危险")

        var done = false
        var count = 0
        while (!done) {
            val (next, d) = flow.confirmCurrent()
            flow = next
            done = d
            count++
            if (count > 10) break
        }
        assertEquals(4, count, "必须恰好四次确认，多一次或少一次都不对")
    }

    @Test
    fun `nextDanger exposes the upcoming danger so the UI can warn ahead`() {
        val flow = startConfirmFlow(oneDanger)
        assertNull(flow.nextDanger(), "当前是通用确认,不应报危险动作")
        assertEquals(DangerAction.DeleteSourceDir, flow.confirmCurrent().first.nextDanger())
    }

    @Test
    fun `an untouched flow reports no steps and is not on its last step`() {
        // 未开始态(sequence == null)必须和"已走完"区分开:总步数报 0,而不是 1。
        // 若 totalSteps 的兜底值写成 1,或 isLastStep 丢掉 isActive 前提,
        // 一个**尚未开始**的流程就会被读成"已是最后一步" —— UI 会照此渲染按钮文案与可用态。
        val untouched = ConfirmFlowState()
        assertEquals(0, untouched.totalSteps, "还没开始时没有可确认的步骤")
        assertFalse(untouched.isLastStep, "还没开始就谈不上最后一步")
    }

    @Test
    fun `isLastStep is false while there is a pending danger`() {
        assertTrue(startConfirmFlow(safe).isLastStep, "只有通用确认时它就是最后一步")
        assertFalse(startConfirmFlow(oneDanger).isLastStep, "后面还有危险确认")
        assertTrue(startConfirmFlow(oneDanger).confirmCurrent().first.isLastStep)
    }

    @Test
    fun `danger sequence is deterministic regardless of set iteration order`() {
        // 同一个集合的不同迭代顺序不应改变确认顺序
        val a = buildConfirmSequence(
            linkedSetOf(DangerAction.CleanEmptyDirs, DangerAction.DeleteSourceDir),
            listOf(Msg(TextKey.SummaryWriteOnly)),
        )
        val b = buildConfirmSequence(
            linkedSetOf(DangerAction.DeleteSourceDir, DangerAction.CleanEmptyDirs),
            listOf(Msg(TextKey.SummaryWriteOnly)),
        )
        assertEquals(
            a.filterIsInstance<ConfirmStep.Danger>().map { it.action },
            b.filterIsInstance<ConfirmStep.Danger>().map { it.action },
            "危险确认顺序必须与集合顺序无关",
        )
        assertNotNull(a.first() as? ConfirmStep.General)
    }
}
