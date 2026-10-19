package com.cyanix.pixfold

import com.cyanix.pixfold.TestText

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.domain.settings.InMemorySettingsStore
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.ui.components.TAG_GRID_CARD
import com.cyanix.pixfold.ui.organize.TAG_STEP_BACK
import com.cyanix.pixfold.ui.organize.TAG_STEP_NAMING
import com.cyanix.pixfold.ui.library.TAG_B_STEP_PACK
import com.cyanix.pixfold.ui.library.TAG_METADATA_PAGE
import com.cyanix.pixfold.ui.library.TAG_PACK_PAGE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * **工作流内部预测性返回**的守护(2026-09-21 建立,2026-09-22 迁移后重写)。
 *
 * ## 修的是什么
 *
 * 步骤容器原先自注册普通 `BackHandler`,其优先级高于根级 `PredictiveBackHandler`
 * (`OnBackInvokedDispatcher` 取最后注册者),于是**工作流内部没有手势跟随动画**:
 * 在步骤 2 拖动返回手势时内容纹丝不动,松手才突然跳回步骤 1。
 *
 * ## 迁移后为什么这些断言仍然成立(且更该留下)
 *
 * 2026-09-22 把两级导航都换成了 `androidx.navigation` 的 NavHost。**这类回归最容易
 * 悄悄发生**:嵌套 NavHost 的返回处理器在栈深为 1 时会自动禁用、预测性转场只能在
 * `NavHost` 级配置(目的地没有该参数)、开关必须一路传到嵌套图的转场策略 —— 任何
 * 一处漏掉,层级的"后退一步"或"手势跟手"就会消失,而普通的功能测试**全都还是绿的**。
 *
 * ## 本测试能断言什么、不能断言什么(诚实边界)
 *
 * 能:①**层级后退在两种开关状态下都成立**(漏传参数 / 改错目标立刻失败);
 * ②**手势取消不导航、完成才导航**。
 *
 * 不能:①真实返回手势的**系统绘制**本身(边缘拖拽的系统动画层)—— 那部分由真机走查覆盖;
 * ②转场的**形态**(淡出/缩放幅度)。形态断言在
 * `android/app/src/test/kotlin/com/cyanix/pixfold/TransitionStyleTest.kt`,那里同时看
 * `boundsInRoot`(缩放)与 `captureToImage` 像素(淡出)。
 *
 * 事件驱动方式:`OnBackPressedDispatcher.dispatchOnBackStarted/Progressed/Cancelled`
 * 与 `onBackPressed()` 均为 androidx.activity 1.13.0 的公开 API;NavHost 的预测性返回
 * 正是挂在同一条 dispatcher 链路上,故这与系统手势走的是**同一条链路**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h1000dp")
class PredictiveBackTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    /** 手势进行中(未松手):开始 + 推进到 [progress]。 */
    private fun gestureTo(progress: Float) {
        rule.activityRule.scenario.onActivity { activity ->
            val dispatcher = activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.dispatchOnBackProgressed(event(progress))
        }
        rule.waitForIdle()
    }

    /** 手势取消(手指移回边缘外):应复位且**不导航**。 */
    private fun cancelGesture() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
        rule.waitForIdle()
    }

    /** 手势完成(松手):先走一遍进度,再触发完成。 */
    private fun completeGesture(progress: Float = 0.8f) {
        gestureTo(progress)
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun openNamingStep() {
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_STEP_BACK).assertIsDisplayed()
    }

    private fun assertOnSortStep(why: String) {
        assertTrue(
            rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isNotEmpty(),
            why,
        )
        assertTrue(
            rule.onAllNodesWithTag(TAG_STEP_BACK).fetchSemanticsNodes().isEmpty(),
            "回到步骤1 后不应再显示「返回排序」",
        )
    }

    // --- ① 层级后退:开关 ON(默认)与 OFF 都必须成立 ---

    @Test
    fun `a completed predictive gesture walks workflow A down one step`() {
        rule.setContent { PixFoldApp() }
        openNamingStep()

        completeGesture()

        assertOnSortStep("手势完成后应回到步骤1(网格),而不是首页或退出应用")
    }

    @Test
    fun `with the animation switch off, back still walks workflow A down one step`() {
        // 开关关掉后嵌套 NavHost 不再跟随手势,但**层级语义必须一模一样** ——
        // 关的是动画,不是返回本身(用户裁决的 (b) 案)。
        rule.setContent {
            PixFoldApp(
                store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = false)),
            )
        }
        openNamingStep()

        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        assertOnSortStep("开关关闭时返回仍应逐级后退")
    }

    @Test
    fun `a completed predictive gesture walks workflow B from the pack step to metadata`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).performClick()
        rule.onNodeWithTag(TAG_B_STEP_PACK).performClick()
        rule.onNodeWithTag(TAG_PACK_PAGE).assertIsDisplayed()

        completeGesture()

        rule.onNodeWithTag(TAG_METADATA_PAGE).assertIsDisplayed()
    }

    // --- ② 取消不导航 ---

    @Test
    fun `a cancelled predictive gesture leaves the naming step in place`() {
        rule.setContent { PixFoldApp() }
        openNamingStep()

        gestureTo(0.9f)
        cancelGesture()

        rule.onNodeWithTag(TAG_STEP_BACK).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isEmpty(),
            "手势取消后不应跳到步骤1",
        )
    }

    // --- ③ 进度驱动内容变换 ---
    //
    // 转场**形态**的断言(几何 + 像素,两级风格的区别)已移到
    // `android/app/src/test/kotlin/com/cyanix/pixfold/TransitionStyleTest.kt` ——
    // 因为 2026-09-22 起步骤级改为「只淡出、不缩放」,原先这里用 `boundsInRoot`
    // 断言步骤内容变窄的写法已与新规格相反。本类只保留**层级语义**的守护。
}
