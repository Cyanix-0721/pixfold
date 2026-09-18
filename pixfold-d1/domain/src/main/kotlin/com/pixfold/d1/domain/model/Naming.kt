package com.pixfold.d1.domain.model

/**
 * 命名结构与逐项覆盖(规格 §4.4 / §6)。
 *
 * 关键设计:**覆盖(override)不放在 [NamingScheme] 里**,而是独立的
 * `Map<SourceItem.id, 完整文件名>`(见 §6.3)。改组件/分隔符/补零只影响
 * `proposedName`,被覆盖项逐字不变 —— 这是"改结构后覆盖不丢失"的机制本身,
 * 而不是靠 UI 小心维护。存储位置在 `NamingState`,不在本文件。
 */

/** 非法字符集:9 个 Windows 非法字符(规格 §7)。控制字符(码点 < 0x20)另行判定。 */
const val ILLEGAL_NAME_CHARS = "\\/:*?\"<>|"

/** 超长阈值:UTF-16 码元数,中文按 1 计(规格 §7)。 */
const val TOO_LONG_THRESHOLD = 180

enum class NameComponentKind(val label: String) {
    Prefix("自定义前缀"),
    RootDir("根目录名"),
    RelDir("相对目录片段"),
    DirIndex("目录序号"),
    ImageIndex("图片序号"),
    OrigName("原文件名"),
    CustomText("自定义文本"),
}

enum class CasePolicy(val label: String) {
    Keep("保持原样"),
    Lower("转小写"),
    Upper("转大写"),
}

enum class ExtPolicy(val label: String) {
    Keep("保留原样"),
    Lower("统一小写"),
    Upper("统一大写"),
    Strip("去除扩展名"),
}

/**
 * 命名组件。
 * @param separatorBefore 本组件**前置**分隔符;首个实际输出的组件不输出分隔符(§6.1)
 * @param text 仅 [NameComponentKind.Prefix] / [NameComponentKind.CustomText] 使用
 */
data class NameComponent(
    val kind: NameComponentKind,
    val enabled: Boolean = true,
    val separatorBefore: String = "_",
    val casePolicy: CasePolicy = CasePolicy.Keep,
    val trimSpaces: Boolean = true,
    val text: String = "",
)

data class NamingScheme(
    val rootDirName: String,
    val components: List<NameComponent> = DEFAULT_COMPONENTS,
    val indexStart: Int = 1,
    val indexPadding: Int = 3,
    val extPolicy: ExtPolicy = ExtPolicy.Lower,
    val sanitize: Boolean = true,
    val replacement: String = "_",
) {
    companion object {
        /** 默认输出 `<根目录名>_<三位序号>.<小写扩展名>`(规格 §4.4)。 */
        val DEFAULT_COMPONENTS = listOf(
            NameComponent(NameComponentKind.RootDir),
            NameComponent(NameComponentKind.ImageIndex),
            NameComponent(NameComponentKind.OrigName, enabled = false),
        )
    }
}

enum class ProposalWarningType(val label: String) {
    Duplicate("重名"),
    IllegalChar("非法字符"),
    CaseCollision("大小写冲突"),
    TooLong("超长"),
    Overridden("人工覆盖"),
}

data class ProposalWarning(val type: ProposalWarningType, val message: String)

/**
 * 单张图片的命名建议。
 *
 * [finalName] 优先取 [overrideName] —— 覆盖项逐字不变(§6.3)。
 */
data class NameProposal(
    val image: SourceItem,
    val proposedName: String,
    val overrideName: String? = null,
    val warnings: List<ProposalWarning> = emptyList(),
) {
    val finalName: String get() = overrideName ?: proposedName
    val isOverridden: Boolean get() = overrideName != null

    /** 会导致改名计划**跳过**的冲突:仅 Duplicate 与 IllegalChar(§7)。 */
    val hasConflict: Boolean
        get() = warnings.any {
            it.type == ProposalWarningType.Duplicate || it.type == ProposalWarningType.IllegalChar
        }

    /**
     * 是否有**不导致跳过**的警告。
     *
     * 定义 = "有警告,且都不是会导致跳过的类型"。**不要**写成枚举白名单
     * (`CaseCollision || TooLong`)—— 那样 [ProposalWarningType.Overridden] 会被漏掉,
     * 出现"行里显示了警告、汇总却写 0"的不一致(真机实测踩过)。
     * 用"取反"表达,新增警告类型时自动被计入,不会再漏。
     */
    val hasWarningOnly: Boolean
        get() = warnings.isNotEmpty() && !hasConflict
}

/**
 * 命名批次状态(规格 §6.3)。
 *
 * [overrides] 键 = `SourceItem.id`,值 = 用户输入的完整文件名;**空值即移除**(清空输入框 = 撤销覆盖)。
 *
 * [rootDirNameOverride] 是"根目录名"的**逐项例外**:
 *  - `null`(默认)= **跟随当前集合** —— 切集合时根目录名随之变化
 *    (用户 2026-09-18 明确要求:"根目录名跟随集合");
 *  - 非 null = 用户显式改过,则不再跟随。
 *
 * 这正是 HANGOFF §2 约束 4「批次统一 + 逐项例外」在命名组件上的落法:
 * 命名的其余部分(组件/分隔符/补零/扩展名…)是批次级;而根目录名有天然的
 * 逐集合来源([ImageCollection.rootDirName]),故默认取集合值、允许用户覆盖。
 */
data class NamingState(
    val scheme: NamingScheme,
    val overrides: Map<String, String> = emptyMap(),
    val rootDirNameOverride: String? = null,
) {
    /** 根目录名是否已被用户显式指定(不再跟随集合)。 */
    val hasRootDirNameOverride: Boolean get() = rootDirNameOverride != null
}

/**
 * 计算**当前生效**的命名结构(唯一入口)。
 *
 * 根目录名:用户改过 -> 用用户的;否则 -> 用当前集合的 [ImageCollection.rootDirName]。
 * 其余字段一律取自 [NamingState.scheme](批次级)。
 *
 * **为什么不直接把集合名写回 `scheme.rootDirName`**:那样就分不清"跟随集合的默认值"
 * 与"用户手输的值" —— 切集合会把用户输入冲掉,或反过来永远不跟随。
 * 用独立的可空字段表达"是否覆盖",语义无歧义。
 */
fun effectiveScheme(state: NamingState, collection: ImageCollection): NamingScheme =
    if (state.rootDirNameOverride != null) {
        state.scheme.copy(rootDirName = state.rootDirNameOverride)
    } else {
        state.scheme.copy(rootDirName = collection.rootDirName)
    }
