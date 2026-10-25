package com.cyanix.pixfold.ui.workspace

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WorkspaceBar] 的状态 → 文案/按钮映射。
 *
 * ## 这一层为什么必须测
 *
 * D2a 之前应用**从不显示数据来源**：用户看到的一直是 mock 数据。加入真实目录后，
 * 「现在屏幕上是演示数据还是你选的目录」变成用户必须能一眼分清的事 ——
 * 分不清就会出现「我明明选了文件夹，怎么还是那几张旧图」这类无法自我诊断的困惑。
 *
 * 因此这里钉三件容易漂移的事：
 *  1. **无授权时条仍然显示**（而不是整条隐藏）——否则用户以为看到的就是真数据；
 *  2. **每个状态都有可操作的出路**（扫描中除外，那是唯一不该给按钮的状态）；
 *  3. **失效文案带目录名** —— 「哪个目录失效了」决定用户该重选哪一个。
 *
 * 刻意**不**测像素/颜色：颜色是非语义信息，本类只承诺「说什么、能不能点」。
 * 视觉规格（错误色）由 [WorkspaceBar] 内部按 `MaterialTheme.colorScheme.error` 取，
 * 属主题体系的事，已由既有 `ThemeTest` 一类覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class WorkspaceBarTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val handle = WorkspaceHandle(treeUri = "content://tree/x", displayName = "照片库")

    private fun setContent(state: WorkspaceState, onPick: () -> Unit = {}) =
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                WorkspaceBar(state = state, onPick = onPick)
            }
        }

    private fun setContentWithList(
        state: WorkspaceState,
        onPick: () -> Unit = {},
        onOpenList: () -> Unit = {},
    ) = rule.setContent {
        PixFoldTheme(darkTheme = false, dynamicColor = false) {
            WorkspaceBar(state = state, onPick = onPick, onOpenList = onOpenList)
        }
    }

    @Test
    fun `未选目录时显示演示数据说明与选择入口`() {
        var picks = 0
        setContent(WorkspaceState.None, onPick = { picks++ })

        // 关键：条**必须**出现。隐藏它会让用户以为屏上的 mock 数据是真的。
        rule.onNodeWithTag(TAG_WORKSPACE_BAR).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_demo_note)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_pick)).assertIsDisplayed()

        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).performClick()
        assertEquals(1, picks, "选择按钮必须把点击转给 onPick")
    }

    @Test
    fun `扫描中不给按钮`() {
        var picks = 0
        setContent(WorkspaceState.Loading, onPick = { picks++ })

        rule.onNodeWithText(TestText.of(R.string.workspace_loading)).assertIsDisplayed()
        // 扫描中再点一次会并发第二个扫描，且用户此刻没有可做的决定。
        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).assertIsNotDisplayed()
        assertEquals(0, picks)
    }

    /**
     * 扫描中整条必须仍有 **≥48dp** 的触控区（AGENTS.md §2.7 硬约束）。
     *
     * ## 这条守的是 Task 5 评审 Important 1
     *
     * Task 5 给 Row 加了 `.clickable(onClick = rowAction)` 让整条可点，而
     * `Modifier.clickable` **自己不给最小触控尺寸**。其余四态之所以安全，是因为
     * 右侧 `TextButton` 的 M3 `minimumInteractiveComponentSize()` 把行撑到 ≥48dp；
     * 而 [WorkspaceState.Loading] 分支把 `actionLabel` 置空 ⇒ 按钮不渲染 ⇒
     * 那层保护恰好缺席，行高退化成一行 `bodySmall` 文本（离线实测 **16.0dp**）。
     *
     * `showsWorkspaceBar` 在 Loading 态为 true，所以这是**生产上真实存在**的
     * 不足 48dp 触控区，不是测试环境的假象。
     *
     * 离线可断：高度由 `.heightIn(min = 48.dp)` 撑出来，**不依赖文本测量**
     * （Robolectric 只在宽度方向量不准）。同型先例见
     * `ui/components/ChipLayoutRulesTest.kt` 的 `the collapse chip has a 48dp touch target`。
     */
    @Test
    fun `扫描中整条仍有 48dp 触控区`() {
        setContent(WorkspaceState.Loading)

        val bar = rule.onNodeWithTag(TAG_WORKSPACE_BAR).getUnclippedBoundsInRoot()
        val height = bar.bottom.value - bar.top.value
        assertTrue(
            height >= 48f,
            "扫描中整条是这一态唯一的可点区域，触摸目标必须 ≥48dp（AGENTS.md §2.7），实际 ${height}dp",
        )
    }

    @Test
    fun `成功时显示目录名与工作区入口`() {
        val state = WorkspaceState.Ready(
            handle = handle,
            collections = listOf(
                ImageCollection(id = "c", name = "c", rootDirName = "c", images = emptyList()),
            ),
        )
        setContent(state)

        // 带目录名：用户要能确认「选的是不是我要的那个文件夹」。
        rule.onNodeWithText(TestText.of(R.string.workspace_current, "照片库")).assertIsDisplayed()
        // 有列表之后 Ready 态的按钮打开的是**列表**而非系统选择器，文案随之改键：
        // 仍叫「更换」会让人以为按下去直接跳系统选择器（Task 5 的改判）。
        rule.onNodeWithText(TestText.of(R.string.workspace_button)).assertIsDisplayed()
    }

    @Test
    fun `授权失效时文案带目录名并允许重选`() {
        var picks = 0
        setContent(WorkspaceState.Denied(handle), onPick = { picks++ })

        rule.onNodeWithText(TestText.of(R.string.workspace_denied, "照片库")).assertIsDisplayed()

        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).performClick()
        assertEquals(1, picks, "失效后必须能直接重选，不能让用户退回首页重进")
    }

    @Test
    fun `授权失效但没有目录名时用通用文案`() {
        // 冷启动时 SharedPreferences 里的显示名可能是空的（旧版本存过、或 query 失败）。
        // 此时格式化串里的 %1$s 会变成空串 —— 必须回退到不带参数的通用文案。
        setContent(WorkspaceState.Denied(WorkspaceHandle(treeUri = "content://tree/x", displayName = "")))

        rule.onNodeWithText(TestText.of(R.string.workspace_denied_generic)).assertIsDisplayed()
    }

    @Test
    fun `其他失败时给重选入口`() {
        var picks = 0
        setContent(WorkspaceState.Failed("boom"), onPick = { picks++ })

        rule.onNodeWithText(TestText.of(R.string.workspace_failed)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).performClick()
        assertEquals(1, picks)
    }


    @Test
    fun `自动重试过仍失败时措辞如实告知而非假装一次成功`() {
        // 「尽力了」与「一次没试就放弃」对用户是两种信号。
        var picks = 0
        setContent(
            WorkspaceState.Denied(
                WorkspaceHandle(treeUri = "content://tree/x", displayName = "旅行照片"),
                autoRetried = 1,
            ),
            onPick = { picks++ },
        )

        // 文案必须**真的换成「重试后」那一版** —— 只断言「目录名在、按钮在」是不够的：
        // 两版文案都含目录名、都有按钮，这种断言对缺陷毫无杀伤力（M6 变异存活实证）。
        val expected = TestText.of(R.string.workspace_denied_retried, "旅行照片")
        rule.onNodeWithText(expected).assertExists()
        // 反向确认：不该同时出现「一次没试就放弃」那版。
        rule.onNodeWithText(TestText.of(R.string.workspace_denied, "旅行照片")).assertDoesNotExist()
        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).assertExists()
        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).performClick()
        assertEquals(1, picks)
    }

    @Test
    fun `就绪态点击整条会打开列表`() {
        var opened = 0
        setContentWithList(WorkspaceState.Ready(handle, emptyList()), onOpenList = { opened++ })

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()

        assertEquals(1, opened, "整条都该能打开列表，不能只有右侧小按钮")
    }

    @Test
    fun `就绪态右侧按钮打开列表而不是系统选择器`() {
        var opened = 0
        var picked = 0
        setContentWithList(
            WorkspaceState.Ready(handle, emptyList()),
            onPick = { picked++ },
            onOpenList = { opened++ },
        )

        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).performClick()

        assertEquals(1, opened)
        assertEquals(0, picked, "就绪态的按钮是「工作区」，不是「更换」")
    }

    @Test
    fun `未选工作区时点击整条仍直接走系统选择器`() {
        var opened = 0
        var picked = 0
        setContentWithList(WorkspaceState.None, onPick = { picked++ }, onOpenList = { opened++ })

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()

        assertEquals(1, picked, "没有列表可开时，整条点击必须仍能选文件夹")
        assertEquals(0, opened)
    }
}
