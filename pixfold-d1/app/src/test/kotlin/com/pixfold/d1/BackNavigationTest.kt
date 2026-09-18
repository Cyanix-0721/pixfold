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
import com.pixfold.d1.ui.workflowa.TAG_STEP_BACK
import com.pixfold.d1.ui.workflowa.TAG_STEP_NAMING
import com.pixfold.d1.ui.workflowa.TAG_STEP_SORT
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
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
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
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
        assertTrue(rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isNotEmpty(), "应停在工作流 A")

        back()
        // 回到首页:两条工作流入口应再次出现
        rule.onNodeWithText("工作流 B · CBZ 制作").assertIsDisplayed()
    }

    @Test
    fun `back from preview closes the preview and stays in workflow A`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
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
    fun `back from workflow B placeholder returns to home`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText("工作流 B · CBZ 制作").performClick()
        rule.onNodeWithText("工作流 B · 待实现（P5）").assertIsDisplayed()

        back()
        rule.onNodeWithText("工作流 A · 图片整理与命名").assertIsDisplayed()
    }

    @Test
    fun `the whole back chain walks down level by level`() {
        // 端到端走一遍层级:命名 -> 排序 -> 首页
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.onNodeWithTag(TAG_STEP_BACK).assertIsDisplayed()

        back()   // 步骤2 -> 步骤1
        rule.onNodeWithTag(TAG_STEP_SORT).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithTag(TAG_STEP_BACK).fetchSemanticsNodes().isEmpty(),
            "第一步不应再有返回排序",
        )

        back()   // 步骤1 -> 首页
        rule.onNodeWithText("工作流 B · CBZ 制作").assertIsDisplayed()
    }
}
