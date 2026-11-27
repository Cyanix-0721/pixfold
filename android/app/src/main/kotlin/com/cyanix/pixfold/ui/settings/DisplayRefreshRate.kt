package com.cyanix.pixfold.ui.settings

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.cyanix.pixfold.ui.theme.findActivity
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class DisplayModeSpec(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float,
    val alternativeRefreshRates: List<Float> = emptyList(),
)

internal data class RefreshRateWindowRequest(
    val preferredDisplayModeId: Int,
    val preferredRefreshRate: Float,
    val powerSavingsBalanced: Boolean,
)

private data class RefreshRateCandidate(
    val modeId: Int,
    val rate: Float,
    val isActiveMode: Boolean,
)

private fun sameResolutionModes(
    modes: List<DisplayModeSpec>,
    activeMode: DisplayModeSpec?,
): List<DisplayModeSpec> {
    if (activeMode == null) return modes
    return modes.filter {
        it.physicalWidth == activeMode.physicalWidth &&
            it.physicalHeight == activeMode.physicalHeight
    }.ifEmpty { modes }
}

/**
 * 提取与当前分辨率 [activeMode] 同物理尺寸的显示模式所支持的全部整数刷新率（去重并升序排列）。
 */
internal fun extractSupportedRefreshRates(
    modes: List<DisplayModeSpec>,
    activeMode: DisplayModeSpec?,
): List<Int> = sameResolutionModes(modes, activeMode)
    .asSequence()
    .flatMap { mode -> sequenceOf(mode.refreshRate) + mode.alternativeRefreshRates.asSequence() }
    .filter { it > 0f }
    .map { it.roundToInt() }
    .filter { it > 0 }
    .distinct()
    .sorted()
    .toList()

/**
 * 将用户选择的目标刷新率（`null` 表示跟随系统）解析为窗口参数请求：
 * 同物理分辨率优先匹配主模式，其次匹配 Android ARR 备选刷新率，最后回落到最近邻刷新率。
 */
internal fun resolveRefreshRateRequest(
    targetHz: Int?,
    modes: List<DisplayModeSpec>,
    activeMode: DisplayModeSpec?,
): RefreshRateWindowRequest {
    if (targetHz == null || targetHz <= 0) {
        return RefreshRateWindowRequest(
            preferredDisplayModeId = 0,
            preferredRefreshRate = 0f,
            powerSavingsBalanced = true,
        )
    }

    val resolutionModes = sameResolutionModes(modes, activeMode)
    if (resolutionModes.isEmpty()) {
        return RefreshRateWindowRequest(
            preferredDisplayModeId = 0,
            preferredRefreshRate = targetHz.toFloat(),
            powerSavingsBalanced = false,
        )
    }

    val activeModeId = activeMode?.modeId
    val primaryExact = pickClosestCandidate(
        candidates = resolutionModes
            .filter { it.refreshRate > 0f && it.refreshRate.roundToInt() == targetHz }
            .map { RefreshRateCandidate(it.modeId, it.refreshRate, it.modeId == activeModeId) },
        targetHz = targetHz,
    )
    if (primaryExact != null) {
        return RefreshRateWindowRequest(
            preferredDisplayModeId = primaryExact.modeId,
            preferredRefreshRate = primaryExact.rate,
            powerSavingsBalanced = false,
        )
    }

    val altExact = pickClosestCandidate(
        candidates = resolutionModes.flatMap { mode ->
            mode.alternativeRefreshRates
                .filter { it > 0f && it.roundToInt() == targetHz }
                .map { alt -> RefreshRateCandidate(mode.modeId, alt, mode.modeId == activeModeId) }
        },
        targetHz = targetHz,
    )
    if (altExact != null) {
        return RefreshRateWindowRequest(
            preferredDisplayModeId = altExact.modeId,
            preferredRefreshRate = altExact.rate,
            powerSavingsBalanced = false,
        )
    }

    val closest = pickClosestCandidate(
        candidates = resolutionModes.flatMap { mode ->
            (listOf(mode.refreshRate) + mode.alternativeRefreshRates)
                .filter { it > 0f }
                .map { rate -> RefreshRateCandidate(mode.modeId, rate, mode.modeId == activeModeId) }
        },
        targetHz = targetHz,
    )
    return if (closest != null) {
        RefreshRateWindowRequest(
            preferredDisplayModeId = closest.modeId,
            preferredRefreshRate = closest.rate,
            powerSavingsBalanced = false,
        )
    } else {
        RefreshRateWindowRequest(
            preferredDisplayModeId = 0,
            preferredRefreshRate = targetHz.toFloat(),
            powerSavingsBalanced = false,
        )
    }
}

private fun pickClosestCandidate(
    candidates: List<RefreshRateCandidate>,
    targetHz: Int,
): RefreshRateCandidate? = candidates.minWithOrNull(
    compareBy<RefreshRateCandidate>(
        { abs(it.rate - targetHz) },
        { !it.isActiveMode },
        { it.modeId },
    ),
)

private fun Display.Mode.toSpec(): DisplayModeSpec = DisplayModeSpec(
    modeId = modeId,
    physicalWidth = physicalWidth,
    physicalHeight = physicalHeight,
    refreshRate = refreshRate,
    alternativeRefreshRates = alternativeRefreshRates.toList(),
)

private fun Display.toModeSpecs(): Pair<List<DisplayModeSpec>, DisplayModeSpec?> {
    val active = mode?.toSpec()
    val all = supportedModes.map { it.toSpec() }
    return all to active
}

/**
 * 读取当前显示设备在当前物理分辨率下支持的全部刷新率（Hz，升序），并监听显示模式变化自动刷新。
 */
@Composable
internal fun rememberSupportedRefreshRates(): List<Int> {
    val view = LocalView.current
    val context = LocalContext.current
    val isEditMode = view.isInEditMode
    val displayManager = remember(context, isEditMode) {
        if (isEditMode) null else context.getSystemService(DisplayManager::class.java)
    }
    var revision by remember { mutableIntStateOf(0) }
    val display = if (isEditMode) {
        null
    } else {
        view.display ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    }
    val currentDisplayId = display?.displayId

    DisposableEffect(displayManager, currentDisplayId) {
        if (displayManager == null) return@DisposableEffect onDispose {}
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (currentDisplayId == null || displayId == currentDisplayId) {
                    revision++
                }
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    return remember(view, display, revision) {
        val (modes, active) = display?.toModeSpecs() ?: return@remember emptyList()
        extractSupportedRefreshRates(modes, active)
    }
}

/**
 * 将用户设置的 [preferredRefreshRateHz] 应用到宿主窗口的 `preferredDisplayModeId` /
 * `preferredRefreshRate` 及帧率省电平衡标记；为 `null` 时清除固定请求以跟随系统默认调度。
 */
@Composable
internal fun ApplyPreferredRefreshRate(preferredRefreshRateHz: Int?) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val context = LocalContext.current
    val displayManager = remember(context) { context.getSystemService(DisplayManager::class.java) }
    val activity = remember(view, context) { view.context.findActivity() ?: context.findActivity() }
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val display = view.display ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val (modes, active) = display?.toModeSpecs() ?: (emptyList<DisplayModeSpec>() to null)
        val request = resolveRefreshRateRequest(preferredRefreshRateHz, modes, active)
        val lp = window.attributes
        if (
            lp.preferredDisplayModeId != request.preferredDisplayModeId ||
            lp.preferredRefreshRate != request.preferredRefreshRate ||
            lp.isFrameRatePowerSavingsBalanced != request.powerSavingsBalanced
        ) {
            lp.preferredDisplayModeId = request.preferredDisplayModeId
            lp.preferredRefreshRate = request.preferredRefreshRate
            lp.isFrameRatePowerSavingsBalanced = request.powerSavingsBalanced
            window.attributes = lp
        }
    }
}
