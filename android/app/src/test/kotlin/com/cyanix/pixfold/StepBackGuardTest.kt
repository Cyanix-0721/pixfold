package com.cyanix.pixfold

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.ui.components.TAG_GRID_CARD
import com.cyanix.pixfold.ui.organize.TAG_STEP_BACK
import com.cyanix.pixfold.ui.organize.TAG_STEP_NAMING
import com.cyanix.pixfold.ui.library.TAG_B_STEP_BACK
import com.cyanix.pixfold.ui.library.TAG_B_STEP_METADATA
import com.cyanix.pixfold.ui.library.TAG_LIBRARY_PAGE
import com.cyanix.pixfold.ui.library.TAG_METADATA_PAGE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * **步骤底栏的返回按钮不得把嵌套返回栈弹空**的守护(2026-10-14 建立)。
 *
 * ## 真机缺陷(用户报告,真机复现并量化)
 *
 * 在步骤 2(命名结构 / 元数据)**连点两次底栏左下角「返回」** ⇒ 步骤区**内容全空**,
 * 只剩顶部三个步骤 Tab;且此时点「步骤 1」**无法恢复**(点步骤 2 / 3 才能恢复)。
 * 真机实测触发窗口:两次点击间隔 ≤0.15s 必炸、≥0.25s 正常 —— 即**退场转场进行中**
 * (转场约 200ms)。间隔 0.05s 也中,说明不是"手抖",双击这个按钮必炸。
 *
 * ## 根因(两层,缺一不可)
 *
 * 1. 步骤底栏的 `onBack` 是裸的 `navController.popBackStack()`。嵌套图的**起始目的地
 *    (步骤 1)与后续步骤在同一个栈里**,而第一次 pop 只让页面进入**退场转场** ——
 *    退场中的页面仍在组合树上、按钮仍可命中,于是第二次点击落在同一个按钮上,
 *    把**步骤 1 也弹掉了** ⇒ 栈空 ⇒ `NavHost` 无内容可组合。
 *    ⚠️ 注释里写的「内层栈深为 1 时自动让位给外层」保护的是**系统返回手势**
 *    (`enabled = currentBackStack.size > 1`),页面底栏的按钮**绕过了那道护栏**。
 * 2. `organizeStepIndex()` 对 null 目的地返回 0 ⇒ 栈空时"当前步骤"读作 0,
 *    点「步骤 1」算 `target == current` 被 `goToOrganizeStep` 直接 return ⇒ 修不回来。
 *
 * ## 本测试怎么在 Robolectric 下卡住转场窗口
 *
 * 缺陷只存在于**退场转场进行中**那约 200ms。默认 `mainClock.autoAdvance = true` 会把
 * 动画一次跑完(退场页面随即被销毁,第二次点击变成"节点找不到"而非复现缺陷)。
 * 故此处**暂停时钟**,两次点击之间只推进 150ms —— 与真机实测的必炸窗口一致。
 *
 * ## 为什么这条断言有杀伤力
 *
 * 把 4 处 `onBack` 里的护栏去掉(或换回裸 `popBackStack()`),本类两条用例立即失败:
 * 步骤区被弹空后 `TAG_GRID_CARD` / `TAG_METADATA_PAGE` 都不再存在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h1000dp")
class StepBackGuardTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 连点两次底栏返回:**第一次点击之后、退场转场结束之前**再点一次。
     *
     * 时钟暂停是复现的前提 —— 详见类注释。150ms 取自真机实测的必炸窗口(≤0.15s 必炸)。
     */
    private fun doubleTapBack(tag: String) {
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag(tag).performClick()
            // 退场转场中:该页面仍在组合树上、按钮仍可命中 —— 缺陷就发生在这个窗口里
            rule.mainClock.advanceTimeBy(150)
            rule.onNodeWithTag(tag).performClick()
        } finally {
            // 无论断言是否抛异常,都要把时钟还给测试框架,否则后续用例会挂住
            rule.mainClock.advanceTimeBy(1000)
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
    }

    @Test
    fun `double tapping back on the naming step must not empty the nested stack`() {
        rule.setContent { PixFoldApp(workspaceSource = ReadyWorkspaceSource()) }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_STEP_BACK).assertExists()

        doubleTapBack(TAG_STEP_BACK)

        // 连点两次只应退回一步:停在步骤 1(网格),而不是把嵌套栈弹空后什么都不渲染
        assertTrue(
            rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isNotEmpty(),
            "连点两次「返回排序」后应停在步骤1(网格);内容全空说明嵌套返回栈被弹空了",
        )
    }

    @Test
    fun `double tapping back on the metadata step must not empty the nested stack`() {
        // 工作流 B 的步骤容器是**另一份实现**(`LibrarySteps`),护栏必须同样接上
        rule.setContent { PixFoldApp(workspaceSource = ReadyWorkspaceSource()) }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).performClick()
        rule.onNodeWithTag(TAG_B_STEP_METADATA).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_B_STEP_BACK).assertExists()

        doubleTapBack(TAG_B_STEP_BACK)

        // ⚠️ 必须断言**正面证据**(漫画库在),不能只断言「元数据页不在」——
        // 栈被弹空时元数据页同样不在,那种写法对缺陷毫无杀伤力(第一版就写错了)。
        assertTrue(
            rule.onAllNodesWithTag(TAG_LIBRARY_PAGE).fetchSemanticsNodes().isNotEmpty(),
            "连点两次「返回」后应停在步骤1(漫画库);内容全空说明嵌套返回栈被弹空了",
        )
        assertTrue(
            rule.onAllNodesWithTag(TAG_METADATA_PAGE).fetchSemanticsNodes().isEmpty(),
            "连点两次「返回」后不应仍停在元数据页",
        )
    }
}
