package com.pixfold.d1.ui.plan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.pixfold.d1.R
import com.pixfold.d1.TestText
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.plan.OutputConflictPolicy
import com.pixfold.d1.domain.plan.SourcePolicy
import com.pixfold.d1.ui.workflowa.TAG_A_PLAN_CLEAN_TOGGLE
import com.pixfold.d1.ui.workflowa.TAG_A_PLAN_CONFIRM
import com.pixfold.d1.ui.workflowa.TAG_A_PLAN_PAGE
import com.pixfold.d1.ui.workflowa.TAG_NEXT_STEP
import com.pixfold.d1.ui.workflowa.TAG_STEP_NAMING
import com.pixfold.d1.ui.workflowa.TAG_STEP_PLAN
import com.pixfold.d1.ui.workflowa.TAG_STEP_SORT
import com.pixfold.d1.ui.workflowa.TAG_STEP_BACK
import com.pixfold.d1.ui.workflowa.TAG_TO_PLAN
import com.pixfold.d1.ui.workflowa.WorkflowASteps
import com.pixfold.d1.ui.workflowb.TAG_B_STEP_BACK
import com.pixfold.d1.ui.workflowb.TAG_B_STEP_PACK
import com.pixfold.d1.ui.workflowb.TAG_CLEAN_EMPTY_TOGGLE
import com.pixfold.d1.ui.workflowb.TAG_DELETE_WARNING
import com.pixfold.d1.ui.workflowb.TAG_LIBRARY_NEXT
import com.pixfold.d1.ui.workflowb.TAG_METADATA_NEXT
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
     * 点「确认执行」。
     *
     * 该按钮如今**常驻底栏**(在 `LazyColumn` 之外),不再需要先 `performScrollToNode`。
     * 底栏化之前的写法是「滚到它再点」——因为计划有 56 行(A 侧整库),按钮在列表末尾,
     * 不滚过去它根本没被组合。这里先断言「不进列表就可见」,把这个性质钉住。
     */
    private fun clickAPlanConfirm() {
        rule.onNodeWithTag(TAG_A_PLAN_CONFIRM).assertIsDisplayed()
        rule.onNodeWithTag(TAG_A_PLAN_CONFIRM).performClick()
    }

    @Test
    fun `workflow A has a third step that opens the plan page`() {
        openWorkflowAPlanStep()
        rule.onNodeWithTag(TAG_A_PLAN_PAGE).assertIsDisplayed()
        // 确认按钮常驻底栏:计划有 56 行,但按钮不滚也该可见
        rule.onNodeWithTag(TAG_A_PLAN_CONFIRM).assertIsDisplayed()
    }

    /**
     * 终局动作在**真机视口**下也必须可见(台账 §2 的回归守卫)。
     *
     * 类级 `@Config` 是 `h2000dp` 的超高视口,足以让「滚到底」和「常驻」两种实现都通过 ——
     * 那会让这个守卫假绿。这里单独用真机视口(361dp × 794dp,与 PJX110 一致)跑一次。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
    fun `workflow A confirm stays visible on the device viewport`() {
        openWorkflowAPlanStep()
        // 视口自检:Qualifiers 写错时直接失败,而不是悄悄换一个宽松视口
        val rootHeightDp =
            (rule.onRoot().getBoundsInRoot().bottom - rule.onRoot().getBoundsInRoot().top).value
        assertTrue(rootHeightDp in 780f..810f, "视口高度应为 794dp，实际 $rootHeightDp dp")
        rule.onNodeWithTag(TAG_A_PLAN_CONFIRM).assertIsDisplayed()
    }

    @Test
    fun `workflow A plan lists every collection not just the active one`() {
        openWorkflowAPlanStep()
        // 计划行数 = 全部集合的图片总数(trip 30 + scan 14 + misc 12 + cn 16 = 72);
        // 输入打乱且中阿混排的 cn 集合后,该总数不再只靠阿拉伯数字样本支撑。
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
        // 撤销入口常驻底部动作栏(报告 56 行),不再需要滚动
        rule.onNodeWithTag(TAG_REPORT_UNDO).assertIsDisplayed()
    }

    // ================= 工作流 B =================

    private fun openWorkflowBPackStep() {
        val lib = MockDataB.library
        rule.setContent { WorkflowBSteps(rootPath = lib.rootPath, volumes = lib.volumes) }
        rule.onNodeWithTag(TAG_B_STEP_PACK).performClick()
    }

    /** 点「确认打包」(常驻底栏,计划区在页码预览之后也不再需要滚动)。 */
    private fun clickBPackConfirm() {
        rule.onNodeWithTag(TAG_PLAN_CONFIRM).assertIsDisplayed()
        rule.onNodeWithTag(TAG_PLAN_CONFIRM).performClick()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
    fun `workflow B next and confirm stay visible on the device viewport`() {
        val lib = MockDataB.library
        rule.setContent { WorkflowBSteps(rootPath = lib.rootPath, volumes = lib.volumes) }

        // 库步骤:下一步常驻底栏(卷多时不必滚到底)
        rule.onNodeWithTag(TAG_LIBRARY_NEXT).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LIBRARY_NEXT).performClick()
        // 元数据步骤:逐卷字段很多,下一步同样常驻
        rule.onNodeWithTag(TAG_METADATA_NEXT).assertIsDisplayed()
        rule.onNodeWithTag(TAG_METADATA_NEXT).performClick()
        // 打包步骤:终局动作常驻
        rule.onNodeWithTag(TAG_PLAN_CONFIRM).assertIsDisplayed()
    }

    @Test
    fun `workflow B pack step shows the plan section not just the preview`() {
        openWorkflowBPackStep()
        rule.onNodeWithTag(TAG_PLAN_SUMMARY).assertExists()
        rule.onNodeWithTag(TAG_PLAN_CONFIRM).assertIsDisplayed()
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
        rule.onNodeWithText(
            "· " + TestText.of(
                R.string.domain_summary_source_dir_prefix,
                TestText.of(R.string.domain_summary_source_dir_delete),
            ),
        ).assertIsDisplayed()
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

    /**
     * **端到端**:Overwrite 策略 → 两次确认 → 报告页 → 点撤销 → 弹出范围警告。
     *
     * 前面那些用例各自只守一段(策略选择触发危险确认、报告页会弹范围框),
     * 这条把整条**真实生产路径**串起来:`WorkflowBSteps` 里选的 `conflictPolicy`
     * 必须真的变成报告里的「覆盖痕迹」,否则界面永远不会警告 ——
     * 而领域层的单测照样全绿(它们直接构造计划,不经过界面选策略那一步)。
     *
     * 这正是用户 m04079 第③项要的那条路:覆盖时先问清楚"只撤销本次生成的"还是
     * "全部删除(含被覆盖的)"。
     */
    @Test
    fun `workflow B overwrite run warns about undo scope end to end`() {
        openWorkflowBPackStep()
        rule.onNodeWithTag("policy-conflict-" + OutputConflictPolicy.Overwrite.name).performClick()
        clickBPackConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()
        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()

        // 到了报告页:整库都成功了,撤销按钮可用
        rule.onNodeWithTag(TAG_REPORT_PAGE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertDoesNotExist()
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        // 计划里有覆盖项 ⇒ 必须先问范围,而不是直接回滚
        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertIsDisplayed()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_THIS_RUN).assertIsDisplayed()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_EVERYTHING).assertIsDisplayed()
        rule.onNodeWithTag(TAG_UNDO_SCOPE_CANCEL).assertIsDisplayed()
    }

    /** 反向守卫:默认 Rename 策略下没有任何覆盖 ⇒ 点撤销**直接执行**,不弹框。 */
    @Test
    fun `workflow B default run undoes without a scope warning`() {
        openWorkflowBPackStep()
        clickBPackConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()

        rule.onNodeWithTag(TAG_REPORT_PAGE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_REPORT_UNDO).performClick()

        rule.onNodeWithTag(TAG_UNDO_SCOPE_DIALOG).assertDoesNotExist()
        rule.onNodeWithTag(TAG_REPORT_UNDO_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `workflow B clean empty toggle is off by default and adds a danger step when on`() {
        openWorkflowBPackStep()
        rule.onNodeWithTag(TAG_CLEAN_EMPTY_TOGGLE).performClick()
        clickBPackConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()
        rule.onNodeWithTag(TAG_CONFIRM_DANGER).assertIsDisplayed()
    }

    // ================= 底栏左右分槽(用户 2026-09-25 指定) =================

    /**
     * 底栏必须**左 = 退回、右 = 推进**。
     *
     * 为什么要有这条断言:页面上「返回上一步」与「下一步」两个按钮都在时,
     * 只断言"两个都显示"是抓不住"位置放反"的 —— 位置才是本次需求本身
     * (用户原话:「下一步、完成之类的按钮都放到底栏的右侧,返回、上一步的按钮改到左侧」)。
     * 这里用 `getBoundsInRoot()` 比较两者的水平位置,并额外确认退回类确实落在左半屏。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
    fun `workflow B bottom bar puts back on the left and next on the right`() {
        val lib = MockDataB.library
        rule.setContent { WorkflowBSteps(rootPath = lib.rootPath, volumes = lib.volumes) }

        rule.onNodeWithTag(TAG_LIBRARY_NEXT).performClick() // 进入步骤 2:两个按钮同时在

        val back = rule.onNodeWithTag(TAG_B_STEP_BACK).getBoundsInRoot()
        val next = rule.onNodeWithTag(TAG_METADATA_NEXT).getBoundsInRoot()
        val rootWidth = rule.onRoot().getBoundsInRoot().right

        assertTrue(back.right <= next.left, "返回应在左、下一步应在右,实际 back=$back next=$next")
        assertTrue(
            back.left < rootWidth / 2,
            "返回是退回类动作,应贴左半屏;实际 left=${back.left} 视口宽=$rootWidth",
        )
        assertTrue(
            next.left > rootWidth / 2,
            "下一步是推进类动作,应贴右半屏;实际 left=${next.left} 视口宽=$rootWidth",
        )
        // 两者同在一行(底栏),不因分槽而换行
        assertTrue(back.top == next.top, "同一底栏行的两个按钮应对齐,实际 ${back.top} vs ${next.top}")
    }

    /** 工作流 A 步骤 2 同理:底栏左 = 返回排序、右 = 下一步 执行计划。 */
    @Test
    @Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
    fun `workflow A naming step bottom bar puts back on the left and next on the right`() {
        val collections = MockData.workspaceA.collections
        rule.setContent { WorkflowASteps(collections = collections) }
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()

        val back = rule.onNodeWithTag(TAG_STEP_BACK).getBoundsInRoot()
        val next = rule.onNodeWithTag(TAG_TO_PLAN).getBoundsInRoot()
        val rootWidth = rule.onRoot().getBoundsInRoot().right

        assertTrue(back.right <= next.left, "返回应在左、下一步应在右,实际 back=$back next=$next")
        assertTrue(back.left < rootWidth / 2, "返回应贴左半屏;实际 left=${back.left}")
        assertTrue(next.left > rootWidth / 2, "下一步应贴右半屏;实际 left=${next.left}")
    }

    /** 报告页底栏:左 = 撤销本次执行、右 = 完成。 */
    @Test
    @Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
    fun `report bottom bar puts undo on the left and done on the right`() {
        openWorkflowAPlanStep()
        clickAPlanConfirm()
        rule.onNodeWithTag(TAG_CONFIRM_OK).performClick()
        rule.onNodeWithTag(TAG_REPORT_PAGE).assertIsDisplayed()

        val undo = rule.onNodeWithTag(TAG_REPORT_UNDO).getBoundsInRoot()
        val done = rule.onNodeWithTag(TAG_REPORT_DONE).getBoundsInRoot()
        val rootWidth = rule.onRoot().getBoundsInRoot().right

        assertTrue(undo.right <= done.left, "撤销应在左、完成应在右,实际 undo=$undo done=$done")
        assertTrue(undo.left < rootWidth / 2, "撤销应贴左半屏;实际 left=${undo.left}")
        assertTrue(done.left > rootWidth / 2, "完成应贴右半屏;实际 left=${done.left}")
    }

    /**
     * 工作流 A 步骤 1 的「下一步」也必须在**底栏右侧**。
     *
     * 底栏化之前它是模式行里的一个 `TextButton`,与「网格/列表」「本组独立」挤在同一行;
     * 用户 2026-09-25 要求推进类动作统一进底栏。这里从步骤 1 底部进入步骤 2,
     * 既钉住「按钮在底栏右侧」,也钉住「点了真的能推进」。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
    fun `workflow A step 1 next sits in the bottom bar and advances`() {
        val collections = MockData.workspaceA.collections
        rule.setContent { WorkflowASteps(collections = collections) }

        val next = rule.onNodeWithTag(TAG_NEXT_STEP).getBoundsInRoot()
        val rootWidth = rule.onRoot().getBoundsInRoot().right
        assertTrue(next.left > rootWidth / 2, "步骤 1 的下一步应贴底栏右侧;实际 left=${next.left}")

        rule.onNodeWithTag(TAG_NEXT_STEP).performClick()
        // 推进到了步骤 2(命名结构):其底栏的「下一步 执行计划」出现
        rule.onNodeWithTag(TAG_TO_PLAN).assertIsDisplayed()
    }
}
