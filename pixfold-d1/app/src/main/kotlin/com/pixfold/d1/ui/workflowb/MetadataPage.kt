package com.pixfold.d1.ui.workflowb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R
import com.pixfold.d1.domain.comic.applyWriterCleaning
import com.pixfold.d1.domain.comic.setMetaValue
import com.pixfold.d1.domain.comic.useSuggestion
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.LangChoice
import com.pixfold.d1.domain.model.MetaField
import com.pixfold.d1.domain.model.MetaValue
import com.pixfold.d1.domain.text.TextKey
import com.pixfold.d1.domain.workflowb.WorkflowBState
import com.pixfold.d1.domain.workflowb.batchApplyToLibrary
import com.pixfold.d1.domain.workflowb.selectVolume
import com.pixfold.d1.domain.workflowb.setVolumeIncluded
import com.pixfold.d1.domain.workflowb.updateVolume
import com.pixfold.d1.ui.components.PageActionBar
import com.pixfold.d1.ui.text.textOf
import com.pixfold.d1.ui.text.textOfOrNull

const val TAG_METADATA_PAGE = "metadata-page"
const val TAG_METADATA_LIST = "metadata-list"
const val TAG_VOLUME_SELECTOR = "metadata-volume-selector"
const val TAG_PENDING_ISSUES = "metadata-pending-issues"
const val TAG_BATCH_ROW = "metadata-batch-row"
const val TAG_BATCH_APPLY = "metadata-batch-apply"
const val TAG_BATCH_RESULT = "metadata-batch-result"
const val TAG_BATCH_FIELD = "metadata-batch-field"
const val TAG_BATCH_VALUE = "metadata-batch-value"
const val TAG_BATCH_FORCE = "metadata-batch-force"
const val TAG_WRITER_CLEAN = "metadata-writer-clean"

/** 「下一步 打包计划」按钮(常驻底栏)。 */
const val TAG_METADATA_NEXT = "metadata-next"

/** 元数据字段输入框(逐卷)。 */
fun metaFieldTag(field: MetaField) = "metadata-field-${field.name}"

/** 字段的"恢复建议值"按钮。 */
fun metaResetTag(field: MetaField) = "metadata-reset-${field.name}"

/** 字段的建议值 + 来源文本容器。 */
fun metaSuggestionTag(field: MetaField) = "metadata-suggestion-${field.name}"

/** 语言下拉框。 */
const val TAG_LANGUAGE_FIELD = "metadata-field-Language"

/** "其他 ISO 639-1" 的手输代码框(仅 Other 时出现)。 */
const val TAG_OTHER_LANG_CODE = "metadata-other-lang-code"

/** 步骤 2 切换到某卷的 chip。 */
fun volumeChipTag(id: String) = "metadata-volume-chip-$id"

/**
 * 工作流 B · 步骤 2:逐卷元数据(验收第 11、12、13 项)。
 *
 * 数据流严格单向(与工作流 A 的 NamingPage 同构):**UI 只调用领域纯函数**,
 * 不在这里算默认名、不在这里判语言合不合法。
 *
 * 四件事必须在界面上可见,否则对应的验收项就是空谈:
 *  1. 每个字段的**建议值 + 来源**(建议 ≠ 事实,HANGOFF §6.1);
 *  2. 每个字段的**逐字段"恢复建议值"**入口;
 *  3. 语言为 [LangChoice.Unset] 时的 `errorText: 语言默认未设置，需人工确认`;
 *  4. 批量设置的**实际生效卷数**(`已设置到 N 卷 (跳过 M 卷逐项例外)`),
 *     而不是笼统的"已应用" —— 批量设置最危险的失败模式就是"以为全改了"。
 *
 * 状态模式与 `NamingPage` 一致:**本地状态权威 + 变更上报**;
 * `state ?: local` 那种写法会让上层的值冻结就地编辑(P4 实测踩过)。
 */
@Composable
fun MetadataPage(
    libraryState: WorkflowBState,
    onLibraryChange: (WorkflowBState) -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onNext: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    var state by remember { mutableStateOf(libraryState) }
    fun update(next: WorkflowBState) {
        state = next
        onLibraryChange(next)
    }

    val volume = state.activeVolume
    // 批量结果存**计数**而非成品文案 —— 成品文案要在组合里用 stringResource 解析,
    // 而回调不是 @Composable 上下文,取不到资源。
    var batchApplied by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_METADATA_PAGE),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_METADATA_LIST),
        ) {
            item(key = "volume-selector") {
                VolumeSelector(
                    state = state,
                    onSelect = { update(selectVolume(state, it)) },
                )
            }

            item(key = "volume-header") {
                VolumeHeader(
                    volume = volume,
                    onToggleIncluded = { checked ->
                        update(setVolumeIncluded(state, volume.id, checked))
                    },
                )
            }

            item(key = "fields") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextFieldRow(
                        field = MetaField.Title,
                        value = volume.title,
                        volume = volume,
                        suggestionText = stringResource(
                            R.string.meta_suggestion_with_source,
                            volume.titleSug.value.orEmpty(),
                            volume.titleSug.source,
                        ),
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.Title, MetaValue.Text(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.Title))) },
                    )
                    TextFieldRow(
                        field = MetaField.Series,
                        value = volume.series,
                        volume = volume,
                        suggestionText = stringResource(
                            R.string.meta_suggestion_with_source,
                            volume.seriesSug.value.orEmpty(),
                            volume.seriesSug.source,
                        ),
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.Series, MetaValue.Text(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.Series))) },
                    )
                    TextFieldRow(
                        field = MetaField.Writer,
                        value = volume.writer,
                        volume = volume,
                        suggestionText = stringResource(
                            R.string.meta_suggestion_with_source,
                            volume.writerSug.value.orEmpty(),
                            volume.writerSug.source,
                        ),
                        extraAction = {
                            val cleanCd = stringResource(R.string.meta_writer_clean_cd)
                            TextButton(
                                onClick = { update(updateVolume(state, applyWriterCleaning(volume))) },
                                enabled = volume.writerCleanedSug.hasValue,
                                modifier = Modifier
                                    .testTag(TAG_WRITER_CLEAN)
                                    .semantics { contentDescription = cleanCd },
                            ) { Text(stringResource(R.string.meta_writer_clean)) }
                        },
                        extraHint = volume.writerCleanedSug.value?.let {
                            stringResource(
                                R.string.meta_writer_cleaned_hint,
                                it,
                                volume.writerCleanedSug.source,
                            )
                        },
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.Writer, MetaValue.Text(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.Writer))) },
                    )
                    VolumeNumberRow(
                        volume = volume,
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.Volume, MetaValue.Number(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.Volume))) },
                    )
                    LanguageRow(
                        volume = volume,
                        onChoice = { choice ->
                            update(
                                updateVolume(
                                    state,
                                    setMetaValue(
                                        volume,
                                        MetaField.Language,
                                        MetaValue.Language(choice, volume.otherLangCode),
                                    ),
                                ),
                            )
                        },
                        onOtherCode = { code ->
                            update(
                                updateVolume(
                                    state,
                                    setMetaValue(
                                        volume,
                                        MetaField.Language,
                                        MetaValue.Language(LangChoice.Other, code),
                                    ),
                                ),
                            )
                        },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.Language))) },
                    )
                    TextFieldRow(
                        field = MetaField.CbzName,
                        value = volume.cbzFileName,
                        volume = volume,
                        suggestionText = stringResource(
                            R.string.meta_cbz_name_hint,
                            volume.titleSug.value.orEmpty(),
                        ),
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.CbzName, MetaValue.Text(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.CbzName))) },
                    )
                    TextFieldRow(
                        field = MetaField.OutputDir,
                        value = volume.outputDir,
                        volume = volume,
                        suggestionText = stringResource(
                            R.string.meta_output_dir_hint,
                            volume.outputDirSug,
                        ),
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.OutputDir, MetaValue.Text(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.OutputDir))) },
                    )
                }
            }

            item(key = "batch") {
                BatchSection(
                    applied = batchApplied,
                    onApply = { field, value, force ->
                        val (next, result) = batchApplyToLibrary(state, field, value, force)
                        update(next)
                        batchApplied = result.appliedCount to result.skippedCount
                    },
                )
            }
        }

        // 底栏:左 = 返回上一步,右 = 下一步 打包计划(用户 2026-09-25 指定)。
        // 任一回调为 null 时不渲染底栏 —— 测试直接组合本页,底栏凭空出现会改变可视区域。
        if (onBack != null || onNext != null) {
            val back = onBack
            val next = onNext
            val backCd = stringResource(R.string.b_step_back_cd)
            val nextCd = stringResource(R.string.meta_next_cd)
            PageActionBar(
                leading = {
                    if (back != null) {
                        FilledTonalButton(
                            onClick = back,
                            modifier = Modifier
                                .testTag(TAG_B_STEP_BACK)
                                .semantics { contentDescription = backCd },
                        ) { Text(stringResource(R.string.b_step_back)) }
                    }
                },
                trailing = {
                    if (next != null) {
                        Button(
                            onClick = next,
                            modifier = Modifier
                                .testTag(TAG_METADATA_NEXT)
                                .semantics { contentDescription = nextCd },
                        ) { Text(stringResource(R.string.meta_next)) }
                    }
                },
            )
        }
    }
}

/**
 * 步骤 2/3 的卷切换行:让"当前卷"在切步骤后仍可换(选中项在领域状态里)。
 *
 * 2026-09-27 真机实测缺陷:原为 `Row + horizontalScroll`。5 卷时第 4、5 卷芯片
 * (`海贼王` 两卷)完全落在视口外 —— `进击的巨人 外传` 右边界已达 1219px(屏宽 1264),
 * 只能靠横向滑动才够得着,而界面上没有任何提示说明这里能滑。
 * 与集合芯片行同源,一并改 FlowRow 自动换行(见 `SortAndPreviewPage.kt` 同处注释)。
 */
@Composable
private fun VolumeSelector(state: WorkflowBState, onSelect: (String) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(TAG_VOLUME_SELECTOR),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.volumes.forEach { v ->
            val chipCd = stringResource(R.string.meta_volume_chip_cd, v.id)
            FilterChip(
                selected = v.id == state.activeVolume.id,
                onClick = { onSelect(v.id) },
                label = { Text(v.title.ifEmpty { v.id }) },
                modifier = Modifier
                    .testTag(volumeChipTag(v.id))
                    .semantics { contentDescription = chipCd },
            )
        }
    }
}

/**
 * 卷头:目录 + 页数 + 待确认项 + "参与打包"开关。
 *
 * **为什么编辑页也要有 included 开关**:批量设置只作用于勾选的卷(验收第 13 项的语义边界),
 * 若这个开关只在步骤 1 存在,用户在步骤 2 改批量时无法就地调整作用范围。
 */
@Composable
private fun VolumeHeader(volume: ComicVolume, onToggleIncluded: (Boolean) -> Unit) {
    // 语义文案与非 composable lambda 里的文案先取出(`textOf` 是 @Composable)。
    val includeCd = stringResource(R.string.meta_include_cd, volume.id)
    val statusText = pluralStringResource(
        R.plurals.meta_volume_status,
        volume.pageCount,
        volume.pageCount,
        volume.overridden.size,
    )
    val conflictText = stringResource(
        R.string.meta_output_conflict,
        "${volume.outputDir}/${volume.cbzFileName}",
    )
    // 待确认项是**领域层给的 Msg 列表**(不能预先拼成字符串:语言要在渲染时才定),
    // `map` 必须内联 —— `joinToString` 的 transform 是普通 lambda,里面不能调 @Composable。
    val issuesText = volume.pendingIssues.map { textOf(it) }.joinToString(" / ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = volume.dirPath,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = volume.included,
                onCheckedChange = onToggleIncluded,
                modifier = Modifier
                    .testTag(volumeIncludedTag(volume.id))
                    .semantics { contentDescription = includeCd },
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (volume.outputExists) {
            Text(
                text = conflictText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (volume.pendingIssues.isNotEmpty()) {
            Text(
                text = stringResource(R.string.meta_pending_prefix) + issuesText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag(TAG_PENDING_ISSUES),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * 单个文本字段:标签 + 输入框 + 建议来源 + 恢复建议值。
 *
 * 建议文本**始终可见**(验收第 11 项:建议值带来源),而不是只在悬停/展开时才出现 ——
 * 手机上"悬停"根本不存在。
 */
@Composable
private fun TextFieldRow(
    field: MetaField,
    value: String,
    volume: ComicVolume,
    suggestionText: String,
    onValue: (String) -> Unit,
    onReset: () -> Unit,
    extraHint: String? = null,
    extraAction: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 字段名来自领域枚举的 textKey(不是成品字符串);`testTag` 用 `field.name` 保持语言无关。
        val fieldLabel = textOf(field.textKey)
        val resetCd = stringResource(R.string.meta_reset_cd, fieldLabel)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                label = { Text(fieldLabel) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag(metaFieldTag(field)),
            )
            extraAction?.invoke()
            TextButton(
                onClick = onReset,
                enabled = field in volume.overridden,
                modifier = Modifier
                    .testTag(metaResetTag(field))
                    .semantics { contentDescription = resetCd },
            ) { Text(stringResource(R.string.meta_reset)) }
        }
        Text(
            text = stringResource(R.string.meta_suggestion_prefix, suggestionText),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(metaSuggestionTag(field)),
        )
        if (extraHint != null) {
            Text(
                text = extraHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Number 字段:只接受数字(空 = 不设置)。 */
@Composable
private fun VolumeNumberRow(
    volume: ComicVolume,
    onValue: (Int?) -> Unit,
    onReset: () -> Unit,
) {
    val suggested = volume.volumeSug.value
    // 卷号字段名与「卷号缺失」提示都来自领域键 —— 后者原是 `PENDING_VOLUME` 常量,
    // 已并入 `TextKey.PendingVolume`(领域不再导出成品字符串)。
    val volumeLabel = textOf(MetaField.Volume.textKey)
    val pendingVolume = textOf(TextKey.PendingVolume)
    val noValue = stringResource(R.string.meta_no_value)
    val resetCd = stringResource(R.string.meta_reset_cd, volumeLabel)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = volume.volume?.toString().orEmpty(),
                onValueChange = { text ->
                    val digits = text.filter { it.isDigit() }
                    // 退格到空 = 明确"没有卷号"(待确认项随之出现),而不是拒绝输入
                    onValue(if (digits.isEmpty()) null else digits.toIntOrNull())
                },
                label = { Text(volumeLabel) },
                singleLine = true,
                isError = volume.volume == null,
                supportingText = if (volume.volume == null) {
                    { Text(pendingVolume) }
                } else {
                    null
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(metaFieldTag(MetaField.Volume)),
            )
            TextButton(
                onClick = onReset,
                enabled = MetaField.Volume in volume.overridden,
                modifier = Modifier
                    .testTag(metaResetTag(MetaField.Volume))
                    .semantics { contentDescription = resetCd },
            ) { Text(stringResource(R.string.meta_reset)) }
        }
        Text(
            text = stringResource(
                R.string.meta_suggestion_prefix,
                stringResource(
                    R.string.meta_suggestion_with_source,
                    suggested ?: noValue,
                    volume.volumeSug.source,
                ),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(metaSuggestionTag(MetaField.Volume)),
        )
    }
}

/**
 * 语言字段(验收第 12 项):默认「未设置（需确认）」必须人工确认,支持逐卷不同,
 * 且"不写入标签"是**独立选项**。
 *
 * `Unset` 时用 `isError` + `supportingText` 明确提示 —— 语言不是可以放心自动推断的字段
 * (HANGOFF §4 工作流 B 原文)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageRow(
    volume: ComicVolume,
    onChoice: (LangChoice) -> Unit,
    onOtherCode: (String) -> Unit,
    onReset: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val unset = volume.language == LangChoice.Unset
    val languageLabel = textOf(MetaField.Language.textKey)
    val unsetError = stringResource(R.string.meta_lang_unset_error)
    // 「恢复建议值 LanguageISO」里的 LanguageISO 是 ComicInfo.xml 的**标签名**,不翻译,
    // 故这里刻意用 `domain_meta_field_language`(`translatable="false"`)而非界面语言。
    val resetCd = stringResource(R.string.meta_reset_cd, languageLabel)
    val noValue = stringResource(R.string.meta_no_value)
    val currentLabel = textOf(volume.language.textKey)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(languageLabel) },
                    isError = unset,
                    supportingText = if (unset) {
                        { Text(unsetError) }
                    } else {
                        null
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .testTag(TAG_LANGUAGE_FIELD),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    LangChoice.entries.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(textOf(choice.textKey)) },
                            onClick = {
                                onChoice(choice)
                                expanded = false
                            },
                        )
                    }
                }
            }
            TextButton(
                onClick = onReset,
                enabled = MetaField.Language in volume.overridden,
                modifier = Modifier
                    .testTag(metaResetTag(MetaField.Language))
                    .semantics { contentDescription = resetCd },
            ) { Text(stringResource(R.string.meta_reset)) }
        }

        if (volume.language == LangChoice.Other) {
            OutlinedTextField(
                value = volume.otherLangCode,
                onValueChange = onOtherCode,
                label = { Text(stringResource(R.string.meta_lang_code_label)) },
                singleLine = true,
                modifier = Modifier
                    .width(200.dp)
                    .testTag(TAG_OTHER_LANG_CODE),
            )
        }

        Text(
            text = stringResource(
                R.string.meta_suggestion_prefix,
                stringResource(
                    R.string.meta_suggestion_with_source,
                    volume.langSug.value ?: noValue,
                    volume.langSug.source,
                ),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(metaSuggestionTag(MetaField.Language)),
        )
    }
}

/**
 * 批量设置区(验收第 13 项)。
 *
 * **必须显示实际生效数量**:批量设置会跳过被人工改过的字段(逐项例外),
 * 若只提示"已应用",用户要到计划页才会发现有的卷没变。
 * `强制覆盖逐项例外` 开关给需要"全改"的场景一个显式出口(默认关闭)。
 */
@Composable
private fun BatchSection(
    applied: Pair<Int, Int>?,
    onApply: (MetaField, MetaValue, Boolean) -> Unit,
) {
    var field by remember { mutableStateOf(MetaField.Series) }
    var text by remember { mutableStateOf("") }
    var force by remember { mutableStateOf(false) }

    // 计数 → 文案在**组合里**解析(回调里取不到 stringResource,故状态只存计数)。
    val message = applied?.let { (count, skipped) ->
        if (skipped > 0) {
            pluralStringResource(R.plurals.meta_batch_applied_skipped, count, count, skipped)
        } else {
            pluralStringResource(R.plurals.meta_batch_applied, count, count)
        }
    }
    val valueLabel = stringResource(R.string.meta_batch_value_label)
    val applyCd = stringResource(R.string.meta_batch_apply_cd)
    val forceCd = stringResource(R.string.meta_batch_force_cd)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(TAG_BATCH_ROW),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.meta_batch_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        // 批量字段芯片行:同样改 FlowRow(3 个字段在 361dp 下本就会挤到边缘,
        // 与卷切换行/集合行同源缺陷)。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 只开放文本类字段给批量(Number/Language 的取值形态不同,留给逐卷编辑器,
            // 避免把"批量语言"做成能一键把整库设成同一个语言的静默覆盖入口)
            listOf(MetaField.Series, MetaField.Writer, MetaField.OutputDir).forEach { f ->
                val fLabel = textOf(f.textKey)
                val fCd = stringResource(R.string.meta_batch_field_cd, fLabel)
                FilterChip(
                    selected = field == f,
                    onClick = { field = f },
                    label = { Text(fLabel) },
                    modifier = Modifier
                        .testTag("$TAG_BATCH_FIELD-${f.name}")
                        .semantics { contentDescription = fCd },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(valueLabel) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TAG_BATCH_VALUE),
            )
            TextButton(
                onClick = { onApply(field, MetaValue.Text(text), force) },
                enabled = text.isNotEmpty(),
                modifier = Modifier
                    .testTag(TAG_BATCH_APPLY)
                    .semantics { contentDescription = applyCd },
            ) { Text(stringResource(R.string.meta_batch_apply)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = force,
                onClick = { force = !force },
                label = {
                    Text(
                        stringResource(
                            if (force) R.string.meta_batch_force_on else R.string.meta_batch_force_off,
                        ),
                    )
                },
                modifier = Modifier
                    .testTag(TAG_BATCH_FORCE)
                    .semantics { contentDescription = forceCd },
            )
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(TAG_BATCH_RESULT),
            )
        }
    }
}
