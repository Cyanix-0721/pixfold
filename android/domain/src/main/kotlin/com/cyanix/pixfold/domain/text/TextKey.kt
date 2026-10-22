package com.cyanix.pixfold.domain.text

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
 * `PackPreviewPage` 会把它显示给用户(「页码与页序不一致：…」——前缀是矢量图标),
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
    PendingTitle,
    PendingVolume,
    PendingSeries,
    PendingWriter,
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

    /**
     * 「N 页读不出来,该卷未写入」—— D4b 真实写盘专有。
     *
     * 与 [PackFailNoPages] 的区别:[PackFailNoPages] 是**计划阶段**就已知「这卷没有页」;
     * 这里是**执行阶段**才发现有页读不出字节(文件被移走 / 授权失效 / provider 报错)。
     * 此时该卷**一个字节都没写、连输出流都没打开**(PackRunner 的「先探测后开流」),
     * 故措辞必须说「未写入」而不是「写了一半」。
     *
     * 参数:%1$d = 读不出的页数。
     */
    PackFailMissingPages,

    /**
     * 「写入失败:<原因>」—— D4b 真实写盘专有。
     *
     * 参数:%1$s = 平台层给出的失败详情(打开输出流失败 / 抛异常的消息)。
     * 刻意把详情原样带出:写盘失败的原因(`ENOSPC` / `EPERM` / provider 抛错)
     * 用户与开发者都无法从「写入失败」四个字里猜到,而这条详情是唯一线索。
     */
    PackFailWrite,

    /**
     * 「尚未开始」—— 用户中途取消时,后面没轮到的卷在报告里的状态。
     *
     * 为什么必须有这一条:真实执行被中断时,报告若只列**已处理**的卷,
     * 行数就比计划少,而报告页的契约是「报告第 N 行 ↔ 计划第 N 行」
     * (`ExecutionReport` KDoc)。少行会让用户以为计划本身漏了几卷。
     * 补成「尚未开始」而不是「跳过」:跳过是**计划里就定了**的
     * (输出已存在 + 跳过策略),而这里是**用户取消导致没轮到** ——
     * 两种原因完全不同,用同一句话会把取消说成计划决定。
     */
    PackFailNotStarted,
    RenameFailEmptyName,

    /**
     * 这张图没有可访问的 URI,无法真实改名。
     *
     * 与「跳过」分开的关键:跳过是**计划层定的**(用户看得到原因),而这条是
     * **执行环境缺信息**(mock 数据 / 扫描没拿到 uri) —— 用户无从预期,
     * 混成跳过会让人以为是自己选的。
     */
    RenameFailNoUri,

    /** 平台层改名失败(目标名被占 / 介质被移除 / 授权失效 / ROM 拒绝)。 */
    RenameFailIo,
    UndoNothing,
    UndoRecorded,

    /**
     * 「已撤销 N 项」—— 真实撤销全部成功。
     *
     * 参数:%1$d = 实际回滚的项数。
     */
    UndoApplied,

    /**
     * 「撤销了 N / M 项,其余失败」—— 真实撤销部分失败。
     *
     * 参数:%1$d = 成功项数,%2$d = 总项数。
     *
     * 为什么成功项也报:用户点撤销时并不知道会动手删/改名多少个文件,
     * 只报总数会让"到底撤干净没有"无法回答。
     */
    UndoAppliedPartly,

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
    PageIssueMissingFromOrder,
    PageIssueWrongIndex,

    // ---------------- 工作区结构警告(nestedDirWarnings,显示在书架页) ----------------
    /** 「系列 %1$s 下出现嵌套目录 %2$s —— 应只放单本文件夹或压缩包」;args = [系列名, 嵌套层相对路径]。 */
    NestedSeriesDir,
}
