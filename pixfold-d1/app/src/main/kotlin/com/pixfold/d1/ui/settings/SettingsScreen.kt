package com.pixfold.d1.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.BuildConfig
import com.pixfold.d1.R
import com.pixfold.d1.domain.settings.AccentChoice
import com.pixfold.d1.domain.settings.AppLanguage
import com.pixfold.d1.domain.settings.ColorSpecVersion
import com.pixfold.d1.domain.settings.ColorStyle
import com.pixfold.d1.domain.settings.PixFoldSettings
import com.pixfold.d1.domain.settings.StorageAccess
import com.pixfold.d1.domain.settings.ThemeMode
import com.pixfold.d1.ui.theme.isAffectedByColorSpec

/**
 * M3 禁用态内容的不透明度(Material Design 规范值 38%)。
 * 用于「色彩标准」在该风格下无效时的置灰。
 */
private const val DISABLED_CONTENT_ALPHA = 0.38f

// ---- 测试 tag(集中定义,便于测试与实现同步) ----
const val TAG_SETTINGS_PAGE = "settings-page"
const val TAG_SETTINGS_BACK = "settings-back"
/** 主题三态:分段选择的三项。 */
fun themeOptionTag(mode: ThemeMode) = "settings-theme-${mode.name.lowercase()}"
const val TAG_SETTINGS_MONET = "settings-monet"
const val TAG_SETTINGS_ACCENT = "settings-accent"
const val TAG_SETTINGS_COLOR_STYLE = "settings-color-style"
const val TAG_SETTINGS_COLOR_SPEC = "settings-color-spec"
const val TAG_SETTINGS_PURE_BLACK = "settings-pure-black"
const val TAG_SETTINGS_LANGUAGE = "settings-language"
const val TAG_SETTINGS_STORAGE = "settings-storage"
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
 * 设置页。
 *
 * **布局**:整页一个可滚动 [Column](设置项数量有限且都需组合,
 * 不需要 LazyColumn 的惰性 —— 也让"改一项后立刻断言"的测试更直接)。
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_SETTINGS_PAGE),
    ) {
        // ---- 顶部:返回 + 标题 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .testTag(TAG_SETTINGS_BACK)
                    .semantics { contentDescription = "返回" },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // ================= 外观 =================
            SectionHeader(stringResource(R.string.settings_section_appearance))

            // 主题三态:用三个可选行(而不是分段控件),与其它行视觉一致、也更好点。
            Text(
                text = stringResource(R.string.settings_theme_mode),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
            ThemeMode.entries.forEach { mode ->
                RadioRow(
                    label = stringResource(themeModeLabel(mode)),
                    selected = settings.themeMode == mode,
                    tag = themeOptionTag(mode),
                    onClick = { onChange(settings.copy(themeMode = mode)) },
                )
            }

            SwitchRow(
                label = stringResource(R.string.settings_monet),
                description = stringResource(R.string.settings_monet_desc),
                checked = settings.monetEnabled,
                tag = TAG_SETTINGS_MONET,
                // 走 withMonet 而不是 copy(accent=...) —— 由模型统一维护
                // "开 Monet 即强调色跟随系统"的不变式(见 PixFoldSettings)。
                onCheckedChange = { onChange(settings.withMonet(it)) },
            )

            ChoiceRow(
                label = stringResource(R.string.settings_accent),
                current = stringResource(accentLabel(settings.accent)),
                tag = TAG_SETTINGS_ACCENT,
                options = AccentChoice.entries.map { it to stringResource(accentLabel(it)) },
                onSelect = { onChange(settings.withAccent(it)) },
            )

            ChoiceRow(
                label = stringResource(R.string.settings_color_style),
                current = stringResource(colorStyleLabel(settings.colorStyle)),
                tag = TAG_SETTINGS_COLOR_STYLE,
                options = ColorStyle.entries.map { it to stringResource(colorStyleLabel(it)) },
                onSelect = { onChange(settings.copy(colorStyle = it)) },
            )

            ChoiceRow(
                label = stringResource(R.string.settings_color_spec),
                current = stringResource(colorSpecLabel(settings.colorSpec)),
                tag = TAG_SETTINGS_COLOR_SPEC,
                options = ColorSpecVersion.entries.map { it to stringResource(colorSpecLabel(it)) },
                onSelect = { onChange(settings.copy(colorSpec = it)) },
                // 如实反映能力边界(2026-09-21 用户裁决「置灰」):
                // 算法库里 2025 规范只重写 4 个变体,其余 5 个风格下两套规范产出
                // 逐字节相同的结果 —— 此时该行给了选择却毫无反馈,属"假开关"。
                // 置灰而非隐藏:值仍可见,用户能看出"设过,只是现在不生效"。
                // 依据见 ColorSchemes.COLOR_SPEC_SENSITIVE_STYLES 的实测注释。
                enabled = settings.colorStyle.isAffectedByColorSpec(),
            )

            SwitchRow(
                label = stringResource(R.string.settings_pure_black),
                description = stringResource(R.string.settings_pure_black_desc),
                checked = settings.pureBlack,
                tag = TAG_SETTINGS_PURE_BLACK,
                onCheckedChange = { onChange(settings.copy(pureBlack = it)) },
            )

            Divider()

            // ================= 语言 =================
            SectionHeader(stringResource(R.string.settings_section_language))
            ChoiceRow(
                label = stringResource(R.string.settings_language),
                current = stringResource(languageLabel(settings.language)),
                tag = TAG_SETTINGS_LANGUAGE,
                options = AppLanguage.entries.map { it to stringResource(languageLabel(it)) },
                onSelect = { onChange(settings.copy(language = it)) },
            )
            // 如实告知:阶段 1 只本地化了设置页自身(见规格 §9.3)。
            Note(stringResource(R.string.settings_language_note))

            Divider()

            // ================= 行为 =================
            SectionHeader(stringResource(R.string.settings_section_behavior))
            ChoiceRow(
                label = stringResource(R.string.settings_storage),
                current = stringResource(storageLabel(settings.storageAccess)),
                tag = TAG_SETTINGS_STORAGE,
                options = StorageAccess.entries.map { it to stringResource(storageLabel(it)) },
                onSelect = { onChange(settings.copy(storageAccess = it)) },
            )
            Note(
                when (settings.storageAccess) {
                    StorageAccess.Saf -> stringResource(R.string.settings_storage_saf_desc)
                    StorageAccess.AllFiles -> stringResource(R.string.settings_storage_all_desc)
                },
            )
            // D1 不连真实文件系统,此项只记偏好 —— 如实标注,不假装已生效。
            Note(stringResource(R.string.settings_storage_note))

            SwitchRow(
                label = stringResource(R.string.settings_predictive_back),
                description = stringResource(R.string.settings_predictive_back_desc),
                checked = settings.predictiveBackAnimation,
                tag = TAG_SETTINGS_PREDICTIVE_BACK,
                onCheckedChange = { onChange(settings.copy(predictiveBackAnimation = it)) },
            )

            Divider()

            // ================= 关于 =================
            SectionHeader(stringResource(R.string.settings_section_about))
            InfoRow(
                label = stringResource(R.string.settings_version),
                value = "$versionName ($versionCode) · ${stringResource(R.string.settings_dev_suffix)}",
                tag = TAG_SETTINGS_VERSION,
            )
            ActionRow(
                label = stringResource(R.string.settings_repo),
                description = stringResource(R.string.settings_repo_desc),
                tag = TAG_SETTINGS_REPO,
                onClick = { openUrl(context, URL_REPO, openFailedMessage) },
            )
            ActionRow(
                label = stringResource(R.string.settings_release),
                description = stringResource(R.string.settings_release_desc),
                tag = TAG_SETTINGS_RELEASE,
                onClick = { openUrl(context, URL_RELEASES, openFailedMessage) },
            )
            InfoRow(
                label = stringResource(R.string.settings_author),
                value = AUTHOR,
                tag = TAG_SETTINGS_AUTHOR,
            )
            ActionRow(
                label = stringResource(R.string.settings_licenses),
                description = null,
                tag = TAG_SETTINGS_LICENSES,
                onClick = onOpenLicenses,
            )

            Divider()

            // ---- 恢复默认设置:底部,带二次确认 ----
            var showResetDialog by remember { mutableStateOf(false) }
            // stringResource 必须在 @Composable 作用域求值:
            // `semantics {}` 的 lambda 不是 @Composable,不能在其中调用它。
            val resetLabel = stringResource(R.string.settings_reset)
            TextButton(
                onClick = { showResetDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_SETTINGS_RESET)
                    .semantics { contentDescription = resetLabel },
            ) {
                Text(
                    text = resetLabel,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.size(24.dp))

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text(stringResource(R.string.settings_reset_title)) },
                    text = { Text(stringResource(R.string.settings_reset_body)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showResetDialog = false
                                // 全部重置(含语言与文件访问方式)—— 用户裁决(2026-09-20)。
                                onChange(PixFoldSettings())
                            },
                            modifier = Modifier.testTag(TAG_SETTINGS_RESET_CONFIRM),
                        ) { Text(stringResource(R.string.settings_reset_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) {
                            Text(stringResource(R.string.settings_reset_cancel))
                        }
                    },
                    modifier = Modifier.testTag(TAG_SETTINGS_RESET_DIALOG),
                )
            }
        }
    }
}

// ---------------- 组件 ----------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .testTag(tag)
                .semantics { contentDescription = label },
        )
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = label }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * 多选一:点击弹对话框选择。
 *
 * 用对话框而非下拉菜单:选项较多(如 9 种色彩风格)、且对话框在窄屏上不会
 * 被挤压;也让"选择真的生效"更容易被测试断言。
 *
 * `enabled = false` 时**整行置灰且不可点开** —— 用于如实反映"该设置在当前
 * 组合下无效"(如「色彩标准」遇到不受其影响的色彩风格,见 [COLOR_SPEC_SENSITIVE_STYLES])。
 * 置灰而**不隐藏**:值仍可见,用户能看出"我设过 2025,只是现在不生效",
 * 而不是以为设置丢了。
 */
@Composable
private fun <T> ChoiceRow(
    label: String,
    current: String,
    tag: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }

    // M3 的禁用态:内容用 on 色 38% 不透明度(Material Design 规范值)。
    // 用语义角色 + 固定 alpha,不硬编码具体色值。
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { open = true }
            .testTag(tag)
            .semantics { contentDescription = label }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
        )
        Text(
            text = current,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }

    if (open && enabled) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    options.forEachIndexed { index, (value, text) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    open = false
                                    onSelect(value)
                                }
                                .testTag(choiceOptionTag("$tag-$index"))
                                .semantics { contentDescription = text }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = text == current,
                                onClick = {
                                    open = false
                                    onSelect(value)
                                },
                            )
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.settings_reset_cancel))
                }
            },
            modifier = Modifier.testTag(TAG_CHOICE_DIALOG),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { contentDescription = label }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    description: String?,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = label }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 打开外部链接。
 *
 * **必须处理"无可用应用"**:部分设备/精简系统没有浏览器,
 * `startActivity` 会抛 [android.content.ActivityNotFoundException] ——
 * 不处理就是崩溃。这里捕获后静默降级(设置页不宜弹出阻塞式错误)。
 */
private fun openUrl(context: android.content.Context, url: String, failedMessage: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        // 无浏览器等情况下如实提示,而不是"点了没反应"。
        Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
    }
}

// ---------------- 枚举 -> 文案(集中在一处,避免散落) ----------------

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

private fun colorStyleLabel(style: ColorStyle): Int = when (style) {
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

private fun storageLabel(access: StorageAccess): Int = when (access) {
    StorageAccess.Saf -> R.string.storage_saf
    StorageAccess.AllFiles -> R.string.storage_all
}
