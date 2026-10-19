package com.cyanix.pixfold

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.ui.components.TAG_GRID_CARD
import com.cyanix.pixfold.ui.components.TAG_RESET_ALL
import com.cyanix.pixfold.ui.components.TAG_RESET_ALL_CONFIRM
import com.cyanix.pixfold.ui.components.TAG_RESET_ALL_DIALOG
import com.cyanix.pixfold.ui.organize.TAG_STEP_BACK
import com.cyanix.pixfold.ui.organize.collectionTag
import com.cyanix.pixfold.ui.organize.TAG_STEP_NAMING
import com.cyanix.pixfold.ui.organize.TAG_STEP_TABS
import com.cyanix.pixfold.ui.organize.OrganizeSteps
import com.cyanix.pixfold.ui.library.TAG_B_STEP_TABS
import com.cyanix.pixfold.ui.library.TAG_LIBRARY_LIST
import com.cyanix.pixfold.ui.library.TAG_LIBRARY_NEXT
import com.cyanix.pixfold.ui.library.TAG_LIBRARY_PAGE
import com.cyanix.pixfold.ui.library.TAG_LIBRARY_SUMMARY
import com.cyanix.pixfold.ui.library.LibrarySteps
import com.cyanix.pixfold.ui.library.volumeIncludedTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「重置全部调整」按钮的守护(用户 2026-10-14 裁决,工作流 A/B 同构)。
 *
 * ## 需求(用户逐字裁决)
 *
 * - 范围:新增「重置全部」图标按钮,**放在步骤 Tab 行右侧**,重置该工作流的全部状态
 *   (排序规则 / 人工调整 / 命名结构与覆盖);**长按浮出「重置全部调整」**。
 * - 二次确认:需要,弹「重置全部调整?· 将清除排序规则、人工调整、命名覆盖与元数据编辑」。
 * - 重置后**回到步骤 1**。
 *
 * ## 为什么必须端到端组合 `OrganizeSteps` / `LibrarySteps`
 *
 * 「重置全部」的价值就在于**跨步骤生效**:在第 2 步点一下,第 1 步的人工调整要没了。
 * 单页级断言(只组合 `SortAndPreviewPage`)看不到这条 —— 那时重置按钮根本不在树上。
 *
 * ## 这条断言为什么有杀伤力
 *
 * 把 `resetAll()` 里的 `updateState(organizeStateOf(collections))` 删掉(只做导航),
 * 第一条用例立即失败:人工调整还在,首格文本与重置前相同。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h1000dp")
class ResetAllTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val collections = MockData.workspaceA.collections

    /** 首格的无障碍描述 —— 拖拽/重置后它是否变化才是「界面真的重建了」的证据。 */
    private fun firstCardText(): String {
        val cfg = rule.onAllNodesWithTag(TAG_GRID_CARD)[0].fetchSemanticsNode().config
        val key = SemanticsProperties.ContentDescription
        return if (cfg.contains(key)) cfg[key].firstOrNull() ?: "" else ""
    }

    private fun dragFirstCardToThird() {
        val node = rule.onAllNodesWithTag(TAG_GRID_CARD)[0].fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val from = node.boundsInRoot.center - origin
        val to = rule.onAllNodesWithTag(TAG_GRID_CARD)[2].fetchSemanticsNode().boundsInRoot.center - origin
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performTouchInput {
            down(from)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveTo(to)
            up()
        }
    }

    @Test
    fun `reset all clears manual adjustment and returns to step 1`() {
        rule.setContent { OrganizeSteps(collections = collections) }

        val before = firstCardText()
        assertTrue(before.isNotEmpty(), "首格应有可读描述")
        dragFirstCardToThird()
        val afterDrag = firstCardText()
        assertTrue(afterDrag != before, "拖拽后首格应变化(否则本用例前提不成立)")

        // 走到步骤 2 —— 重置按钮要能在别的步骤上按,且按完回到步骤 1
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_STEP_BACK).assertExists()

        rule.onNodeWithTag(TAG_RESET_ALL).performClick()
        rule.waitForIdle()
        // 必须先确认:重置是破坏性动作(约束 5)
        rule.onNodeWithTag(TAG_RESET_ALL_DIALOG).assertExists()
        rule.onNodeWithTag(TAG_RESET_ALL_CONFIRM).performClick()
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithTag(TAG_GRID_CARD).fetchSemanticsNodes().isNotEmpty(),
            "重置全部后应回到步骤1(网格);实际没回到",
        )
        assertEquals(
            before,
            firstCardText(),
            "重置全部必须清除人工调整 —— 首格应回到自动序(重置前=$before, 重置后=${firstCardText()})",
        )
    }

    @Test
    fun `reset all returns to the first collection`() {
        rule.setContent { OrganizeSteps(collections = collections) }

        // 切到第二个集合 —— 制造与初值的可见差异
        val secondId = collections[1].id
        rule.onNodeWithTag(collectionTag(secondId)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(collectionTag(secondId)).assertIsSelected()

        rule.onNodeWithTag(TAG_RESET_ALL).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_RESET_ALL_CONFIRM).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(collectionTag(collections.first().id)).assertIsSelected()
        rule.onNodeWithTag(collectionTag(secondId)).assertIsNotSelected()
    }

    /**
     * 用户 2026-10-14 裁决:「默认返回到主页改为触发重置。**只有工作流内部切换不会自动重置**」。
     *
     * ## 为什么必须走真的 `PixFoldApp`(而不是裸组合 `OrganizeSteps`)
     *
     * 这条规则是**导航层**的:工作流 A 的共享状态提升在 `PixFoldApp` 里,「回主页」
     * 是根 NavHost 的一次 pop。裸组合 `OrganizeSteps` 时那条 pop 根本不存在,
     * 断言会假绿。
     *
     * ## 与上一条用例互补
     *
     * 上一条(`reset all returns to the first collection`)守「点按钮 ⇒ 内部复位」;
     * 本条守「离开工作流 ⇒ 复位」。两条都删不掉:只按按钮离开、或只离开不按按钮,
     * 都是用户明确要的行为。
     */
    @Test
    fun `returning home resets the workflow so re-entering starts clean`() {
        rule.setContent { PixFoldApp() }

        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.waitForIdle()

        val before = firstCardText()
        assertTrue(before.isNotEmpty(), "首格应有可读描述")
        dragFirstCardToThird()
        assertTrue(firstCardText() != before, "拖拽后首格应变化(否则本用例前提不成立)")

        // 步骤内部切一下再回来 —— 不能因为切步骤就把调整清掉
        // (用户逐字:「只有工作流内部切换不会自动重置」)
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_STEP_BACK).performClick()
        rule.waitForIdle()
        assertTrue(
            firstCardText() != before,
            "工作流内部切步骤**不得**重置调整;实际已回到自动序",
        )

        // 回主页
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).assertIsDisplayed()

        // 再次进入 —— 应回到干净初值
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.waitForIdle()
        assertEquals(
            before,
            firstCardText(),
            "回到主页应触发重置:再次进入应是自动序(进入前=$before, 再次进入后=${firstCardText()})",
        )
    }

    @Test
    fun `workflow A reset button sits at the right end of the step tab row`() {
        rule.setContent { OrganizeSteps(collections = collections) }

        val tabs = rule.onNodeWithTag(TAG_STEP_TABS).getBoundsInRoot()
        val reset = rule.onNodeWithTag(TAG_RESET_ALL).getBoundsInRoot()
        val rootWidth = rule.onRoot().getBoundsInRoot().right

        assertTrue(
            reset.left > rootWidth / 2,
            "重置按钮应在右半屏;实际 left=${reset.left} 视口宽=$rootWidth",
        )
        assertTrue(
            reset.left >= tabs.right - 1.dp,
            "重置按钮应紧接在页签行右侧;实际 reset.left=${reset.left} tabs.right=${tabs.right}",
        )
        assertTrue(
            reset.top < tabs.bottom && reset.bottom > tabs.top,
            "重置按钮应与步骤页签同处一行;实际 reset=$reset tabs=$tabs",
        )
    }

    @Test
    fun `workflow A long pressing reset shows its label`() {
        rule.setContent { OrganizeSteps(collections = collections) }

        rule.onNodeWithTag(TAG_RESET_ALL).performTouchInput { longClick() }
        rule.waitForIdle()

        rule.onNodeWithText(TestText.of(R.string.common_reset_all_hint)).assertExists()
    }

    @Config(sdk = [36], qualifiers = "w400dp-h2000dp")
    @Test
    fun `workflow B reset restores volume selection and returns to step 1`() {
        val lib = MockDataB.library
        rule.setContent { LibrarySteps(rootPath = lib.rootPath, volumes = lib.volumes) }

        fun summary(): String =
            rule.onNodeWithTag(TAG_LIBRARY_SUMMARY, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

        val beforeSummary = summary()

        // 取消勾选一卷 —— 这是「卷勾选」这一项的可见差异
        rule.onNodeWithTag(TAG_LIBRARY_LIST)
            .performScrollToNode(hasTestTag(volumeIncludedTag("aot-1")))
        rule.onNodeWithTag(volumeIncludedTag("aot-1")).performClick()
        rule.waitForIdle()
        assertTrue(
            summary() != beforeSummary,
            "取消勾选后摘要应变化(否则本用例前提不成立):$beforeSummary",
        )

        // 走到步骤 2 再重置 —— 与 A 侧同构
        rule.onNodeWithTag(TAG_LIBRARY_NEXT).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_RESET_ALL).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_RESET_ALL_DIALOG).assertExists()
        rule.onNodeWithTag(TAG_RESET_ALL_CONFIRM).performClick()
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithTag(TAG_LIBRARY_PAGE).fetchSemanticsNodes().isNotEmpty(),
            "工作流 B 重置全部后应回到步骤1(漫画库)",
        )
        assertEquals(
            beforeSummary,
            summary(),
            "工作流 B 的重置必须恢复卷勾选(重置前=$beforeSummary, 重置后=${summary()})",
        )
    }

    @Config(sdk = [36], qualifiers = "w400dp-h2000dp")
    @Test
    fun `workflow B reset button sits at the right end of the step tab row`() {
        val lib = MockDataB.library
        rule.setContent { LibrarySteps(rootPath = lib.rootPath, volumes = lib.volumes) }

        val tabs = rule.onNodeWithTag(TAG_B_STEP_TABS).getBoundsInRoot()
        val reset = rule.onNodeWithTag(TAG_RESET_ALL).getBoundsInRoot()
        val rootWidth = rule.onRoot().getBoundsInRoot().right

        assertTrue(
            reset.left > rootWidth / 2,
            "工作流 B 的重置按钮也应在右半屏;实际 left=${reset.left} 视口宽=$rootWidth",
        )
        assertTrue(
            reset.left >= tabs.right - 1.dp,
            "工作流 B 的重置按钮应紧接页签行右侧;实际 reset.left=${reset.left} tabs.right=${tabs.right}",
        )
    }
}
