package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R
import com.pixfold.d1.domain.model.NameProposal
import com.pixfold.d1.domain.model.ProposalWarningType
import com.pixfold.d1.ui.components.IssueFilterRow
import com.pixfold.d1.ui.text.textOf

/**
 * 整页唯一可滚动容器的 tag(建议表就是整页的 LazyColumn)。
 * `performScrollToNode` 必须作用于**可滚动节点本身**(实测踩过)。
 */
const val TAG_PROPOSAL_LIST = "proposal-list"

const val TAG_FILTER_CONFLICTS = "filter-conflicts"
const val TAG_FILTER_ALL = "filter-all"
const val TAG_PROPOSAL_SUMMARY = "proposal-summary"

/** 建议表某行的输入框(即"逐项覆盖"入口)。 */
fun proposalFieldTag(id: String) = "proposal-name-$id"

/** 建议表某行的"恢复建议值"按钮;仅被覆盖时可用。 */
fun proposalResetTag(id: String) = "proposal-reset-$id"

/** 建议表某行的警告文本容器(供测试断言冲突可见)。 */
fun proposalWarningTag(id: String) = "proposal-warning-$id"

/** 建议表某行的容器。 */
fun proposalRowTag(id: String) = "proposal-row-$id"

/**
 * 建议表汇总 + 筛选(验收第 10 项要求"冲突与警告可见")。
 *
 * 抽成独立组件以便作为整页 LazyColumn 的一个 item —— **整页只有一个滚动容器**,
 * 避免"外层滚动 + 内层 LazyColumn"的高度竞争。(实测踩过:内层被挤到 0 高。)
 */
@Composable
fun ProposalSummary(
    total: Int,
    conflictCount: Int,
    warningOnlyCount: Int,
    conflictOnly: Boolean,
    onConflictOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 筛选条的文案与无障碍说明都在共享组件里解析(见 IssueFilterRow)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pluralStringResource(R.plurals.a_table_summary, total, total, conflictCount, warningOnlyCount),
            style = MaterialTheme.typography.labelMedium,
            color = if (conflictCount > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            modifier = Modifier.testTag(TAG_PROPOSAL_SUMMARY),
        )
        IssueFilterRow(
            issueCount = conflictCount + warningOnlyCount,
            issuesOnly = conflictOnly,
            onIssuesOnlyChange = onConflictOnlyChange,
            allTag = TAG_FILTER_ALL,
            issuesTag = TAG_FILTER_CONFLICTS,
        )
    }
}

/**
 * 建议表的一行:原路径 → 新名称 + 逐项覆盖 + 警告。
 *
 * 覆盖的显示规则(规格 §6.3):被覆盖的行**始终显示用户文本**([overrideText]),
 * 未覆盖的行显示 [NameProposal.proposedName];故上层改结构时覆盖项逐字不变 ——
 * 这是数据结构的性质,不靠 UI 记忆。
 *
 * @param overrideText 该行当前覆盖值;null = 未覆盖
 */
@Composable
fun ProposalRow(
    proposal: NameProposal,
    overrideText: String?,
    onOverride: (String, String) -> Unit,
    onResetOverride: (String) -> Unit,
) {
    val id = proposal.image.id
    val hasConflict = proposal.hasConflict
    // 要进 semantics{},故先在组合上下文里解析
    val resetCd = stringResource(R.string.a_table_restore_cd, id)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(proposalRowTag(id)),
    ) {
        Text(
            text = proposal.image.relPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = overrideText ?: proposal.proposedName,
                onValueChange = { onOverride(id, it) },
                singleLine = true,
                isError = hasConflict,
                label = {
                    Text(
                        stringResource(
                            if (proposal.isOverridden) {
                                R.string.a_table_new_name_overridden
                            } else {
                                R.string.a_table_new_name
                            },
                        ),
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(proposalFieldTag(id)),
            )

            TextButton(
                onClick = { onResetOverride(id) },
                enabled = proposal.isOverridden,
                modifier = Modifier
                    .testTag(proposalResetTag(id))
                    .semantics { contentDescription = resetCd },
            ) { Text(stringResource(R.string.a_table_restore)) }
        }

        if (proposal.warnings.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 2.dp)
                    .testTag(proposalWarningTag(id)),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                proposal.warnings.forEach { w ->
                    Text(
                        // 「· 」是符号前缀,不属于文案 —— 与已删除的 `plan_skip_reason_prefix`
                        // 同一判法(那两处改用了矢量图标,这里保留文字圆点,故仍拼接)。
                        text = "· ${textOf(w.message)}",
                        style = MaterialTheme.typography.labelSmall,
                        // 会导致跳过 -> error 色;仅警告 -> 次级色(视觉分级)
                        color = if (w.type == ProposalWarningType.Duplicate ||
                            w.type == ProposalWarningType.IllegalChar
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
