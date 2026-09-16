package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule

const val TAG_RULE_LEVEL_PREFIX = "rule-level-"
const val TAG_RULE_ADD = "rule-add"
const val TAG_RULE_REMOVE_PREFIX = "rule-remove-"
const val TAG_RULE_APPLY = "rule-apply"

/** 规则级 i 的字段选择器 testTag。 */
fun ruleLevelTag(i: Int) = "$TAG_RULE_LEVEL_PREFIX$i"

/** 规则级 i 的删除按钮 testTag。 */
fun ruleRemoveTag(i: Int) = "$TAG_RULE_REMOVE_PREFIX$i"

/**
 * 排序规则编辑器(验收第 7 项):多级排序可**增删改**,每级独立升/降序。
 *
 * 约束(取自 HANGOFF §4 / 规格 §2 约束 11):
 *  - 最多 4 级(达到上限时"添加"按钮禁用);
 *  - **至少保留 1 级**(只剩 1 级时删除按钮禁用);
 *  - 字段下拉顺序 = [SortField] 枚举声明顺序(产品定序,不得重排)。
 *
 * 编辑用**草稿**:点"应用排序"才落地(归档语义:编辑不实时生效)。
 */
@Composable
fun SortRuleEditor(
    rule: SortRule,
    onApply: (SortRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(rule) { mutableStateOf(rule.keys) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(TAG_SORT_RULE_EDITOR),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "排序规则（${draft.size}/${SortRule.MAX_LEVELS} 级）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row {
                TextButton(
                    onClick = { draft = draft + SortKey(SortField.NaturalName, true) },
                    enabled = draft.size < SortRule.MAX_LEVELS,
                    modifier = Modifier
                        .testTag(TAG_RULE_ADD)
                        .semantics { contentDescription = "添加排序级" },
                ) { Text("添加") }

                TextButton(
                    onClick = { onApply(SortRule(draft)) },
                    modifier = Modifier
                        .testTag(TAG_RULE_APPLY)
                        .semantics { contentDescription = "应用排序" },
                ) { Text("应用排序") }
            }
        }

        draft.forEachIndexed { index, key ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "第${index + 1}级",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 字段选择:用 FilterChip 平铺(避免 DropdownMenu 在测试中难以驱动,也更直观)
                SortField.entries.forEach { field ->
                    FilterChip(
                        selected = key.field == field,
                        onClick = { draft = draft.toMutableList().also { it[index] = key.copy(field = field) } },
                        label = { Text(field.label) },
                        modifier = Modifier.testTag("${ruleLevelTag(index)}-${field.name}"),
                    )
                }

                // 升降序切换
                AssistChip(
                    onClick = { draft = draft.toMutableList().also { it[index] = key.copy(ascending = !key.ascending) } },
                    label = { Text(if (key.ascending) "升序" else "降序") },
                    modifier = Modifier.semantics {
                        contentDescription = "第${index + 1}级${if (key.ascending) "升序" else "降序"}"
                    },
                )

                // 删除该级(至少保留 1 级)
                TextButton(
                    onClick = { draft = draft.toMutableList().also { it.removeAt(index) } },
                    enabled = draft.size > 1,
                    modifier = Modifier
                        .testTag(ruleRemoveTag(index))
                        .semantics { contentDescription = "删除第${index + 1}级" },
                ) { Text("删除") }
            }
        }
    }
}
