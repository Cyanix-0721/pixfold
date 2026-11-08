package com.cyanix.pixfold.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.comic.defaultCbzName
import com.cyanix.pixfold.domain.comic.setMetaValue
import com.cyanix.pixfold.domain.comic.useSuggestion
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.MetaValue
import com.cyanix.pixfold.domain.text.TextKey
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.batchApplyToLibrary
import com.cyanix.pixfold.domain.library.selectVolume
import com.cyanix.pixfold.domain.library.setVolumeIncluded
import com.cyanix.pixfold.domain.library.updateVolume
import com.cyanix.pixfold.ui.components.ChipRowTags
import com.cyanix.pixfold.ui.components.MetaActionButton
import com.cyanix.pixfold.ui.components.PageActionBar
import com.cyanix.pixfold.ui.components.VolumeChipRows
import com.cyanix.pixfold.ui.text.textOf
import com.cyanix.pixfold.ui.text.textOfOrNull

/**
 * 字段编辑器的最大显示行数。
 *
 * 取 4 而不是无限:元数据字段是**短文本**(标题/作者/系列名),正常不超过两行;
 * 留 4 行给超长标题足够,同时挡住「粘贴一整篇文章把整页顶开」。
 * 超过这个高度后输入框内部纵向滚动 —— 纵向滚动**不影响**拖拽选择(手柄在
 * 视口内就能拖到视口内任意位置,纵向滚动条本身可拖),与单行的横向滚动不是一回事。
 */
private const val META_FIELD_MAX_LINES = 4

const val TAG_METADATA_PAGE = "metadata-page"
const val TAG_METADATA_LIST = "metadata-list"

/**
 * 1 级「系列」行(2026-10-19 起卷选择器分两级,见 [VolumeChipRows])。
 *
 * 与 2 级的 [TAG_VOLUME_SELECTOR] 分开:同一个界面里两行同时存在,
 * Compose 的 `onNodeWithTag` 在多节点匹配时会直接失败,两行**不能共用一个 tag**。
 */
const val TAG_VOLUME_SERIES_ROW = "metadata-volume-series-row"

/** 2 级「卷」行(单级时代它就是唯一的卷选择行,名字保持不变)。 */
const val TAG_VOLUME_SELECTOR = "metadata-volume-selector"

/** 1 级系列芯片。key 是 `series:` / `single:` 前缀化的分组键。 */
fun volumeGroupTag(key: String) = "metadata-volume-group-$key"

/** 1 级「全部 N 项」入口。 */
const val TAG_VOLUME_GROUP_ALL = "metadata-volume-group-all"

const val TAG_PENDING_ISSUES = "metadata-pending-issues"
const val TAG_BATCH_ROW = "metadata-batch-row"
const val TAG_BATCH_APPLY = "metadata-batch-apply"
const val TAG_BATCH_RESULT = "metadata-batch-result"
const val TAG_BATCH_FIELD = "metadata-batch-field"
const val TAG_BATCH_VALUE = "metadata-batch-value"
const val TAG_BATCH_FORCE = "metadata-batch-force"

/** 「下一步 打包计划」按钮(常驻底栏)。 */
const val TAG_METADATA_NEXT = "metadata-next"

/**
 * 「更新元数据」按钮(工作流 C 入口二,常驻底栏的第三槽)。
 *
 * 与 [TAG_METADATA_NEXT] 分开:两者在同一底栏同时存在,共用 tag 时
 * `onNodeWithTag` 多节点匹配直接失败 —— 且它们语义不同(一个是推进流程,
 * 一个是就地写盘终局)。
 */
const val TAG_METADATA_UPDATE = "metadata-update"

/**
 * 空工作区的显式空态(2026-10-19)。
 *
 * 真实 SAF 工作区可能**一卷都没有**:目录里没有任何图片,或所有图片都散在根目录
 * (按「每个直接含图片的目录 = 一个集合」的规则,根目录本身不构成卷)⇒ 上层传下来的
 * `volumes = emptyList()`。这条路径原先会在读取 `activeVolume` 时抛
 * `NoSuchElementException`(领域层 `volumes.first()`),整页崩掉。
 */
const val TAG_META_EMPTY = "library-meta-empty"

/** 元数据字段输入框(逐卷)。 */
fun metaFieldTag(field: MetaField) = "metadata-field-${field.name}"

/** 字段的"恢复建议值"按钮。 */
fun metaResetTag(field: MetaField) = "metadata-reset-${field.name}"

/** 字段的建议值 + 来源文本容器。 */
fun metaSuggestionTag(field: MetaField) = "metadata-suggestion-${field.name}"

/** 语言下拉框。 */
const val TAG_LANGUAGE_FIELD = "metadata-field-Language"

/** 步骤 2 切换到某卷的 chip。 */
fun volumeChipTag(id: String) = "metadata-volume-chip-$id"

/** 2 级卷行「全部 N 卷」入口(2026-10-23 起是原地展开,不再是 sheet 打开器)。 */
const val TAG_VOLUME_ALL = "metadata-volume-all"

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
    libraryState: LibraryState,
    onLibraryChange: (LibraryState) -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onNext: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    /**
     * 用户点底栏「更新元数据」（工作流 C，2026-10-26 规格 §8.2）。
     *
     * `null` = **不渲染这个入口**（默认值，既有测试零改动）。
     * 只在当前卷 `outputExists == true` 时出现，与库页入口同一条判据。
     */
    onUpdateMeta: ((ComicVolume) -> Unit)? = null,
) {
    // **必须带 key**(2026-10-22 修)。
    //
    // 无参 `remember` 只会在首次组合时用 `libraryState` 播种,之后上层把新状态传下来
    // (例如页签行的「按目录结构获取」改了当前卷的字段)时,本地副本仍是旧的 ⇒
    // **输入框看起来毫无反应**。这与 D2a 那轮「热切换工作区后白屏」是同一个成因:
    // 上层给新状态、下层 remember 不重新播种。
    //
    // 带 key 不会丢本地编辑:每次编辑都经 `update` 同步回上层,上层始终是最新的。
    var state by remember(libraryState) { mutableStateOf(libraryState) }
    fun update(next: LibraryState) {
        state = next
        onLibraryChange(next)
    }

    // 空工作区必须**先于**任何 `activeVolume` 读取判定(2026-10-19)。
    // 真实 SAF 扫描可能一卷都不产生:目录里没有图片,或图片全散在根目录(根目录本身不成卷)。
    // 此时 `LibraryState.activeVolume` 落到 `volumes.first()`,直接抛
    // `NoSuchElementException: List is empty.` —— 整页崩在组合期。
    // 领域层的那个定义由别的分支负责,这里只在 UI 层做防护:不读空列表、给出可见空态。
    val empty = libraryState.volumes.isEmpty()
    if (empty) {
        // 空态**不渲染「下一步」**:没有卷可编辑,推进过去只会把空列表继续传下去。
        // 但「返回」必须保留 —— 用户唯一的出路是退回去换一个目录。
        MetadataEmptyState(
            modifier = modifier,
            contentInsets = contentInsets,
            onBack = onBack,
        )
        return
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
        // 2026-10-18 真机实测(P2):卷切换芯片原先作为 LazyColumn 的第一个 item,
        // 一次全屏 swipe 后 5 个芯片全部滚出视口(dump 内 content-desc 以「卷 」开头的
        // 节点数为 0)⇒ 换卷必须先回顶;且换卷后列表内容重建、滚动位置回到顶部,
        // 构成「换卷 → 回顶 → 再滚回原字段」的来回。
        // 故移出 LazyColumn 常驻于其上方 —— 结构与本步骤 3 的卷选择器一致
        // (`PackPreviewPage.kt` 的 FlowRow 也在 LazyColumn 之外)。
        // 附带效果:切卷时 LazyColumn 的 item key 全部不变,LazyListState 得以保留滚动位置。
        //
        // 2026-10-19 用户要求「打包漫画的元数据步骤也改下支持刚才的双层芯片选取」⇒
        // 由单级 [VolumeChipRow] 换成两级 [VolumeChipRows]。**2 级卷芯片与单本 1 级芯片
        // 沿用原来的 `volumeChipTag`** —— 既有 20 余处测试按它点卷,语义没变。
        VolumeChipRows(
            volumes = state.volumes,
            activeId = state.activeVolume.id,
            onSelect = { update(selectVolume(state, it)) },
            topTags = ChipRowTags(
                selectorTag = TAG_VOLUME_SERIES_ROW,
                chipTag = ::volumeGroupTag,
                allEntryTag = TAG_VOLUME_GROUP_ALL,
            ),
            childTags = ChipRowTags(
                selectorTag = TAG_VOLUME_SELECTOR,
                chipTag = ::volumeChipTag,
                allEntryTag = TAG_VOLUME_ALL,
            ),
            singleTopChipTag = ::volumeChipTag,
            topChipDescription = { group ->
                if (group.isSeries) {
                    stringResource(R.string.meta_series_cd, group.name)
                } else {
                    stringResource(R.string.meta_volume_chip_cd, group.members.first().id)
                }
            },
            topChipLabel = { group ->
                // 系列芯片显示组名 + 卷数;单本芯片显示卷标题(与 2 级行同一文案,
                // 因为单本只有自己一卷,再标「1 卷」是噪声)。
                if (group.isSeries) {
                    stringResource(R.string.meta_series_chip, group.name, group.members.size)
                } else {
                    group.name
                }
            },
            topAllEntryText = { n -> pluralStringResource(R.plurals.volume_group_all, n, n) },
            childChipDescription = { v -> stringResource(R.string.meta_volume_chip_cd, v.id) },
            childChipLabel = { v -> v.title.ifEmpty { v.id } },
            childAllEntryText = { n -> pluralStringResource(R.plurals.volume_all, n, n) },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_METADATA_LIST),
        ) {
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
                        // 「按目录结构获取」已上移到**页签行**右侧(作用于当前卷全部字段,
                        // 不只作者) —— 用户 2026-10-22 裁决。本行只留「恢复建议值」。
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
                                        MetaValue.Language(choice),
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
                        // 提示行必须直接给出**恢复后真正会得到的那个文件名**，
                        // 不能自己拼一个「看起来像」的。
                        //
                        // 2026-10-19 真实缺陷：这里原先传的是 `titleSug.value`，
                        // 而「恢复建议值」实际走 `defaultCbzName(..., cbzBaseSug)`——
                        // 后者**保留 `[作者]` 前缀**且带「第NN卷」后缀。真实素材下
                        // 提示显示「彼女がボクの知らないところで」而恢复得到
                        // 「[Cior (Ken-1)] 彼女がボクの知らないところで 第01卷.cbz」，
                        // 用户照着提示改反而改错。
                        //
                        // 唯一不会漂移的写法是调同一个函数（domain 的唯一权威），
                        // 只把「同一个函数」写两遍就等于日后两处会分叉。
                        // 文案键只做包装，值仍来自 `defaultCbzName(volume)`。
                        suggestionText = stringResource(
                            R.string.meta_cbz_name_hint,
                            defaultCbzName(volume),
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
        // 第三槽「更新元数据」的判据：当前卷在磁盘上真有归档。
        val updateCb = onUpdateMeta?.takeIf { volume.outputExists }
        if (onBack != null || onNext != null || updateCb != null) {
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
                // 工作流 C 入口二：走 extraContent 槽位（按钮行**下方**的整行按钮），
                // 而不是塞进 leading/trailing —— 那两个槽位的语义分别是「退回类」与
                // 「推进流程」，就地更新是**另一种终局**，挤进去会让人以为它也能推进。
                extraContent = {
                    if (updateCb != null) {
                        OutlinedButton(
                            onClick = { updateCb(volume) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TAG_METADATA_UPDATE),
                        ) { Text(stringResource(R.string.update_meta_action)) }
                    }
                },
            )
        }
    }
}

/**
 * 空工作区时的整页替代内容。
 *
 * 与 `OrganizeSteps` 的 `EmptyWorkspace`、`SortAndPreviewPage` 的 `a_sort_empty` 空态
 * 同一立场:**状态错配必须可见,白屏/崩溃是最坏的失败模式**。
 *
 * 结构刻意与正常态同构(外层 `Column` 撑满 + 内边距 + `PageActionBar` 在列表之外),
 * 这样返回按钮落在与正常态完全相同的位置 —— 用户在两种状态下不用重新找它。
 * 「下一步」不渲染:没有卷可编辑,推进没有意义。
 */
@Composable
private fun MetadataEmptyState(
    modifier: Modifier,
    contentInsets: WindowInsets,
    onBack: (() -> Unit)?,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_METADATA_PAGE),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.b_meta_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag(TAG_META_EMPTY),
            )
        }

        // 底栏只保留返回槽位。渲染条件与正常态同形(`onBack != null` 才出现),
        // 这样「直接组合本页的测试」不会凭空多出一条底栏、改变可用区域。
        if (onBack != null) {
            val backCd = stringResource(R.string.b_step_back_cd)
            PageActionBar(
                leading = {
                    FilledTonalButton(
                        onClick = onBack,
                        modifier = Modifier
                            .testTag(TAG_B_STEP_BACK)
                            .semantics { contentDescription = backCd },
                    ) { Text(stringResource(R.string.b_step_back)) }
                },
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
        // **空值就在这一格标红**(2026-10-22 用户原话:「我说的不是顶部的待确认,
        // 而是卷号那样空值的红色警告」)。判据来自 `volume.pendingIssueOf(field)`,
        // 与页头汇总共用同一份映射 —— 两处各写一份的话迟早出现
        // 「页头说标题缺失、标题框却不标红」这种用户看得见的分歧。
        val pending = volume.pendingIssueOf(field)
        val pendingText = pending?.let { textOf(it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = value,
                // **不用 `singleLine = true`**:单行输入框在文本超宽时只能横向滚动,
                // 而 Compose 的拖拽选择手柄拖动时**不会**驱动这个横向滚动 ——
                // 用户把光标拖到输入框右边缘就再也拖不动,视口外的那半截文字只能靠
                // 输入法的左右移动键够到(用户 2026-10-20 报告的就是这个现象)。
                //
                // 改成多行软换行后,长文本折行显示、每个字符都落在视口内,
                // 拖拽手柄自然能拖到任意位置。换行符本身仍然禁止(见 onValueChange),
                // 因为文件名/元数据里出现换行会破坏 `<Title>` 这类单值标签。
                onValueChange = { onValue(it.replace("\n", "").replace("\r", "")) },
                label = { Text(fieldLabel) },
                maxLines = META_FIELD_MAX_LINES,
                isError = pending != null,
                supportingText = pendingText?.let { text -> { Text(text) } },
                modifier = Modifier
                    .weight(1f)
                    .testTag(metaFieldTag(field)),
            )
            extraAction?.invoke()
            MetaActionButton(
                icon = ImageVector.vectorResource(R.drawable.ic_undo),
                description = resetCd,
                onClick = onReset,
                tag = metaResetTag(field),
                enabled = field in volume.overridden,
            )
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
            MetaActionButton(
                icon = ImageVector.vectorResource(R.drawable.ic_undo),
                description = resetCd,
                onClick = onReset,
                tag = metaResetTag(MetaField.Volume),
                enabled = MetaField.Volume in volume.overridden,
            )
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
    onReset: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val languageLabel = textOf(MetaField.Language.textKey)
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
            MetaActionButton(
                icon = ImageVector.vectorResource(R.drawable.ic_undo),
                description = resetCd,
                onClick = onReset,
                tag = metaResetTag(MetaField.Language),
                enabled = MetaField.Language in volume.overridden,
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
                // 与字段编辑器同因:单行横向滚动下拖拽手柄够不到视口外的字符
                onValueChange = { text = it.replace("\n", "").replace("\r", "") },
                label = { Text(valueLabel) },
                maxLines = META_FIELD_MAX_LINES,
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
