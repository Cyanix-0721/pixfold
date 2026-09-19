package com.pixfold.d1.ui.workflowb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.comic.applyWriterCleaning
import com.pixfold.d1.domain.comic.setMetaValue
import com.pixfold.d1.domain.comic.useSuggestion
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.LangChoice
import com.pixfold.d1.domain.model.MetaField
import com.pixfold.d1.domain.model.MetaValue
import com.pixfold.d1.domain.model.PENDING_VOLUME
import com.pixfold.d1.domain.workflowb.WorkflowBState
import com.pixfold.d1.domain.workflowb.batchApplyToLibrary
import com.pixfold.d1.domain.workflowb.selectVolume
import com.pixfold.d1.domain.workflowb.setVolumeIncluded
import com.pixfold.d1.domain.workflowb.updateVolume

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
) {
    var state by remember { mutableStateOf(libraryState) }
    fun update(next: WorkflowBState) {
        state = next
        onLibraryChange(next)
    }

    val volume = state.activeVolume
    var batchMessage by remember { mutableStateOf<String?>(null) }

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
                        suggestionText = "${volume.titleSug.value.orEmpty()}（${volume.titleSug.source}）",
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.Title, MetaValue.Text(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.Title))) },
                    )
                    TextFieldRow(
                        field = MetaField.Series,
                        value = volume.series,
                        volume = volume,
                        suggestionText = "${volume.seriesSug.value.orEmpty()}（${volume.seriesSug.source}）",
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.Series, MetaValue.Text(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.Series))) },
                    )
                    TextFieldRow(
                        field = MetaField.Writer,
                        value = volume.writer,
                        volume = volume,
                        suggestionText = "${volume.writerSug.value.orEmpty()}（${volume.writerSug.source}）",
                        extraAction = {
                            TextButton(
                                onClick = { update(updateVolume(state, applyWriterCleaning(volume))) },
                                enabled = volume.writerCleanedSug.hasValue,
                                modifier = Modifier
                                    .testTag(TAG_WRITER_CLEAN)
                                    .semantics { contentDescription = "一键清理作者" },
                            ) { Text("一键清理") }
                        },
                        extraHint = volume.writerCleanedSug.value?.let {
                            "清理建议：$it（${volume.writerCleanedSug.source}）"
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
                        suggestionText = "由 Title + Number 建议值派生（${volume.titleSug.value.orEmpty()}）",
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.CbzName, MetaValue.Text(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.CbzName))) },
                    )
                    TextFieldRow(
                        field = MetaField.OutputDir,
                        value = volume.outputDir,
                        volume = volume,
                        suggestionText = "建议输出目录（${volume.outputDirSug}）",
                        onValue = { update(updateVolume(state, setMetaValue(volume, MetaField.OutputDir, MetaValue.Text(it)))) },
                        onReset = { update(updateVolume(state, useSuggestion(volume, MetaField.OutputDir))) },
                    )
                }
            }

            item(key = "batch") {
                BatchSection(
                    message = batchMessage,
                    onApply = { field, value, force ->
                        val (next, result) = batchApplyToLibrary(state, field, value, force)
                        update(next)
                        batchMessage = "已设置到 ${result.appliedCount} 卷" +
                            if (result.skippedCount > 0) "（跳过 ${result.skippedCount} 卷逐项例外）" else ""
                    },
                )
            }

            if (onNext != null) {
                item(key = "next") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = onNext,
                            label = { Text("下一步 打包计划") },
                            modifier = Modifier
                                .testTag(TAG_METADATA_NEXT)
                                .semantics { contentDescription = "下一步 打包计划" },
                        )
                    }
                }
            }
        }
    }
}

const val TAG_METADATA_NEXT = "metadata-next"

/** 步骤 2/3 的卷切换行:让"当前卷"在切步骤后仍可换(选中项在领域状态里)。 */
@Composable
private fun VolumeSelector(state: WorkflowBState, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(TAG_VOLUME_SELECTOR),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.volumes.forEach { v ->
            FilterChip(
                selected = v.id == state.activeVolume.id,
                onClick = { onSelect(v.id) },
                label = { Text(v.title.ifEmpty { v.id }) },
                modifier = Modifier
                    .testTag(volumeChipTag(v.id))
                    .semantics { contentDescription = "卷 ${v.id}" },
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
                    .semantics { contentDescription = "勾选卷 ${volume.id}" },
            )
            Text(
                text = "参与打包 · ${volume.pageCount} 页 · 已覆盖 ${volume.overridden.size} 个字段",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (volume.outputExists) {
            Text(
                text = "输出冲突：输出目录已存在同名 CBZ（${volume.outputDir}/${volume.cbzFileName}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (volume.pendingIssues.isNotEmpty()) {
            Text(
                text = "待确认：" + volume.pendingIssues.joinToString(" / "),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                label = { Text(field.label) },
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
                    .semantics { contentDescription = "恢复建议值 ${field.label}" },
            ) { Text("恢复建议") }
        }
        Text(
            text = "建议：$suggestionText",
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
                label = { Text(MetaField.Volume.label) },
                singleLine = true,
                isError = volume.volume == null,
                supportingText = if (volume.volume == null) {
                    { Text(PENDING_VOLUME) }
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
                    .semantics { contentDescription = "恢复建议值 ${MetaField.Volume.label}" },
            ) { Text("恢复建议") }
        }
        Text(
            text = "建议：${suggested ?: "无"}（${volume.volumeSug.source}）",
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
                    value = volume.language.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(MetaField.Language.label) },
                    isError = unset,
                    supportingText = if (unset) {
                        { Text("语言默认未设置，需人工确认") }
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
                            text = { Text(choice.label) },
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
                    .semantics { contentDescription = "恢复建议值 LanguageISO" },
            ) { Text("恢复建议") }
        }

        if (volume.language == LangChoice.Other) {
            OutlinedTextField(
                value = volume.otherLangCode,
                onValueChange = onOtherCode,
                label = { Text("ISO 639-1 代码") },
                singleLine = true,
                modifier = Modifier
                    .width(200.dp)
                    .testTag(TAG_OTHER_LANG_CODE),
            )
        }

        Text(
            text = "建议：${volume.langSug.value ?: "无"}（${volume.langSug.source}）",
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
    message: String?,
    onApply: (MetaField, MetaValue, Boolean) -> Unit,
) {
    var field by remember { mutableStateOf(MetaField.Series) }
    var text by remember { mutableStateOf("") }
    var force by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(TAG_BATCH_ROW),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "批量设置（跳过已人工修改的字段）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 只开放文本类字段给批量(Number/Language 的取值形态不同,留给逐卷编辑器,
            // 避免把"批量语言"做成能一键把整库设成同一个语言的静默覆盖入口)
            listOf(MetaField.Series, MetaField.Writer, MetaField.OutputDir).forEach { f ->
                FilterChip(
                    selected = field == f,
                    onClick = { field = f },
                    label = { Text(f.label) },
                    modifier = Modifier
                        .testTag("$TAG_BATCH_FIELD-${f.name}")
                        .semantics { contentDescription = "批量设置字段 ${f.label}" },
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
                label = { Text("批量值") },
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
                    .semantics { contentDescription = "应用批量设置" },
            ) { Text("应用") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = force,
                onClick = { force = !force },
                label = { Text(if (force) "强制覆盖逐项例外" else "尊重逐项例外") },
                modifier = Modifier
                    .testTag(TAG_BATCH_FORCE)
                    .semantics { contentDescription = "强制覆盖逐项例外" },
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
