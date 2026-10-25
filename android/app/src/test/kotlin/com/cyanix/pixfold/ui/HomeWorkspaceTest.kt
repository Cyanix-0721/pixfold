package com.cyanix.pixfold.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_ACTION
import com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_BAR
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 首页的工作区条与入口可用性(2026-10-24 用户裁决 乙/甲/乙)。
 *
 * ## 三条裁决原文
 *
 * 用户先问「把工作区选取改到首页?写功能里感觉意义不大」,我给了三组选项,用户回「乙甲乙」:
 *  1. **工作流页面里那两条工作区条怎么办** → **乙**:只在授权失效 / 读取失败 / 扫描中显示。
 *     全删的隐患是 `WorkspaceState.collectionsOr` 在 Denied 时**回退 mock** ——
 *     整理到一半授权失效,用户会以为在编辑真实文件,而页面上没有重新选择的入口。
 *  2. **选完工作区后停首页还是自动跳** → **甲**:停首页。两条工作流是平级任务,
 *     自动跳等于替用户决定了他要做什么。
 *  3. **还没选工作区时两张大卡禁用吗** → **乙**:禁用,首页变成「先选文件夹」引导页。
 *
 * ## 为什么这**不是**新增设计
 *
 * `HANGOFF.md:184` 的 2026-10-15 裁决早就写明「首页不加第三个入口;工作区选取长在工作流
 * 内部,首页将来只加**从属层**显示」,并给出目标形态:`:211-212`「标题行 + 设置齿轮 →
 * 两张工作流大卡(不变) → 其下从属层一行『当前工作区: … [更换]』」。当时不碰首页只是
 * 因为还没接真实文件系统("执行时机:属 D2b/D4b,本轮(D1 收尾)不碰首页")。
 *
 * ## 为什么大卡要**禁用**而不是维持 mock 演示
 *
 * [WorkspaceState.None] 的 KDoc 写着「界面按 mock 数据演示」——那是 D1 的临时态。
 * MVP 面向真实文件,首屏若不选目录就能点进工作流,用户会在 mock 数据上做排序/改名,
 * 完成后却发现什么都没写进他的文件夹。禁用 + 明示「先选文件夹」把这件事挡在入口。
 *
 * ## `workspaceState = null` 仍是**默认**
 *
 * 与 `OrganizeSteps` / `LibraryPage` 同款语义:null = 与本次改版前完全一致(不画条、
 * 不禁用)。既有 [HomeScreenTest] / [HomeCardStyleTest] 因此一行都不用改。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class HomeWorkspaceTest {

    @get:Rule
    val rule = createComposeRule()

    private val handle = WorkspaceHandle(treeUri = "content://tree/x", displayName = "照片库")

    private fun home(
        state: WorkspaceState? = null,
        onPick: () -> Unit = {},
        onOrganize: () -> Unit = {},
        onLibrary: () -> Unit = {},
    ) = rule.setContent {
        PixFoldTheme(darkTheme = false, dynamicColor = false) {
            HomeScreen(
                onOpenOrganize = onOrganize,
                onOpenLibrary = onLibrary,
                workspaceState = state,
                onPickWorkspace = onPick,
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
    }

    private val organizeTitle get() = TestText.of(R.string.home_workflow_a)
    private val libraryTitle get() = TestText.of(R.string.home_workflow_b)

    // ================= 1. 默认不改变布局 =================

    @Test
    fun `不接工作区时首页与改版前一致`() {
        var clicks = 0
        home(onOrganize = { clicks++ })

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).assertDoesNotExist()
        rule.onNodeWithText(organizeTitle).assertIsDisplayed()
        rule.onNodeWithText(organizeTitle).assertIsEnabled()
        rule.onNodeWithText(organizeTitle).performClick()
        assertEquals(1, clicks, "不接工作区时大卡必须照旧可点 —— 既有 24 处 navigation 测试的前提")
    }

    // ================= 2. 裁决 3 = 乙:未就绪时禁用两张大卡 =================

    @Test
    fun `未选工作区时两张大卡都不可点`() {
        var organize = 0
        var library = 0
        home(
            state = WorkspaceState.None,
            onOrganize = { organize++ },
            onLibrary = { library++ },
        )

        rule.onNodeWithText(organizeTitle).assertIsNotEnabled()
        rule.onNodeWithText(libraryTitle).assertIsNotEnabled()

        // 禁用必须是真的拦得住,不只是画得灰 —— 点了也不能进工作流
        rule.onNodeWithText(organizeTitle).performClick()
        rule.onNodeWithText(libraryTitle).performClick()
        assertEquals(0, organize, "未选工作区时点「整理图片」不得进入工作流")
        assertEquals(0, library, "未选工作区时点「打包漫画」不得进入工作流")
    }

    /**
     * 四个「不可进」状态各占一条用例。
     *
     * **为什么不用 for 循环塞进一条**：`createComposeRule` 的 `setContent` 每例只能调一次
     * （第二次抛 `IllegalStateException: has already set content`）—— 首版写成循环，
     * 全量跑时正是这条红的。拆开也顺带让失败信息直接点名是哪个状态漏了。
     */
    private fun assertBlocked(state: WorkspaceState, why: String) {
        var clicks = 0
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                HomeScreen(
                    onOpenOrganize = { clicks++ },
                    onOpenLibrary = { clicks++ },
                    workspaceState = state,
                    onPickWorkspace = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.onNodeWithText(organizeTitle).performClick()
        rule.onNodeWithText(libraryTitle).performClick()
        assertEquals(0, clicks, "$state 下两张大卡必须禁用 —— $why")
        rule.onNodeWithTag(TAG_WORKSPACE_GATE_HINT).assertIsDisplayed()
    }

    @Test
    fun `扫描中两张大卡不可点`() {
        assertBlocked(WorkspaceState.Loading, "正在扫描,还不知道有没有内容")
    }

    @Test
    fun `授权失效时两张大卡不可点`() {
        assertBlocked(WorkspaceState.Denied(handle), "授权已断,点进去读不到真实文件")
    }

    @Test
    fun `读取失败时两张大卡不可点`() {
        assertBlocked(WorkspaceState.Failed("io"), "读不到目录,点进去只是一列 mock")
    }

    @Test
    fun `目录里没有图片时两张大卡不可点`() {
        assertBlocked(WorkspaceState.Ready(handle, emptyList()), "扫到 0 张,进去只有空态")
    }

    @Test
    fun `工作区就绪后两张大卡可点`() {
        var organize = 0
        var library = 0
        home(
            state = WorkspaceState.Ready(handle, MockData.workspaceA.collections),
            onOrganize = { organize++ },
            onLibrary = { library++ },
        )

        rule.onNodeWithText(organizeTitle).assertIsEnabled()
        rule.onNodeWithText(libraryTitle).assertIsEnabled()
        rule.onNodeWithText(organizeTitle).performClick()
        rule.onNodeWithText(libraryTitle).performClick()
        assertEquals(1, organize)
        assertEquals(1, library)
    }

    // ================= 3. 工作区条:位置与文案 =================

    @Test
    fun `工作区条在两张大卡之下`() {
        home(state = WorkspaceState.Ready(handle, MockData.workspaceA.collections))

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).assertIsDisplayed()
        val bar = rule.onNodeWithTag(TAG_WORKSPACE_BAR).getBoundsInRoot()
        val cardB = rule.onNodeWithText(libraryTitle).getBoundsInRoot()

        // HANGOFF.md:211-212 定的形态:标题行 → 两张卡 → 其下从属层。
        // 条若跑到卡上面,就是「第三个入口与两个任务抢视觉重量」——正是 2026-10-15
        // 裁决要避免的那件事。
        assertTrue(
            bar.top >= cardB.bottom,
            "工作区条必须在两张大卡之下(bar.top=${bar.top} 卡B.bottom=${cardB.bottom})",
        )
    }

    @Test
    fun `工作区条显示当前目录名`() {
        home(state = WorkspaceState.Ready(handle, MockData.workspaceA.collections))
        rule.onNodeWithText(TestText.of(R.string.workspace_current, "照片库")).assertIsDisplayed()
    }

    @Test
    fun `未选工作区时条给出选择入口并触发回调`() {
        var picks = 0
        home(state = WorkspaceState.None, onPick = { picks++ })

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).assertIsDisplayed()
        rule.onNodeWithTag(TAG_WORKSPACE_ACTION).performClick()
        assertEquals(1, picks, "首页必须能从条上直接选文件夹")
    }
}
