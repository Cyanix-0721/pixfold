package com.cyanix.pixfold.ui.settings

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.BuildConfig
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.AppLanguage
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.StorageAccess
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.components.LANDSCAPE_MAX_COLUMNS
import com.cyanix.pixfold.ui.components.PORTRAIT_MAX_COLUMNS
import com.cyanix.pixfold.ui.components.adaptiveColumnCount
import com.cyanix.pixfold.ui.components.columnOptions
import com.cyanix.pixfold.ui.components.orientationWidth
import com.cyanix.pixfold.ui.theme.isAffectedByColorSpec

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
    // `stringResource` 不能在 `semantics {}` 里调用(非 @Composable lambda),先取出。
    val backCd = stringResource(R.string.common_back)

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
                    .semantics { contentDescription = backCd },
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
            // 语言区**不再**带说明行:原先那句「其他页面的多语言将在后续版本补全」是
            // 设置与主题体系**阶段 1** 的产物(当时只本地化了设置页自身,见规格 §9.3)。
            // 全量 i18n 已于 2026-09-25 落地(`2221ef1` / `0368be5` / `53149ba`),
            // 此后这句话就是假信息 —— 界面在说「多语言还没做」,而实际全应用早已双语。
            // 选项本身已自解释(跟随系统 / 中文 / English),无需任何补充说明。
            // 2026-10-14 真机走查发现,守卫见 `SettingsScreenTest` 的
            // `language section no longer claims other screens are untranslated`。

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

            // 网格默认列数(用户 2026-09-26 第二轮裁决:「设置里竖屏横屏分出 2 个选项,
            // 默认都是自适应值作为默认」+「设置里两个方向各多一项『跟随自适应』(显示算出的数)」)。
            // 两项都含「跟随自适应」档 ⇒ `null`(跟随)与具体数字在界面上**都可见**,不是隐藏状态。
            DefaultColumnsRow(
                label = stringResource(R.string.settings_columns_portrait),
                tag = TAG_SETTINGS_COLUMNS_PORTRAIT,
                current = settings.portraitDefaultColumns,
                portrait = true,
                onSelect = { onChange(settings.copy(portraitDefaultColumns = it)) },
            )
            DefaultColumnsRow(
                label = stringResource(R.string.settings_columns_landscape),
                tag = TAG_SETTINGS_COLUMNS_LANDSCAPE,
                current = settings.landscapeDefaultColumns,
                portrait = false,
                onSelect = { onChange(settings.copy(landscapeDefaultColumns = it)) },
            )
            Note(stringResource(R.string.settings_columns_note))

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

/**
 * 某一屏幕方向的**默认网格列数**设置项
 * (用户 2026-09-26 第二轮裁决:「设置里两个方向各多一项『跟随自适应』(显示算出的数);
 * 选它就回到实时跟随」)。
 *
 * ## 值模型:`Int?`
 *
 * - `null` = **跟随自适应** —— 每次都按当前可用宽重算,设备换了/分屏了也跟着变;
 * - 具体数字 = 固定列数,不随宽度变。
 *
 * 为什么不用枚举把两者合并(如 `DefaultColumns { Adaptive, C2..C9 }`):那样"自适应"
 * 与"固定 N 列"变成同一个枚举的两类成员,而下面的选项列表长度本身随方向变
 * (竖屏到 5、横屏到 9),枚举就得为每个方向各写一份。`null` 这一个哨兵值更小也更准 ——
 * 它天然就是 `Int` 类型的"没有值"。
 *
 * ## 为什么"跟随自适应"要显式列出来
 *
 * 早期设想是不给选项、直接把算出的数当默认。用户裁决改成**显式一项**,且**显示算出的数**
 * (`跟随自适应（4）`)—— 否则用户选了固定值之后就没有回到自适应的入口,
 * 只能靠"重置全部设置"这种重锤。
 *
 * ## 与下拉列表的分工
 *
 * 这里是**默认值**(下次进入排序页时的起点);排序页工具栏的列数按钮是**本次进入的临时覆盖**
 * (用户裁决:「设置 = 默认值;工具栏 = 本页临时覆盖,离开页面即失效」)。这条关系写在
 * 本 Section 下方的 [Note] 里,免得用户以为在排序页改了就持久化了。
 *
 * 复用 [ChoiceRow](而不是新写一个对话框):选项数量最多 9 个(横屏),
 * 与"色彩风格"同一量级,`ChoiceRow` 的既定理由(选项多、窄屏不挤、可断言已生效)同样成立。
 * 唯一要求是"当前值文案"必须与对应选项文案**逐字相同** —— `ChoiceRow` 用
 * `text == current` 判选中,故选中的档位文案一律由同一组字符串资源生成。
 *
 * @param portrait true = 竖屏那行(上限 5、宽度取屏幕短边);false = 横屏那行。
 */
@Composable
private fun DefaultColumnsRow(
    label: String,
    tag: String,
    current: Int?,
    portrait: Boolean,
    onSelect: (Int?) -> Unit,
) {
    // 该方向的自适应值:设置页拿不到网格容器的实测宽,用屏幕宽估算(见 orientationWidthDp)。
    // 与排序页共用同一算式,免得两处各算各的。
    val adaptive = adaptiveColumnCount(orientationWidth(LocalConfiguration.current, portrait))
    val adaptiveText = stringResource(R.string.settings_columns_adaptive_value, adaptive)
    val maxColumns = if (portrait) PORTRAIT_MAX_COLUMNS else LANDSCAPE_MAX_COLUMNS
    // 数字档用 columnOptions 生成:若存量设置里有个超出本方向上上限的数(旧版本写入、
    // 或手改偏好文件),它会被并进列表 ⇒ 当前值不会"看不见"。
    val numbers = columnOptions(maxColumns, current ?: adaptive)
    // 选项文案**先在 lambda 外算好**:`buildList {}` 的接收者类型带 @BuilderInference,
    // 在里面直接调 @Composable 的 stringResource 会推断失败(实测报
    // "Cannot infer type for value parameter" + "@Composable invocations can only happen
    // from the context of a @Composable function")。用 map 预计算与列数按钮里的写法一致。
    val numberTexts = numbers.map { stringResource(R.string.a_cols_cd, it) }

    ChoiceRow(
        label = label,
        current = if (current == null) adaptiveText else stringResource(R.string.a_cols_cd, current),
        tag = tag,
        // 「跟随自适应」排第一,且**显示算出的数** —— 否则「跟随自适应」是句空话,
        // 用户看不出跟随结果是不是自己想要的。
        options = listOf<Pair<Int?, String>>(null to adaptiveText) +
            numbers.mapIndexed { index, n -> n to numberTexts[index] },
        onSelect = onSelect,
    )
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
