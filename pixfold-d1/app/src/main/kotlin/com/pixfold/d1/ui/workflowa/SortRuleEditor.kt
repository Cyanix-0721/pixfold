package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R
import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule

const val TAG_RULE_LEVEL_PREFIX = "rule-level-"
const val TAG_RULE_ADD = "rule-add"
const val TAG_RULE_REMOVE_PREFIX = "rule-remove-"

/** 规则级 i 的字段选择器 testTag。 */
fun ruleLevelTag(i: Int) = "$TAG_RULE_LEVEL_PREFIX$i"

/** 规则级 i 中「某字段」按钮的 testTag。 */
fun fieldTag(i: Int, field: SortField) = "${ruleLevelTag(i)}-${field.name}"

/**
 * 规则级 i 中「方向三角」的 testTag;**仅当该字段被选中时存在**。
 * 查询需用 `useUnmergedTree = true`(位于可点击 Chip 的合并语义内)。
 */
fun directionTag(i: Int, field: SortField) = "rule-direction-$i-${field.name}"

/** 规则级 i 的删除按钮 testTag。 */
fun ruleRemoveTag(i: Int) = "$TAG_RULE_REMOVE_PREFIX$i"

/**
 * 排序规则编辑器(验收第 7 项):多级排序可**增删改**,每级独立升/降序。
 *
 * 约束(取自 HANGOFF §4 / 规格 §2 约束 11):
 *  - 最多 4 级(达到上限时"添加"按钮禁用);
 *  - **至少保留 1 级**(只剩 1 级时删除按钮禁用);
 *  - 字段顺序 = [SortField] 枚举声明顺序(产品定序,不得重排)。
 *
 * **变动即自动应用**(用户 2026-09-17 指定):不再有"应用排序"按钮与草稿态 ——
 * 每次改动(换字段 / 翻转方向 / 增删级)立即通过 [onRuleChange] 上报。
 * 注:此前的"草稿 + 手动应用"取自归档语义(规格 §5.4),现按用户决定覆盖。
 *
 * **升降序交互(用户 2026-09-17 指定,替换原独立切换按钮)**:
 *  - 每级默认**升序**;
 *  - **再次点击已选中的字段** → 切换升/降序;点击**其他**字段 → 换字段并回到默认升序;
 *  - 用**实心三角**表示方向(正三角=升序、倒三角=降序),**仅显示在选中字段名之后**;
 *  - 三角为官方 Material Symbols `arrow_drop_up/down` 矢量资源(与图钉同策略:用矢量,不自绘)。
 *  这样把"选谁"和"什么方向"合并在一个控件里,少一行控件、少一次点击。
 *
 * @param scopeLabel 该规则的作用范围说明(批次默认 / 本组独立),
 *   让用户知道当前规则会作用于哪些集合(§5.4)。
 */
@Composable
fun SortRuleEditor(
    rule: SortRule,
    onRuleChange: (SortRule) -> Unit,
    modifier: Modifier = Modifier,
    scopeLabel: String? = null,
) {
    // 无草稿:直接以传入规则为唯一事实来源,任何变动立刻上报(单一数据源,避免双份状态不同步)
    val draft = rule.keys
    fun emit(keys: List<SortKey>) = onRuleChange(SortRule(keys))

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
            Column {
                Text(
                    text = "排序规则（${draft.size}/${SortRule.MAX_LEVELS} 级）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (scopeLabel != null) {
                    Text(
                        text = scopeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row {
                TextButton(
                    onClick = { emit(draft + SortKey(SortField.NaturalName, true)) },
                    enabled = draft.size < SortRule.MAX_LEVELS,
                    modifier = Modifier
                        .testTag(TAG_RULE_ADD)
                        .semantics { contentDescription = "添加排序级" },
                ) { Text("添加") }

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

                SortField.entries.forEach { field ->
                    val selected = key.field == field
                    val direction = if (key.ascending) "升序" else "降序"

                    FilterChip(
                        selected = selected,
                        onClick = {
                            emit(draft.toMutableList().also {
                                it[index] = if (selected) {
                                    // 再次点击已选中字段 -> 只翻转方向,不换字段
                                    key.copy(ascending = !key.ascending)
                                } else {
                                    // 换字段 -> 回到默认升序
                                    SortKey(field, true)
                                }
                            })
                        },
                        label = { Text(field.label) },
                        // 方向三角只挂在**选中**字段之后(用户指定)
                        trailingIcon = if (selected) {
                            {
                                Icon(
                                    painter = painterResource(
                                        if (key.ascending) {
                                            R.drawable.ic_arrow_drop_up
                                        } else {
                                            R.drawable.ic_arrow_drop_down
                                        },
                                    ),
                                    contentDescription = null, // 方向由下方 semantics 统一播报
                                    modifier = Modifier
                                        .size(16.dp)
                                        .testTag(directionTag(index, field)),
                                )
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .testTag(fieldTag(index, field))
                            .semantics {
                                contentDescription = if (selected) {
                                    "第${index + 1}级 ${field.label} $direction"
                                } else {
                                    "第${index + 1}级 ${field.label}"
                                }
                            },
                    )
                }

                // 删除该级(至少保留 1 级)
                TextButton(
                    onClick = { emit(draft.toMutableList().also { it.removeAt(index) }) },
                    enabled = draft.size > 1,
                    modifier = Modifier
                        .testTag(ruleRemoveTag(index))
                        .semantics { contentDescription = "删除第${index + 1}级" },
                ) { Text("删除") }
            }
        }
    }
}
