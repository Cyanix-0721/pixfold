package com.pixfold.d1.domain.text

/**
 * 用户可见文案的**稳定键**。
 *
 * ## 为什么领域层不直接持中文
 *
 * `domain/` 是纯 Kotlin JVM 模块(无 Android `Context`),用不了 `stringResource`。
 * 但真正的理由不是"没办法",而是**语言不该在建计划的那一刻被冻住**:
 * 排序规则、冲突警告、危险确认框正文、执行计划行都是在扫描/建计划时生成的,
 * 若那些位置存的是成品字符串,那么"用户在设置里切换语言"就只能影响之后新生成的
 * 对象 —— 屏幕上已经显示的旧对象仍是旧语言,直到重新扫描。
 *
 * 因此本模块的约定是:
 * - 枚举**只携带 [TextKey]**,不携带译文;
 * - 运行时拼装的文案一律产出 [Msg](键 + 参数);
 * - 真正把键解析成字符串只在 **app 层渲染时**发生(见 `ui/text/PixFoldText.kt`)。
 *
 * ## 键的命名
 *
 * 前缀即所属域,便于在资源文件里分组,也便于 grep:
 * `Sort*` / `NameKind*` / `CasePolicy*` / `ExtPolicy*` / `Warn*` / `Lang*` /
 * `MetaField*` / `OpStatus*` / `Outcome*` / `ExecKind*` / `Conflict*` / `Source*` /
 * `Danger*` / `Summary*` / `Pack*` / `RenamePlan*` / `Exec*` / `NamingSummary*` /
 * `RenameSummary*` / `Confirm*` / `Pending*` / `Unnamed*` / `PageIssue*` / `List*`。
 *
 * ## 开发者诊断不算在内
 *
 * `require { "…" }` 的断言消息**不是**用户文案(它们面向堆栈与日志,不随 UI 语言变化),
 * 继续保持中文字面量,不建键。
 *
 * ⚠️ 与 `ComicPreview.pageIssues` **不同**:后者虽然也是"自检"性质,但
 * `PackPreviewPage` 会把它显示给用户(「⚠️ 页码与页序不一致：…」),
 * 故那两条**是**用户文案,走 `PageIssue*` 键。
 */
enum class TextKey {
    // ---------------- 排序字段(SortField) ----------------
    SortNaturalName,
    SortFileName,
    SortDirName,
    SortModified,
    SortCreated,
    SortSize,

    // ---------------- 命名组件类型(NameComponentKind) ----------------
    NameKindPrefix,
    NameKindRootDir,
    NameKindRelDir,
    NameKindDirIndex,
    NameKindImageIndex,
    NameKindOrigName,
    NameKindCustomText,

    // ---------------- 大小写策略(CasePolicy) ----------------
    CasePolicyKeep,
    CasePolicyLower,
    CasePolicyUpper,

    // ---------------- 扩展名策略(ExtPolicy) ----------------
    ExtPolicyKeep,
    ExtPolicyLower,
    ExtPolicyUpper,
    ExtPolicyStrip,

    // ---------------- 建议警告类型(ProposalWarningType) ----------------
    WarnTypeDuplicate,
    WarnTypeIllegalChar,
    WarnTypeCaseCollision,
    WarnTypeTooLong,
    WarnTypeOverridden,

    // ---------------- 语言选择(LangChoice) ----------------
    LangUnset,
    LangZh,
    LangJa,
    LangOther,
    LangUnknown,
    LangSkip,

    // ---------------- 元数据字段(MetaField) ----------------
    MetaFieldTitle,
    MetaFieldSeries,
    MetaFieldWriter,
    MetaFieldVolume,
    MetaFieldLanguage,
    MetaFieldCbzName,
    MetaFieldOutputDir,

    // ---------------- 重命名计划条目状态(OpStatus) ----------------
    OpStatusRename,
    OpStatusSkip,

    // ---------------- 执行结果(ExecOutcome) ----------------
    OutcomeSucceeded,
    OutcomeSkipped,
    OutcomeFailed,

    // ---------------- 执行报告标题(ExecKind) ----------------
    ExecKindRename,
    ExecKindPackage,

    // ---------------- 输出冲突策略(OutputConflictPolicy) ----------------
    ConflictAutoRename,
    ConflictSkipVolume,
    ConflictOverwrite,

    // ---------------- 源目录处置(SourcePolicy) ----------------
    SourceKeep,
    SourceDelete,

    // ---------------- 危险动作(DangerAction) ----------------
    DangerDeleteSourceDirLabel,
    DangerDeleteSourceDirBody,
    DangerDeleteSourceDirButton,
    DangerCleanEmptyDirsLabel,
    DangerCleanEmptyDirsBody,
    DangerCleanEmptyDirsButton,
    DangerOverwriteExistingLabel,
    DangerOverwriteExistingBody,
    DangerOverwriteExistingButton,

    // ---------------- 独立确认对话框 ----------------
    DangerDialogTitle,
    GeneralConfirmTitle,
    GeneralConfirmCancel,
    GeneralConfirmOk,

    // ---------------- 待确认标记 / 未命名系列 ----------------
    PendingLanguage,
    PendingVolume,
    UnnamedSeries,

    // ---------------- 影响范围摘要(generalSummaryLines) ----------------
    SummaryProcessingVolumes,
    SummarySourceDirPrefix,
    SummarySourceDirDelete,
    SummarySourceDirKeep,
    SummaryCleanEmptyDirsWarning,
    SummaryOverwriteCbz,
    SummaryDeleteSourceDirWarning,
    SummaryWriteOnly,
    ListSeparator,

    // ---------------- 打包计划行(VolumePackPlan.describe) ----------------
    PackCreateCbz,
    PackWriteComicInfo,
    PackPackPages,
    PackDeleteSourceDir,
    PackValueUnset,
    PackValueEmpty,
    PackValueNotWritten,
    PackSkipOutputExists,

    // ---------------- 改名计划行(RenamePlannedOp.describe) ----------------
    /**
     * 「把 `%1$s` 改成 `%2$s`」—— 两个参数都是**文件名字符串**,无数量变格。
     *
     * ## 为什么这一条必须存在
     *
     * 打包侧 [PackCreateCbz] 等句子说明"这一项具体要做什么";改名侧原先只有
     * [OpStatusRename] 一句状态词(「将重命名」),用户看完一整列也不知道**会改成什么**。
     * 计划页(`RenamePlanPage`)渲的是"原名 → 新名"两列,那是 UI 自己的布局;
     * 而 `describe()` 是领域层给"计划必须可解释"留的契约(见 `PackagePlan.kt` 里的说明),
     * 缺了这条,两个入口解释同一批操作的能力就不对等。
     *
     * ⚠️ 第 1 个参数是**改之前**的名字(`op.proposal.image.fileName`),
     * 第 2 个是**改之后**的(`op.finalName`)—— 顺序反了句子仍然通顺,但意思全错。
     */
    RenamePlanRename,

    // ---------------- 执行报告文本 ----------------
    ExecSummary,
    PackFailNoPages,
    PackFailEmptyOutputDir,
    RenameFailEmptyName,
    UndoNothing,
    UndoRecorded,

    // ---------------- 重命名警告(attachWarnings) ----------------
    WarnDuplicate,
    WarnIllegalCharControl,
    /** 非法字符列表内部的连接符(原文用空格:`: ?`)。 */
    WarnIllegalCharSeparator,
    WarnIllegalChar,
    WarnCaseCollision,
    WarnTooLong,
    WarnOverridden,

    // ---------------- 命名结构摘要(namingSummaryLine) ----------------
    NamingSummaryEnabled,
    NamingSummaryWithHead,
    /**
     * 「含 N 处手工调整」段(HANGOFF §11 第 10 条);仅 `manualCount > 0` 时出现。
     *
     * 段首自带分隔符(` · `),与 [NamingSummaryWithHead] 里那个分隔符同源 ——
     * 摘要的段间留白习惯是语言相关的,故整体走资源而不是在 UI 里拼 `" · "`。
     */
    NamingSummaryManual,

    // ---------------- 重命名影响范围(renameSummaryLines) ----------------
    RenameSummary,
    RenameSummarySourceKept,
    RenameSummaryCleanEmptyDirs,

    // ---------------- 确认流程进度(ConfirmFlowState.progressLabel) ----------------
    ConfirmProgress,

    // ---------------- 页码一致性自检(validatePageConsistency,会显示在打包预览页) ----------------
    PageIssueCountMismatch,
    PageIssueWrongIndex,
}
