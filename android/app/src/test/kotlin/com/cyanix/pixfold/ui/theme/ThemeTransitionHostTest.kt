package com.cyanix.pixfold.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.components.PixFoldSegmentedItem
import com.cyanix.pixfold.ui.components.PixFoldTopBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ThemeTransitionHost] 的状态机、抓帧时序、底层动效打断、像素揭示波与几何计算回归断言。
 *
 * 在 `@GraphicsMode(NATIVE)` 下用 `mainClock.autoAdvance = false` 逐帧推进并触发真实 Skia 绘制，
 * 钉死三条核心不变量：
 * 1. `Capturing` 阶段必须在切走旧主题**之前**真实完成 `snapshotLayer.record`；
 * 2. 进入 `Revealing` 揭示帧时，底层主题颜色动画已在 `Settling` 期间经 `snap()` 对齐目标色，
 *    不会再出现背景 / 顶栏 / 选项框分层变色；揭示完成后自动恢复 `MotionScheme.expressive()`；
 * 3. 圆形揭示波中途，圆心处已显新主题像素，而圆外远角仍保持旧主题快照像素。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeTransitionHostTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun stepFrameAndDraw(frameStepMillis: Long = 16L) {
        rule.mainClock.advanceTimeBy(frameStepMillis)
        rule.waitForIdle()
        // Robolectric NATIVE 仅在 captureToImage() 渲染根窗口时同步刷出 View.draw -> drawWithContent，
        // 借此镜像真机 Choreographer 每帧 animation -> traversal 的完整顺序。
        rule.onRoot().captureToImage()
        rule.waitForIdle()
    }

    private fun advanceUntil(
        maxFrames: Int = 90,
        frameStepMillis: Long = 16L,
        predicate: () -> Boolean,
    ): Boolean {
        for (i in 0 until maxFrames) {
            if (predicate()) return true
            stepFrameAndDraw(frameStepMillis)
        }
        return predicate()
    }

    /** 先推一帧让刚变更的状态触发 LaunchedEffect，再推进到 Idle 并多推一帧刷出最终组合状态。 */
    private fun advanceThroughTransitionToIdle(
        maxFrames: Int = 120,
        readPhase: () -> ThemeTransitionPhase?,
    ) {
        stepFrameAndDraw()
        assertTrue(
            advanceUntil(maxFrames = maxFrames) { readPhase() == ThemeTransitionPhase.Idle },
            "应顺利完成整轮主题过渡并回到 Idle; actual=${readPhase()}",
        )
        stepFrameAndDraw()
    }

    @Test
    fun `captures old frame before switching displayedSettings on dark to light and pure black toggle`() {
        rule.mainClock.autoAdvance = false
        var currentSettings by mutableStateOf(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                pureBlack = false,
            ),
        )
        val controller = ThemeTransitionController()
        val snapshots = mutableListOf<ThemeTransitionStateSnapshot>()
        var latestDisplayed = currentSettings

        rule.setContent {
            ThemeTransitionHost(
                targetSettings = currentSettings,
                controller = controller,
                onStateChange = { snap -> snapshots += snap },
            ) { displayed ->
                latestDisplayed = displayed
                PixFoldTheme(settings = displayed) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Text(text = "${displayed.themeMode}-${displayed.pureBlack}")
                    }
                }
            }
        }
        stepFrameAndDraw()

        // 1) Dark -> Light
        controller.recordOrigin(Offset(200f, 300f))
        currentSettings = currentSettings.copy(themeMode = ThemeMode.Light)
        rule.waitForIdle()
        assertTrue(
            advanceUntil { snapshots.lastOrNull()?.phase == ThemeTransitionPhase.Revealing },
            "Dark->Light 应进入 Revealing 阶段; snapshots=$snapshots",
        )
        val firstCapture = snapshots.last { it.phase == ThemeTransitionPhase.Revealing }
        assertEquals(1, firstCapture.captureCount, "Dark->Light 切换前必须真实抓帧 1 次")
        assertTrue(firstCapture.usedWindowPixelCopy, "Dark->Light 默认应优先走异步 PixelCopy 抓帧")
        assertTrue(firstCapture.hasFrozenBitmap, "Dark->Light 进入 Revealing 时必须持有冻结快照")
        assertTrue(advanceUntil { snapshots.lastOrNull()?.phase == ThemeTransitionPhase.Idle })
        stepFrameAndDraw()
        assertEquals(ThemeMode.Light, latestDisplayed.themeMode)

        // 2) Dark (pureBlack = false) -> Dark (pureBlack = true)
        currentSettings = PixFoldSettings(themeMode = ThemeMode.Dark, accent = AccentChoice.Default, pureBlack = false)
        advanceThroughTransitionToIdle { snapshots.lastOrNull()?.phase }

        val countBeforePureBlack = snapshots.last().captureCount
        controller.recordOrigin(Offset(180f, 520f))
        currentSettings = currentSettings.copy(pureBlack = true)
        rule.waitForIdle()
        assertTrue(
            advanceUntil { snapshots.lastOrNull()?.phase == ThemeTransitionPhase.Revealing },
            "Dark->PureBlack 应进入 Revealing 阶段; snapshots=$snapshots",
        )
        val afterPureBlack = snapshots.last { it.phase == ThemeTransitionPhase.Revealing }
        assertEquals(
            countBeforePureBlack + 1,
            afterPureBlack.captureCount,
            "深色切换纯黑时也必须真实抓到旧深灰主题快照",
        )
        assertTrue(afterPureBlack.usedWindowPixelCopy, "Dark->PureBlack 默认应优先走异步 PixelCopy 抓帧")
        assertTrue(afterPureBlack.hasFrozenBitmap, "Dark->PureBlack 进入 Revealing 时必须持有冻结快照")
        assertTrue(advanceUntil { snapshots.lastOrNull()?.phase == ThemeTransitionPhase.Idle })
        assertTrue(latestDisplayed.pureBlack)
    }

    @Test
    fun `captures strictly before displayedSettings flips to new settings`() {
        rule.mainClock.autoAdvance = false
        val darkSettings = PixFoldSettings(themeMode = ThemeMode.Dark, accent = AccentChoice.Default, pureBlack = false)
        val lightSettings = darkSettings.copy(themeMode = ThemeMode.Light)
        var currentSettings by mutableStateOf(darkSettings)
        val controller = ThemeTransitionController()
        val observedDisplayedAtCapturePhase = mutableListOf<PixFoldSettings>()
        var latestDisplayed = darkSettings
        var latestSnapshot: ThemeTransitionStateSnapshot? = null

        rule.setContent {
            ThemeTransitionHost(
                targetSettings = currentSettings,
                controller = controller,
                onStateChange = { snap ->
                    latestSnapshot = snap
                    if (snap.phase == ThemeTransitionPhase.Capturing) {
                        observedDisplayedAtCapturePhase += latestDisplayed
                    }
                },
            ) { displayed ->
                latestDisplayed = displayed
                PixFoldTheme(settings = displayed) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Text(text = displayed.themeMode.name)
                    }
                }
            }
        }
        stepFrameAndDraw()

        controller.recordOrigin(Offset(100f, 100f))
        currentSettings = lightSettings
        rule.waitForIdle()
        assertTrue(advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Revealing })

        assertTrue(observedDisplayedAtCapturePhase.isNotEmpty(), "必须经历 Capturing 阶段")
        assertTrue(
            observedDisplayedAtCapturePhase.all { it == darkSettings },
            "Capturing 阶段必须仍然显示旧主题 ($darkSettings), 实际=$observedDisplayedAtCapturePhase",
        )
        assertEquals(1, latestSnapshot?.captureCount)

        assertTrue(advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Idle })
        assertEquals(lightSettings, latestDisplayed)
    }

    @Test
    fun `snaps M3E color animations so TopAppBar and SegmentedItem settle before Revealing starts`() {
        rule.mainClock.autoAdvance = false
        var currentSettings by mutableStateOf(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                pureBlack = false,
            ),
        )
        val controller = ThemeTransitionController()
        var latestSnapshot: ThemeTransitionStateSnapshot? = null
        var snapMotionActive = false
        var currentTargetBg = Color.Unspecified
        var animatedBg = Color.Unspecified
        var effectsSpecIsSnap = false

        rule.setContent {
            ThemeTransitionHost(
                targetSettings = currentSettings,
                controller = controller,
                onStateChange = { latestSnapshot = it },
            ) { displayed ->
                PixFoldTheme(settings = displayed) {
                    snapMotionActive = LocalSnapThemeMotion.current
                    currentTargetBg = MaterialTheme.colorScheme.background
                    val effectSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
                    effectsSpecIsSnap = effectSpec is SnapSpec<*>
                    val observedBg by animateColorAsState(
                        targetValue = currentTargetBg,
                        animationSpec = effectSpec,
                        label = "observedBg",
                    )
                    animatedBg = observedBg

                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {},
                        color = currentTargetBg,
                    ) {
                        Column {
                            PixFoldTopBar(
                                title = "设置",
                                onBack = {},
                                backTag = "back",
                                backCd = "返回",
                                modifier = Modifier.testTag("test-top-bar"),
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            )
                            PixFoldSegmentedItem(
                                onClick = {},
                                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                                modifier = Modifier
                                    .height(80.dp)
                                    .testTag("test-seg-item"),
                            ) {
                                Text("纯黑背景")
                            }
                        }
                    }
                }
            }
        }
        stepFrameAndDraw()

        assertFalse(snapMotionActive, "Idle 时不得压掉 M3E 动效")
        assertFalse(effectsSpecIsSnap, "Idle 时 motionScheme 应为 expressive")

        // 1) Dark -> Light（圆心设在顶栏与选项卡附近，验证进入 Revealing 时顶栏与卡片均已落定新色）
        controller.recordOrigin(Offset(200f, 100f))
        currentSettings = currentSettings.copy(themeMode = ThemeMode.Light)
        rule.waitForIdle()
        assertTrue(
            advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Revealing },
            "应推进到 Revealing 阶段; snapshot=$latestSnapshot",
        )
        assertTrue(snapMotionActive, "Revealing 阶段 LocalSnapThemeMotion 必须为 true")
        assertTrue(effectsSpecIsSnap, "Revealing 阶段 defaultEffectsSpec 必须为 SnapSpec")
        assertEquals(
            currentTargetBg,
            animatedBg,
            "进入 Revealing 第一帧时，顶栏/选项框颜色动画必须已在 Settling 对齐目标色，不能滞后",
        )

        repeat(6) { stepFrameAndDraw() }
        val midBitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val topBounds = rule.onNodeWithTag("test-top-bar").getBoundsInRoot()
        val segBounds = rule.onNodeWithTag("test-seg-item").getBoundsInRoot()
        val (topPxX, topPxY, segPxX, segPxY) = with(rule.density) {
            listOf(
                (topBounds.right - 20.dp).toPx().toInt().coerceIn(0, midBitmap.width - 1),
                ((topBounds.top + topBounds.bottom) / 2).toPx().toInt().coerceIn(0, midBitmap.height - 1),
                (segBounds.right - 20.dp).toPx().toInt().coerceIn(0, midBitmap.width - 1),
                ((segBounds.top + segBounds.bottom) / 2).toPx().toInt().coerceIn(0, midBitmap.height - 1),
            )
        }
        val topColor = Color(midBitmap.getPixel(topPxX, topPxY))
        val segColor = Color(midBitmap.getPixel(segPxX, segPxY))
        val outsideCorner = Color(midBitmap.getPixel(midBitmap.width - 20, midBitmap.height - 20))
        assertTrue(
            topColor.luminance() > 0.65f,
            "揭示波经过后顶栏底色必须已是浅色，实际 luminance=${topColor.luminance()} ($topColor)",
        )
        assertTrue(
            segColor.luminance() > 0.65f,
            "揭示波经过后 SegmentedItem 卡片必须已在幕后落定为浅色，不能滞后 4 帧仍为深色; luminance=${segColor.luminance()} ($segColor)",
        )
        assertTrue(
            outsideCorner.luminance() < 0.25f,
            "揭示波圆外右下角必须仍保持旧深色冻结快照; luminance=${outsideCorner.luminance()} ($outsideCorner)",
        )

        assertTrue(advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Idle })
        stepFrameAndDraw()
        assertFalse(snapMotionActive, "回到 Idle 后必须释放 LocalSnapThemeMotion")
        assertFalse(effectsSpecIsSnap, "回到 Idle 后必须恢复 MotionScheme.expressive()")

        // 2) Dark -> PureBlack
        currentSettings = currentSettings.copy(themeMode = ThemeMode.Dark, pureBlack = false)
        advanceThroughTransitionToIdle { latestSnapshot?.phase }

        controller.recordOrigin(Offset(150f, 360f))
        currentSettings = currentSettings.copy(pureBlack = true)
        rule.waitForIdle()
        assertTrue(advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Revealing })
        assertTrue(snapMotionActive)
        assertEquals(
            currentTargetBg,
            animatedBg,
            "深色切换纯黑时，进入 Revealing 第一帧颜色也必须一步对齐",
        )
        assertTrue(advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Idle })
        stepFrameAndDraw()
        assertFalse(effectsSpecIsSnap)
    }

    @Test
    fun `uses circular reveal when origin is recorded and falls back to crossfade without origin`() {
        rule.mainClock.autoAdvance = false
        var currentSettings by mutableStateOf(
            PixFoldSettings(themeMode = ThemeMode.System, accent = AccentChoice.Default, pureBlack = false),
        )
        var systemDark by mutableStateOf(false)
        val controller = ThemeTransitionController()
        val seenRevealingModes = mutableListOf<ThemeTransitionStateSnapshot>()
        var latestSnapshot: ThemeTransitionStateSnapshot? = null

        rule.setContent {
            ThemeTransitionHost(
                targetSettings = currentSettings,
                systemDark = systemDark,
                controller = controller,
                onStateChange = { snap ->
                    latestSnapshot = snap
                    if (snap.phase == ThemeTransitionPhase.Revealing) {
                        seenRevealingModes += snap
                    }
                },
            ) { displayed ->
                PixFoldTheme(settings = displayed) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {},
                        color = MaterialTheme.colorScheme.background,
                    ) {}
                }
            }
        }
        stepFrameAndDraw()

        // A) 非视觉改动（网格列数 / 刷新率 / 浅色下切 pureBlack / 同极性下 System->Light）均不触发抓帧
        currentSettings = currentSettings.copy(portraitDefaultColumns = 3)
        stepFrameAndDraw()
        assertEquals(0, latestSnapshot?.captureCount ?: 0)

        currentSettings = currentSettings.copy(preferredRefreshRateHz = 120)
        stepFrameAndDraw()
        assertEquals(0, latestSnapshot?.captureCount ?: 0)

        currentSettings = currentSettings.copy(pureBlack = true)
        stepFrameAndDraw()
        assertEquals(0, latestSnapshot?.captureCount ?: 0)

        currentSettings = currentSettings.copy(themeMode = ThemeMode.Light)
        stepFrameAndDraw()
        assertEquals(0, latestSnapshot?.captureCount ?: 0)

        // B) 有点击原点 -> CircularReveal（先切到 Dark，再从 (100, 100) 切 Light 并校验中途像素）
        currentSettings = currentSettings.copy(themeMode = ThemeMode.Dark, pureBlack = true)
        advanceThroughTransitionToIdle { latestSnapshot?.phase }

        val expectedOrigin = Offset(100f, 100f)
        controller.recordOrigin(expectedOrigin)
        currentSettings = currentSettings.copy(themeMode = ThemeMode.Light, pureBlack = false)
        stepFrameAndDraw()
        assertTrue(advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Revealing })
        assertEquals(ThemeTransitionMode.CircularReveal, seenRevealingModes.last().mode)
        assertEquals(expectedOrigin, seenRevealingModes.last().origin)
        assertTrue(seenRevealingModes.last().hasFrozenBitmap, "Revealing 阶段必须持有冻结位图快照")

        // 推进 5 帧（~80ms）：圆心 (100, 100) 已在圆内显露浅色新主题，而远角仍在圆外保留纯黑旧快照。
        repeat(5) { stepFrameAndDraw() }
        val midBitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val insideColor = Color(midBitmap.getPixel(100, 100))
        val outsideColor = Color(midBitmap.getPixel(midBitmap.width - 20, midBitmap.height - 20))
        assertTrue(
            insideColor.luminance() > 0.5f,
            "揭示波圆内像素应已切换为浅色新主题，实际 luminance=${insideColor.luminance()} ($insideColor)",
        )
        assertTrue(
            outsideColor.luminance() < 0.25f,
            "揭示波圆外远角像素应仍保留旧深色快照，实际 luminance=${outsideColor.luminance()} ($outsideColor)",
        )

        assertTrue(advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Idle })
        stepFrameAndDraw()
        assertFalse(latestSnapshot?.hasFrozenBitmap == true, "回到 Idle 后必须释放冻结位图")

        // C) 跟随系统极性变化（无点击原点）-> 自动回退整屏 Crossfade
        currentSettings = currentSettings.copy(themeMode = ThemeMode.System, pureBlack = false)
        stepFrameAndDraw()
        val captureBeforeSystemFlip = latestSnapshot?.captureCount ?: 0
        systemDark = true
        stepFrameAndDraw()
        assertTrue(
            advanceUntil {
                (latestSnapshot?.captureCount ?: 0) > captureBeforeSystemFlip &&
                    latestSnapshot?.phase == ThemeTransitionPhase.Revealing
            },
            "系统深浅极性变化应进入 Revealing; snapshot=$latestSnapshot",
        )
        assertEquals(ThemeTransitionMode.Crossfade, seenRevealingModes.last().mode)
        assertNull(seenRevealingModes.last().origin)
        assertTrue(advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Idle })
        stepFrameAndDraw()

        // D) 几何辅助：最大半径必须覆盖画布四角
        val size = Size(1080f, 2400f)
        val clamped = clampOrigin(Offset(-50f, 2600f), size)
        assertEquals(Offset(0f, 2400f), clamped)
        val radius = maxDistanceToCorners(Offset(300f, 500f), size)
        val expectedMax = hypot(1080f - 300f, 2400f - 500f)
        assertEquals(expectedMax, radius, 0.01f)
    }

    @Test
    fun `handles rapid mid reveal interruption cleanly`() {
        rule.mainClock.autoAdvance = false
        var currentSettings by mutableStateOf(PixFoldSettings(themeMode = ThemeMode.Dark))
        val controller = ThemeTransitionController()
        var latestSnapshot: ThemeTransitionStateSnapshot? = null
        var latestDisplayed = currentSettings
        var snapMotionActive = false

        rule.setContent {
            ThemeTransitionHost(
                targetSettings = currentSettings,
                controller = controller,
                onStateChange = { latestSnapshot = it },
            ) { displayed ->
                latestDisplayed = displayed
                PixFoldTheme(settings = displayed) {
                    snapMotionActive = LocalSnapThemeMotion.current
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Text(displayed.themeMode.name)
                    }
                }
            }
        }
        stepFrameAndDraw()

        // 开始 Dark -> Light 揭示并停在约 150ms 处打断切回 Dark
        controller.recordOrigin(Offset(100f, 100f))
        currentSettings = currentSettings.copy(themeMode = ThemeMode.Light)
        rule.waitForIdle()
        assertTrue(advanceUntil { latestSnapshot?.phase == ThemeTransitionPhase.Revealing })
        repeat(9) { stepFrameAndDraw() }
        assertNotEquals(ThemeTransitionPhase.Idle, latestSnapshot?.phase)

        controller.recordOrigin(Offset(200f, 100f))
        currentSettings = currentSettings.copy(themeMode = ThemeMode.Dark)
        advanceThroughTransitionToIdle { latestSnapshot?.phase }
        assertEquals(ThemeMode.Dark, latestDisplayed.themeMode)
        assertFalse(snapMotionActive, "最终回到 Idle 后必须释放 LocalSnapThemeMotion")
    }

    @Test
    fun `falls back to graphics layer snapshot when window pixel copy is disabled`() {
        rule.mainClock.autoAdvance = false
        var currentSettings by mutableStateOf(
            PixFoldSettings(
                themeMode = ThemeMode.Dark,
                accent = AccentChoice.Default,
                pureBlack = false,
            ),
        )
        val controller = ThemeTransitionController()
        val snapshots = mutableListOf<ThemeTransitionStateSnapshot>()
        var latestDisplayed = currentSettings

        rule.setContent {
            ThemeTransitionHost(
                targetSettings = currentSettings,
                controller = controller,
                preferWindowPixelCopy = false,
                onStateChange = { snapshots += it },
            ) { displayed ->
                latestDisplayed = displayed
                PixFoldTheme(settings = displayed) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {},
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Text(displayed.themeMode.name)
                    }
                }
            }
        }
        stepFrameAndDraw()

        controller.recordOrigin(Offset(100f, 100f))
        currentSettings = currentSettings.copy(themeMode = ThemeMode.Light)
        rule.waitForIdle()
        assertTrue(
            advanceUntil { snapshots.lastOrNull()?.phase == ThemeTransitionPhase.Revealing },
            "关闭 PixelCopy 时应平滑回退 GraphicsLayer 并进入 Revealing; snapshots=$snapshots",
        )
        val revealingSnapshot = snapshots.last { it.phase == ThemeTransitionPhase.Revealing }
        assertFalse(revealingSnapshot.usedWindowPixelCopy, "回退路径不应标记 usedWindowPixelCopy")
        assertTrue(revealingSnapshot.hasFrozenBitmap, "GraphicsLayer 回退路径也必须光栅化为冻结位图")
        assertEquals(1, revealingSnapshot.captureCount)

        repeat(5) { stepFrameAndDraw() }
        val midBitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val insideColor = Color(midBitmap.getPixel(100, 100))
        val outsideColor = Color(midBitmap.getPixel(midBitmap.width - 20, midBitmap.height - 20))
        assertTrue(
            insideColor.luminance() > 0.5f,
            "回退路径圆形揭示波圆内像素应为浅色新主题; luminance=${insideColor.luminance()} ($insideColor)",
        )
        assertTrue(
            outsideColor.luminance() < 0.25f,
            "回退路径圆形揭示波圆外远角像素应保持深色旧快照; luminance=${outsideColor.luminance()} ($outsideColor)",
        )

        assertTrue(advanceUntil { snapshots.lastOrNull()?.phase == ThemeTransitionPhase.Idle })
        stepFrameAndDraw()
        assertFalse(snapshots.last().hasFrozenBitmap, "回到 Idle 后必须释放回退冻结位图")
        assertEquals(ThemeMode.Light, latestDisplayed.themeMode)
    }
}
