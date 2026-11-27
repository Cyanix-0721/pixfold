package com.cyanix.pixfold.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.settings.CHOICE_MENU_MAX_WIDTH
import com.cyanix.pixfold.ui.settings.CHOICE_MENU_MIN_WIDTH
import com.cyanix.pixfold.ui.settings.PaletteDotsPreview
import com.cyanix.pixfold.ui.settings.SettingsChoiceMenuOptions
import com.cyanix.pixfold.ui.settings.TAG_SETTINGS_COLOR_STYLE
import com.cyanix.pixfold.ui.settings.choiceMenuContainerColor
import com.cyanix.pixfold.ui.settings.colorStyleLabel
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.theme.pixFoldSurfaceBorder
import com.cyanix.pixfold.ui.theme.resolveSeedArgb

private const val PREVIEW_WIDTH_DP = 412
private const val PREVIEW_HEIGHT_DP = 915
private const val PREVIEW_LOCALE_ZH = "zh-rCN"

@Composable
private fun PixFoldPreviewHost(
    scene: DebugScene,
    themeMode: ThemeMode = ThemeMode.Dark,
    pureBlack: Boolean = false,
    colorStyle: ColorStyle = ColorStyle.TonalSpot,
    fontScale: Float = 1.0f,
) {
    DebugShowcaseScreen(
        request = DebugShowcaseRequest(
            scene = scene,
            settings = PixFoldSettings(
                themeMode = themeMode,
                accent = AccentChoice.Default,
                colorStyle = colorStyle,
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

@Composable
private fun SettingsChoiceMenuPreviewHost(pureBlack: Boolean) {
    val previewSettings = PixFoldSettings(
        themeMode = ThemeMode.Dark,
        accent = AccentChoice.Default,
        colorStyle = ColorStyle.TonalSpot,
        colorSpec = ColorSpecVersion.V2025,
        pureBlack = pureBlack,
    )
    Box(modifier = Modifier.fillMaxSize()) {
        PixFoldPreviewHost(scene = DebugScene.SETTINGS, themeMode = ThemeMode.Dark, pureBlack = pureBlack)
        PixFoldTheme(settings = previewSettings) {
            val context = LocalContext.current
            val seedArgb = resolveSeedArgb(context, previewSettings, dark = true)
            val options = ColorStyle.entries.map { it to stringResource(colorStyleLabel(it)) }
            val currentLabel = stringResource(colorStyleLabel(previewSettings.colorStyle))
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 52.dp, end = 16.dp)
                    .widthIn(min = CHOICE_MENU_MIN_WIDTH, max = CHOICE_MENU_MAX_WIDTH),
                shape = MaterialTheme.shapes.largeIncreased,
                color = choiceMenuContainerColor(),
                tonalElevation = if (pureBlack) 0.dp else 6.dp,
                shadowElevation = 8.dp,
                border = pixFoldSurfaceBorder(),
            ) {
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .padding(vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SettingsChoiceMenuOptions(
                        options = options,
                        current = currentLabel,
                        tag = TAG_SETTINGS_COLOR_STYLE,
                        onSelect = {},
                        optionLeading = { style ->
                            PaletteDotsPreview(
                                seedArgb = seedArgb,
                                dark = true,
                                style = style,
                                spec = previewSettings.colorSpec,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Preview(
    name = "Settings Choice Menu Dark",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun SettingsChoiceMenuDarkPreview() {
    SettingsChoiceMenuPreviewHost(pureBlack = false)
}

@Preview(
    name = "Settings Choice Menu Pure Black",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun SettingsChoiceMenuPureBlackPreview() {
    SettingsChoiceMenuPreviewHost(pureBlack = true)
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
    name = "Workspace Dialog Pure Black",
    showBackground = true,
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    locale = PREVIEW_LOCALE_ZH,
)
@Composable
fun WorkspaceDialogPureBlackPreview() {
    PixFoldPreviewHost(scene = DebugScene.WORKSPACE_DIALOG, themeMode = ThemeMode.Dark, pureBlack = true)
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
