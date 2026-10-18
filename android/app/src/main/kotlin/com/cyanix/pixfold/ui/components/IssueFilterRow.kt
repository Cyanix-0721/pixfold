package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R

/**
 * 「全部 / 仅问题(N)」筛选条 —— **全站唯一实现**。
 *
 * **为什么抽成共享组件**:台账 §3(P1)记的正是「建议表已有『仅问题 / 全部』筛选,
 * 计划页与报告页都没有」—— 即同一种交互在三处各写一遍,必然漂移。现在命名页
 * ([com.cyanix.pixfold.ui.workflowa.ProposalSummary])、计划页
 * ([com.cyanix.pixfold.ui.workflowa.RenamePlanPage])、报告页
 * ([com.cyanix.pixfold.ui.plan.ExecutionReportPage]) 共用这一份。
 *
 * **调用方必须自己决定的两件事**(组件不替它猜):
 * ① **N 是谁** —— 各页对「问题」的定义不同:命名页 = 冲突 + 仅警告,
 *    计划页 = 被跳过的操作数,报告页 = 跳过 + 失败。组件只负责显示这个数。
 * ② **过滤后如何保留原始索引** —— 报告行/计划行的 testTag 是「原始序号」
 *    (报告 KDoc 明示「报告第 3 行与计划第 3 行对得上」),故过滤时**不能**
 *    把可见序号当成行号。做法见 [ExecutionReportPage] 的 `withIndex()`。
 *
 * 筛选状态由**调用方**持有(本组件无状态)。这是本项目的状态提升约定
 * (见 `NamingPage.kt`):本地状态权威 + 初值取自上层 + 每次变更上报。
 *
 * 无障碍文案在这里解析(而不是传参),因为它是**与页面无关**的固定文案;
 * 同时 `semantics {}` 的 lambda 不是 `@Composable`,必须在外面取好。
 */
@Composable
fun IssueFilterRow(
    issueCount: Int,
    issuesOnly: Boolean,
    onIssuesOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    allTag: String,
    issuesTag: String,
) {
    val allCd = stringResource(R.string.common_filter_all_cd)
    val issuesCd = stringResource(R.string.common_filter_issues_cd)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = !issuesOnly,
            onClick = { onIssuesOnlyChange(false) },
            label = { Text(stringResource(R.string.common_filter_all)) },
            modifier = Modifier
                .testTag(allTag)
                .semantics { contentDescription = allCd },
        )
        FilterChip(
            selected = issuesOnly,
            onClick = { onIssuesOnlyChange(true) },
            label = { Text(stringResource(R.string.common_filter_issues, issueCount)) },
            modifier = Modifier
                .testTag(issuesTag)
                .semantics { contentDescription = issuesCd },
        )
    }
}
