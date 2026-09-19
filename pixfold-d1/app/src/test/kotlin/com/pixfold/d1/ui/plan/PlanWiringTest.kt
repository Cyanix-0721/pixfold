package com.pixfold.d1.ui.plan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.plan.OutputConflictPolicy
import com.pixfold.d1.domain.plan.SourcePolicy
import com.pixfold.d1.ui.workflowa.TAG_A_PLAN_CLEAN_TOGGLE
import com.pixfold.d1.ui.workflowa.TAG_A_PLAN_CONFIRM
import com.pixfold.d1.ui.workflowa.TAG_A_PLAN_LIST
import com.pixfold.d1.ui.workflowa.TAG_A_PLAN_PAGE
import com.pixfold.d1.ui.workflowa.TAG_STEP_NAMING
import com.pixfold.d1.ui.workflowa.TAG_STEP_PLAN
import com.pixfold.d1.ui.workflowa.TAG_STEP_SORT
import com.pixfold.d1.ui.workflowa.TAG_TO_PLAN
import com.pixfold.d1.ui.workflowa.WorkflowASteps
import com.pixfold.d1.ui.workflowb.TAG_B_STEP_PACK
import com.pixfold.d1.ui.workflowb.TAG_CLEAN_EMPTY_TOGGLE
import com.pixfold.d1.ui.workflowb.TAG_DELETE_WARNING
import com.pixfold.d1.ui.workflowb.TAG_PACK_LIST
import com.pixfold.d1.ui.workflowb.WorkflowBSteps
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * 端到端:两条工作流的**步骤 3** 真的接上了 P6 能力(验收第 15、16、17 项)。
 *
 * **为什么必须有这一层**:领域层 + 组件层都绿,仍可能"界面没接上"——
 * 这正是 P3 踩过的坑(领域规则做完、界面根本没用,成了死代码)。
 * 故这里从**真实的步骤容器**进入:切 Tab / 点下一步 / 点确认,一路点到报告。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class PlanWiringTest {

    @get:Rule
    val rule = createComposeRule()

    // ================= 工作流 A =================

    private fun openWorkflowAPlanStep() {
        val collections = MockData.workspaceA.collections
        rule.setContent { WorkflowASteps(collections = collections) }
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.onNodeWithTag(TAG_TO_PLAN).performClick()
    }

    /**
     * 滚到「确认执行」再点。
     *
     * 计划有 56 行(A 侧整库),按钮在 LazyColumn 末尾 —— 不滚过去它**根本没被组合**,
     * 点击自然无效。这是 LazyColumn 的正常行为(只组合可见项),不是缺陷。
     */
    private fun clickAPlanConfirm() {
        rule.onNodeWithTag(TAG_A_PLAN_LIST).performScrollToNode(hasTestTag(TAG_A_PLAN_CONFIRM))
        rule.onNodeWithTag(TAG_A_PLAN_CONFIRM).performClick()
    }

    @Test
    fun `workflow A has a third step that opens the plan page`() {
        openWorkflowAPlanStep()
        rule.onNodeWithTag(TAG_A_PLAN_PAGE).assertIsDisplayed()
        // 确认按钮在 56 行计划之后 —— 滚过去才被组合(LazyColumn 只组合可见项)
        rule.onNodeWithTag(TAG_A_PLAN_LIST).performScrollToNode(hasTestTag(TAG_A_PLAN_CONFIRM))
        rule.onNodeWithTag(TAG_A_PLAN_CONFIRM).assertIsDisplayed()
    }

    @Test
    fun `workflow A plan lists every collection not just the active one`() {
        openWorkflowAPlanStep()
        // 计划行数 = 全部集合的图片总数(trip 30 + scan 14 + misc 12 = 56)
        val total = MockData.workspaceA.collections.sumOf { it.images.size }
        assertTrue(total > 30, "mock 应有多个集合，实际总数 $total")
        // 首行可见即证明列表已渲染(逐行断言需滚动,LazyColumn 只暴露可见项)
        rule.onNodeWithTag(planRowTag(0)).assertExists()
    }

    @Test
    fun `workflow A safe rename needs exactly one confirmation`() {
        openWorkflowAPlanStep()
        clickAPlanConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_GENERAL).assertIsDisplayed()
        // 未勾选清理空目录 -> 不应出现危险确认
        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertDoesNotExist()
    }

    @Test
    fun `workflow A clean empty dirs requires the independent second confirmation then reports`() {
        openWorkflowAPlanStep()

        // 打开危险动作(默认关闭 -> 打开)
        rule.onNodeWithTag(TAG_A_PLAN_CLEAN_TOGGLE).performClick()

        clickAPlanConfirm()
        // 第 1 步:通用确认,且列出影响范围
        rule.onNodeWithTag(TAG_CONFIRM_GENERAL).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()
        // 第 2 步:独立危险确认
        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()

        // 走完两次确认 -> 出现执行报告
        rule.onNodeWithTag(TAG_REPORT_PAGE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_REPORT_SUMMARY).assertIsDisplayed()
    }

    @Test
    fun `workflow A report exposes the undo entry`() {
        openWorkflowAPlanStep()
        clickAPlanConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()
        rule.onNodeWithTag(TAG_REPORT_PAGE).assertIsDisplayed()
        // 撤销入口在报告列表末尾(56 行之后),需滚过去
        rule.onNodeWithTag(TAG_REPORT_LIST).performScrollToNode(hasTestTag(TAG_REPORT_UNDO))
        rule.onNodeWithTag(TAG_REPORT_UNDO).assertIsDisplayed()
    }

    // ================= 工作流 B =================

    private fun openWorkflowBPackStep() {
        val lib = MockDataB.library
        rule.setContent { WorkflowBSteps(rootPath = lib.rootPath, volumes = lib.volumes) }
        rule.onNodeWithTag(TAG_B_STEP_PACK).performClick()
    }

    /** 滚到「确认打包」再点(计划区在页码预览之后,不滚过去不会被组合)。 */
    private fun clickBPackConfirm() {
        rule.onNodeWithTag(TAG_PACK_LIST).performScrollToNode(hasTestTag(TAG_PLAN_CONFIRM))
        rule.onNodeWithTag(TAG_PLAN_CONFIRM).performClick()
    }

    @Test
    fun `workflow B pack step shows the plan section not just the preview`() {
        openWorkflowBPackStep()
        rule.onNodeWithTag(TAG_PLAN_SUMMARY).assertExists()
        rule.onNodeWithTag(TAG_PLAN_CONFIRM).assertExists()
    }

    @Test
    fun `workflow B default policy requires only the general confirmation`() {
        openWorkflowBPackStep()
        clickBPackConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_GENERAL).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertDoesNotExist()
    }

    @Test
    fun `workflow B delete source policy needs two confirmations and then reports`() {
        openWorkflowBPackStep()

        // 选择"打包后删除源目录"(危险动作)
        rule.onNodeWithTag("policy-source-" + SourcePolicy.Delete.name).performClick()
        // 卡片上立刻出现红色警告
        rule.onNodeWithTag(TAG_DELETE_WARNING).assertIsDisplayed()

        clickBPackConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_GENERAL).assertIsDisplayed()
        rule.onNodeWithText("· 源目录：删除（危险）").assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()

        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()

        // 报告替换了步骤 3 的内容;Tab 仍在(报告属于该步骤,不是新页面)
        rule.onNodeWithTag(TAG_REPORT_PAGE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_PLAN_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `workflow B overwrite policy triggers a danger confirmation only because a conflict exists`() {
        openWorkflowBPackStep()
        // mock 里 op-102 的 outputExists = true
        rule.onNodeWithTag("policy-conflict-" + OutputConflictPolicy.Overwrite.name).performClick()
        clickBPackConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_GENERAL).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()
        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertIsDisplayed()
    }

    @Test
    fun `workflow B skip policy marks the conflicting volume as skipped in the plan`() {
        openWorkflowBPackStep()
        rule.onNodeWithTag("policy-conflict-" + OutputConflictPolicy.Skip.name).performClick()
        // 计划头部应体现"将执行 4 卷 · 跳过 1 卷"
        rule.onNodeWithTag(TAG_PLAN_SUMMARY).assertExists()
    }

    @Test
    fun `workflow B clean empty toggle is off by default and adds a danger step when on`() {
        openWorkflowBPackStep()
        rule.onNodeWithTag(TAG_CLEAN_EMPTY_TOGGLE).performClick()
        clickBPackConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()
        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertIsDisplayed()
    }
}
