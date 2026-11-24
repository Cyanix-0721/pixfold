package com.cyanix.pixfold.ui.library

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.data.task.PackTaskBus
import com.cyanix.pixfold.data.task.PackTaskRequest
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.libraryStateOf
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.ui.components.TAG_PACK_PROGRESS_DIALOG
import com.cyanix.pixfold.ui.components.TAG_PACK_PROGRESS_PERCENT
import com.cyanix.pixfold.ui.organize.NamingPage
import com.cyanix.pixfold.ui.organize.TAG_FILTER_CONFLICTS
import com.cyanix.pixfold.ui.organize.TAG_PROPOSAL_SUMMARY
import com.cyanix.pixfold.ui.plan.planRowTag
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P0 #3 与 P0 #4 的运行时行为与性能回归守卫（全部基于 `junit4.v2`）：
 *
 * 1. `LibrarySteps` 逐页进度更新只重组 `PackProgressDialog`，不触发 `LibrarySteps` / `PackPreviewPage` 重组；
 * 2. `PackPreviewPage` 的 `PlanRow` 走 `LazyColumn` 虚拟化，不一次性组合全部计划行；
 * 3. `:domain` 不可变值对象经 `compose_stability.conf` 声明为稳定后，在等价不同实例上可跳过重组；
 * 4. `PixFoldTheme` 缓存 `ColorScheme` 实例，且设置变更时正常切换；
 * 5. `NamingPage` 与 `LibraryPage` 在缓存改造后行为完整一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class PackRecompositionAndVirtualizationTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    @After
    fun tearDown() {
        PackTaskBus.resetForTest()
    }

    @Test
    fun `per-page PackTaskBus updates recompose PackProgressDialog without recomposing LibrarySteps or PackPreviewPage`() {
        val request = PackTaskRequest(
            treeUri = "content://test/tree/root",
            rootDisplayName = "root",
            plans = emptyList(),
            workspaceName = "root",
        )
        assertTrue(PackTaskBus.requestStart(request))
        PackTaskBus.publish(TaskProgress(total = 2, done = 0), cancelling = false)

        val libraryStepsCount = AtomicInteger(0)
        val packPreviewCount = AtomicInteger(0)

        rule.setContent {
            CompositionLocalProvider(
                LocalRecompositionObserver provides { target ->
                    when (target) {
                        "LibrarySteps" -> libraryStepsCount.incrementAndGet()
                        "PackPreviewPage" -> packPreviewCount.incrementAndGet()
                    }
                },
            ) {
                LibrarySteps(
                    rootPath = library.rootPath,
                    volumes = library.volumes,
                    backgroundPacking = true,
                    startAtPackStep = true,
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_PACK_PROGRESS_DIALOG).assertIsDisplayed()
        val baseSteps = libraryStepsCount.get()
        val basePreview = packPreviewCount.get()
        assertTrue(baseSteps >= 1 && basePreview >= 1, "基线应已进入组合")

        for (page in 1..20) {
            PackTaskBus.publishPage(pageDone = page, pageTotal = 100)
            rule.waitForIdle()
        }

        // 弹窗内容必须真实更新到第 20 页（证明弹窗本身确实在收集并刷新）
        rule.onNodeWithText(TestText.of(R.string.pack_progress_pages, 20, 100)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_PACK_PROGRESS_PERCENT).assertIsDisplayed()

        assertEquals(
            baseSteps,
            libraryStepsCount.get(),
            "逐页进度更新不得触发顶层 LibrarySteps 重组（实测多出 ${libraryStepsCount.get() - baseSteps} 次）",
        )
        assertEquals(
            basePreview,
            packPreviewCount.get(),
            "逐页进度更新不得触发 PackPreviewPage 全树重组（实测多出 ${packPreviewCount.get() - basePreview} 次）",
        )

        // 取消态更新同样隔离在进度弹窗内
        PackTaskBus.requestCancel()
        rule.waitForIdle()
        rule.onNodeWithText(TestText.of(R.string.pack_progress_cancelling)).assertIsDisplayed()
        assertEquals(baseSteps, libraryStepsCount.get(), "取消态更新不应触发 LibrarySteps 重组")
        assertEquals(basePreview, packPreviewCount.get(), "取消态更新不应触发 PackPreviewPage 重组")
    }

    @Test
    fun `PackPreviewPage virtualizes PlanRows instead of eagerly composing every plan`() {
        val base = library.volumes.first()
        val manyVolumes = (0 until 60).map { i ->
            base.copy(
                id = "vol-$i",
                dirPath = "Series/Vol ${i + 1}",
                title = "Vol ${i + 1}",
                volume = i + 1,
                pages = base.pages.take(2).mapIndexed { p, item ->
                    item.copy(id = "vol-$i-p$p")
                },
                pageOrder = pageOrderOf(
                    base.pages.take(2).mapIndexed { p, item ->
                        item.copy(id = "vol-$i-p$p")
                    },
                ),
            )
        }

        rule.setContent {
            var state by remember { mutableStateOf(libraryStateOf(library.rootPath, manyVolumes)) }
            PackPreviewPage(
                libraryState = state,
                onLibraryChange = { state = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
                showPlanSection = true,
            )
        }
        rule.waitForIdle()

        // 滚到首条计划行：第 1 行可见，但第 60 行必须尚未被 LazyColumn 组合（虚拟化生效）
        rule.onNodeWithTag(TAG_PACK_LIST).performScrollToNode(hasTestTag(planRowTag(0)))
        rule.waitForIdle()
        rule.onNodeWithTag(planRowTag(0)).assertIsDisplayed()
        rule.onAllNodesWithTag(planRowTag(59)).assertCountEquals(0)

        // 滚到底部后：第 60 行按需组合并可见，而第 1 行已被回收离场
        rule.onNodeWithTag(TAG_PACK_LIST).performScrollToNode(hasTestTag(planRowTag(59)))
        rule.waitForIdle()
        rule.onNodeWithTag(planRowTag(59)).assertIsDisplayed()
        rule.onAllNodesWithTag(planRowTag(0)).assertCountEquals(0)
    }

    @Test
    fun `domain immutable models skip recomposition on equal distinct instances via stability config`() {
        var tick by mutableIntStateOf(0)
        val probeCompositions = AtomicInteger(0)
        val baseVolume = library.volumes.first()
        val baseLibrary = libraryStateOf(library.rootPath, library.volumes)
        val baseSettings = PixFoldSettings(themeMode = ThemeMode.Light, accent = AccentChoice.Default)
        val baseProgress = TaskProgress(total = 5, done = 2)
        val baseNaming = NamingState(NamingScheme("root"))
        val baseItem = baseVolume.pages.first()

        rule.setContent {
            // 读取 tick 迫使父作用域重组，每次构造内容完全相同但引用不同的 `:domain` 副本
            val currentTick = tick
            Text("tick-$currentTick")
            DomainStabilityProbe(
                settings = baseSettings.copy(),
                progress = baseProgress.copy(),
                libraryState = baseLibrary.copy(),
                volume = baseVolume.copy(),
                item = baseItem.copy(),
                namingState = baseNaming.copy(),
                onComposed = { probeCompositions.incrementAndGet() },
            )
        }
        rule.waitForIdle()
        assertEquals(1, probeCompositions.get())

        tick = 1
        rule.waitForIdle()
        tick = 2
        rule.waitForIdle()

        assertEquals(
            1,
            probeCompositions.get(),
            ":domain 不可变类型应经 compose_stability.conf 识别为稳定并按结构相等跳过重组，实际重组 ${probeCompositions.get()} 次",
        )
    }

    @Test
    fun `PixFoldTheme caches ColorScheme across unrelated recompositions and updates on settings change`() {
        var systemDark by mutableStateOf(false)
        var settings by mutableStateOf(
            PixFoldSettings(themeMode = ThemeMode.Light, accent = AccentChoice.Default, pureBlack = false),
        )
        val observed = mutableListOf<ColorScheme>()

        rule.setContent {
            // ThemeMode.Light 下翻转 systemDark 会迫使 PixFoldTheme 本体重新执行组合，
            // 但派生出的 dark 仍为 false；未 remember 时 ColorSchemes.fromSeed 会新建实例。
            PixFoldTheme(settings = settings, systemDark = systemDark) {
                val scheme = MaterialTheme.colorScheme
                SideEffect { observed += scheme }
                Text("dark=$systemDark")
            }
        }
        rule.waitForIdle()
        val first = observed.last()

        systemDark = true
        rule.waitForIdle()
        val afterUnrelatedRecompose = observed.last()
        assertSame(
            first,
            afterUnrelatedRecompose,
            "ThemeMode.Light 下 systemDark 变化迫使 PixFoldTheme 重组时，仍应复用缓存的 ColorScheme 实例",
        )

        settings = settings.copy(themeMode = ThemeMode.Dark, pureBlack = true)
        rule.waitForIdle()
        val afterThemeChange = observed.last()
        assertNotSame(first, afterThemeChange, "主题设置变更后必须产出新的 ColorScheme")
    }

    @Test
    fun `NamingPage cached proposals and filter stay consistent`() {
        val trip = MockData.workspaceA.collections.first { it.id == "trip" }
        rule.setContent {
            NamingPage(
                collection = trip,
                order = pageOrderOf(trip.images),
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PROPOSAL_SUMMARY).assertIsDisplayed()
        rule.onNodeWithTag(TAG_FILTER_CONFLICTS).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(TestText.of(R.string.a_naming_empty_issues)).assertIsDisplayed()
    }

    @Test
    fun `LibraryPage renders series headers and volumes with cached seriesGroups`() {
        var state by mutableStateOf(libraryStateOf(library.rootPath, library.volumes))
        rule.setContent {
            LibraryPage(
                state = state,
                onStateChange = { state = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(seriesHeaderTag("进击的巨人")).assertIsDisplayed()
        rule.onNodeWithTag(volumeCardTag("aot-1")).assertIsDisplayed()

        // 切换选中卷不改变 volumes 列表，分组标题与列表保持正常
        rule.onNodeWithTag(volumeCardTag("aot-2")).performClick()
        rule.waitForIdle()
        assertEquals("aot-2", state.activeVolumeId)
        rule.onNodeWithTag(seriesHeaderTag("进击的巨人")).assertIsDisplayed()
    }
}

@Composable
private fun DomainStabilityProbe(
    settings: PixFoldSettings,
    progress: TaskProgress,
    libraryState: LibraryState,
    volume: ComicVolume,
    item: SourceItem,
    namingState: NamingState,
    onComposed: () -> Unit,
) {
    SideEffect { onComposed() }
    Text("${settings.themeMode}-${progress.done}-${libraryState.volumeCount}-${volume.id}-${item.id}-${namingState.scheme.rootDirName}")
}
