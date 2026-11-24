package com.cyanix.pixfold.debug

import android.content.Intent
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode

internal const val EXTRA_DEBUG_SCENE = "pixfold_scene"
internal const val EXTRA_DEBUG_THEME = "pixfold_theme"
internal const val EXTRA_DEBUG_COLOR_SPEC = "pixfold_color_spec"
internal const val EXTRA_DEBUG_ACCENT = "pixfold_accent"
internal const val EXTRA_DEBUG_FONT_SCALE = "pixfold_font_scale"
internal const val EXTRA_DEBUG_EXIT = "pixfold_exit_showcase"

private const val MIN_FONT_SCALE = 0.85f
private const val MAX_FONT_SCALE = 2.0f

internal enum class DebugScene(
    val id: String,
    val title: String,
    val group: String,
) {
    CATALOG("catalog", "场景目录", "入口"),
    HOME("home", "首页（工作流大卡与工作区条）", "基础与设置"),
    SETTINGS("settings", "设置页（M3E 分段与色板）", "基础与设置"),
    WORKSPACE_DIALOG("workspace_dialog", "工作区列表弹窗（高斯模糊）", "基础与设置"),
    CHIPS("chips", "单层芯片瀑布流（3行收口/展开）", "芯片组件"),
    CHIPS_TWO_LEVEL("chips_two_level", "二级系列芯片与顶点浮层", "芯片组件"),
    SORT("sort", "工作流A · 排序与预览", "工作流 A/B"),
    LIBRARY("library", "工作流B · 书架页", "工作流 A/B"),
    METADATA("metadata", "工作流B · 元数据编辑页", "工作流 A/B"),
    PACK_PREVIEW("pack_preview", "工作流B · 打包预览页", "工作流 A/B"),
    PACK_PROGRESS("pack_progress", "双波浪打包进度弹窗", "弹窗与进度"),
    RENAME_PLAN("rename_plan", "工作流A · 改名计划页", "计划与报告"),
    EXECUTION_REPORT("execution_report", "工作流A · 执行报告页", "计划与报告"),
    UNDO_DIALOG("undo_dialog", "撤销范围弹窗（SoftBurst + 模糊）", "弹窗与进度"),
    IMAGE_PREVIEW("image_preview", "全屏大图与活力浮动工具栏", "预览"),
}

internal data class DebugShowcaseRequest(
    val scene: DebugScene,
    val settings: PixFoldSettings,
    val fontScale: Float,
)

internal fun parseDebugShowcaseParams(
    sceneRaw: String?,
    themeRaw: String? = null,
    colorSpecRaw: String? = null,
    accentRaw: String? = null,
    fontScaleRaw: Float? = null,
): DebugShowcaseRequest? {
    val sceneId = sceneRaw?.trim()?.lowercase().orEmpty()
    if (sceneId.isEmpty()) return null
    val scene = DebugScene.entries.firstOrNull { it.id == sceneId } ?: return null

    val (themeMode, pureBlack) = when (themeRaw?.trim()?.lowercase()) {
        "light" -> ThemeMode.Light to false
        "pure_black" -> ThemeMode.Dark to true
        "system" -> ThemeMode.System to false
        else -> ThemeMode.Dark to false
    }
    val colorSpec = when (colorSpecRaw?.trim()) {
        "2021" -> ColorSpecVersion.V2021
        else -> ColorSpecVersion.V2025
    }
    val accentName = accentRaw?.trim().orEmpty()
    val accent = AccentChoice.entries.firstOrNull {
        it.name.equals(accentName, ignoreCase = true)
    } ?: AccentChoice.Default

    val rawScale = fontScaleRaw ?: 1.0f
    val clampedScale = if (rawScale.isFinite()) {
        rawScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    } else {
        1.0f
    }

    return DebugShowcaseRequest(
        scene = scene,
        settings = PixFoldSettings(
            themeMode = themeMode,
            accent = accent,
            colorStyle = ColorStyle.Expressive,
            colorSpec = colorSpec,
            pureBlack = pureBlack,
        ),
        fontScale = clampedScale,
    )
}

internal fun parseDebugShowcaseRequest(intent: Intent?): DebugShowcaseRequest? {
    if (intent == null) return null
    val sceneRaw = intent.getStringExtra(EXTRA_DEBUG_SCENE)
    val themeRaw = intent.getStringExtra(EXTRA_DEBUG_THEME)
    val colorSpecRaw = intent.getStringExtra(EXTRA_DEBUG_COLOR_SPEC)
    val accentRaw = intent.getStringExtra(EXTRA_DEBUG_ACCENT)
    val fontScaleRaw = intent.getStringExtra(EXTRA_DEBUG_FONT_SCALE)?.toFloatOrNull()
        ?: if (intent.hasExtra(EXTRA_DEBUG_FONT_SCALE)) {
            intent.getFloatExtra(EXTRA_DEBUG_FONT_SCALE, 1.0f)
        } else {
            null
        }
    return parseDebugShowcaseParams(
        sceneRaw = sceneRaw,
        themeRaw = themeRaw,
        colorSpecRaw = colorSpecRaw,
        accentRaw = accentRaw,
        fontScaleRaw = fontScaleRaw,
    )
}

internal fun isExitShowcaseIntent(intent: Intent?): Boolean =
    intent?.getBooleanExtra(EXTRA_DEBUG_EXIT, false) == true

internal fun clearDebugShowcaseExtras(intent: Intent?) {
    if (intent == null) return
    intent.removeExtra(EXTRA_DEBUG_SCENE)
    intent.removeExtra(EXTRA_DEBUG_THEME)
    intent.removeExtra(EXTRA_DEBUG_COLOR_SPEC)
    intent.removeExtra(EXTRA_DEBUG_ACCENT)
    intent.removeExtra(EXTRA_DEBUG_FONT_SCALE)
    intent.removeExtra(EXTRA_DEBUG_EXIT)
}
