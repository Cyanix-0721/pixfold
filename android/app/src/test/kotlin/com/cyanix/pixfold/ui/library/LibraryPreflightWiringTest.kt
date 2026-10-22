package com.cyanix.pixfold.ui.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.R
import com.cyanix.pixfold.ui.components.TAG_TASK_PROGRESS
import com.cyanix.pixfold.data.task.PackTaskRequest
import com.cyanix.pixfold.data.task.PackTaskBus
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.pack.PackPreflightResult
import com.cyanix.pixfold.domain.pack.Zip64Requirement
import com.cyanix.pixfold.domain.pack.Zip64Reason
import com.cyanix.pixfold.domain.pack.PackVolumeCheck
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 计划页预检的**接线**断言（D4b）。
 *
 * 为什么单独一个文件：`PackPreviewPageTest` 里的 `PackPreflightPanelTest` 直接给
 * `PackPreviewPage` 喂一个写死的 `PackUiPreflight` ⇒ 它证明的是「渲染对不对」，
 * **证不了**「有没有人去算」。这类「界面读数一样、但背后根本没跑」的静默失效
 * 在 D4b 已经踩过两次（真实改名 vs 模拟执行、预检只在执行时才跑）。
 *
 * 所以这里注入一个**记账用的假执行器**，断言两件事：
 *  1. 进步骤 3 时 `preflight()` 真的被调用了（次数 > 0）；
 *  2. 它算出来的结论真的出现在界面上（而不是被丢了）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class LibraryPreflightWiringTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /** 记账执行器：`execute` 一次都不该被调到（预检不是执行）。 */
    private class RecordingExecutor(
        private val answer: PackUiPreflight,
    ) : PackExecutorPort {
        var preflightCalls = 0
            private set
        var executeCalls = 0
            private set
        val askedPlans = mutableListOf<List<VolumePackPlan>>()

        override suspend fun preflight(plans: List<VolumePackPlan>): PackUiPreflight {
            preflightCalls++
            askedPlans += plans
            return answer
        }

        override suspend fun execute(
            plans: List<VolumePackPlan>,
            token: CancellationToken,
            onProgress: (TaskProgress) -> Unit,
            onPageWritten: (volumeIndex: Int, pageDone: Int, pageTotal: Int) -> Unit,
        ): PackUiOutcome {
            executeCalls++
            return PackUiOutcome.Error("不应被调用")
        }
    }

    private fun readyWithZip64(): PackUiPreflight {
        val plan = com.cyanix.pixfold.domain.plan.buildPackagePlans(library.volumes).first()
        return PackUiPreflight.Ready(
            result = PackPreflightResult(
                volumes = listOf(
                    PackVolumeCheck(
                        plan = plan,
                        entryCount = 3,
                        estimatedBytes = 5L * 1024 * 1024 * 1024,
                        hasUnknownSize = false,
                        zip64 = Zip64Requirement.Needed(Zip64Reason.CentralDirOffsetTooLarge),
                    ),
                ),
                blockers = emptyList(),
                warnings = listOf(
                    com.cyanix.pixfold.domain.pack.PackWarning.Zip64Required(
                        outputDisplay = plan.outputDisplay,
                        reason = Zip64Reason.CentralDirOffsetTooLarge,
                    ),
                ),
                estimatedBytes = 5L * 1024 * 1024 * 1024,
                availableBytes = 20L * 1024 * 1024 * 1024,
            ),
            availableBytes = 20L * 1024 * 1024 * 1024,
        )
    }

    private fun show(executor: PackExecutorPort?) {
        rule.setContent {
            LibrarySteps(
                rootPath = library.rootPath,
                volumes = library.volumes,
                packExecutor = executor,
            )
        }
    }

    private fun gotoPackStep() {
        rule.onNodeWithTag(TAG_LIBRARY_NEXT).performClick()
        rule.onNodeWithTag(TAG_METADATA_NEXT).performClick()
    }

    @Test
    fun `entering the pack step runs the preflight`() {
        val executor = RecordingExecutor(readyWithZip64())
        show(executor)

        // 进步骤 3 之前**一次都不该算**：步骤 1/2 的卷还在改，
        // 提前算出来的结论会立刻过期。
        assertEquals(0, executor.preflightCalls, "还没进计划页就不该预检")

        gotoPackStep()
        rule.waitForIdle()

        assertTrue(executor.preflightCalls > 0, "进入计划页后必须真的跑一次预检")
        // 预检不是执行：这条分支一旦被走到，说明「先算再问」退化成了「直接开干」。
        assertEquals(0, executor.executeCalls, "预检不能触发执行")
    }

    @Test
    fun `the preflight conclusion reaches the screen`() {
        // 只断「preflight 被调了」不够 —— 调用结果被丢掉时界面照样一片干净，
        // 而那正是这条链路要解决的原始问题（告知项没有出口）。
        val executor = RecordingExecutor(readyWithZip64())
        show(executor)
        gotoPackStep()
        rule.waitForIdle()

        rule.onAllNodesWithTag(TAG_PACK_PREFLIGHT).assertCountEquals(1)
        rule.onAllNodesWithTag(TAG_PACK_PREFLIGHT_WARNING).assertCountEquals(1)
        rule.onNodeWithText(
            TestText.of(R.string.pack_warn_zip64_reason_offset),
            substring = true,
        ).assertExists()
    }

    @Test
    fun `an unstable executor identity does not restart an in-flight preflight`() {
        // 2026-10-20 真机缺陷的回归测试。
        //
        // 现场：`packExecutor` 在 `PixFoldApp` 里没有 `remember`，每次组合都是**新对象**。
        // 一旦它进了 `LaunchedEffect` 的 key，「换了对象」就被当成「条件变了」⇒
        // 预检被反复取消重启，而每次重启都先把结论置 null ⇒ 结论永远落不到界面。
        //
        // 判别力来自「让 preflight **永不返回**」：
        //  · 修复后（`rememberUpdatedState`）⇒ 只调用 1 次，之后重组不再触发；
        //  · 修复前（对象身份进 key）⇒ 每重组一次就多一次调用，计数持续增长。
        // 若只让 preflight 立刻返回，两种实现最后都能显示出结论 —— 那样断不出区别，
        // 这正是我第一版测试变异存活的原因。
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val tick = androidx.compose.runtime.mutableIntStateOf(0)

        rule.setContent {
            // 读 tick 让外部能推动重组。
            @Suppress("UNUSED_EXPRESSION")
            tick.intValue
            LibrarySteps(
                rootPath = library.rootPath,
                volumes = library.volumes,
                // 刻意不 remember：每次重组都新建。
                packExecutor = object : PackExecutorPort {
                    override suspend fun preflight(plans: List<VolumePackPlan>): PackUiPreflight {
                        calls.incrementAndGet()
                        // 挂住不返回，模拟「真机上 StatFs + 领域计算还没算完」那段窗口。
                        kotlinx.coroutines.awaitCancellation()
                    }

                    override suspend fun execute(
                        plans: List<VolumePackPlan>,
                        token: CancellationToken,
                        onProgress: (TaskProgress) -> Unit,
                        onPageWritten: (volumeIndex: Int, pageDone: Int, pageTotal: Int) -> Unit,
                    ): PackUiOutcome = PackUiOutcome.Error("不应被调用")
                },
            )
        }
        gotoPackStep()
        rule.waitForIdle()
        val afterEntry = calls.get()
        assertTrue(afterEntry >= 1, "进入计划页必须发起一次预检")

        // 推三次重组；修复后不该追加任何调用。
        repeat(3) {
            tick.intValue += 1
            rule.waitForIdle()
        }
        assertEquals(
            afterEntry,
            calls.get(),
            "执行器换成新实例不该重启预检 —— 否则每次都把结论清空，界面永远看不到",
        )
    }

    @Test
    fun `no preflight runs when no executor is wired`() {
        // 模拟路径（测试、D1 演示）没有平台去问空间。此时硬算会得到一条假的
        // 「空间无法判定」，比什么都不显示更糟。
        show(executor = null)
        gotoPackStep()
        rule.waitForIdle()
        rule.onAllNodesWithTag(TAG_PACK_PREFLIGHT).assertCountEquals(0)
    }

    /**
     * 点打包通知进来 ⇒ 直接落在步骤 3。
     *
     * 2026-10-20 真机缺陷：进度通知点了回到**首页**，而用户想看的是进度。
     * 修法分两段（intent 带 extra + 本组件按标记跳步），这里钉第二段。
     *
     * ## 为什么断言「计划页的独有内容」而不是「步骤序号 == 2」
     *
     * 步骤序号是内部派生值，断言它等于在测自己的实现；用户在界面上区分三个
     * 步骤的唯一依据是各页内容。断言「确认打包」按钮存在，才真的说明人到了那一步。
     */
    @Test
    fun `starting at the pack step lands on the pack page without clicking through`() {
        rule.setContent {
            LibrarySteps(
                rootPath = library.rootPath,
                volumes = library.volumes,
                // 不点任何「下一步」，靠参数直接到位。
                startAtPackStep = true,
            )
        }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_LIBRARY_NEXT).assertDoesNotExist()
        rule.onNodeWithText(TestText.of(R.string.pack_confirm)).assertExists()
    }

    /** 默认（正常从首页进入）必须仍停在步骤 1 —— 否则每次进工作流都跳到打包页。 */
    @Test
    fun `without the flag the workflow still starts on step one`() {
        show(executor = null)
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_LIBRARY_NEXT).assertExists()
    }

    /**
     * 跳完之后必须回调上层清零。
     *
     * **为什么单独一条**（2026-10-20 真机第二轮）：热启动点通知时 `onNewIntent`
     * 把标志置 true；若只导航、不回清，用户从打包页按返回回书架后会立刻被弹回
     * 步骤 3（表现成「退不出打包页」），而且**第二次点通知会静默失效** ——
     * `LaunchedEffect` 的 key 没变就不再跑。真机实测正是这个症状。
     *
     * 断言只钉「回调发生了」，不钉调用次数：重组次数属于实现细节，
     * 钉次数会让无关的重构变红。
     */
    @Test
    fun `landing on the pack step reports back so the request can be cleared`() {
        var handled = false
        rule.setContent {
            LibrarySteps(
                rootPath = library.rootPath,
                volumes = library.volumes,
                startAtPackStep = true,
                onStartAtPackStepHandled = { handled = true },
            )
        }
        rule.waitForIdle()

        rule.onNodeWithText(TestText.of(R.string.pack_confirm)).assertExists()
        assertTrue(handled, "到达步骤 3 后必须回清上层标志，否则第二次点通知会静默失效")
    }

    /**
     * 总线在跑、但工作区还没恢复完 —— 必须直接显示进度条。
     *
     * **2026-10-20 真机缺陷**：从通知点回来时先闪一下「未开始」的打包页，
     * 再切成进度条。根因是执行态当时经由 `backgroundPacking` 这个代理判断，
     * 而 `backgroundPacking` 依赖 `packTreeUri`（工作区恢复要读 prefs + 扫目录，
     * 比首次组合慢）。这几十毫秒里总线明明在跑，界面却按「没在执行」渲染。
     *
     * 这里刻意把 `backgroundPacking` 留在默认 false（模拟「工作区还没恢复」），
     * 只把总线拨到 `Running`：**执行态的唯一判据必须是总线**。
     */
    @Test
    fun `a running task shows progress even before the workspace is restored`() {
        val request = PackTaskRequest(
            treeUri = "content://test/tree",
            rootDisplayName = "test",
            plans = emptyList(),
            workspaceName = "test",
        )
        assertTrue(PackTaskBus.requestStart(request), "夹具：总线应接受这次请求")
        try {
            // backgroundPacking 保持 false = 工作区尚未恢复。
            rule.setContent {
                LibrarySteps(
                    rootPath = library.rootPath,
                    volumes = library.volumes,
                    startAtPackStep = true,
                )
            }
            rule.waitForIdle()

            rule.onNodeWithTag(TAG_TASK_PROGRESS).assertExists()
            rule.onAllNodesWithText(TestText.of(R.string.pack_confirm)).assertCountEquals(0)
        } finally {
            PackTaskBus.resetForTest()
        }
    }
}
