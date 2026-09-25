package com.pixfold.d1.ui.plan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.pixfold.d1.R
import com.pixfold.d1.TestText
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.plan.ConfirmFlowState
import com.pixfold.d1.domain.plan.describe
import com.pixfold.d1.domain.plan.DangerAction
import com.pixfold.d1.domain.plan.OutputConflictPolicy
import com.pixfold.d1.domain.plan.SourcePolicy
import com.pixfold.d1.domain.plan.buildConfirmSequence
import com.pixfold.d1.domain.plan.buildPackagePlans
import com.pixfold.d1.domain.plan.cancel
import com.pixfold.d1.domain.plan.confirmCurrent
import com.pixfold.d1.domain.plan.effectiveDangerActions
import com.pixfold.d1.domain.plan.executePackagePlans
import com.pixfold.d1.domain.plan.generalSummaryLines
import com.pixfold.d1.domain.plan.performUndo
import com.pixfold.d1.domain.plan.planCountsOf
import com.pixfold.d1.domain.plan.startConfirmFlow
import com.pixfold.d1.domain.plan.undoRecordOf
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey
import com.pixfold.d1.ui.text.resolveText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 计划 / 确认 / 报告组件的 UI 层断言(验收第 15、16、17 项)。
 *
 * 与领域测试的分工:领域测试证明"**恰好**两次确认""文案契约";这里证明
 * "**界面上**真的弹了两次、按钮真的能用、报告真的显示三类"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class PlanUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /**
     * 期望值一律经 [resolveText] 算出 —— 那是**界面用的同一条解析路径**。
     *
     * 若在测试里手写中文字面量,①换语言就红(与裁决 `test_strategy=B` 相悖),
     * ②等于把实现复制一份,两边同时写错时测试照样绿。
     */
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ================= 第 15 项:计划清单 =================

    @Test
    fun `plan rows show the describe lines and the skip reason`() {
        val plans = buildPackagePlans(library.volumes, conflictPolicy = OutputConflictPolicy.Skip)
        rule.setContent { PlanListStub(plans) }

        // 每行都有 describe() 契约的第一条
        val first = plans.first()
        rule.onNodeWithTag(planLineTag(0, 0))
            .assertTextEquals("· " + resolveText(context, first.describe().first()))

        // 跳过行带原因(op-102 有输出冲突)
        val skipIndex = plans.indexOfFirst { it.skipped }
        assertTrue(skipIndex >= 0, "mock 库应含一个输出冲突卷")
        rule.onNodeWithTag(planSkipTag(skipIndex)).assertIsDisplayed()
    }

    @Test
    fun `plan header counts runnable and skipped volumes`() {
        val plans = buildPackagePlans(library.volumes, conflictPolicy = OutputConflictPolicy.Skip)
        rule.setContent { PlanListStub(plans) }
        val counts = planCountsOf(plans)
        // 与 PlanHeader 同形:标题 + 仅当有跳过时才追加的那一段
        val expected = TestText.plural(R.plurals.plan_summary_title, counts.total, counts.total, counts.runnable) +
            context.getString(R.string.plan_summary_skipped, counts.skipped)
        rule.onNodeWithTag(TAG_PLAN_SUMMARY).assertTextEquals(expected)
    }

    // ================= 第 16 项:两次确认 =================

    @Test
    fun `safe operation shows exactly one general dialog then executes`() {
        var executed = false
        rule.setContent {
            ConfirmFlowHost(dangers = emptySet(), onExecuted = { executed = true })
        }

        rule.onNodeWithTag(TAG_PLAN_CONFIRM).performClick()
        rule.onNodeWithTag(TAG_CONFIRM_GENERAL).assertIsDisplayed()
        // 只有一步:直接显示「执行」按钮,且没有"第 N 步"进度(单步无需进度)
        rule.onNodeWithText(context.getString(R.string.domain_general_confirm_ok)).performClick()
        assertTrue(executed, "通用确认通过后应执行")
    }

    @Test
    fun `dangerous operation requires the general dialog first then the independent one`() {
        var executed = false
        rule.setContent {
            ConfirmFlowHost(
                conflictPolicy = OutputConflictPolicy.Rename,
                sourcePolicy = SourcePolicy.Delete,
                onExecuted = { executed = true },
            )
        }

        rule.onNodeWithTag(TAG_PLAN_CONFIRM).performClick()

        // 第 1 步:通用确认(含「删除（危险）」影响范围)
        rule.onNodeWithTag(TAG_CONFIRM_GENERAL).assertIsDisplayed()
        // 通用确认正文里必须标出破坏性影响范围(源目录将被删除)
        val plans = buildPackagePlans(library.volumes, OutputConflictPolicy.Rename, SourcePolicy.Delete)
        val dangers0 = effectiveDangerActions(plans)
        val sourceLine = generalSummaryLines(plans, dangers0).single { it.key == TextKey.SummarySourceDirPrefix }
        rule.onNodeWithText("· " + resolveText(context, sourceLine)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.domain_general_confirm_ok)).performClick()

        // 第 2 步:独立危险确认(标题固定 + 按钮专有)
        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFIRM_DANGER_TITLE)
            .assertTextEquals(context.getString(R.string.domain_danger_dialog_title))
        rule.onNodeWithText(resolveText(context, DangerAction.DeleteSourceDir.buttonKey)).performClick()
        assertTrue(executed, "两次确认都通过后才执行")
    }

    @Test
    fun `cancelling the danger dialog aborts the whole flow`() {
        var executed = false
        rule.setContent {
            ConfirmFlowHost(sourcePolicy = SourcePolicy.Delete, onExecuted = { executed = true })
        }

        rule.onNodeWithTag(TAG_PLAN_CONFIRM).performClick()
        rule.onNodeWithText(context.getString(R.string.domain_general_confirm_ok)).performClick() // 走到危险步
        rule.onNodeWithText(context.getString(R.string.common_cancel)).performClick()             // 取消

        // 流程归零:重新点「确认打包」从通用确认开始(而不是接着上次的危险步)
        rule.onNodeWithTag(TAG_PLAN_CONFIRM).performClick()
        rule.onNodeWithTag(TAG_CONFIRM_GENERAL).assertIsDisplayed()
        assertTrue(!executed)
    }

    @Test
    fun `no danger dialog appears when nothing dangerous is selected`() {
        rule.setContent { ConfirmFlowHost(dangers = emptySet()) }
        rule.onNodeWithTag(TAG_PLAN_CONFIRM).performClick()
        rule.onNodeWithTag(TAG_CONFIRM_GENERAL).assertIsDisplayed()
        // 未选危险动作 -> 不该出现独立危险确认框
        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertDoesNotExist()
    }

    // ================= 第 17 项:报告与撤销 =================

    @Test
    fun `report shows all three outcome classes with reasons`() {
        val aot1 = library.volumes.first { it.id == "aot-1" }
        val op102 = library.volumes.first { it.id == "op-102" }
        val noPages = aot1.copy(pageOrder = aot1.pageOrder.copy(order = emptyList()))
        val plans = buildPackagePlans(
            listOf(library.volumes[1], op102, noPages),
            conflictPolicy = OutputConflictPolicy.Skip,
        )
        val report = executePackagePlans(plans)
        assertEquals(1, report.succeededCount)
        assertEquals(1, report.skippedCount)
        assertEquals(1, report.failedCount)

        rule.setContent { ExecutionReportPage(report = report, undoMessage = null, onUndo = {}, onDone = {}) }

        rule.onNodeWithTag(TAG_REPORT_SUMMARY).assertTextEquals(resolveText(context, report.summary))
        // 三类结果标签都在
        for (outcome in com.pixfold.d1.domain.plan.ExecOutcome.entries) {
            rule.onNodeWithText(resolveText(context, outcome.textKey)).assertExists()
        }
        // 两条失败/跳过原因都显示出来(且走的是界面同一条「原因：%1$s」拼接)
        val reasons = report.entries.mapNotNull { it.reason }
        assertTrue(reasons.size >= 2, "本条要覆盖至少两条带原因的行")
        reasons.forEach { r ->
            rule.onNodeWithText(context.getString(R.string.report_reason, resolveText(context, r))).assertExists()
        }
    }

    @Test
    fun `undo button is disabled when nothing succeeded`() {
        val op102 = library.volumes.first { it.id == "op-102" }
        val plans = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Skip)
        val report = executePackagePlans(plans)
        rule.setContent { ExecutionReportPage(report = report, undoMessage = null, onUndo = {}, onDone = {}) }
        rule.onNodeWithTag(TAG_REPORT_UNDO).assertIsNotEnabled()
        rule.onNodeWithText(context.getString(R.string.report_nothing_to_undo)).assertIsDisplayed()
    }

    @Test
    fun `undo is honest that it is a mock and never claims success`() {
        val aot1 = library.volumes.first { it.id == "aot-1" }
        val report = executePackagePlans(buildPackagePlans(listOf(aot1)))
        rule.setContent {
            // undoMessage 必须是 **Compose 状态**,否则点撤销后界面不会重组,
            // 「显示在哪里」就无从断言(这正是本项目"状态持有器"约定要防的错)。
            var message by remember { mutableStateOf<Msg?>(null) }
            ExecutionReportPage(
                report = report,
                undoMessage = message,
                onUndo = { message = performUndo(undoRecordOf(report)).message },
                onDone = {},
            )
        }
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()
        rule.onNodeWithTag(TAG_REPORT_UNDO_MESSAGE).assertIsDisplayed()
        // 不能出现"已撤销"的假结论 —— 撤销在 D1 是 mock,只报"已记录 N 项逆向操作"
        rule.onNodeWithText(context.getString(R.string.report_done)).assertExists()
        val undoMsg = resolveText(context, performUndo(undoRecordOf(report)).message)
        rule.onNodeWithTag(TAG_REPORT_UNDO_MESSAGE).assertTextEquals(undoMsg)
        assertTrue("已撤销" !in undoMsg, "D1 不得谎称已撤销，实际：$undoMsg")
    }

    @Test
    fun `done returns control to the caller`() {
        var done = false
        val aot1 = library.volumes.first { it.id == "aot-1" }
        val report = executePackagePlans(buildPackagePlans(listOf(aot1)))
        rule.setContent { ExecutionReportPage(report = report, undoMessage = null, onUndo = {}, onDone = { done = true }) }
        rule.onNodeWithText(context.getString(R.string.report_done)).performClick()
        assertTrue(done)
    }

    // ---- 脚手架 ----

    @androidx.compose.runtime.Composable
    private fun PlanListStub(plans: List<com.pixfold.d1.domain.plan.VolumePackPlan>) {
        androidx.compose.foundation.lazy.LazyColumn {
            item { PlanHeader(counts = planCountsOf(plans), dangers = emptyList()) }
            for (i in plans.indices) {
                item { PlanRow(plans[i], i) }
            }
        }
    }

    /**
     * 确认流程的**完整宿主**:像真实的步骤容器那样持有流程状态。
     * 不复用领域函数做断言 —— 这里驱动的是 UI 的真实点击路径。
     */
    @androidx.compose.runtime.Composable
    private fun ConfirmFlowHost(
        dangers: Set<DangerAction> = emptySet(),
        conflictPolicy: OutputConflictPolicy = OutputConflictPolicy.Rename,
        sourcePolicy: SourcePolicy = SourcePolicy.Keep,
        onExecuted: () -> Unit = {},
    ) {
        val plans = buildPackagePlans(library.volumes, conflictPolicy, sourcePolicy)
        val realDangers = if (dangers.isNotEmpty()) dangers else effectiveDangerActions(plans)
        var flow by remember { mutableStateOf(ConfirmFlowState()) }
        androidx.compose.material3.Surface {
            androidx.compose.material3.TextButton(
                onClick = {
                    flow = startConfirmFlow(buildConfirmSequence(realDangers, generalSummaryLines(plans, realDangers)))
                },
                modifier = Modifier.testTag(TAG_PLAN_CONFIRM),
            ) { androidx.compose.material3.Text("确认打包") }
        }
        val step = flow.currentStep
        if (step != null) {
            ConfirmDialog(
                step = step,
                stepIndex = flow.stepIndex,
                totalSteps = flow.totalSteps,
                onConfirm = {
                    val (next, done) = flow.confirmCurrent()
                    flow = next
                    if (done) onExecuted()
                },
                onCancel = { flow = flow.cancel() },
            )
        }
    }
}
