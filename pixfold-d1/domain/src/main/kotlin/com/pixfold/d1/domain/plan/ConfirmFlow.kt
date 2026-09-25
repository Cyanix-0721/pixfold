package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey

/**
 * 确认流程的状态机(规格 §9.2,验收第 16 项)。
 *
 * **为什么用状态机而不是几个 `Boolean`**:验收第 16 项要的是"**两次**确认"。
 * 若用 `showGeneralDialog` / `showDangerDialog` 两个布尔量,就会写出
 * "两个都可能为 true""取消一个另一个还留着""危险动作没选却弹了危险框"
 * 这类组合错误,而且很难穷举测试。
 *
 * 这里把流程压成**一条序列 + 一个下标**:能否执行 ⇔ 序列走完。
 * 于是"恰好几次确认""顺序如何""取消是否回到原点"都成为**可断言的事实**。
 */
data class ConfirmFlowState(
    /** 待走完的确认序列;null = 当前没有进行中的确认。 */
    val sequence: List<ConfirmStep>? = null,
    /** 当前停在第几步(0 起)。 */
    val stepIndex: Int = 0,
) {
    val isActive: Boolean get() = sequence != null

    /** 当前该显示哪一步;不在流程中时为 null。 */
    val currentStep: ConfirmStep? get() = sequence?.getOrNull(stepIndex)

    val totalSteps: Int get() = sequence?.size ?: 0

    val isLastStep: Boolean get() = isActive && stepIndex >= totalSteps - 1

    /** 人类可读的进度(供 UI 显示"第 N / M 步");不在流程中时为 null。 */
    val progressLabel: Msg? get() = if (isActive) Msg(TextKey.ConfirmProgress, listOf(stepIndex + 1, totalSteps)) else null
}

/** 开始一次确认流程。空序列视为无需确认(直接可执行),故也返回非活跃状态。 */
fun startConfirmFlow(sequence: List<ConfirmStep>): ConfirmFlowState =
    if (sequence.isEmpty()) ConfirmFlowState() else ConfirmFlowState(sequence, 0)

/**
 * 通过当前这一步。
 *
 * 返回 `Pair<新状态, 是否已全部通过>`:走完最后一步时 new state 回到**不活跃**,
 * 调用方据 `allPassed = true` 去执行 —— 这样"执行"只可能发生在流程走完之后。
 */
fun ConfirmFlowState.confirmCurrent(): Pair<ConfirmFlowState, Boolean> {
    if (!isActive) return this to false
    return if (isLastStep) {
        ConfirmFlowState() to true
    } else {
        copy(stepIndex = stepIndex + 1) to false
    }
}

/**
 * 取消:整个流程**回到原点**,而不是只退一步。
 *
 * 理由:确认序列是"一次执行前的闸门"。用户在任何一步点取消,意图都是
 * "这次先不执行了";只退一步会让他困在剩下的确认里,还得再取消若干次。
 */
fun ConfirmFlowState.cancel(): ConfirmFlowState = ConfirmFlowState()

/** 下一次要显示的危险动作(供 UI 提前提示);没有则 null。 */
fun ConfirmFlowState.nextDanger(): DangerAction? =
    (currentStep as? ConfirmStep.Danger)?.action
