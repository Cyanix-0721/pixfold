package com.cyanix.pixfold.ui.workspace

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.organize.TAG_EMPTY_WORKSPACE
import com.cyanix.pixfold.ui.organize.TAG_EMPTY_WORKSPACE_PICK
import com.cyanix.pixfold.ui.organize.TAG_STEP_TABS
import com.cyanix.pixfold.ui.organize.OrganizeSteps
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * 工作区（D2a）与工作流 A 的接线。
 *
 * ## 要守住的东西
 *
 * 1. **空目录不能白屏**：用户选了一个没有图片的文件夹后，三个步骤页都会渲染成空白
 *    （`SortAndPreviewPage` 里 `activeCollection` 为 null 就直接 return）。空白界面上
 *    没有任何可操作入口 —— 用户会以为应用卡死。必须显式说明并给重选入口。
 * 2. **工作区条在页签行上方，不是第 4 个页签**：HANGOFF §4 的裁决。这里通过
 *    「步骤页签仍是 3 个 / 条存在且不与页签混在一起」间接钉住。
 * 3. **默认不显示条**：`workspaceState = null` 时行为与 D1 完全一致 ——
 *    这是既有 491 个测试不用改的前提，也必须有断言守着，否则将来有人把默认值改成
 *    `WorkspaceState.None` 就会静默改变所有页面布局。
 *
 * ## 刻意不测的
 *
 * - 真实 SAF 遍历（`AndroidSafSource`）需要真机 provider，Robolectric 的
 *   `ContentResolver` 里没有 DocumentsProvider，造出来的 query 全是空游标 ——
 *   那样的测试只会证明「什么都没扫到」，属假保障。这一层交给真机核对。
 * - 缩略图是否真的是那张图（同上，需要真实图片字节）。这里只钉「取不到时回退」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class OrganizeWorkspaceTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val handle = WorkspaceHandle(treeUri = "content://tree/x", displayName = "照片库")

    private fun setContent(state: WorkspaceState?, onPick: () -> Unit = {}) =
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                OrganizeSteps(
                    collections = MockData.workspaceA.collections,
                    workspaceState = state,
                    onPickWorkspace = onPick,
                )
            }
        }

    @Test
    fun `默认不显示工作区条，行为与 D1 一致`() {
        setContent(state = null)

        // 默认 null ⇒ 不画条。若将来有人把默认值改成 None，这条会红 ——
        // 那正是需要的提醒：491 个既有测试的布局前提被改动了。
        rule.onNodeWithTag(TAG_WORKSPACE_BAR).assertDoesNotExist()
        rule.onNodeWithTag(TAG_STEP_TABS).assertIsDisplayed()
    }

    @Test
    fun `授权失效时条显示在页签上方`() {
        // 2026-10-24 用户裁决(乙)：常态(None/Ready)不再挂这条 —— 选目录入口搬到首页了。
        // 只有「正忙」与「出问题」两种状态才留,这里用 Denied 作为代表。
        setContent(state = WorkspaceState.Denied(handle))

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).assertIsDisplayed()
        rule.onNodeWithTag(TAG_STEP_TABS).assertIsDisplayed()

        // 「不是第 4 个页签」：条是独立节点，不参与页签行。
        val bar = rule.onNodeWithTag(TAG_WORKSPACE_BAR).getBoundsInRoot()
        val tabs = rule.onNodeWithTag(TAG_STEP_TABS).getBoundsInRoot()
        org.junit.Assert.assertTrue(
            "工作区条必须在页签行上方（bar.top=${bar.top} tabs.top=${tabs.top}）",
            bar.top < tabs.top,
        )
    }

    @Test
    fun `空目录显示空态而不是白屏`() {
        var picks = 0
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                OrganizeSteps(
                    collections = emptyList(),
                    workspaceState = WorkspaceState.Ready(handle, emptyList()),
                    onPickWorkspace = { picks++ },
                )
            }
        }

        // 核心：不能白屏。必须有说明文字 + 可操作的重选入口。
        rule.onNodeWithTag(TAG_EMPTY_WORKSPACE).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_empty)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_empty_hint)).assertIsDisplayed()

        rule.onNodeWithTag(TAG_EMPTY_WORKSPACE_PICK).performClick()
        assertEquals(1, picks, "空态必须给出重选入口，否则用户只能退出应用")
    }

    @Test
    fun `常态下工作流里不再挂工作区条`() {
        // 2026-10-24 用户裁决(乙)的第 1 条:**只在授权失效/读取失败/扫描中显示**。
        // None 与 Ready 都不挂 —— 入口已经搬到首页,工作流里再摆一条是同一功能两处。
        // 这条与上面「默认不显示条」是一对:那条守 workspaceState == null,
        // 这条守「接了但正常」,漏掉任一条都留得下后退空间。
        setContent(state = WorkspaceState.Ready(handle, MockData.workspaceA.collections))
        rule.onNodeWithTag(TAG_WORKSPACE_BAR).assertDoesNotExist()
    }

    @Test
    fun `未选工作区时工作流里也不挂条`() {
        // 与上一条分开写:createComposeRule 的 setContent 只能调用一次,
        // 塞进同一条会在第二次调用处抛 "setContent cannot be called twice"。
        setContent(state = WorkspaceState.None)
        rule.onNodeWithTag(TAG_WORKSPACE_BAR).assertDoesNotExist()
    }

    @Test
    fun `数据源断了的时候条必须还在`() {
        // 这是本轮最关键的一条守卫:collectionsOr 在非 Ready 状态一律回退 mock,
        // 也就是说 Denied / Failed 时界面上照样有一整列看着正常的图片。条是那种处境下
        // **唯一**的重选入口 —— 若有人把它一并收掉,用户会以为自己在整理真实文件。
        setContent(state = WorkspaceState.Denied(handle))
        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).assertIsDisplayed()
    }

    @Test
    fun `读取失败的时候条也必须还在`() {
        setContent(state = WorkspaceState.Failed("io"))
        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).assertIsDisplayed()
    }

    @Test
    fun `扫描中条显示但不给动作`() {
        setContent(state = WorkspaceState.Loading)

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).assertIsDisplayed()
        // 扫描中再点一次会并发两个扫描,且用户没有可做的决定 ⇒ 不给按钮。
        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).assertDoesNotExist()
    }

    @Test
    fun `空目录但宿主未接工作区时不显示选择按钮`() {
        // 宿主不接工作区（workspaceState == null）时无从选起，只显示说明。
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                OrganizeSteps(collections = emptyList())
            }
        }

        rule.onNodeWithTag(TAG_EMPTY_WORKSPACE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_EMPTY_WORKSPACE_PICK).assertDoesNotExist()
    }
}
