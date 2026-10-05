package com.pixfold.d1.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pixfold.d1.R
import com.pixfold.d1.domain.plan.ExecEntry
import com.pixfold.d1.domain.plan.ExecOutcome
import com.pixfold.d1.domain.plan.ExecutionReport
import com.pixfold.d1.domain.plan.UndoScope
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.ui.components.IssueFilterRow
import com.pixfold.d1.ui.components.PageActionBar
import com.pixfold.d1.ui.text.WarnLine
import com.pixfold.d1.ui.text.textOf
import com.pixfold.d1.ui.text.textOfOrNull
import com.pixfold.d1.ui.text.warnIcon
import com.pixfold.d1.ui.theme.dangerActionButtonColors

const val TAG_REPORT_PAGE = "report-page"
const val TAG_REPORT_LIST = "report-list"
const val TAG_REPORT_SUMMARY = "report-summary"
const val TAG_REPORT_UNDO = "report-undo"
const val TAG_REPORT_DONE = "report-done"
const val TAG_REPORT_UNDO_MESSAGE = "report-undo-message"
const val TAG_REPORT_EMPTY = "report-empty"
const val TAG_REPORT_FILTER_ALL = "report-filter-all"
const val TAG_REPORT_FILTER_ISSUES = "report-filter-issues"
const val TAG_REPORT_FILTER_NONE = "report-filter-none"
const val TAG_UNDO_SCOPE_DIALOG = "undo-scope-dialog"
const val TAG_UNDO_SCOPE_THIS_RUN = "undo-scope-this-run"
const val TAG_UNDO_SCOPE_EVERYTHING = "undo-scope-everything"
const val TAG_UNDO_SCOPE_CANCEL = "undo-scope-cancel"

/** 报告里第 i 行的 testTag。 */
fun reportRowTag(index: Int) = "report-row-$index"

/** 报告行的结果标签 testTag。 */
fun reportOutcomeTag(index: Int) = "report-row-$index-outcome"

/**
 * 执行报告页(验收第 17 项:成功 / 跳过 / 失败 + **撤销入口**)。
 *
 * 三类结果**各自带原因**;撤销入口在无可撤销项时**禁用**并有说明 ——
 * 不显示一个点了没反应的假按钮(P4/P5 的一贯做法)。
 *
 * 撤销是 mock(规格 §9.2),界面必须**如实**说明,不能显示"已撤销"这种
 * D1 做不到的结论。
 *
 * ## 撤销前先问范围(用户 2026-10-04,m04079 第③项)
 *
 * 一次成功的执行可能是**新建**,也可能是**覆盖掉用户原本已存在的文件**。
 * 后者删掉就等于把用户原有的内容一并抹掉(已被覆盖,找不回来),所以
 * [ExecutionReport.needsUndoWarning] 为真时,点「撤销」**不直接执行**,
 * 而是先弹 [UndoScopeDialog] 让用户选范围:
 *
 *  - **安全档**「仅撤销本次生成的」→ [UndoScope.ThisRun](默认,也不删覆盖项);
 *  - **危险档**「全部删除(含被覆盖的)」→ [UndoScope.Everything];
 *  - **取消** → 关掉对话框,什么都不做。
 *
 * 没有覆盖项时**不弹**:两种范围的结果逐字相同,弹框只是多一次无谓点击。
 *
 * ## 为什么 [onUndoScope] 必须带 [UndoScope] 参数
 *
 * 用户选的档位**必须**传到领域层。若这里仍收 `() -> Unit`,界面就能让用户确认
 * "全部删除"而调用方照旧按安全档回滚 —— 警告弹了、选择也做了,结果什么都没变,
 * 那比不弹更糟(用户以为自己授权了删除)。故形参显式带上范围,**没有默认值**:
 * 每个调用点都得表态,不存在"忘了传就静默走安全档"的路径。
 */
@Composable
fun ExecutionReportPage(
    report: ExecutionReport,
    undoMessage: Msg?,
    onUndoScope: (UndoScope) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    // 筛选状态是**本页的**显示偏好。默认「全部」是硬约束而不是口味:
    // 报告是「刚才到底跑了什么」的凭证,默认收窄会让用户以为成功项根本没执行
    // (验收第 17 项要的是"成功/跳过/失败三类都看得见")。
    var issuesOnly by remember { mutableStateOf(false) }

    // 撤销范围警告是否正开着。用 `remember` 持有:它是纯显示状态,
    // 对话框关掉就该复位(不跨报告残留)。
    var scopeDialogOpen by remember { mutableStateOf(false) }

    // 台账 §3:56 行的报告里通常只有少数是「跳过/失败」,却要滚完全部。
    // 成功项无需逐条核对,所以「仅问题」= 非成功。
    val issueCount = report.skippedCount + report.failedCount

    // ⚠️ 过滤后**必须保留原始下标**:报告 KDoc 明示 entries 顺序 = 计划顺序,
    // 「报告第 3 行」要能与「计划第 3 行」对得上。用 `withIndex()` 存原始下标,
    // 只把可见序号当渲染顺序,两者不能混。
    val shown = report.entries.withIndex().filter { !issuesOnly || it.value.outcome != ExecOutcome.Succeeded }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_REPORT_PAGE),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = textOf(report.kind.titleKey),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = textOf(report.summary),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_REPORT_SUMMARY),
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        }

        // 筛选条常驻(与计划页同一形态):报告 56 行,滚到一半想改筛选不该被迫滚回顶部。
        if (report.entries.isNotEmpty()) {
            IssueFilterRow(
                issueCount = issueCount,
                issuesOnly = issuesOnly,
                onIssuesOnlyChange = { issuesOnly = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                allTag = TAG_REPORT_FILTER_ALL,
                issuesTag = TAG_REPORT_FILTER_ISSUES,
            )
        }

        if (report.entries.isEmpty()) {
            Text(
                text = stringResource(R.string.report_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .testTag(TAG_REPORT_EMPTY),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_REPORT_LIST),
        ) {
            if (issuesOnly && shown.isEmpty()) {
                // 全绿时点「仅问题」会得到空列表。必须说清是**筛空了**而不是报告丢了。
                item(key = "filter-none") {
                    Text(
                        text = stringResource(R.string.common_filter_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(16.dp)
                            .testTag(TAG_REPORT_FILTER_NONE),
                    )
                }
            }
            items(shown, key = { it.index }) { indexed ->
                ReportRow(indexed.value, indexed.index)
            }
        }

        // 终局动作常驻底栏(A 侧整库报告有 56 行,原先要滚到底才够得到撤销入口)。
        // 左 = 退回(撤销本次执行),右 = 推进(完成)—— 与全站底栏一致(用户 2026-09-25)。
        PageActionBar(
            leading = {
                // ⚠️ 无障碍文案必须在 `semantics {}` **外面**解析(那个 lambda 不是 `@Composable`)。
                val undoLabel = stringResource(R.string.report_undo)
                // 撤销是**破坏性 / 回退类**动作,用「危险动作」配色着底
                // (用户 2026-09-25:「步骤3的撤销按钮背景改红色」;
                //  2026-09-25 收紧为「表示这是个危险性质的按钮」)。
                // 深浅两种模式要取 error 族的**不同档位**:浅色 `error`(#B3261E 深红)、
                // 深色 `errorContainer`(#8C1D18 深红)—— 深色的 `error` 是 #F2B8B5 淡粉,
                // 当底色不像危险动作。完整理由见 `dangerActionButtonColors()` 的 KDoc。
                Button(
                    onClick = {
                        // 有覆盖项 ⇒ 先问范围,不直接执行(用户 m04079 第③项)。
                        if (report.needsUndoWarning) scopeDialogOpen = true else onUndoScope(UndoScope.ThisRun)
                    },
                    enabled = report.canUndo,
                    colors = dangerActionButtonColors(),
                    modifier = Modifier
                        .testTag(TAG_REPORT_UNDO)
                        .semantics { contentDescription = undoLabel },
                ) { Text(undoLabel) }
            },
            trailing = {
                val done = stringResource(R.string.report_done)
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .testTag(TAG_REPORT_DONE)
                        .semantics { contentDescription = done },
                ) { Text(done) }
            },
            extraContent = {
                if (!report.canUndo) {
                    Text(
                        text = stringResource(R.string.report_nothing_to_undo),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                textOfOrNull(undoMessage)?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag(TAG_REPORT_UNDO_MESSAGE),
                    )
                }
            },
        )
    }

    if (scopeDialogOpen) {
        UndoScopeDialog(
            overwrittenCount = report.overwrittenCount,
            onChoose = { scope ->
                scopeDialogOpen = false
                // ⚠️ 选了危险档就必须让调用方**知道**选了它 —— 否则界面上让用户
                // 确认了"全部删除",实际回滚的仍是安全档那一份,等于白警告一场。
                onUndoScope(scope)
            },
            onCancel = { scopeDialogOpen = false },
        )
    }
}

/**
 * 撤销范围警告(用户 m04079 第③项)。
 *
 * 「警告 + 三选一 + 按钮颜色区分 + 取消放最后」这四条都是用户明说的,逐条对应:
 *
 *  1. **警告**:标题带警示图标、正文说清"覆盖掉的内容已经找不回来了";
 *  2. **三选一**:两个可执行档 + 取消,没有第四条路;
 *  3. **颜色区分**:两个执行档都是**破坏性**动作 ⇒ [dangerActionButtonColors]
 *     (与底栏撤销按钮同一套深红),取消是 [TextButton](无底色)⇒ 一眼能分出
 *     "哪个是危险动作、哪个只是关掉";
 *  4. **取消在最后**:按钮顺序 = 安全档 → 危险档 → 取消。
 *
 * 为什么危险档排在安全档**后面**却仍在取消**前面**:两档都是"会删东西"的动作,
 * 按危险程度递增排列(删本次产物 → 连用户原有的也删),最后才是非动作的取消。
 *
 * ⚠️ 两个 Button 用的是 `AlertDialog` 的 `confirmButton` / `dismissButton` 槽位吗?
 * **不是**。Material3 的 `AlertDialog` 只有这两个槽位,摆不下三个同级按钮,
 * 且槽位位置会随平台排版改变"取消在最后"这条明说的要求。故这里用
 * `Dialog` + 自绘 `Surface`/`Column`,顺序由我们的布局决定。
 */
@Composable
private fun UndoScopeDialog(
    overwrittenCount: Int,
    onChoose: (UndoScope) -> Unit,
    onCancel: () -> Unit,
) {
    // ⚠️ 文案一律在 `Dialog` 的 lambda **外面**解析(理由同 `semantics {}`:
    // 那些 lambda 不是 `@Composable`,里面调 `stringResource` 编译失败)。
    val title = stringResource(R.string.report_undo_warning_title)
    val body = pluralStringResource(
        R.plurals.report_undo_warning_body,
        overwrittenCount,
        overwrittenCount,
    )
    val thisRun = stringResource(R.string.report_undo_this_run)
    val everything = stringResource(R.string.report_undo_everything)
    val cancel = stringResource(R.string.common_cancel)

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.testTag(TAG_UNDO_SCOPE_DIALOG),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 警示由矢量图标承担(2026-10-05 用户要求,资源里已无 ⚠️ 前缀)。
                // 标题是 `headlineSmall`,故图标放大到 24dp 与之齐平;`Text` 节点本身
                // 仍只含标题文本,`UndoScopeDialogTest` 的 `onNodeWithText` 不受影响。
                WarnLine(
                    icon = warnIcon(R.string.report_undo_warning_title) ?: R.drawable.ic_warning_triangle,
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                    iconSize = 24.dp,
                )
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
                // 顺序即语义:安全 → 危险 → 取消(用户明说"取消放到最后边")。
                Button(
                    onClick = { onChoose(UndoScope.ThisRun) },
                    colors = dangerActionButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_UNDO_SCOPE_THIS_RUN)
                        .semantics { contentDescription = thisRun },
                ) { Text(thisRun) }
                Button(
                    onClick = { onChoose(UndoScope.Everything) },
                    colors = dangerActionButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_UNDO_SCOPE_EVERYTHING)
                        .semantics { contentDescription = everything },
                ) { Text(everything) }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_UNDO_SCOPE_CANCEL)
                        .semantics { contentDescription = cancel },
                ) { Text(cancel) }
            }
        }
    }
}

@Composable
private fun ReportRow(entry: ExecEntry, index: Int) {
    // ⚠️ `semantics {}` 的 lambda 不是 `@Composable`,故无障碍文案必须在外面取好。
    val outcomeCd = stringResource(R.string.report_outcome_cd, textOf(entry.outcome.textKey))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(reportRowTag(index)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = textOf(entry.outcome.textKey),
                style = MaterialTheme.typography.labelMedium,
                color = outcomeColor(entry.outcome),
                modifier = Modifier
                    .testTag(reportOutcomeTag(index))
                    .semantics { contentDescription = outcomeCd },
            )
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        textOfOrNull(entry.reason)?.let { reason ->
            Text(
                text = stringResource(R.string.report_reason, reason),
                style = MaterialTheme.typography.labelSmall,
                color = outcomeColor(entry.outcome),
            )
        }
        if (entry.createdPath != null) {
            Text(
                text = "→ ${entry.createdPath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun outcomeColor(outcome: ExecOutcome) = when (outcome) {
    ExecOutcome.Succeeded -> MaterialTheme.colorScheme.primary
    ExecOutcome.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
    ExecOutcome.Failed -> MaterialTheme.colorScheme.error
}
