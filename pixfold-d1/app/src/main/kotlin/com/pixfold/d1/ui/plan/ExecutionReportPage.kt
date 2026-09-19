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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.plan.ExecEntry
import com.pixfold.d1.domain.plan.ExecOutcome
import com.pixfold.d1.domain.plan.ExecutionReport

const val TAG_REPORT_PAGE = "report-page"
const val TAG_REPORT_LIST = "report-list"
const val TAG_REPORT_SUMMARY = "report-summary"
const val TAG_REPORT_UNDO = "report-undo"
const val TAG_REPORT_UNDO_MESSAGE = "report-undo-message"
const val TAG_REPORT_EMPTY = "report-empty"

/** 「撤销本次执行」按钮文案(UI 与测试共用,避免两处各写一遍)。 */
const val UNDO_BUTTON_LABEL = "撤销本次执行"

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
 */
@Composable
fun ExecutionReportPage(
    report: ExecutionReport,
    undoMessage: String?,
    onUndo: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_REPORT_PAGE),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = report.kind.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = report.summaryLine,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_REPORT_SUMMARY),
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        }

        if (report.entries.isEmpty()) {
            Text(
                text = "本次没有任何操作（计划为空）",
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
            items(report.entries.size) { i ->
                ReportRow(report.entries[i], i)
            }

            item(key = "actions") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onUndo,
                            enabled = report.canUndo,
                            modifier = Modifier
                                .testTag(TAG_REPORT_UNDO)
                                .semantics { contentDescription = UNDO_BUTTON_LABEL },
                        ) { Text(UNDO_BUTTON_LABEL) }

                        Button(
                            onClick = onDone,
                            modifier = Modifier.semantics { contentDescription = "完成" },
                        ) { Text("完成") }
                    }
                    if (!report.canUndo) {
                        Text(
                            text = "没有成功执行的操作，无需撤销",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (undoMessage != null) {
                        Text(
                            text = undoMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.testTag(TAG_REPORT_UNDO_MESSAGE),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportRow(entry: ExecEntry, index: Int) {
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
                text = entry.outcome.label,
                style = MaterialTheme.typography.labelMedium,
                color = outcomeColor(entry.outcome),
                modifier = Modifier
                    .testTag(reportOutcomeTag(index))
                    .semantics { contentDescription = "结果：${entry.outcome.label}" },
            )
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        if (entry.reason != null) {
            Text(
                text = "原因：${entry.reason}",
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
