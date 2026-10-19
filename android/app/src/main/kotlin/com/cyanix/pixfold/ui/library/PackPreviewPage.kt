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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.comic.includedVolumes
import com.cyanix.pixfold.domain.plan.ConfirmFlowState
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.SourcePolicy
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.plan.effectiveDangerActions
import com.cyanix.pixfold.domain.plan.generalSummaryLines
import com.cyanix.pixfold.domain.plan.buildConfirmSequence
import com.cyanix.pixfold.domain.plan.planCountsOf
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.ui.plan.ConfirmDialog
import com.cyanix.pixfold.ui.plan.PlanHeader
import com.cyanix.pixfold.ui.plan.PlanRow
import com.cyanix.pixfold.ui.plan.TAG_PLAN_CONFIRM
import com.cyanix.pixfold.domain.comic.previewOf
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.selectVolume
import com.cyanix.pixfold.ui.components.PageActionBar
import com.cyanix.pixfold.ui.components.VolumeChipRow
import com.cyanix.pixfold.ui.text.WarnLine
import com.cyanix.pixfold.ui.text.textOf
import com.cyanix.pixfold.ui.text.warnIcon

const val TAG_PACK_PAGE = "pack-page"
const val TAG_PACK_LIST = "pack-list"
const val TAG_XML_PREVIEW = "pack-xml-preview"
const val TAG_PAGE_PREVIEW = "pack-page-preview"
const val TAG_PAGE_PREVIEW_COUNT = "pack-page-preview-count"
const val TAG_PACK_VOLUME_SELECTOR = "pack-volume-selector"
const val TAG_PACK_PENDING = "pack-pending-issues"
const val TAG_PACK_EXCLUDED = "pack-excluded"
const val TAG_PACK_INCLUDED_COUNT = "pack-included-count"

/**
 * 步骤 3 的空工作区态（没有任何卷可打包）。
 *
 * 与 `MetadataPage` 的 `TAG_META_EMPTY` 分开：两页的空态文案不同（「没有可编辑的卷」
 * vs 「没有可打包的卷」），共用一个 tag 会让测试无法区分是不是渲染了**错误的**那一页。
 */
const val TAG_PACK_EMPTY = "pack-empty"

/** 执行被阻断/故障时的错误横条（D2b）。 */
const val TAG_PACK_EXECUTION_ERROR = "pack-execution-error"

/** 页码预览第 i 行。 */
fun packPageRowTag(index: Int) = "pack-page-row-$index"

/** 步骤 3 的卷切换 chip。 */
fun packVolumeChipTag(id: String) = "pack-volume-chip-$id"

/** 「全部 N 卷」入口(sheet 打开器)。 */
const val TAG_PACK_VOLUME_ALL = "pack-volume-all"

/** 「全部 N 卷」sheet 的标题/列表容器。 */
const val TAG_PACK_VOLUME_SHEET = "pack-volume-sheet"
const val TAG_PACK_VOLUME_SHEET_LIST = "pack-volume-sheet-list"

/** sheet 内的卷芯片(与页面上常驻的 [packVolumeChipTag] 区分,避免 tag 冲突)。 */
fun packVolumeSheetChipTag(id: String) = "pack-volume-sheet-chip-$id"

/**
 * 工作流 B · 步骤 3:预览(验收第 14 项:**ComicInfo.xml 预览与页码列表预览一致**)。
 *
 * **一致性靠结构保证,不靠 UI 小心同步**:XML 与页码来自**同一次** [previewOf] 求值
 * (页序 → 重编号 → XML),不存在"两处各算一份、结果不一致"的可能。
 * 本页是纯展示 + 切卷,不持有自己的状态副本(切卷把新状态上报上层),
 * 因此也不会出现"预览与当前设置不一致"的陈旧副本。
 *
 * P6(验收 15–17)起,本页在预览之下承载**执行计划**:
 * 冲突策略 / 源目录策略 / 危险动作开关 + 操作清单( describe() 契约) + 两次确认 + 执行。
 * 预览仍由 [previewOf] 驱动,与计划**同源**(同一份页序快照),不会互相矛盾。
 */
@Composable
fun PackPreviewPage(
    libraryState: LibraryState,
    onLibraryChange: (LibraryState) -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    conflictPolicy: OutputConflictPolicy = OutputConflictPolicy.Rename,
    sourcePolicy: SourcePolicy = SourcePolicy.Keep,
    cleanEmptyDirs: Boolean = false,
    onConflictPolicyChange: (OutputConflictPolicy) -> Unit = {},
    onSourcePolicyChange: (SourcePolicy) -> Unit = {},
    onCleanEmptyDirsChange: (Boolean) -> Unit = {},
    confirmFlow: ConfirmFlowState = ConfirmFlowState(),
    onStartConfirm: () -> Unit = {},
    onConfirmStep: () -> Unit = {},
    onCancelConfirm: () -> Unit = {},
    onExecute: () -> Unit = {},
    showPlanSection: Boolean = false,
    onBack: (() -> Unit)? = null,
    /**
     * 是否正在真实执行（D2b）。
     *
     * `true` 时底部按钮变成「取消」、上方显示进度条 —— 用户必须在长任务里有**两件事**：
     * 知道还要多久（进度）、以及能中途叫停（取消）。只给进度不给取消，在 30 卷
     * × 每卷 20 页的规模下就是「关掉应用才能停」。
     *
     * 刻意不做成「根据 executor 是否为空自动推断」：测试里可以注入执行器但只想看
     * 静态布局，两种需求要能分开表达。
     */
    executing: Boolean = false,
    /**
     * 打包进度（D2b）。`null` = 未开始或无进度可报。
     *
     * 类型用领域层已算好的 [TaskProgress] 而不是「已完成/总数」两个 Int：分段进度条
     * 需要 `fraction`，而 `fraction` 在空任务时返回 1f（不是 NaN），这个边界已经在
     * 领域层单测里钉住了，UI 侧不该再算一遍。
     */
    progress: TaskProgress? = null,
    /** 用户要求中止执行。仅在 [executing] 为 true 时有意义。 */
    onCancelExecution: () -> Unit = {},
    /**
     * 取消已发出、任务正在收尾（D2b）。
     *
     * 它会禁用「取消」按钮**但不隐藏它**：任务不是在收到信号的那一毫秒就停，
     * 当前这一卷要写完（否则磁盘上会留下半个 CBZ）。这段时间里用户需要看到
     * 「我已经按过了」的反馈，否则会连点，而连点会让上层重复发取消信号。
     */
    cancelling: Boolean = false,
    /**
     * 执行被阻断/故障的一句话说明（D2b）。`null` = 无故障。
     *
     * 与 `ExecutionReportPage` 的报告是**两种东西**：报告说「哪些卷成功了」，
     * 这里说「这次压根没跑起来」。前者是终局，后者是错误态 —— 混进报告里
     * 会让用户以为部分卷已经写出来了。
     */
    executionError: String? = null,
) {
    // 空工作区必须**先于**任何 `activeVolume` 读取判定（2026-10-19）。
    // 真实 SAF 扫描可能一卷都不产生（目录里没有图片，或图片全散在根目录 ——
    // 按「每个直接含图片的目录 = 一个集合」的规则，根目录本身不构成卷）。
    // 此时 `LibraryState.activeVolume` 落到 `volumes.first()`，
    // 直接抛 `NoSuchElementException: List is empty.`，整页崩在组合期。
    // 步骤 2（`MetadataPage`）已有同样的防护；本页是同一缺陷的第二个落点。
    if (libraryState.volumes.isEmpty()) {
        PackEmptyState(
            modifier = modifier,
            contentInsets = contentInsets,
            onBack = onBack,
        )
        return
    }

    val volume = libraryState.activeVolume
    val preview = previewOf(volume)

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_PACK_PAGE),
    ) {
        // 2026-09-25 真机实测:原为 `Row + horizontalScroll`,5 卷时第 4、5 卷芯片落在
        // 视口外 ⇒ 改 FlowRow 换行。2026-10-18:该行在 `LazyColumn` 之外且不设个数上限,
        // 卷一多就把下方预览挤到不可达(步骤 2 同源,40 卷实测已证)⇒ 改用全站共用的
        // `VolumeChipRow`:常驻限 5 个 + 溢出时给「全部 N 卷」入口。
        // 芯片标签保留本页特有语义:未勾选的卷要标出来,否则用户会误以为它也会被打包。
        VolumeChipRow(
            volumes = libraryState.volumes,
            activeId = volume.id,
            onSelect = { onLibraryChange(selectVolume(libraryState, it)) },
            selectorTag = TAG_PACK_VOLUME_SELECTOR,
            chipTag = ::packVolumeChipTag,
            allEntryTag = TAG_PACK_VOLUME_ALL,
            sheetTag = TAG_PACK_VOLUME_SHEET,
            sheetListTag = TAG_PACK_VOLUME_SHEET_LIST,
            sheetChipTag = ::packVolumeSheetChipTag,
            chipDescription = { id -> stringResource(R.string.pack_volume_chip_cd, id) },
            chipLabel = { v ->
                val title = v.title.ifEmpty { v.id }
                Text(
                    if (v.included) {
                        title
                    } else {
                        stringResource(R.string.pack_volume_excluded, title)
                    },
                )
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_PACK_LIST),
        ) {
            item(key = "header") {
                PackHeader(
                    volume = volume,
                    includedCount = includedVolumes(libraryState.volumes).size,
                    totalCount = libraryState.volumes.size,
                )
            }

            // ---- ComicInfo.xml 预览 ----
            item(key = "xml") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.pack_xml_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = preview.xml,
                        style = MaterialTheme.typography.bodySmall,
                        // 等宽字体:XML 的缩进与对齐才有意义
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag(TAG_XML_PREVIEW),
                    )
                    HorizontalDivider()
                }
            }

            // ---- 页码列表预览 ----
            item(key = "page-header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = pluralStringResource(R.plurals.pack_page_header, preview.pages.size, preview.pages.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag(TAG_PAGE_PREVIEW_COUNT),
                    )
                    Text(
                        text = stringResource(R.string.pack_page_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .testTag(TAG_PAGE_PREVIEW),
                    )
                    if (!preview.consistent) {
                        // pageIssues 是领域层的 `List<Msg>` —— 逐条解析后在**这里**拼接,
                        // 连接符也是资源(中英标点不同)。`map` 必须内联:`joinToString` 的
                        // transform 是普通 lambda,里面调不了 @Composable。
                        val issueSeparator = stringResource(R.string.pack_page_issue_separator)
                        val issues = preview.pageIssues.map { textOf(it) }.joinToString(issueSeparator)
                        WarnLine(
                            icon = warnIcon(R.string.pack_page_issues) ?: R.drawable.ic_warning_triangle,
                            text = stringResource(R.string.pack_page_issues, issues),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }

            items(preview.pages.indices.toList()) { i ->
                val page = preview.pages[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .testTag(packPageRowTag(i)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = page.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "← ${page.item.fileName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (volume.pageOrder.isManual(page.item.id)) {
                        // 人工调整过的页:预览必须能看出来(归档原型用蓝字标注同一意图)
                        Text(
                            text = stringResource(R.string.pack_page_manual),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // ---- P6:执行计划(仅当前卷? 不 —— 计划覆盖全部 included 卷) ----
            if (showPlanSection) {
                item(key = "plan") {
                    val plans = buildPackagePlans(
                        libraryState.volumes,
                        conflictPolicy = conflictPolicy,
                        sourcePolicy = sourcePolicy,
                    )
                    val dangers = effectiveDangerActions(plans, cleanEmptyDirs)

                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        Text(
                            text = stringResource(R.string.pack_plan_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )

                        // ---- 策略选择(默认 Rename / Keep) ----
                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                text = stringResource(R.string.pack_conflict_policy_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 2026-09-25:改 FlowRow(同源缺陷,与卷芯片行一并处理)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                OutputConflictPolicy.entries.forEach { p ->
                                    FilterChip(
                                        selected = conflictPolicy == p,
                                        onClick = { onConflictPolicyChange(p) },
                                        label = { Text(textOf(p.textKey)) },
                                        modifier = Modifier.testTag("policy-conflict-" + p.name),
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.pack_source_policy_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            // 2026-09-25:改 FlowRow(同源缺陷)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SourcePolicy.entries.forEach { p ->
                                    FilterChip(
                                        selected = sourcePolicy == p,
                                        onClick = { onSourcePolicyChange(p) },
                                        label = { Text(textOf(p.textKey)) },
                                        modifier = Modifier.testTag("policy-source-" + p.name),
                                    )
                                }
                            }
                            FilterChip(
                                selected = cleanEmptyDirs,
                                onClick = { onCleanEmptyDirsChange(!cleanEmptyDirs) },
                                label = {
                                    Text(
                                        stringResource(
                                            if (cleanEmptyDirs) {
                                                R.string.pack_clean_empty_on
                                            } else {
                                                R.string.pack_clean_empty_off
                                            },
                                        ),
                                    )
                                },
                                modifier = Modifier.testTag(TAG_CLEAN_EMPTY_TOGGLE),
                            )
                            if (sourcePolicy == SourcePolicy.Delete) {
                                WarnLine(
                                    icon = warnIcon(R.string.pack_delete_warning) ?: R.drawable.ic_warning_triangle,
                                    text = stringResource(R.string.pack_delete_warning),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag(TAG_DELETE_WARNING),
                                )
                            }
                        }

                        PlanHeader(counts = planCountsOf(plans), dangers = dangers.toList())
                        for (i in plans.indices) {
                            PlanRow(plans[i], i)
                        }
                    }
                }
            }
        }

        // 执行故障横条:必须是**错误色**而不是普通的提示色 —— 它是「这次什么都没写成」,
        // 不是「有个小提醒」。放在底栏正上方,因为用户的视线在按钮附近。
        if (executionError != null) {
            WarnLine(
                icon = warnIcon(R.string.pack_error_space) ?: R.drawable.ic_warning_triangle,
                text = executionError,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag(TAG_PACK_EXECUTION_ERROR),
            )
        }

        // 底栏:左 = 返回上一步,右 = 确认打包(用户 2026-09-25 指定)。
        // 终局动作原先在计划区末尾,计划覆盖全部 included 卷,长列表要滚很久才够得到,
        // 且未滚到之前按钮根本没被组合。与 A 侧 [TAG_A_PLAN_CONFIRM] 一致。
        //
        // 执行期间(D2b)整条底栏换形态:计划区已经不可编辑,「确认打包」按下去只会
        // 再启一次任务。此时唯一有意义的动作是**取消**,故右侧换成取消按钮,
        // 左侧的返回也一并撤掉 —— 返回会离开这个目的地,而任务还在后台跑。
        if (showPlanSection && executing) {
            // 进度与取消由 `PackProgressBar` 一体承担（进度条 + 行内取消,见其 KDoc
            // 「为什么取消按钮在进度条行内」）。**不在这里自作主张再写一套** ——
            // 同一屏出现两个挂 TAG_PACK_PROGRESS 的进度条时,`onNodeWithTag` 会直接
            // 撞到多个节点而失败,而且两条进度条的读数还可能不同源。
            //
            // 底栏在执行期整条撤掉:左侧「返回」会离开这个目的地(任务却还在后台跑),
            // 右侧「确认打包」再按一次就是并发写同一批文件。
            PackProgressBar(
                progress = progress ?: TaskProgress(total = 0),
                onCancel = onCancelExecution,
                cancelling = cancelling,
            )
        } else if (showPlanSection) {
            val back = onBack
            val backCd = stringResource(R.string.b_step_back_cd)
            val confirmCd = stringResource(R.string.pack_confirm_cd)
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
                    Button(
                        onClick = onStartConfirm,
                        modifier = Modifier
                            .testTag(TAG_PLAN_CONFIRM)
                            .semantics { contentDescription = confirmCd },
                    ) { Text(stringResource(R.string.pack_confirm)) }
                },
            )
        }
    }

    // 确认对话框:严格按领域层给出的序列推进(通用 → 危险)。
    // 必须在 Column **之外**:弹窗是独立窗口层,放进滚动容器会被裁切。
    val step = confirmFlow.currentStep
    if (step != null) {
        ConfirmDialog(
            step = step,
            stepIndex = confirmFlow.stepIndex,
            totalSteps = confirmFlow.totalSteps,
            onConfirm = onConfirmStep,
            onCancel = onCancelConfirm,
        )
    }
}

/** 「清理空目录」开关(默认关闭;勾选后仍需在确认对话框中再次确认)。 */
const val TAG_CLEAN_EMPTY_TOGGLE = "pack-clean-empty-toggle"

/** 选择删除源目录时的红色警告。 */
const val TAG_DELETE_WARNING = "pack-delete-warning"

@Composable
private fun PackHeader(volume: ComicVolume, includedCount: Int, totalCount: Int) {
    val outputText = stringResource(R.string.pack_output, "${volume.outputDir}/${volume.cbzFileName}") +
        if (volume.outputExists) stringResource(R.string.pack_output_exists) else ""
    val languageText = stringResource(R.string.pack_language_iso, langLabel(volume))
    val includedText = pluralStringResource(R.plurals.pack_included_count, includedCount, includedCount, totalCount) +
        if (includedCount < totalCount) stringResource(R.string.pack_included_count_hint) else ""
    // pendingIssues 是领域 `List<Msg>` —— 逐条解析后拼接(`map` 内联,理由同页码问题行)。
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
        Text(
            text = outputText,
            style = MaterialTheme.typography.labelSmall,
            color = if (volume.outputExists) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = languageText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!volume.included) {
            // 未勾选 = 本次不打包。必须显式说明,否则"能看到预览"会被当成"会被打包"。
            WarnLine(
                icon = warnIcon(R.string.pack_excluded_warning) ?: R.drawable.ic_warning_triangle,
                text = stringResource(R.string.pack_excluded_warning),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TAG_PACK_EXCLUDED),
            )
        }
        // 用领域层 includedVolumes 求"本次实际会打包几卷"——预览必须回答"这次到底做什么",
        // 而不是只展示当前这一卷(用户勾选状态改了却看不到反馈,是 P3 那类"界面没用上领域规则")。
        Text(
            text = includedText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TAG_PACK_INCLUDED_COUNT),
        )
        if (volume.pendingIssues.isNotEmpty()) {
            Text(
                text = stringResource(R.string.meta_pending_prefix) + issuesText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag(TAG_PACK_PENDING),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * 步骤 3 的空工作区态。
 *
 * 与 `MetadataPage` 的 `MetadataEmptyState` 刻意同构（外层 `Column` 撑满 + 内边距 +
 * `PageActionBar` 在列表之外），这样返回按钮在两种状态下落在同一位置。
 *
 * 为什么步骤 2 与步骤 3 都要各写一份而不是抽公共组件：两页的 tag 与文案都不同，
 * 且这个空态只有 30 行、没有第二个变化维度；抽成公共组件反而要传 tag 与文案两个
 * 参数，比复制更难读。真正需要统一的是**位置**（底栏同槽位），而这一点由两处都
 * 用 `PageActionBar` 保证。
 */
@Composable
private fun PackEmptyState(
    modifier: Modifier,
    contentInsets: WindowInsets,
    onBack: (() -> Unit)?,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_PACK_PAGE),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.b_pack_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag(TAG_PACK_EMPTY),
            )
        }

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
 * LanguageISO 的实际取值说明(不写入时给出"为什么")。
 *
 * 因为要取 `stringResource`,本函数是 `@Composable`;`ifEmpty` 是 inline 函数,
 * 故其 lambda 里可以直接调 composable。
 */
@Composable
private fun langLabel(volume: ComicVolume): String = when (volume.language) {
    LangChoice.Zh -> "zh"
    LangChoice.Ja -> "ja"
    LangChoice.Other -> volume.otherLangCode.ifEmpty { stringResource(R.string.pack_lang_other_empty) }
    LangChoice.Unset -> stringResource(R.string.pack_lang_unset)
    LangChoice.Unknown -> stringResource(R.string.pack_lang_unknown)
    LangChoice.Skip -> stringResource(R.string.pack_lang_skip)
}
