package com.cyanix.pixfold.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.cyanix.pixfold.PixFoldApp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.ReadyWorkspaceSource
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.TwoEntrySource
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_ACTION
import com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_ADD
import com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_BAR
import com.cyanix.pixfold.ui.workspace.TAG_WORKSPACE_DIALOG
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import com.cyanix.pixfold.ui.workspace.workspaceRowTag
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

    // ================= 4. 工作区列表弹窗(2026-10-25 规格 §7.3) =================

    /**
     * 弹窗**初始不得存在** —— 它只能由用户点条打开。
     *
     * 这条是变异 A(把 `showWorkspaceList` 初值改成 `true`)的杀手:初值一旦是 `true`,
     * 用户冷启动就被一个工作区列表盖住首页,而 `assertIsDisplayed` 类断言**看不见这件事**
     * (弹窗是独立 window,背后节点仍算「已显示」,不判定遮挡)—— 实测把 840 条全跑一遍
     * 一条都不红,正是这条断言补上的缺口。`assertDoesNotExist` 才有杀伤力。
     */
    @Test
    fun `未点条之前不存在工作区列表弹窗`() {
        rule.setContent { PixFoldApp(workspaceSource = ReadyWorkspaceSource()) }

        rule.onNodeWithTag(TAG_WORKSPACE_DIALOG).assertDoesNotExist()
    }

    /**
     * 就绪态点工作区条必须打开列表弹窗,而不是再弹一次系统选择器。
     *
     * 判据来自规格 §7.1/§7.3:`WorkspaceBar` 整条可点,Ready 态走 `onOpenList`、
     * 其余态走 `onPick`。这里走的是**整条真实装配**(`PixFoldApp` + `ReadyWorkspaceSource`),
     * 所以它同时覆盖两件事:三处调用点接上了 `onOpenWorkspaceList`,以及弹窗挂在
     * NavHost 之外的顶层 —— 只要弹窗被写进某个页面里,这条就是第一个红的。
     *
     * ## 为什么断 `assertExists` 而不是 `assertIsDisplayed`(2026-10-25 评审 M-3 订正)
     *
     * **`assertIsDisplayed` 不感知 Dialog 遮挡**。弹窗是独立 window(实测开弹窗前后的
     * semantics root 数:1 → 2),它盖住的背景节点仍然「在语义树里且 bounds 非空」,
     * 于是对着背后的东西调 `assertIsDisplayed` 照样通过 —— 它验的是「存在且在屏内」,
     * **不是**「没被盖住」。用它断「弹窗还在」会误导后来人以为这条断言验了层级。
     *
     * 反过来它对「**不存在**」是有效的(`assertIsDisplayed` 会抛 `is not displayed`),
     * 所以「弹窗初始不显示」那一条用 `assertDoesNotExist` 依然是对的、也是变异 A 的唯一杀手。
     *
     * ⇒ **本类以及后续所有用例:Dialog 的「在不在」一律用 `assertExists` / `assertDoesNotExist`。**
     */
    @Test
    fun `就绪态点工作区条打开列表弹窗`() {
        rule.setContent { PixFoldApp(workspaceSource = ReadyWorkspaceSource()) }

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()

        // assertExists:见上方 KDoc —— assertIsDisplayed 看不见 Dialog 遮挡,是假保障。
        rule.onNodeWithTag(TAG_WORKSPACE_DIALOG).assertExists()
    }

    /**
     * 弹窗里点「添加文件夹」不得把弹窗顶掉,也不得崩。
     *
     * 刻意**不**断言系统选择器被拉起:Robolectric 下 `rememberLauncherForActivityResult`
     * 永远等不到真实 Activity result,断它只是假保障。这里只锁两件事 —— 点击不抛异常、
     * 弹窗仍在。规格 §7.3 要的是「加完文件夹回到列表,能立刻点开新的那条」。
     *
     * 同前一条:**`assertExists`,不用 `assertIsDisplayed`** —— 弹窗是独立 window,
     * `assertIsDisplayed` 不判遮挡,拿它断「弹窗还在」只给出「存在且在屏内」,
     * 后面的人会以为它验了层级(评审 M-3 / 第四节实测)。
     */
    @Test
    fun `弹窗里点添加文件夹不崩且弹窗还在`() {
        rule.setContent { PixFoldApp(workspaceSource = ReadyWorkspaceSource()) }

        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()
        rule.onNodeWithTag(TAG_WORKSPACE_ADD).performClick()

        rule.onNodeWithTag(TAG_WORKSPACE_DIALOG).assertExists()
    }

    // ============ 5. 两条 entry 的装配:拖拽上报 / 「使用中」唯一性 ============
    //
    // 下面两条都换 [TwoEntrySource]:一条 entry 的假源在语义上**压不住**它们 ——
    // 拖拽时 from == to,错序与正确归到同一下标;「使用中」也没法挂到「另一行」上。
    // 这是 Task 7 评审 I-1 / I-2 的结论(两条变异原先存活,补上这两条后转 KILLED)。

    /**
     * **`PixFoldApp.onMove` 上报的是「被拖那条 → 目标那条」,不是反过来的。**
     *
     * 压的是 `PixFoldApp.kt:793-794` 的 `all[from]` / `all[to]` 写反(评审 M8)。
     * 写反的线上表现不崩、不报错:拖 A 到 B 的位置,结果 B 被挪到了 A 的位置 ——
     * 用户只会觉得「拖了但不是我想要的效果」,而这种错最难被自己发现。
     *
     * 断的是 **treeUri 对**而不是下标对:假源在 `moveWorkspace(from, to)` 调用瞬间
     * 把下标换成 URI(见 `TwoEntrySource.moveCalls`),于是断言写成
     * `(alpha, beta)` 这种一眼能判对错的形态。
     *
     * `assertEquals(listOf(...))` 而不是 `assertTrue(size == 1)` + 单独断内容:
     * 一次手势若被识别成「拖拽 + 点击」各一次,`onMove` 会多调一遍,`size` 与内容
     * 一起断才拦得住那种多出来的调用。
     */
    @Test
    fun `拖工作区行上报的是被拖那条与目标那条`() {
        val source = TwoEntrySource()
        rule.setContent { PixFoldApp(workspaceSource = source) }
        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()

        dragWorkspaceRow(workspaceRowTag(source.uriA), workspaceRowTag(source.uriB))

        assertEquals(
            listOf(source.uriA to source.uriB),
            source.moveCalls.toList(),
            "第 1 行拖到第 2 行,onMove 必须恰好收到一次 (alpha, beta)",
        )
    }

    /**
     * **「使用中」只出现在当前那一行**,不是每行都标、也不是一行都不标。
     *
     * 压的是 `PixFoldApp.kt:774` 的 `currentTreeUri = workspaceHandle?.treeUri`
     * 被改成 `null`(评审 M9):那之后标记**全灭**,用户分不清哪个目录是当前打开的,
     * 切错目录不会立刻察觉 —— 而首页那条「当前工作区: …」在长目录名下会被省略号截断。
     *
     * 用 `onAllNodesWithText` + `assertCountEquals(1)` 而不是
     * `onNodeWithText(...).assertExists()`:**存在性断言在「只标了 1 行」与
     * 「两行都标了」上是同一个结果**,数个数才区分得开。
     *
     * 走 **merged tree**(默认)即可:行根有 `.clickable`,会 mergeDescendants,
     * 行内的 `Text` 被合并进行节点 —— 所以这里数到的是**行**,恰好对应
     * 「有几行被标成使用中」这个语义,正是要问的问题。
     */
    @Test
    fun `使用中标记只出现在当前那一行`() {
        val source = TwoEntrySource()
        rule.setContent { PixFoldApp(workspaceSource = source) }
        rule.onNodeWithTag(TAG_WORKSPACE_BAR).performClick()

        rule.onAllNodesWithText(TestText.of(R.string.workspace_in_use))
            .assertCountEquals(1)
    }

    /**
     * 模拟一次从 [fromTag] 行拖到 [toTag] 行的完整手势。
     *
     * 手法照抄 `WorkspaceListDialogTest.kt:267-286`(Task 6 已用它压住
     * `WorkspaceListDialog` 里的拖拽下标):`down → advanceEventTime(700) → moveBy →
     * moveTo → up`。
     *
     * **`advanceEventTime(700)` 不能省**:`detectDragGesturesAfterLongPress` 要真的越过
     * 长按阈值(默认 500ms)才会把 down 升级成 drag;少这一步只有一次点击,`onMove`
     * 一次都不会调,用例会以「一条都没收到」而红 —— 那种红是手势没启动,不是实现错。
     *
     * **两个节点必须减同一个 origin**(from 行的 `boundsInRoot.topLeft`):
     * `performTouchInput` 收的是**节点本地坐标**,拿两个不同的 `boundsInRoot.center`
     * 直接相减会带上两行之间的绝对偏移 ⇒ 位移算出来是 0 或翻倍(Task 6 踩过)。
     */
    private fun dragWorkspaceRow(fromTag: String, toTag: String) {
        // 弹窗正文是 300dp 上限的 verticalScroll:行可能在视口外,先确保它进来。
        rule.onNodeWithTag(fromTag).performScrollTo()
        rule.waitForIdle()

        val node = rule.onNodeWithTag(fromTag).fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val from = node.boundsInRoot.center - origin
        val to = rule.onNodeWithTag(toTag).fetchSemanticsNode().boundsInRoot.center - origin

        rule.onNodeWithTag(fromTag).performTouchInput {
            down(from)
            advanceEventTime(700) // 长按阈值必须真实经过,否则拖拽不启动
            moveBy(Offset(0f, -20f))
            moveTo(to)
            up()
        }
        rule.waitForIdle()
    }
}
