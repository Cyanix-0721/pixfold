package com.cyanix.pixfold.ui.workspace

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
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
@Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
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

    @Test
    fun `成功时显示目录名并可更换`() {
        val state = WorkspaceState.Ready(
            handle = handle,
            collections = listOf(
                ImageCollection(id = "c", name = "c", rootDirName = "c", images = emptyList()),
            ),
        )
        setContent(state)

        // 带目录名：用户要能确认「选的是不是我要的那个文件夹」。
        rule.onNodeWithText(TestText.of(R.string.workspace_current, "照片库")).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.workspace_change)).assertIsDisplayed()
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
}
