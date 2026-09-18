package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R
import com.pixfold.d1.domain.model.CasePolicy
import com.pixfold.d1.domain.model.ExtPolicy
import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.naming.addComponent
import com.pixfold.d1.domain.naming.moveComponent
import com.pixfold.d1.domain.naming.removeComponent
import com.pixfold.d1.domain.naming.toggleComponent
import com.pixfold.d1.domain.naming.updateComponent
import com.pixfold.d1.domain.naming.updateScheme

const val TAG_SCHEME_EDITOR = "scheme-editor"
const val TAG_SCHEME_ADD = "scheme-add"
const val TAG_SCHEME_SANITIZE = "scheme-sanitize"
const val TAG_SCHEME_ROOT_DIR = "scheme-root-dir"
const val TAG_SCHEME_INDEX_START = "scheme-index-start"
const val TAG_SCHEME_INDEX_PADDING = "scheme-index-padding"

/** 组件 i 的启用开关。 */
fun compToggleTag(i: Int) = "scheme-comp-toggle-$i"

/** 组件 i 的删除按钮。 */
fun compRemoveTag(i: Int) = "scheme-comp-remove-$i"

/** 组件 i 上移 / 下移按钮。 */
fun compMoveUpTag(i: Int) = "scheme-comp-up-$i"
fun compMoveDownTag(i: Int) = "scheme-comp-down-$i"

/** 组件 i 的类型下拉框。 */
fun compKindTag(i: Int) = "scheme-comp-kind-$i"

/** 组件 i 的分隔符输入框。 */
fun compSepTag(i: Int) = "scheme-comp-sep-$i"

/** 组件 i 的文本输入框(仅 Prefix / CustomText 有意义)。 */
fun compTextTag(i: Int) = "scheme-comp-text-$i"

/** 扩展名策略下拉框。 */
const val TAG_SCHEME_EXT_POLICY = "scheme-ext-policy"

/**
 * 命名结构编辑器(验收第 8 项:组件启用/顺序/分隔符/大小写/补零/扩展名策略可调)。
 *
 * 与 `SortRuleEditor` 同样的设计:**无草稿,单一事实来源** —— 每次改动立刻通过
 * [onChange] 上报新 [NamingState],由上层重新求值建议表。
 *
 * 覆盖(overrides)存放在 [NamingState] 内、**独立于 scheme**,
 * 故改结构不会动到用户已覆盖的名字(验收第 9 项,规格 §6.3)。
 */
@Composable
fun SchemeEditor(
    state: NamingState,
    onChange: (NamingState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = state.scheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(TAG_SCHEME_EDITOR),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "命名结构（${scheme.components.count { it.enabled }} 个组件启用）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(
                onClick = { onChange(addComponent(state, NameComponentKind.CustomText)) },
                modifier = Modifier
                    .testTag(TAG_SCHEME_ADD)
                    .semantics { contentDescription = "添加命名组件" },
            ) { Text("添加组件") }
        }

        // ---- 组件列表:启用 / 类型 / 分隔符 / 文本 / 顺序 / 删除 ----
        scheme.components.forEachIndexed { index, comp ->
            ComponentRow(
                index = index,
                component = comp,
                canRemove = scheme.components.size > 1,
                onChange = onChange,
                state = state,
            )
        }

        // ---- 全局选项:根目录名 / 起始序号 / 补零 / 扩展名策略 / 自动清洗 ----
        //
        // **分两行而非横向滚动**:手机上横向滚动会让靠后的控件落在视口之外,
        // 用户点不到(实测:sanitize 开关点不动,导致"可调"名不副实)。
        // 纵向空间在手机上更宽裕,故按语义分成"命名要素"与"策略开关"两行。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = scheme.rootDirName,
                onValueChange = { onChange(updateScheme(state, scheme.copy(rootDirName = it))) },
                label = { Text("根目录名") },
                singleLine = true,
                modifier = Modifier
                    .widthInChars(12)
                    .testTag(TAG_SCHEME_ROOT_DIR),
            )
            NumberField(
                label = "起始序号",
                value = scheme.indexStart,
                tag = TAG_SCHEME_INDEX_START,
                min = 1,
                onValue = { onChange(updateScheme(state, scheme.copy(indexStart = it))) },
            )
            NumberField(
                label = "补零位数",
                value = scheme.indexPadding,
                tag = TAG_SCHEME_INDEX_PADDING,
                min = 1,
                onValue = { onChange(updateScheme(state, scheme.copy(indexPadding = it))) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtPolicyField(
                value = scheme.extPolicy,
                onValue = { onChange(updateScheme(state, scheme.copy(extPolicy = it))) },
            )
            CasePolicyHint()
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "自动清洗",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = scheme.sanitize,
                    onCheckedChange = { onChange(updateScheme(state, scheme.copy(sanitize = it))) },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .testTag(TAG_SCHEME_SANITIZE)
                        .semantics { contentDescription = "自动清洗非法字符" },
                )
            }
        }
    }
}

/**
 * 单个组件的编辑卡片。
 *
 * **为什么分两行而不是横向滚动的一行**:手机上横向空间最稀缺 —— 若把
 * 类型/分隔符/文本/大小写/上移/下移/删除全排在一行,靠后的控件会落在视口之外,
 * 用户(和测试)都够不到(实测:上移按钮不可见,导致"顺序可调"实际不可操作)。
 * 改为两行后,屏幕宽度内全部可达;纵向空间在手机上更宽裕。
 */
/** 说明自动清洗的作用,避免用户误以为它总会去重。 */
@Composable
private fun CasePolicyHint() {
    Text(
        text = "清洗把非法字符替换为 _ ；清洗后仍可能重名",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ComponentRow(
    index: Int,
    component: NameComponent,
    canRemove: Boolean,
    state: NamingState,
    onChange: (NamingState) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 第一行:序号 + 启用 + 类型 + 删除
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = component.enabled,
                    onCheckedChange = { onChange(toggleComponent(state, index)) },
                    modifier = Modifier
                        .testTag(compToggleTag(index))
                        .semantics { contentDescription = "启用组件 ${index + 1}" },
                )
                KindField(
                    value = component.kind,
                    index = index,
                    onValue = { kind -> onChange(updateComponent(state, index) { it.copy(kind = kind) }) },
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onChange(removeComponent(state, index)) },
                    enabled = canRemove,
                    modifier = Modifier
                        .testTag(compRemoveTag(index))
                        .semantics { contentDescription = "删除组件 ${index + 1}" },
                ) { Text("删除") }
            }

            // 第二行:分隔符 + 文本(按需)+ 大小写 + 顺序
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = component.separatorBefore,
                    onValueChange = { sep ->
                        onChange(updateComponent(state, index) { it.copy(separatorBefore = sep) })
                    },
                    label = { Text("分隔符") },
                    singleLine = true,
                    modifier = Modifier
                        .widthInChars(7)
                        .testTag(compSepTag(index)),
                )

                // 文本:仅 Prefix / CustomText 有意义
                if (component.kind == NameComponentKind.Prefix ||
                    component.kind == NameComponentKind.CustomText
                ) {
                    OutlinedTextField(
                        value = component.text,
                        onValueChange = { t -> onChange(updateComponent(state, index) { it.copy(text = t) }) },
                        label = { Text("文本") },
                        singleLine = true,
                        modifier = Modifier
                            .widthInChars(9)
                            .testTag(compTextTag(index)),
                    )
                }

                CaseField(
                    value = component.casePolicy,
                    index = index,
                    onValue = { p -> onChange(updateComponent(state, index) { it.copy(casePolicy = p) }) },
                )

                Spacer(Modifier.weight(1f))

                // 顺序可调:均为 ≥48dp 触控目标(IconButton 默认 48dp)
                IconButton(
                    onClick = { onChange(moveComponent(state, index, -1)) },
                    modifier = Modifier
                        .testTag(compMoveUpTag(index))
                        .semantics { contentDescription = "上移组件 ${index + 1}" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_drop_up),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { onChange(moveComponent(state, index, +1)) },
                    modifier = Modifier
                        .testTag(compMoveDownTag(index))
                        .semantics { contentDescription = "下移组件 ${index + 1}" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 组件类型下拉。用 ExposedDropdownMenuBox 以符合 M3。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindField(value: NameComponentKind, index: Int, onValue: (NameComponentKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("组件") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .widthInChars(12)
                .testTag(compKindTag(index)),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NameComponentKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.label) },
                    onClick = {
                        onValue(kind)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaseField(value: CasePolicy, index: Int, onValue: (CasePolicy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("大小写") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .widthInChars(9)
                .testTag("scheme-comp-case-$index"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CasePolicy.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = {
                        onValue(p)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtPolicyField(value: ExtPolicy, onValue: (ExtPolicy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("扩展名") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .widthInChars(12)
                .testTag(TAG_SCHEME_EXT_POLICY),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExtPolicy.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = {
                        onValue(p)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 仅接受数字的小输入框;非法/越界输入回落到 [min]。 */
@Composable
private fun NumberField(
    label: String,
    value: Int,
    tag: String,
    min: Int,
    onValue: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            val n = text.filter { it.isDigit() }.toIntOrNull()
            if (n != null && n >= min) onValue(n)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .widthInChars(8)
            .testTag(tag),
    )
}

/** 给 OutlinedTextField 一个与字符数相关的固定宽度,避免占满整行又保持可用。 */
private fun Modifier.widthInChars(chars: Int): Modifier = this.width((chars * 9 + 32).dp)
