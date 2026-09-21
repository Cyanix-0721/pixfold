package com.pixfold.d1

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pixfold.d1.domain.settings.InMemorySettingsStore
import com.pixfold.d1.domain.settings.PixFoldSettings
import com.pixfold.d1.ui.components.PredictiveBackContainer
import com.pixfold.d1.ui.components.TAG_GRID_CARD
import com.pixfold.d1.ui.workflowa.TAG_STEP_BACK
import com.pixfold.d1.ui.workflowa.TAG_STEP_NAMING
import com.pixfold.d1.ui.workflowb.TAG_B_STEP_PACK
import com.pixfold.d1.ui.workflowb.TAG_METADATA_PAGE
import com.pixfold.d1.ui.workflowb.TAG_PACK_PAGE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertTrue

/** 探针用 tag:隔离验证「进度真的驱动了缩放」。 */
private const val TAG_ANIMATED = "test-animated-content"

/**
 * **工作流内部预测性返回**的守护(2026-09-21,HANGOFF §9.1 第 2 步)。
 *
 * ## 修的是什么
 *
 * 步骤容器原先自注册普通 `BackHandler`,其优先级高于根级 `PredictiveBackHandler`
 * (`OnBackInvokedDispatcher` 取最后注册者),于是**工作流内部没有手势跟随动画**:
 * 在步骤 2 拖动返回手势时内容纹丝不动,松手才突然跳回步骤 1。修复 = 把
 * `settings.predictiveBackAnimation` 透传进 `WorkflowASteps` / `WorkflowBSteps`,
 * 二者改用与根级同一实现的 [PredictiveBackContainer]。
 *
 * ## 本测试能断言什么、不能断言什么(诚实边界)
 *
 * 能:①**层级后退在两种开关状态下都成立**(修复若漏传参数 / 改错目标,立刻失败);
 * ②**手势取消不导航、完成才导航**;③**进度真的驱动了内容变换**(见最后一条,用
 * `boundsInRoot` 观察 `graphicsLayer` 的缩放 —— 这同时反证「步骤容器确实吃到了
 * 进度流」,普通 `BackHandler` 对进度是 no-op,变不出这个变换)。
 *
 * 不能:真实返回手势的**系统绘制**本身(边缘拖拽的系统动画层)。那部分由真机走查覆盖,
 * 与 [BackNavigationTest] 的既有说明一致。
 *
 * 事件驱动方式:`OnBackPressedDispatcher.dispatchOnBackStarted/Progressed/Cancelled`
 * 与 `onBackPressed()` 均为 androidx.activity 1.13.0 的公开 API(已反编译确认),
 * 且 `PredictiveBackHandler` 内部正是经 `BackHandlerDispatcherCompat` 桥接到同一个
 * `OnBackPressedDispatcher`,故这与系统手势走的是**同一条链路**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp")
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
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
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
        // 开关关掉时步骤容器退回普通 BackHandler —— 层级语义必须一模一样。
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
        rule.onNodeWithText("工作流 B · CBZ 制作").performClick()
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

    // --- ③ 进度真的驱动了内容变换(反证步骤容器吃到了进度流) ---

    @Test
    fun `gesture progress transforms the content only when the switch is on`() {
        rule.setContent {
            PredictiveBackContainer(enabled = true, onBack = {}) {
                Box(Modifier.fillMaxSize().testTag(TAG_ANIMATED))
            }
        }

        val before = rule.onNodeWithTag(TAG_ANIMATED).fetchSemanticsNode().boundsInRoot
        gestureTo(1f)
        val during = rule.onNodeWithTag(TAG_ANIMATED).fetchSemanticsNode().boundsInRoot
        println("PredictiveBack animation probe: before=$before during=$during")
        assertTrue(
            during.width < before.width - 1f,
            "开关开启时进度应缩放内容:before=$before during=$during",
        )

        cancelGesture()
        val restored = rule.onNodeWithTag(TAG_ANIMATED).fetchSemanticsNode().boundsInRoot
        assertTrue(
            abs(restored.width - before.width) < 1f,
            "手势取消后应复位:before=$before restored=$restored",
        )
    }

    @Test
    fun `with the switch off the same gesture leaves the content untransformed`() {
        rule.setContent {
            PredictiveBackContainer(enabled = false, onBack = {}) {
                Box(Modifier.fillMaxSize().testTag(TAG_ANIMATED))
            }
        }

        val before = rule.onNodeWithTag(TAG_ANIMATED).fetchSemanticsNode().boundsInRoot
        gestureTo(1f)
        val during = rule.onNodeWithTag(TAG_ANIMATED).fetchSemanticsNode().boundsInRoot

        assertTrue(
            abs(during.width - before.width) < 1f,
            "开关关闭时不应有跟随动画:before=$before during=$during",
        )
    }

    // --- ④ 设置真的接到了**步骤容器**上(反证「透传参数」这一步没被漏掉) ---
    //
    // 只断言"层级后退成立"是不够的:把步骤容器的 `enabled` 写死成 false/true,
    // 后退语义仍然对,但工作流内部的手势跟随动画会**悄悄消失**。
    // 这里直接在应用级观察步骤内容的缩放 —— 若设置没传进来(或写死了),
    // 普通 `BackHandler` 对进度是 no-op,变不出这个变换,断言即失败。

    @Test
    fun `inside workflow A the step content follows the gesture when the switch is on`() {
        rule.setContent {
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = true)))
        }
        openNamingStep()

        val before = rule.onNodeWithTag(TAG_STEP_BACK).fetchSemanticsNode().boundsInRoot
        gestureTo(1f)
        val during = rule.onNodeWithTag(TAG_STEP_BACK).fetchSemanticsNode().boundsInRoot
        println("WorkflowA step animation probe: before=$before during=$during")

        assertTrue(
            during.width < before.width - 1f,
            "工作流 A 步骤容器应跟随手势进度:before=$before during=$during",
        )
    }

    @Test
    fun `inside workflow B the step content follows the gesture when the switch is on`() {
        rule.setContent {
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = true)))
        }
        rule.onNodeWithText("工作流 B · CBZ 制作").performClick()
        rule.onNodeWithTag(TAG_B_STEP_PACK).performClick()
        rule.onNodeWithTag(TAG_PACK_PAGE).assertIsDisplayed()

        val before = rule.onNodeWithTag(TAG_PACK_PAGE).fetchSemanticsNode().boundsInRoot
        gestureTo(1f)
        val during = rule.onNodeWithTag(TAG_PACK_PAGE).fetchSemanticsNode().boundsInRoot
        println("WorkflowB step animation probe: before=$before during=$during")

        assertTrue(
            during.width < before.width - 1f,
            "工作流 B 步骤容器应跟随手势进度:before=$before during=$during",
        )
    }

    @Test
    fun `inside a workflow the step content does not transform when the switch is off`() {
        rule.setContent {
            PixFoldApp(
                store = InMemorySettingsStore(PixFoldSettings(predictiveBackAnimation = false)),
            )
        }
        openNamingStep()

        val before = rule.onNodeWithTag(TAG_STEP_BACK).fetchSemanticsNode().boundsInRoot
        gestureTo(1f)
        val during = rule.onNodeWithTag(TAG_STEP_BACK).fetchSemanticsNode().boundsInRoot

        assertTrue(
            abs(during.width - before.width) < 1f,
            "开关关闭时步骤内容不应跟随手势:before=$before during=$during",
        )
    }
}
