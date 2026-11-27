package com.cyanix.pixfold.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.cyanix.pixfold.BuildConfig
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.AppLanguage
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.components.LANDSCAPE_MAX_COLUMNS
import com.cyanix.pixfold.ui.components.PORTRAIT_MAX_COLUMNS
import com.cyanix.pixfold.ui.components.PixFoldConfirmDialog
import com.cyanix.pixfold.ui.components.PixFoldSegmentedItem
import com.cyanix.pixfold.ui.components.PixFoldTopBar
import com.cyanix.pixfold.ui.components.adaptiveColumnCount
import com.cyanix.pixfold.ui.components.columnOptions
import com.cyanix.pixfold.ui.components.orientationWidth
import com.cyanix.pixfold.ui.theme.AccentPresets
import com.cyanix.pixfold.ui.theme.ColorSchemes
import com.cyanix.pixfold.ui.theme.LocalIsPureBlack
import com.cyanix.pixfold.ui.theme.LocalThemeSeedArgb
import com.cyanix.pixfold.ui.theme.LocalThemeTransitionController
import com.cyanix.pixfold.ui.theme.LocalThemeTransitionSystemDark
import com.cyanix.pixfold.ui.theme.dangerOutlineBorder
import com.cyanix.pixfold.ui.theme.dangerOutlineButtonColors
import com.cyanix.pixfold.ui.theme.isAffectedByColorSpec
import com.cyanix.pixfold.ui.theme.pixFoldSurfaceBorder
import com.cyanix.pixfold.ui.theme.resolveSeedArgb

/**
 * M3 禁用态内容的不透明度(Material Design 规范值 38%)。
 * 用于「色彩标准」在该风格下无效时的置灰。
 */
private const val DISABLED_CONTENT_ALPHA = 0.38f
private val SETTINGS_ROW_PADDING = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
internal val CHOICE_MENU_MIN_WIDTH = 112.dp
internal val CHOICE_MENU_MAX_WIDTH = 280.dp

// ---- 测试 tag(集中定义,便于测试与实现同步) ----
const val TAG_SETTINGS_PAGE = "settings-page"
const val TAG_SETTINGS_BACK = "settings-back"

/** 主题三态:相连切换组的三项。 */
fun themeOptionTag(mode: ThemeMode) = "settings-theme-${mode.name.lowercase()}"
const val TAG_SETTINGS_MONET = "settings-monet"
const val TAG_SETTINGS_ACCENT = "settings-accent"

/** 强调色行上的实时色块预览。 */
const val TAG_SETTINGS_ACCENT_SWATCH = "settings-accent-swatch"

/** 强调色选择弹窗中某一项的实时色块预览。 */
fun accentSwatchTag(choice: AccentChoice) = "settings-accent-swatch-${choice.name.lowercase()}"
const val TAG_SETTINGS_COLOR_STYLE = "settings-color-style"
const val TAG_SETTINGS_COLOR_SPEC = "settings-color-spec"
const val TAG_SETTINGS_PURE_BLACK = "settings-pure-black"
const val TAG_SETTINGS_LANGUAGE = "settings-language"

/** 网格默认列数:竖屏 / 横屏两行(用户 2026-09-26 第二轮裁决「设置里竖屏横屏分出 2 个选项」)。 */
const val TAG_SETTINGS_COLUMNS_PORTRAIT = "settings-columns-portrait"
const val TAG_SETTINGS_COLUMNS_LANDSCAPE = "settings-columns-landscape"
const val TAG_SETTINGS_PREDICTIVE_BACK = "settings-predictive-back"
const val TAG_SETTINGS_VERSION = "settings-version"
const val TAG_SETTINGS_REPO = "settings-repo"
const val TAG_SETTINGS_RELEASE = "settings-release"
const val TAG_SETTINGS_AUTHOR = "settings-author"
const val TAG_SETTINGS_LICENSES = "settings-licenses"
const val TAG_SETTINGS_RESET = "settings-reset"
const val TAG_SETTINGS_RESET_DIALOG = "settings-reset-dialog"
const val TAG_SETTINGS_RESET_CONFIRM = "settings-reset-confirm"

/** 通用选择对话框(选项行 i 用 [choiceOptionTag])。 */
const val TAG_CHOICE_DIALOG = "settings-choice-dialog"
fun choiceOptionTag(key: String) = "settings-choice-$key"

/** 关于页链接(仓库 / 发布页)。 */
private const val URL_REPO = "https://github.com/Cyanix-0721/pixfold"
private const val URL_RELEASES = "https://github.com/Cyanix-0721/pixfold/releases"
private const val AUTHOR = "Cyanix-0721"

/**
 * 设置页（Material 3 Expressive 分组卡片布局）。
 *
 * **布局**:整页一个可滚动 [Column]（设置项数量有限且都需组合，
 * 不需要 LazyColumn 的惰性 —— 也让"改一项后立刻断言"的测试更直接）。
 *
 * 所有文案走**字符串资源**(中/英),不含硬编码文案 —— 见设置规格 §9。
 *
 * @param settings 当前设置(由上层持有,本页不自行持久化)。
 * @param onChange 设置变更回调;上层负责持久化。
 */
@Composable
fun SettingsScreen(
    settings: PixFoldSettings,
    onChange: (PixFoldSettings) -> Unit,
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    val context = LocalContext.current
    val openFailedMessage = stringResource(R.string.settings_open_failed)
    // `stringResource` 不能在 `semantics {}` 里调用(非 @Composable lambda),先取出。
    val backCd = stringResource(R.string.common_back)
    val isDark = when (settings.themeMode) {
        ThemeMode.System -> LocalThemeTransitionSystemDark.current ?: isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val providedSeed = LocalThemeSeedArgb.current
    val seedArgb = remember(providedSeed, settings.monetEnabled, settings.accent, isDark, context) {
        providedSeed ?: resolveSeedArgb(context, settings, isDark)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_SETTINGS_PAGE),
    ) {
        // 顶部统一 M3E 顶栏（外层 Column 已消费 contentInsets，此处置零避免双重 inset）。
        PixFoldTopBar(
            title = stringResource(R.string.settings_title),
            onBack = onBack,
            backTag = TAG_SETTINGS_BACK,
            backCd = backCd,
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ================= 外观 =================
            SectionHeader(stringResource(R.string.settings_section_appearance), first = true)

            ThemeModeToggleGroup(
                current = settings.themeMode,
                onSelect = { onChange(settings.copy(themeMode = it)) },
            )

            SegmentedGroup {
                SwitchRow(
                    label = stringResource(R.string.settings_monet),
                    description = stringResource(R.string.settings_monet_desc),
                    iconRes = R.drawable.ic_auto_awesome,
                    iconTone = SettingIconTone.Appearance,
                    checked = settings.monetEnabled,
                    tag = TAG_SETTINGS_MONET,
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 5),
                    recordThemeTransitionOrigin = true,
                    // 走 withMonet 而不是 copy(accent=...) —— 由模型统一维护
                    // "开 Monet 即强调色跟随系统"的不变式(见 PixFoldSettings)。
                    onCheckedChange = { onChange(settings.withMonet(it)) },
                )

                ChoiceRow(
                    label = stringResource(R.string.settings_accent),
                    current = stringResource(accentLabel(settings.accent)),
                    iconRes = R.drawable.ic_palette,
                    iconTone = SettingIconTone.Appearance,
                    tag = TAG_SETTINGS_ACCENT,
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 5),
                    options = AccentChoice.entries.map { it to stringResource(accentLabel(it)) },
                    recordThemeTransitionOrigin = true,
                    onSelect = { onChange(settings.withAccent(it)) },
                    trailingPreview = {
                        AccentSwatch(
                            choice = settings.accent,
                            modifier = Modifier.testTag(TAG_SETTINGS_ACCENT_SWATCH),
                        )
                    },
                    optionLeading = { choice ->
                        AccentSwatch(
                            choice = choice,
                            modifier = Modifier.testTag(accentSwatchTag(choice)),
                        )
                    },
                )

                ChoiceRow(
                    label = stringResource(R.string.settings_color_style),
                    current = stringResource(colorStyleLabel(settings.colorStyle)),
                    iconRes = R.drawable.ic_tune,
                    iconTone = SettingIconTone.Appearance,
                    tag = TAG_SETTINGS_COLOR_STYLE,
                    shapes = ListItemDefaults.segmentedShapes(index = 2, count = 5),
                    options = ColorStyle.entries.map { it to stringResource(colorStyleLabel(it)) },
                    recordThemeTransitionOrigin = true,
                    onSelect = { onChange(settings.copy(colorStyle = it)) },
                    optionLeading = { style ->
                        PaletteDotsPreview(
                            seedArgb = seedArgb,
                            dark = isDark,
                            style = style,
                            spec = settings.colorSpec,
                        )
                    },
                )

                ChoiceRow(
                    label = stringResource(R.string.settings_color_spec),
                    current = stringResource(colorSpecLabel(settings.colorSpec)),
                    iconRes = R.drawable.ic_layers,
                    iconTone = SettingIconTone.Appearance,
                    tag = TAG_SETTINGS_COLOR_SPEC,
                    shapes = ListItemDefaults.segmentedShapes(index = 3, count = 5),
                    options = ColorSpecVersion.entries.map { it to stringResource(colorSpecLabel(it)) },
                    recordThemeTransitionOrigin = true,
                    onSelect = { onChange(settings.copy(colorSpec = it)) },
                    optionLeading = { spec ->
                        PaletteDotsPreview(
                            seedArgb = seedArgb,
                            dark = isDark,
                            style = settings.colorStyle,
                            spec = spec,
                        )
                    },
                    // 如实反映能力边界(2026-09-21 用户裁决「置灰」):
                    // 算法库里 2025 规范只重写 4 个变体,其余 5 个风格下两套规范产出
                    // 逐字节相同的结果 —— 置灰而非隐藏:值仍可见,用户能看出"设过,只是现在不生效"。
                    enabled = settings.colorStyle.isAffectedByColorSpec(),
                )

                SwitchRow(
                    label = stringResource(R.string.settings_pure_black),
                    description = stringResource(R.string.settings_pure_black_desc),
                    iconRes = R.drawable.ic_invert_colors,
                    iconTone = SettingIconTone.Appearance,
                    checked = settings.pureBlack,
                    tag = TAG_SETTINGS_PURE_BLACK,
                    shapes = ListItemDefaults.segmentedShapes(index = 4, count = 5),
                    recordThemeTransitionOrigin = true,
                    onCheckedChange = { onChange(settings.copy(pureBlack = it)) },
                )
            }

            // ================= 语言 =================
            SectionHeader(stringResource(R.string.settings_section_language))
            SegmentedGroup {
                ChoiceRow(
                    label = stringResource(R.string.settings_language),
                    current = stringResource(languageLabel(settings.language)),
                    iconRes = R.drawable.ic_translate,
                    iconTone = SettingIconTone.Language,
                    tag = TAG_SETTINGS_LANGUAGE,
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    options = AppLanguage.entries.map { it to stringResource(languageLabel(it)) },
                    onSelect = { onChange(settings.copy(language = it)) },
                )
            }

            // ================= 行为 =================
            SectionHeader(stringResource(R.string.settings_section_behavior))
            SegmentedGroup {
                DefaultColumnsRow(
                    label = stringResource(R.string.settings_columns_portrait),
                    iconRes = R.drawable.ic_stay_current_portrait,
                    iconTone = SettingIconTone.Behavior,
                    tag = TAG_SETTINGS_COLUMNS_PORTRAIT,
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                    current = settings.portraitDefaultColumns,
                    portrait = true,
                    onSelect = { onChange(settings.copy(portraitDefaultColumns = it)) },
                )
                DefaultColumnsRow(
                    label = stringResource(R.string.settings_columns_landscape),
                    iconRes = R.drawable.ic_stay_current_landscape,
                    iconTone = SettingIconTone.Behavior,
                    tag = TAG_SETTINGS_COLUMNS_LANDSCAPE,
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                    current = settings.landscapeDefaultColumns,
                    portrait = false,
                    onSelect = { onChange(settings.copy(landscapeDefaultColumns = it)) },
                )
            }
            Note(stringResource(R.string.settings_columns_note))

            SegmentedGroup {
                SwitchRow(
                    label = stringResource(R.string.settings_predictive_back),
                    description = stringResource(R.string.settings_predictive_back_desc),
                    iconRes = R.drawable.ic_dynamic_feed,
                    iconTone = SettingIconTone.Behavior,
                    checked = settings.predictiveBackAnimation,
                    tag = TAG_SETTINGS_PREDICTIVE_BACK,
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    onCheckedChange = { onChange(settings.copy(predictiveBackAnimation = it)) },
                )
            }

            // ================= 关于 =================
            SectionHeader(stringResource(R.string.settings_section_about))
            SegmentedGroup {
                InfoRow(
                    label = stringResource(R.string.settings_version),
                    value = "$versionName ($versionCode) · ${stringResource(R.string.settings_dev_suffix)}",
                    iconRes = R.drawable.ic_info,
                    iconTone = SettingIconTone.About,
                    tag = TAG_SETTINGS_VERSION,
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 5),
                )
                ActionRow(
                    label = stringResource(R.string.settings_repo),
                    description = stringResource(R.string.settings_repo_desc),
                    iconRes = R.drawable.ic_code,
                    iconTone = SettingIconTone.About,
                    tag = TAG_SETTINGS_REPO,
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 5),
                    onClick = { openUrl(context, URL_REPO, openFailedMessage) },
                )
                ActionRow(
                    label = stringResource(R.string.settings_release),
                    description = stringResource(R.string.settings_release_desc),
                    iconRes = R.drawable.ic_new_releases,
                    iconTone = SettingIconTone.About,
                    tag = TAG_SETTINGS_RELEASE,
                    shapes = ListItemDefaults.segmentedShapes(index = 2, count = 5),
                    onClick = { openUrl(context, URL_RELEASES, openFailedMessage) },
                )
                InfoRow(
                    label = stringResource(R.string.settings_author),
                    value = AUTHOR,
                    iconRes = R.drawable.ic_person,
                    iconTone = SettingIconTone.About,
                    tag = TAG_SETTINGS_AUTHOR,
                    shapes = ListItemDefaults.segmentedShapes(index = 3, count = 5),
                )
                ActionRow(
                    label = stringResource(R.string.settings_licenses),
                    description = null,
                    iconRes = R.drawable.ic_description,
                    iconTone = SettingIconTone.About,
                    tag = TAG_SETTINGS_LICENSES,
                    shapes = ListItemDefaults.segmentedShapes(index = 4, count = 5),
                    onClick = onOpenLicenses,
                )
            }

            // ---- 恢复默认设置:底部,带二次确认 ----
            var showResetDialog by remember { mutableStateOf(false) }
            val resetLabel = stringResource(R.string.settings_reset)
            OutlinedButton(
                onClick = { showResetDialog = true },
                shapes = ButtonDefaults.shapes(),
                colors = dangerOutlineButtonColors(),
                border = dangerOutlineBorder(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .heightIn(min = 48.dp)
                    .testTag(TAG_SETTINGS_RESET)
                    .semantics { contentDescription = resetLabel },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_restart_alt),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = resetLabel,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
            Spacer(Modifier.height(24.dp))

            if (showResetDialog) {
                PixFoldConfirmDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = stringResource(R.string.settings_reset_title),
                    body = stringResource(R.string.settings_reset_body),
                    confirmText = stringResource(R.string.settings_reset_confirm),
                    onConfirm = {
                        showResetDialog = false
                        // 全部重置(含语言与网格默认列数)—— 用户裁决(2026-09-20)。
                        onChange(PixFoldSettings())
                    },
                    dismissText = stringResource(R.string.settings_reset_cancel),
                    isDangerous = true,
                    confirmTag = TAG_SETTINGS_RESET_CONFIRM,
                    modifier = Modifier.testTag(TAG_SETTINGS_RESET_DIALOG),
                )
            }
        }
    }
}

// ---------------- 组件 ----------------

private enum class SettingIconTone {
    Appearance,
    Language,
    Behavior,
    About,
}

@Composable
private fun SettingLeadingIcon(
    @DrawableRes iconRes: Int,
    tone: SettingIconTone,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val (baseContainer, baseContent) = when (tone) {
        SettingIconTone.Appearance -> scheme.primaryContainer to scheme.onPrimaryContainer
        SettingIconTone.Language -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SettingIconTone.Behavior -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SettingIconTone.About -> {
            val container = if (LocalIsPureBlack.current) {
                scheme.surfaceVariant
            } else {
                scheme.surfaceContainerHigh
            }
            container to scheme.onSurfaceVariant
        }
    }
    val containerColor = if (enabled) baseContainer else baseContainer.copy(alpha = DISABLED_CONTENT_ALPHA)
    val contentColor = if (enabled) baseContent else baseContent.copy(alpha = DISABLED_CONTENT_ALPHA)

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String, first: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 12.dp,
            top = if (first) 10.dp else 24.dp,
            bottom = 10.dp,
        ),
    )
}

@Composable
private fun SegmentedGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        content = content,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
    )
}

/**
 * 主题三态相连切换组（M3E Connected Toggle Group）。
 *
 * 使用 `Row(spacedBy(ConnectedSpaceBetween))` + 等权 `weight(1f)` + `IntrinsicSize.Min`，
 * 避免 `ButtonGroup` 在 361dp × 1.3x 字号下折叠进溢出菜单；英文 "Follow system" 在 1.3x
 * 下允许两行居中换行，三颗按钮始终等高且满足 `>= 48.dp` 触控高度。
 */
@Composable
private fun ThemeModeToggleGroup(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val modes = ThemeMode.entries
    val groupCd = stringResource(R.string.settings_theme_mode)
    val transitionController = LocalThemeTransitionController.current
    val colors = FilledTonalToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(bottom = 14.dp)
            .semantics { contentDescription = groupCd },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, mode ->
            val checked = current == mode
            val label = stringResource(themeModeLabel(mode))
            var buttonCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            FilledTonalToggleButton(
                checked = checked,
                onCheckedChange = {
                    transitionController?.recordOriginFrom(buttonCoords)
                    onSelect(mode)
                },
                shapes = shapes,
                colors = colors,
                border = if (!checked) pixFoldSurfaceBorder() else null,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 72.dp)
                    .onGloballyPositioned { buttonCoords = it }
                    .testTag(themeOptionTag(mode)),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(themeModeIcon(mode)),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = label,
                        style = if (checked) {
                            MaterialTheme.typography.labelLargeEmphasized
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 24dp `Cookie9Sided` 有机形状强调色实时色块。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccentSwatch(
    choice: AccentChoice,
    modifier: Modifier = Modifier,
) {
    val swatchColor = if (choice == AccentChoice.System) {
        MaterialTheme.colorScheme.primary
    } else {
        AccentPresets.seedOf(choice)
    }
    val shape = MaterialShapes.Cookie9Sided.toShape()
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(shape)
            .background(swatchColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    @DrawableRes iconRes: Int,
    iconTone: SettingIconTone,
    checked: Boolean,
    tag: String,
    shapes: ListItemShapes,
    onCheckedChange: (Boolean) -> Unit,
    recordThemeTransitionOrigin: Boolean = false,
) {
    val transitionController = LocalThemeTransitionController.current
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    PixFoldSegmentedItem(
        onClick = {
            if (recordThemeTransitionOrigin) {
                transitionController?.recordOriginFrom(rowCoords)
            }
            onCheckedChange(!checked)
        },
        shapes = shapes,
        contentPadding = SETTINGS_ROW_PADDING,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowCoords = it }
            .testTag(tag)
            .semantics {
                contentDescription = label
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
        leadingContent = {
            SettingLeadingIcon(iconRes = iconRes, tone = iconTone)
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            // onCheckedChange = null 交由整行统一承载点击与无障碍语义，避免 TalkBack 报重复节点。
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 多选一:点击从行尾弹出右侧 M3E 悬浮下拉菜单选择。
 *
 * `enabled = false` 时**整行置灰且不可点开** —— 用于如实反映"该设置在当前组合下无效"。
 */
@Composable
private fun <T> ChoiceRow(
    label: String,
    current: String,
    @DrawableRes iconRes: Int,
    iconTone: SettingIconTone,
    tag: String,
    shapes: ListItemShapes,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    recordThemeTransitionOrigin: Boolean = false,
    trailingPreview: (@Composable () -> Unit)? = null,
    optionLeading: (@Composable (T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val transitionController = LocalThemeTransitionController.current
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
    }
    val valueColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowCoords = it },
    ) {
        PixFoldSegmentedItem(
            onClick = { open = true },
            shapes = shapes,
            enabled = enabled,
            contentPadding = SETTINGS_ROW_PADDING,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .semantics { contentDescription = label },
            leadingContent = {
                SettingLeadingIcon(
                    iconRes = iconRes,
                    tone = iconTone,
                    enabled = enabled,
                )
            },
            supportingContent = {
                Text(
                    text = current,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                )
            },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (trailingPreview != null) {
                        trailingPreview()
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = valueColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = labelColor,
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(0.dp),
        ) {
            val isPureBlack = LocalIsPureBlack.current
            DropdownMenu(
                expanded = open && enabled,
                onDismissRequest = { open = false },
                offset = DpOffset(x = 0.dp, y = 4.dp),
                shape = MaterialTheme.shapes.largeIncreased,
                containerColor = choiceMenuContainerColor(),
                tonalElevation = if (isPureBlack) 0.dp else 6.dp,
                shadowElevation = 8.dp,
                border = pixFoldSurfaceBorder(),
                modifier = Modifier
                    .widthIn(min = CHOICE_MENU_MIN_WIDTH, max = CHOICE_MENU_MAX_WIDTH)
                    .testTag(TAG_CHOICE_DIALOG),
            ) {
                SettingsChoiceMenuOptions(
                    options = options,
                    current = current,
                    tag = tag,
                    onSelect = { selectedValue ->
                        open = false
                        if (recordThemeTransitionOrigin) {
                            transitionController?.recordOriginFrom(rowCoords)
                        }
                        onSelect(selectedValue)
                    },
                    optionLeading = optionLeading,
                )
            }
        }
    }
}

@Composable
internal fun choiceMenuContainerColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
internal fun PaletteDotsPreview(
    seedArgb: Int,
    dark: Boolean,
    style: ColorStyle,
    spec: ColorSpecVersion,
) {
    val dots = remember(seedArgb, dark, style, spec) {
        val scheme = ColorSchemes.fromSeed(
            seedArgb = seedArgb,
            dark = dark,
            style = style,
            spec = spec,
        )
        listOf(scheme.primary, scheme.secondary, scheme.tertiary)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dots.forEach { dotColor ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
internal fun <T> SettingsChoiceMenuOptions(
    options: List<Pair<T, String>>,
    current: String,
    tag: String,
    onSelect: (T) -> Unit,
    optionLeading: (@Composable (T) -> Unit)? = null,
) {
    options.forEachIndexed { index, (value, text) ->
        val isSelected = text == current
        Surface(
            onClick = { onSelect(value) },
            shape = MaterialTheme.shapes.large,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primaryFixed
            } else {
                Color.Transparent
            },
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryFixed
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .testTag(choiceOptionTag("$tag-$index"))
                .semantics {
                    selected = isSelected
                    contentDescription = text
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (optionLeading != null) {
                    optionLeading(value)
                }
                Text(
                    text = text,
                    style = if (isSelected) {
                        MaterialTheme.typography.titleSmallEmphasized
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                )
            }
        }
    }
}

/**
 * 某一屏幕方向的**默认网格列数**设置项。
 */
@Composable
private fun DefaultColumnsRow(
    label: String,
    @DrawableRes iconRes: Int,
    iconTone: SettingIconTone,
    tag: String,
    shapes: ListItemShapes,
    current: Int?,
    portrait: Boolean,
    onSelect: (Int?) -> Unit,
) {
    val adaptive = adaptiveColumnCount(orientationWidth(LocalConfiguration.current, portrait))
    val adaptiveText = stringResource(R.string.settings_columns_adaptive_value, adaptive)
    val maxColumns = if (portrait) PORTRAIT_MAX_COLUMNS else LANDSCAPE_MAX_COLUMNS
    val numbers = columnOptions(maxColumns, current ?: adaptive)
    val numberTexts = numbers.map { stringResource(R.string.a_cols_cd, it) }

    ChoiceRow(
        label = label,
        current = if (current == null) adaptiveText else stringResource(R.string.a_cols_cd, current),
        iconRes = iconRes,
        iconTone = iconTone,
        tag = tag,
        shapes = shapes,
        options = listOf<Pair<Int?, String>>(null to adaptiveText) +
            numbers.mapIndexed { index, n -> n to numberTexts[index] },
        onSelect = onSelect,
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    @DrawableRes iconRes: Int,
    iconTone: SettingIconTone,
    tag: String,
    shapes: ListItemShapes,
) {
    PixFoldSegmentedItem(
        shapes = shapes,
        contentPadding = SETTINGS_ROW_PADDING,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { contentDescription = label },
        leadingContent = {
            SettingLeadingIcon(iconRes = iconRes, tone = iconTone)
        },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    description: String?,
    @DrawableRes iconRes: Int,
    iconTone: SettingIconTone,
    tag: String,
    shapes: ListItemShapes,
    onClick: () -> Unit,
) {
    PixFoldSegmentedItem(
        onClick = onClick,
        shapes = shapes,
        contentPadding = SETTINGS_ROW_PADDING,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { contentDescription = label },
        leadingContent = {
            SettingLeadingIcon(iconRes = iconRes, tone = iconTone)
        },
        supportingContent = description?.let { desc ->
            {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 打开外部链接。
 *
 * **必须处理"无可用应用"**:部分设备/精简系统没有浏览器,
 * `startActivity` 会抛 [android.content.ActivityNotFoundException] ——
 * 不处理就是崩溃。这里捕获后提示并降级(设置页不宜弹出阻塞式错误)。
 */
private fun openUrl(context: android.content.Context, url: String, failedMessage: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
    }
}

// ---------------- 枚举 -> 文案 / 图标(集中在一处,避免散落) ----------------

private fun themeModeIcon(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.drawable.ic_contrast
    ThemeMode.Light -> R.drawable.ic_light_mode
    ThemeMode.Dark -> R.drawable.ic_dark_mode
}

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.string.settings_theme_system
    ThemeMode.Light -> R.string.settings_theme_light
    ThemeMode.Dark -> R.string.settings_theme_dark
}

private fun accentLabel(choice: AccentChoice): Int = when (choice) {
    AccentChoice.System -> R.string.settings_theme_system
    AccentChoice.Default -> R.string.accent_default
    AccentChoice.Blue -> R.string.accent_blue
    AccentChoice.Green -> R.string.accent_green
    AccentChoice.Purple -> R.string.accent_purple
    AccentChoice.Orange -> R.string.accent_orange
    AccentChoice.Pink -> R.string.accent_pink
}

internal fun colorStyleLabel(style: ColorStyle): Int = when (style) {
    ColorStyle.TonalSpot -> R.string.style_tonal_spot
    ColorStyle.Vibrant -> R.string.style_vibrant
    ColorStyle.Expressive -> R.string.style_expressive
    ColorStyle.Neutral -> R.string.style_neutral
    ColorStyle.Monochrome -> R.string.style_monochrome
    ColorStyle.Fidelity -> R.string.style_fidelity
    ColorStyle.Content -> R.string.style_content
    ColorStyle.Rainbow -> R.string.style_rainbow
    ColorStyle.FruitSalad -> R.string.style_fruit_salad
}

private fun colorSpecLabel(spec: ColorSpecVersion): Int = when (spec) {
    ColorSpecVersion.V2021 -> R.string.spec_2021
    ColorSpecVersion.V2025 -> R.string.spec_2025
}

private fun languageLabel(language: AppLanguage): Int = when (language) {
    AppLanguage.System -> R.string.settings_theme_system
    AppLanguage.Chinese -> R.string.language_chinese
    AppLanguage.English -> R.string.language_english
}
