package com.cyanix.pixfold.ui.theme

import android.graphics.Rect as AndroidRect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val THEME_CIRCULAR_REVEAL_MILLIS = 380
private const val THEME_CROSSFADE_MILLIS = 280
private const val POINTER_ORIGIN_MAX_AGE_MS = 1000L
private const val SETTLE_DRAW_PASSES = 4
private const val PIXEL_COPY_MAX_WAIT_FRAMES = 8
private val CIRCULAR_REVEAL_EASING = LinearOutSlowInEasing

internal enum class ThemeTransitionPhase {
    Idle,
    Capturing,
    Settling,
    Revealing,
}

internal enum class ThemeTransitionMode {
    CircularReveal,
    Crossfade,
}

internal data class ThemeTransitionStateSnapshot(
    val phase: ThemeTransitionPhase,
    val mode: ThemeTransitionMode,
    val origin: Offset?,
    val captureCount: Int,
    val hasFrozenBitmap: Boolean = false,
    val usedWindowPixelCopy: Boolean = false,
)

/**
 * 为 `true` 时，主题层将 `MaterialTheme.motionScheme` 临时切为全 `snap()`，
 * 让底层 `TopAppBar` / `SegmentedListItem` / `FilledTonalToggleButton` 等在幕后同一帧对齐目标色，
 * 避免整屏揭示波扫过时各组件分节奏变色。
 */
internal val LocalSnapThemeMotion = compositionLocalOf { false }

/**
 * 为 `true` 时表示正处于幕后对齐帧（`Settling`），状态栏/导航栏图标极性延迟至揭示开始时同步切换。
 */
internal val LocalThemeTransitionSettling = compositionLocalOf { false }

/**
 * 在 [ThemeTransitionHost] 控制下当前实际展示的系统深浅极性，确保 `Capturing` 阶段录制的仍是旧主题画面。
 */
internal val LocalThemeTransitionSystemDark = compositionLocalOf<Boolean?> { null }

/**
 * 用于从下拉菜单或无障碍点击等无法直接由根容器指针捕获的场景显式提供揭示波圆心。
 */
val LocalThemeTransitionController = compositionLocalOf<ThemeTransitionController?> { null }

/**
 * 协调主题切换的点击圆心来源（优先取最近 1 秒内的真实触摸坐标，回退到显式控件中心）。
 */
@Stable
class ThemeTransitionController internal constructor(
    private val clockMillis: () -> Long = { SystemClock.uptimeMillis() },
) {
    constructor() : this(clockMillis = { SystemClock.uptimeMillis() })

    internal var rootCoordinates: LayoutCoordinates? = null
    private var pointerOrigin: Offset? = null
    private var pointerTimestampMs: Long = 0L
    private var explicitOrigin: Offset? = null

    internal fun recordPointerOrigin(localPosition: Offset) {
        pointerOrigin = localPosition
        pointerTimestampMs = clockMillis()
    }

    /** 以 [childCoordinates] 的中心在 [ThemeTransitionHost] 局部坐标系中的位置作为揭示圆心。 */
    fun recordOriginFrom(childCoordinates: LayoutCoordinates?) {
        val child = childCoordinates?.takeIf { it.isAttached } ?: return
        val center = Offset(child.size.width / 2f, child.size.height / 2f)
        val root = rootCoordinates?.takeIf { it.isAttached }
        explicitOrigin = if (root != null) {
            root.localPositionOf(child, center)
        } else {
            child.positionInRoot() + center
        }
    }

    /** 直接指定 [ThemeTransitionHost] 局部坐标系中的揭示圆心。 */
    fun recordOrigin(localPosition: Offset) {
        explicitOrigin = localPosition
    }

    internal fun clearOrigins() {
        pointerOrigin = null
        explicitOrigin = null
    }

    internal fun consumeOrigin(): Offset? {
        val age = clockMillis() - pointerTimestampMs
        val candidate = if (pointerOrigin != null && age in 0L..POINTER_ORIGIN_MAX_AGE_MS) {
            pointerOrigin
        } else {
            explicitOrigin
        }
        clearOrigins()
        return candidate
    }
}

private class TransitionSignals {
    var drawDone: CompletableDeferred<Unit>? = null
    var capturedInLastPass: Boolean = false
    var layerCaptureRequested: Boolean = false
    var lastCaptureUsedPixelCopy: Boolean = false
    var captureCount: Int = 0
}

internal fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * 判断从 [previous] 切到 [next] 是否修改了任意主题视觉字段（深浅模式、纯黑开关、莫奈开关、强调色、色彩风格或色彩标准）。
 */
internal fun isThemeVisualChange(
    previous: PixFoldSettings,
    next: PixFoldSettings,
): Boolean = previous.themeMode != next.themeMode ||
    previous.pureBlack != next.pureBlack ||
    previous.monetEnabled != next.monetEnabled ||
    previous.accent != next.accent ||
    previous.colorStyle != next.colorStyle ||
    previous.colorSpec != next.colorSpec

/**
 * 结合解析后的系统深浅极性，判断从 [previous] 切到 [next] 是否会改变实际视觉主题，
 * 避免仅改非视觉配置或无效字段（如浅色模式下的纯黑开关、同极性下 System 与 Dark 互切）时误触发整屏抓帧。
 */
internal fun isEffectiveThemeVisualChange(
    previous: PixFoldSettings,
    next: PixFoldSettings,
    previousSystemDark: Boolean,
    nextSystemDark: Boolean,
): Boolean {
    val prevDark = resolveDarkTheme(previous.themeMode, previousSystemDark)
    val nextDark = resolveDarkTheme(next.themeMode, nextSystemDark)
    val prevPureBlack = previous.pureBlack && prevDark
    val nextPureBlack = next.pureBlack && nextDark
    return prevDark != nextDark ||
        prevPureBlack != nextPureBlack ||
        previous.monetEnabled != next.monetEnabled ||
        previous.accent != next.accent ||
        previous.colorStyle != next.colorStyle ||
        previous.colorSpec != next.colorSpec
}

internal fun clampOrigin(origin: Offset, size: Size): Offset = Offset(
    x = origin.x.coerceIn(0f, size.width),
    y = origin.y.coerceIn(0f, size.height),
)

internal fun maxDistanceToCorners(origin: Offset, size: Size): Float {
    val dTopLeft = hypot(origin.x, origin.y)
    val dTopRight = hypot(size.width - origin.x, origin.y)
    val dBottomLeft = hypot(origin.x, size.height - origin.y)
    val dBottomRight = hypot(size.width - origin.x, size.height - origin.y)
    return maxOf(dTopLeft, dTopRight, dBottomLeft, dBottomRight)
}

private suspend fun captureWindowPixelCopy(
    window: Window,
    view: View,
    rootCoordinates: LayoutCoordinates?,
    mainHandler: Handler,
): ImageBitmap? {
    if (!view.isAttachedToWindow || view.isInEditMode) return null
    val decorView = window.decorView
    if (decorView.width <= 0 || decorView.height <= 0) return null
    val bounds = rootCoordinates?.takeIf { it.isAttached }?.boundsInWindow() ?: return null
    val left = floor(bounds.left).toInt().coerceIn(0, decorView.width)
    val top = floor(bounds.top).toInt().coerceIn(0, decorView.height)
    val right = ceil(bounds.right).toInt().coerceIn(left, decorView.width)
    val bottom = ceil(bounds.bottom).toInt().coerceIn(top, decorView.height)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null

    val destBitmap = runCatching { createBitmap(width, height) }.getOrNull() ?: return null
    val resultDeferred = CompletableDeferred<Int>()
    val requested = runCatching {
        PixelCopy.request(
            window,
            AndroidRect(left, top, right, bottom),
            destBitmap,
            { copyResult -> resultDeferred.complete(copyResult) },
            mainHandler,
        )
    }.isSuccess
    if (!requested) {
        destBitmap.recycle()
        return null
    }

    val copyResult = coroutineScope {
        val timeoutJob = launch {
            repeat(PIXEL_COPY_MAX_WAIT_FRAMES) { withFrameNanos { } }
            resultDeferred.complete(PixelCopy.ERROR_TIMEOUT)
        }
        try {
            resultDeferred.await()
        } finally {
            timeoutJob.cancel()
        }
    }
    // 不可在超时/失败时手动 recycle：RenderThread 仍可能异步写入该缓冲区，交由 GC 回收更安全。
    if (copyResult != PixelCopy.SUCCESS) return null
    destBitmap.prepareToDraw()
    return destBitmap.asImageBitmap()
}

/**
 * 包裹应用主题根节点：优先通过异步 `PixelCopy` 直接拷贝当前窗口 GPU 表面作为旧主题冻结快照，
 * 并在窗口不可用时自动回退至 [androidx.compose.ui.graphics.layer.GraphicsLayer] 光栅化；
 * 随后在旧画面遮罩下以全 `snap()` 完成底层新主题对齐，再由点击位置发起整屏圆形扩散揭示
 * （无点击圆心时自动回退为整屏交叉淡入淡出）。
 */
@Composable
fun ThemeTransitionHost(
    targetSettings: PixFoldSettings,
    modifier: Modifier = Modifier,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable (PixFoldSettings) -> Unit,
) {
    val controller = remember { ThemeTransitionController() }
    ThemeTransitionHost(
        targetSettings = targetSettings,
        modifier = modifier,
        systemDark = systemDark,
        controller = controller,
        preferWindowPixelCopy = true,
        onStateChange = null,
        content = content,
    )
}

@Composable
internal fun ThemeTransitionHost(
    targetSettings: PixFoldSettings,
    modifier: Modifier = Modifier,
    systemDark: Boolean = isSystemInDarkTheme(),
    controller: ThemeTransitionController = remember { ThemeTransitionController() },
    preferWindowPixelCopy: Boolean = true,
    onStateChange: ((ThemeTransitionStateSnapshot) -> Unit)? = null,
    content: @Composable (PixFoldSettings) -> Unit,
) {
    var displayedSettings by remember { mutableStateOf(targetSettings) }
    var displayedSystemDark by remember { mutableStateOf(systemDark) }
    var phase by remember { mutableStateOf(ThemeTransitionPhase.Idle) }
    var activeMode by remember { mutableStateOf(ThemeTransitionMode.Crossfade) }
    var activeOrigin by remember { mutableStateOf<Offset?>(null) }
    var renderTick by remember { mutableIntStateOf(0) }
    var frozenBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var snapMotion by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val snapshotLayer = rememberGraphicsLayer()
    val revealPath = remember { Path() }
    val signals = remember { TransitionSignals() }
    val view = LocalView.current
    val hostWindow = remember(view) {
        if (view.isInEditMode) null else view.context.findActivity()?.window
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun emit() {
        onStateChange?.invoke(
            ThemeTransitionStateSnapshot(
                phase = phase,
                mode = activeMode,
                origin = activeOrigin,
                captureCount = signals.captureCount,
                hasFrozenBitmap = frozenBitmap != null,
                usedWindowPixelCopy = signals.lastCaptureUsedPixelCopy,
            ),
        )
    }

    suspend fun awaitNextDraw() {
        val deferred = CompletableDeferred<Unit>()
        signals.drawDone = deferred
        renderTick++
        coroutineScope {
            val frameFallback = launch {
                // 首帧 withFrameNanos 运行在 CALLBACK_ANIMATION（drawWithContent 之前）；
                // 次帧 withFrameNanos 运行在首帧 CALLBACK_TRAVERSAL（drawWithContent）之后。
                // 真机上首帧 drawWithContent 会先完成 deferred 并取消本协程；
                // 不执行 View.draw 的 Robolectric LEGACY 测试则按 Compose 帧时钟推进不卡死。
                withFrameNanos { }
                withFrameNanos { }
                deferred.complete(Unit)
            }
            deferred.await()
            frameFallback.cancel()
        }
        signals.drawDone = null
    }

    LaunchedEffect(targetSettings, systemDark) {
        if (targetSettings == displayedSettings && systemDark == displayedSystemDark) {
            snapMotion = false
            phase = ThemeTransitionPhase.Idle
            activeOrigin = null
            frozenBitmap = null
            signals.lastCaptureUsedPixelCopy = false
            emit()
            return@LaunchedEffect
        }
        if (!isEffectiveThemeVisualChange(displayedSettings, targetSettings, displayedSystemDark, systemDark)) {
            controller.clearOrigins()
            displayedSettings = targetSettings
            displayedSystemDark = systemDark
            snapMotion = false
            phase = ThemeTransitionPhase.Idle
            activeOrigin = null
            frozenBitmap = null
            signals.lastCaptureUsedPixelCopy = false
            emit()
            return@LaunchedEffect
        }

        val origin = controller.consumeOrigin()
        activeOrigin = origin
        activeMode = if (origin != null) {
            ThemeTransitionMode.CircularReveal
        } else {
            ThemeTransitionMode.Crossfade
        }

        try {
            frozenBitmap = null
            signals.capturedInLastPass = false
            signals.layerCaptureRequested = false
            signals.lastCaptureUsedPixelCopy = false
            phase = ThemeTransitionPhase.Capturing
            emit()

            var capturedBitmap: ImageBitmap? = if (preferWindowPixelCopy && hostWindow != null) {
                captureWindowPixelCopy(
                    window = hostWindow,
                    view = view,
                    rootCoordinates = controller.rootCoordinates,
                    mainHandler = mainHandler,
                )?.also {
                    signals.captureCount += 1
                    signals.lastCaptureUsedPixelCopy = true
                }
            } else {
                null
            }

            if (capturedBitmap == null) {
                signals.layerCaptureRequested = true
                awaitNextDraw()
                signals.layerCaptureRequested = false
                if (signals.capturedInLastPass) {
                    // 必须立即光栅化为不可变位图：RenderNode DisplayList 持有子节点的实时引用，
                    // 一旦 Settling 重组子组件，继续 drawLayer(snapshotLayer) 会直接画出新主题内容导致过渡失效。
                    capturedBitmap = runCatching { snapshotLayer.toImageBitmap() }.getOrNull()
                }
            }

            if (capturedBitmap == null || capturedBitmap.width <= 0 || capturedBitmap.height <= 0) {
                displayedSettings = targetSettings
                displayedSystemDark = systemDark
                snapMotion = false
                phase = ThemeTransitionPhase.Idle
                activeOrigin = null
                frozenBitmap = null
                emit()
                return@LaunchedEffect
            }
            frozenBitmap = capturedBitmap

            progress.snapTo(0f)
            snapMotion = true
            displayedSettings = targetSettings
            displayedSystemDark = systemDark
            phase = ThemeTransitionPhase.Settling
            emit()
            // 幕后保持冻结旧图 100% 遮罩，等待 4 轮真实绘制让 Surface / TopAppBar / SegmentedListItem 全部对齐新色。
            repeat(SETTLE_DRAW_PASSES) { awaitNextDraw() }

            phase = ThemeTransitionPhase.Revealing
            emit()
            if (activeMode == ThemeTransitionMode.CircularReveal) {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = THEME_CIRCULAR_REVEAL_MILLIS,
                        easing = CIRCULAR_REVEAL_EASING,
                    ),
                )
            } else {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = THEME_CROSSFADE_MILLIS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }

            // 先释放旧图快照并让揭示末帧无阻塞呈递，再切回 expressive 动效，避免结尾掉帧。
            frozenBitmap = null
            withFrameNanos { }
            snapMotion = false
            activeOrigin = null
            phase = ThemeTransitionPhase.Idle
            emit()
        } finally {
            signals.drawDone = null
            signals.layerCaptureRequested = false
        }
    }

    CompositionLocalProvider(
        LocalSnapThemeMotion provides snapMotion,
        LocalThemeTransitionSettling provides (phase == ThemeTransitionPhase.Settling),
        LocalThemeTransitionSystemDark provides displayedSystemDark,
        LocalThemeTransitionController provides controller,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { controller.rootCoordinates = it }
                .pointerInput(controller) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) {
                                event.changes.firstOrNull()?.let { change ->
                                    controller.recordPointerOrigin(change.position)
                                }
                            }
                        }
                    }
                }
                .drawWithContent {
                    // 读取 renderTick 确保每次 awaitNextDraw() 都触发真实绘制回调。
                    if (renderTick < 0) return@drawWithContent
                    when (phase) {
                        ThemeTransitionPhase.Idle -> drawContent()
                        ThemeTransitionPhase.Capturing -> {
                            if (signals.layerCaptureRequested) {
                                if (size.width >= 1f && size.height >= 1f) {
                                    snapshotLayer.alpha = 1f
                                    snapshotLayer.record {
                                        this@drawWithContent.drawContent()
                                    }
                                    drawLayer(snapshotLayer)
                                    signals.capturedInLastPass = true
                                    signals.captureCount += 1
                                } else {
                                    drawContent()
                                }
                                signals.drawDone?.complete(Unit)
                            } else {
                                drawContent()
                            }
                        }
                        ThemeTransitionPhase.Settling -> {
                            drawContent()
                            val bmp = frozenBitmap
                            if (bmp != null && size.width >= 1f && size.height >= 1f) {
                                drawImage(
                                    image = bmp,
                                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                )
                            }
                            signals.drawDone?.complete(Unit)
                        }
                        ThemeTransitionPhase.Revealing -> {
                            drawContent()
                            val bmp = frozenBitmap
                            val p = progress.value
                            if (bmp != null && p < 1f && size.width >= 1f && size.height >= 1f) {
                                val dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                                val origin = activeOrigin
                                if (activeMode == ThemeTransitionMode.CircularReveal && origin != null) {
                                    val clamped = clampOrigin(origin, size)
                                    val radius = maxDistanceToCorners(clamped, size) * p
                                    if (radius <= 0.5f) {
                                        drawImage(
                                            image = bmp,
                                            dstSize = dstSize,
                                        )
                                    } else {
                                        revealPath.rewind()
                                        revealPath.addOval(
                                            Rect(
                                                left = clamped.x - radius,
                                                top = clamped.y - radius,
                                                right = clamped.x + radius,
                                                bottom = clamped.y + radius,
                                            ),
                                        )
                                        clipPath(revealPath, clipOp = ClipOp.Difference) {
                                            drawImage(
                                                image = bmp,
                                                dstSize = dstSize,
                                            )
                                        }
                                    }
                                } else {
                                    drawImage(
                                        image = bmp,
                                        dstSize = dstSize,
                                        alpha = (1f - p).coerceIn(0f, 1f),
                                    )
                                }
                            }
                        }
                    }
                },
        ) {
            content(displayedSettings)
        }
    }
}
