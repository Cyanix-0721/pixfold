package com.pixfold.d1

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pixfold.d1.ui.components.TAG_GRID_CARD
import com.pixfold.d1.ui.preview.TAG_PREVIEW
import com.pixfold.d1.ui.workflowa.TAG_A_PLAN_PAGE
import com.pixfold.d1.ui.workflowa.TAG_STEP_BACK
import com.pixfold.d1.ui.workflowa.TAG_STEP_NAMING
import com.pixfold.d1.ui.workflowa.TAG_STEP_SORT
import com.pixfold.d1.ui.workflowa.TAG_TO_PLAN
import com.pixfold.d1.ui.workflowb.TAG_B_STEP_LIBRARY
import com.pixfold.d1.ui.workflowb.TAG_B_STEP_PACK
import com.pixfold.d1.ui.workflowb.TAG_LIBRARY_PAGE
import com.pixfold.d1.ui.workflowb.TAG_METADATA_PAGE
import com.pixfold.d1.ui.workflowb.TAG_PACK_PAGE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * 返回手势 / 返回键的**层级后退**守护(用户 2026-09-18 反馈后补)。
 *
 * **真机缺陷**:未处理返回时,在"步骤2 命名结构"按返回会**直接退出应用**
 * (前台切到别的 App),用户丢失当前位置 —— 与 Android 的层级返回预期不符。
 *
 * 修复后应有的层级:
 *   预览 --返回--> 工作流A(步骤2) --返回--> 工作流A(步骤1) --返回--> 首页 --返回--> 退出
 *
 * 本测试用 `createAndroidComposeRule` 拿到真实 [ComponentActivity],
 * 从而能调用 `onBackPressedDispatcher` —— 这与系统返回手势/预测性返回走的是**同一条链路**,
 * 因此比"直接断言状态变量"更接近真实行为。
 *
 * 预测性返回(predictive back)说明:`BackHandler` 会注册到 `OnBackInvokedDispatcher`,
 * 系统据此在手势进行中查询"返回会去哪"。本测试验证的是**调度结果**;
 * 手势动画本身属系统绘制,无法在单测中断言(真机走查确认)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp")
class BackNavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun openNamingStep() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_STEP_BACK).assertIsDisplayed()
    }

    @Test
    fun `back from naming step returns to the sort step, not out of the app`() {
        openNamingStep()
        back()
        // 仍在工作流 A 的步骤 1 —— 而不是退出了应用
        assertTrue(rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isNotEmpty(), "应回到步骤1(网格)")
        assertTrue(
            rule.onAllNodesWithTag(TAG_STEP_BACK).fetchSemanticsNodes().isEmpty(),
            "回到步骤1 后不应再显示「返回排序」",
        )
    }

    @Test
    fun `back from sort step returns to home`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        assertTrue(rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isNotEmpty(), "应停在工作流 A")

        back()
        // 回到首页:两条工作流入口应再次出现
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).assertIsDisplayed()
    }

    @Test
    fun `back from preview closes the preview and stays in workflow A`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()
        rule.onNodeWithTag(TAG_PREVIEW).assertIsDisplayed()

        back()
        assertTrue(
            rule.onAllNodesWithTag(TAG_PREVIEW).fetchSemanticsNodes().isEmpty(),
            "返回应关闭预览",
        )
        assertTrue(rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isNotEmpty(), "应停在工作流 A")
    }

    @Test
    fun `back from workflow B first step returns to home`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).performClick()
        rule.onNodeWithTag(TAG_LIBRARY_PAGE).assertIsDisplayed()

        back()
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).assertIsDisplayed()
    }

    @Test
    fun `back from workflow B third step walks down to the library step`() {
        // 层级:步骤3 打包计划 -> 步骤2 元数据 -> 步骤1 漫画库 -> 首页
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).performClick()
        rule.onNodeWithTag(TAG_B_STEP_PACK).performClick()
        rule.onNodeWithTag(TAG_PACK_PAGE).assertIsDisplayed()

        back()
        rule.onNodeWithTag(TAG_METADATA_PAGE).assertIsDisplayed()

        back()
        rule.onNodeWithTag(TAG_LIBRARY_PAGE).assertIsDisplayed()

        back()
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).assertIsDisplayed()
    }

    @Test
    fun `the whole back chain walks down level by level`() {
        // 端到端走一遍层级:命名 -> 排序 -> 首页
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.onNodeWithTag(TAG_STEP_BACK).assertIsDisplayed()

        back()   // 步骤2 -> 步骤1
        rule.onNodeWithTag(TAG_STEP_SORT).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithTag(TAG_STEP_BACK).fetchSemanticsNodes().isEmpty(),
            "第一步不应再有返回排序",
        )

        back()   // 步骤1 -> 首页
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).assertIsDisplayed()
    }

    /**
     * Tab **反向**跳转后返回链仍须是线性的。
     *
     * 步骤序号以前是 `stepIndex: Int`,Tab 点击只是赋值,返回链天然线性。
     * 迁移到嵌套 NavHost 后,Tab 跳转若不 `popBackStack` 而是 `navigate`,
     * 返回栈会变成 `[步骤1, 步骤2, 步骤3, 步骤1]` —— 此时按返回会退到**步骤3**,
     * 而不是退到首页,层级返回就断了。
     *
     * 这条断言是**变异存活后补的**:把 `goToWorkflowAStep` 的往回分支改成
     * `navigate` 时,原有 6 条用例全部通过 —— 说明该约束此前无人守护。
     */
    @Test
    fun `a backward tab jump in workflow A keeps the back chain linear`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.onNodeWithTag(TAG_TO_PLAN).performClick()
        rule.onNodeWithTag(TAG_A_PLAN_PAGE).assertIsDisplayed()

        // 从步骤3 直接点回步骤1 —— 返回栈应被裁剪回 [步骤1]
        rule.onNodeWithTag(TAG_STEP_SORT).performClick()
        assertTrue(rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isNotEmpty(), "应回到步骤1(网格)")

        back()
        // 线性栈下:步骤1 的上一层是首页。若非线性(残留 [步骤2, 步骤3]),
        // 这里会退到步骤3 的执行计划页。
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).assertIsDisplayed()
    }

    /** 同上,工作流 B 走的是独立的 `goToWorkflowBStep`。 */
    @Test
    fun `a backward tab jump in workflow B keeps the back chain linear`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).performClick()
        // 步骤1 -> 步骤3(按 helper 会逐级推入元数据、打包)
        rule.onNodeWithTag(TAG_B_STEP_PACK).performClick()
        rule.onNodeWithTag(TAG_PACK_PAGE).assertIsDisplayed()

        // 从步骤3 直接点回步骤1
        rule.onNodeWithTag(TAG_B_STEP_LIBRARY).performClick()
        rule.onNodeWithTag(TAG_LIBRARY_PAGE).assertIsDisplayed()

        back()
        // 线性栈下直接回首页;非线性的 `navigate` 版本会退到步骤2/步骤3
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).assertIsDisplayed()
    }
}
