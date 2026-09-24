package com.pixfold.d1.ui.workflowa

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R
import com.pixfold.d1.domain.model.CasePolicy
import com.pixfold.d1.domain.model.ExtPolicy
import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingScheme.Companion.DEFAULT_REPLACEMENT
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.naming.addComponent
import com.pixfold.d1.domain.naming.moveComponent
import com.pixfold.d1.domain.naming.clearRootDirNameOverride
import com.pixfold.d1.domain.naming.removeComponent
import com.pixfold.d1.domain.naming.sanitizeName
import com.pixfold.d1.domain.naming.setRootDirNameOnScheme
import com.pixfold.d1.domain.naming.toggleComponent
import com.pixfold.d1.domain.naming.updateComponent
import com.pixfold.d1.domain.naming.updateScheme

/** 编辑器**完整内容**(展开时才存在;折叠态下不在组合树里)。 */
const val TAG_SCHEME_EDITOR = "scheme-editor"

/** 折叠栏容器(常驻,不随列表滚动)。 */
const val TAG_SCHEME_BAR = "scheme-bar"

/** 折叠栏的点击目标(展开 / 收起)。 */
const val TAG_SCHEME_TOGGLE = "scheme-toggle"

const val TAG_SCHEME_ADD = "scheme-add"
const val TAG_SCHEME_SANITIZE = "scheme-sanitize"
const val TAG_SCHEME_ROOT_DIR = "scheme-root-dir"
const val TAG_SCHEME_INDEX_START = "scheme-index-start"
const val TAG_SCHEME_INDEX_PADDING = "scheme-index-padding"
const val TAG_SCHEME_REPLACEMENT = "scheme-replacement"
const val TAG_SCHEME_REPLACEMENT_RESET = "scheme-replacement-reset"

/** 「清洗」说明对话框里的**实时示例**文案(用真实 sanitizeName 算出,不写死)。 */
const val TAG_SCHEME_SANITIZE_EXAMPLE = "scheme-sanitize-example"

/** "自动清洗"说明入口(圆形感叹号图标)。 */
const val TAG_SCHEME_SANITIZE_INFO = "scheme-sanitize-info"

/** 根目录名跟随状态标签 / 恢复跟随按钮。 */
const val TAG_ROOT_DIR_FOLLOW_LABEL = "root-dir-follow-label"
const val TAG_ROOT_DIR_FOLLOW_RESET = "root-dir-follow-reset"

/** 说明对话框及其确认按钮。 */
const val TAG_SCHEME_SANITIZE_INFO_DIALOG = "scheme-sanitize-info-dialog"
const val TAG_SCHEME_SANITIZE_INFO_OK = "scheme-sanitize-info-ok"

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
 * 命名结构编辑器的**折叠摘要栏**(常驻在命名页顶部,不随建议表滚动)。
 *
 * 左侧一行摘要([summary],由 `namingSummaryLine` 用**真实建议名**做样本),右侧一个
 * 箭头指示当前展开态;整行可点,点击切换展开 / 收起。
 *
 * **为什么默认折叠**(P0,2026-09-24):真机(361dp × 794dp)实测展开态的编辑器
 * 高达 **692dp = 视口的 87%**,首屏连一条建议行都放不下 —— 而"预览是主工作区"
 * (约束 3)要求用户一进页面就能看到建议名。折叠后固定区约 140dp,首屏可容 6~7 行。
 *
 * 展开态的编辑器**不作为固定区块**,而是插进建议表这个**唯一滚动容器**的头部:
 * 否则编辑器增长(用户加组件)会再次把主工作区挤到 0 高,那正是 P0 的成因。
 *
 * @param expanded 当前展开态(由上层持有,故切换集合 / 重组都不丢)。
 * @param onToggle 点击回调(上层翻转展开态)。
 */
@Composable
fun SchemeSummaryBar(
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_SCHEME_BAR),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 触控目标 ≥48dp(约束 7);摘要行本身只有一行文字,靠最小高度撑开
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp)
                .testTag(TAG_SCHEME_TOGGLE)
                .semantics {
                    contentDescription = if (expanded) "收起命名结构" else "展开命名结构"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 复用既有箭头资源:展开时朝上(提示"点它收起"),折叠时朝下
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down,
                ),
                contentDescription = null, // 语义由整行的 contentDescription 统一播报
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

/**
 * 命名结构编辑器(验收第 8 项:组件启用/顺序/分隔符/大小写/补零/扩展名策略可调)。
 *
 * 与 `SortRuleEditor` 同样的设计:**无草稿,单一事实来源** —— 每次改动立刻通过
 * [onChange] 上报新 [NamingState],由上层重新求值建议表。
 *
 * 覆盖(overrides)存放在 [NamingState] 内、**独立于 scheme**,
 * 故改结构不会动到用户已覆盖的名字(验收第 9 项,规格 §6.3)。
 *
 * **只在展开态才组合**(折叠态由 [SchemeSummaryBar] 承担入口,P0,2026-09-24)。
 */
@Composable
fun SchemeEditor(
    state: NamingState,
    onChange: (NamingState) -> Unit,
    modifier: Modifier = Modifier,
    effective: NamingScheme = state.scheme,
) {
    // 显示与编辑一律用**生效结构**:根目录名默认跟随集合,
    // 用户改过则以用户的为准(判定内聚在 effectiveScheme,见 NamingState 注释)。
    val scheme = effective

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
                onValueChange = { onChange(setRootDirNameOnScheme(state, it)) },
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

        // 根目录名的跟随状态:默认跟随集合;用户改过则提示并可一键恢复跟随
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (state.hasRootDirNameOverride) {
                    "根目录名：已手动指定（不再跟随集合）"
                } else {
                    "根目录名：跟随当前集合"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.hasRootDirNameOverride) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag(TAG_ROOT_DIR_FOLLOW_LABEL),
            )
            TextButton(
                onClick = { onChange(clearRootDirNameOverride(state)) },
                enabled = state.hasRootDirNameOverride,
                modifier = Modifier
                    .testTag(TAG_ROOT_DIR_FOLLOW_RESET)
                    .semantics { contentDescription = "恢复根目录名跟随集合" },
            ) { Text("跟随集合") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtPolicyField(
                value = scheme.extPolicy,
                onValue = { onChange(updateScheme(state, scheme.copy(extPolicy = it))) },
            )
            // 把"自动清洗"整组推到右侧;说明改由图标承载后,这里不再需要长文案,
            // weight 得以正常吸收剩余空间(此前长文案会把它压到 0,开关被挤出屏幕)。
            Spacer(Modifier.weight(1f))

            Text(
                text = "自动清洗",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 替换字符放在**说明对话框内**(而非再加一行):
            // 编辑器展开时在真机上高达 ~692dp(占视口 87%),再加一行会把它顶得更高
            // —— 折叠摘要栏(P0 修复,2026-09-24)已经把"主工作区被挤出屏幕"这个后果
            // 消掉了,但"不给一个低频字段再加一行高度"这个取舍仍然成立。
            // 放进对话框既零高度成本,也与「清洗规则」的说明同处一室,语义更顺。
            // 历史注:首版曾加成行内新行,当时展开态常驻,加行后建议行完全不组合、
            // 14 项既有测试失败 —— 这条教训促成了后来的折叠修复。
            SanitizeInfoButton(
                scheme = scheme,
                onScheme = { onChange(updateScheme(state, it)) },
            )
            Switch(
                checked = scheme.sanitize,
                onCheckedChange = { onChange(updateScheme(state, scheme.copy(sanitize = it))) },
                modifier = Modifier
                    .testTag(TAG_SCHEME_SANITIZE)
                    .semantics { contentDescription = "自动清洗非法字符" },
            )
        }
    }
}

/**
 * "自动清洗"的说明文案。
 *
 * 用**原始字符串**:它把非法字符集里的 `\` 与 `"` 原样保留(无需转义),
 * 且**不含跨行拼接** —— 后者会被 lint 的 `TextConcatSpace` 判为"可能漏空格"
 * (中文其实不需要空格,但项目要求 lint 零告警,故从结构上避免)。
 */
/**
 * 清洗示例的**输入**名(含 `:` 与 `?` 两个非法字符 + 扩展名)。
 *
 * 选它是因为它同时演示两件事:非法字符被替换、**扩展名也参与清洗**(§6.1 第 7 步)。
 */
private const val SANITIZE_EXAMPLE_INPUT = "封:面?.jpg"

private val SANITIZE_EXPLANATION = """
    开启后，文件名中的非法字符（\ / : * ? " < > | 以及控制字符）会被替换为「_」。

    注意：清洗只替换字符，「不会去重」—— 清洗后不同文件名可能变成同一个，仍会以「重名」提示。
""".trimIndent()

/**
 * "自动清洗"说明入口:圆形感叹号图标,点击弹出说明(用户 2026-09-18 指定)。
 *
 * **为什么不再用行内文案**:原先把说明写成一行长文案,它占满剩余宽度、
 * 把右侧"自动清洗"开关**挤出屏幕**(真机实测)。改为图标后:
 *  - 行内只占一个固定宽度的触控目标(48dp),不再与开关抢宽度;
 *  - 说明内容由对话框承载,反而可以写得更完整(非法字符集 + "不会去重"的提醒)。
 */
@Composable
private fun SanitizeInfoButton(
    scheme: NamingScheme,
    onScheme: (NamingScheme) -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showInfo = true },
        modifier = Modifier
            .testTag(TAG_SCHEME_SANITIZE_INFO)
            .semantics { contentDescription = "自动清洗说明" },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_exclamation_circle),
            contentDescription = null, // 语义由外层 contentDescription 统一播报
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("自动清洗") },
            text = {
                Column {
                    Text(SANITIZE_EXPLANATION)
                    // 替换字符:**仅在开启清洗时可改**。
                    // 关闭时它完全不起作用(领域层 `if (scheme.sanitize)`),
                    // 留个改了没反应的框就是假控件 —— 故此处直接不显示。
                    if (scheme.sanitize) {
                        Spacer(Modifier.size(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "替换为",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            OutlinedTextField(
                                value = scheme.replacement,
                                onValueChange = {
                                    // 只取**单个**字符:多于一个会让"一个非法字符"变成多个字符,
                                    // 破坏逐字符替换的可预期性(粘贴多字符时取首个,避免怪结果)。
                                    onScheme(scheme.copy(replacement = it.take(1)))
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .widthInChars(6)
                                    .testTag(TAG_SCHEME_REPLACEMENT)
                                    .semantics { contentDescription = "非法字符替换为" },
                            )
                            TextButton(
                                onClick = { onScheme(scheme.copy(replacement = DEFAULT_REPLACEMENT)) },
                                enabled = scheme.replacement != DEFAULT_REPLACEMENT,
                                modifier = Modifier
                                    .testTag(TAG_SCHEME_REPLACEMENT_RESET)
                                    .semantics { contentDescription = "恢复默认替换字符" },
                            ) { Text("默认") }
                        }
                        Text(
                            // 留空不是"没填",而是一种真实语义:领域层 `append(replacement)`,
                            // 替换字符为空 = 把非法字符**删掉**。必须说清,否则用户清空后
                            // 看到非法字符凭空消失、却不知道是自己造成的。
                            text = if (scheme.replacement.isEmpty()) {
                                "留空 = 直接删除非法字符"
                            } else {
                                "非法字符将被替换为该字符"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            // ---- 实时示例(用户 2026-09-19 裁决「方案 A」) ----
                            //
                            // 用户困惑的根源是"不知道这个字符做什么"。一句具体示例即可消除;
                            // 比"条件提示"更值:后者在真实数据下(根目录名/原文件名常含非法字符)
                            // 很少触发,等真看到提示时用户多半已经困惑过了。
                            //
                            // **示例值由真实的 `sanitizeName` 算出,不是写死的字符串** ——
                            // 写死的话,一旦清洗规则变了(改非法字符集/改替换逻辑),
                            // 说明文字会与真实行为不符,而那种"文档说谎"极难被发现。
                            // 这里让示例**结构上不可能说谎**(规则一改,示例自动跟着改)。
                            text = "示例：" + SANITIZE_EXAMPLE_INPUT + " → " +
                                sanitizeName(SANITIZE_EXAMPLE_INPUT, scheme.replacement),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .testTag(TAG_SCHEME_SANITIZE_EXAMPLE)
                                .semantics { contentDescription = "清洗示例" },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showInfo = false },
                    modifier = Modifier
                        .testTag(TAG_SCHEME_SANITIZE_INFO_OK)
                        .semantics { contentDescription = "知道了" },
                ) { Text("知道了") }
            },
            modifier = Modifier.testTag(TAG_SCHEME_SANITIZE_INFO_DIALOG),
        )
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
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
