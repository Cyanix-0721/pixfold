package com.cyanix.pixfold.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode

private const val PREVIEW_WIDTH_DP = 412
private const val PREVIEW_HEIGHT_DP = 915
private const val PREVIEW_LOCALE_ZH = "zh-rCN"

@Composable
private fun PixFoldPreviewHost(
    scene: DebugScene,
    themeMode: ThemeMode = ThemeMode.Dark,
    pureBlack: Boolean = false,
    fontScale: Float = 1.0f,
) {
    DebugShowcaseScreen(
        request = DebugShowcaseRequest(
            scene = scene,
            settings = PixFoldSettings(
                themeMode = themeMode,
                accent = AccentChoice.Default,
                colorStyle = ColorStyle.Expressive,
                colorSpec = ColorSpecVersion.V2025,
                pureBlack = pureBlack,
            ),
            fontScale = fontScale,
        ),
        onExitShowcase = {},
    )
}

@Preview(
    name = "Home Dark",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun HomeDarkPreview() {
    PixFoldPreviewHost(scene = DebugScene.HOME, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Home Pure Black",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun HomePureBlackPreview() {
    PixFoldPreviewHost(scene = DebugScene.HOME, themeMode = ThemeMode.Dark, pureBlack = true)
}

@Preview(
    name = "Home Light",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun HomeLightPreview() {
    PixFoldPreviewHost(scene = DebugScene.HOME, themeMode = ThemeMode.Light)
}

@Preview(
    name = "Settings Dark",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun SettingsDarkPreview() {
    PixFoldPreviewHost(scene = DebugScene.SETTINGS, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Settings Pure Black",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun SettingsPureBlackPreview() {
    PixFoldPreviewHost(scene = DebugScene.SETTINGS, themeMode = ThemeMode.Dark, pureBlack = true)
}

@Preview(
    name = "Workspace Dialog",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun WorkspaceDialogPreview() {
    PixFoldPreviewHost(scene = DebugScene.WORKSPACE_DIALOG, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Chips Single Level",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun ChipsSingleLevelPreview() {
    PixFoldPreviewHost(scene = DebugScene.CHIPS, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Chips Two Level",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun ChipsTwoLevelPreview() {
    PixFoldPreviewHost(scene = DebugScene.CHIPS_TWO_LEVEL, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Sort And Preview",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun SortAndPreviewPagePreview() {
    PixFoldPreviewHost(scene = DebugScene.SORT, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Library",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun LibraryPagePreview() {
    PixFoldPreviewHost(scene = DebugScene.LIBRARY, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Metadata",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun MetadataPagePreview() {
    PixFoldPreviewHost(scene = DebugScene.METADATA, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Pack Preview",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun PackPreviewPagePreview() {
    PixFoldPreviewHost(scene = DebugScene.PACK_PREVIEW, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Pack Progress 1.3x",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun PackProgressDialogPreview() {
    PixFoldPreviewHost(
        scene = DebugScene.PACK_PROGRESS,
        themeMode = ThemeMode.Dark,
        pureBlack = true,
        fontScale = 1.3f,
    )
}

@Preview(
    name = "Rename Plan",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun RenamePlanPagePreview() {
    PixFoldPreviewHost(scene = DebugScene.RENAME_PLAN, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Execution Report",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun ExecutionReportPagePreview() {
    PixFoldPreviewHost(scene = DebugScene.EXECUTION_REPORT, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Undo Dialog",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun UndoScopeDialogPreview() {
    PixFoldPreviewHost(scene = DebugScene.UNDO_DIALOG, themeMode = ThemeMode.Dark)
}

@Preview(
    name = "Image Preview",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun ImagePreviewPagePreview() {
    PixFoldPreviewHost(scene = DebugScene.IMAGE_PREVIEW, themeMode = ThemeMode.Dark)
}
