package com.cyanix.pixfold.ui.text

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey

/**
 * 把领域层的 [TextKey] / [Msg] 解析成当前语言的字符串。
 *
 * ## 为什么解析放在这里(而不是领域层)
 *
 * `domain/` 是纯 Kotlin JVM 模块,拿不到 Android 资源;但更根本的理由是
 * **语言不该在建计划的那一刻被冻住** —— 计划、警告、确认框正文都是扫描时生成的,
 * 若它们存的是成品字符串,用户在设置里切语言后就只有**新生成**的对象是新语言,
 * 屏幕上已有的旧对象仍是旧语言。故领域层只产出「键 + 参数」,解析推迟到这里。
 *
 * ## 用法
 *
 * ```
 * Text(textOf(msg))                     // 领域层给的 Msg
 * Text(textOf(TextKey.OpStatusRename))  // 领域枚举的 textKey
 * ```
 *
 * ⚠️ **不能**在 `semantics { contentDescription = textOf(...) }` 这类非 composable
 * 的 lambda 里调用(它们是普通 lambda,不是 `@Composable`)。必须先提到外面:
 * ```
 * val label = textOf(TextKey.SomeKey)
 * Modifier.semantics { contentDescription = label }
 * ```
 *
 * ⚠️ **不要**把解析结果用作 `testTag` / 相等性键 / 文件名 —— 那些必须是语言无关的稳定值。
 */
@Composable
fun textOf(msg: Msg): String = textOf(msg.key, *msg.args.toTypedArray())

/** 可为空的版本:null 原样返回 null(省去调用方到处写 `if (x != null)`)。 */
@Composable
fun textOfOrNull(msg: Msg?): String? = msg?.let { textOf(it) }

/**
 * **非 Composable** 的解析入口(同一个实现,供测试与逻辑代码调用)。
 *
 * 存在的理由:测试要断言"界面显示的正是这条领域消息",若在测试里自己拼一遍
 * 期望字符串,就等于**把实现复制了一份** —— 两边同时写错时测试照样绿。
 * 故测试用本函数算出期望值,走的是与界面完全相同的那条解析路径。
 *
 * ⚠️ 界面代码请用 [textOf]/[textOfOrNull]:它们会随 `LocalConfiguration` 重组,
 * 语言切换时立即刷新;本函数只是快照。
 */
fun resolveText(context: Context, msg: Msg): String = resolveText(context, msg.key, msg.args)

/** [resolveText] 的键+参数版本(领域枚举的 `textKey` 直接走这里)。 */
fun resolveText(context: Context, key: TextKey, args: List<Any> = emptyList()): String {
    val resId = key.resId()
    val resolved: List<Any> = args.map { resolveArgWith(key, it, { m -> resolveText(context, m) }, { k -> resolveText(context, k) }) }
    val quantityIndex = key.pluralQuantityIndex()
    return if (quantityIndex == null) {
        context.getString(resId, *resolved.toTypedArray())
    } else {
        val quantity = (resolved.getOrNull(quantityIndex) as? Int) ?: 0
        context.resources.getQuantityString(resId, quantity, *resolved.toTypedArray())
    }
}

/**
 * 按键取文案。[args] 依次对应资源里的 `%1$s` / `%2$d`…
 *
 * 参数里的 [Msg] 会被**递归解析**(如「源目录：<删除(危险)>」这种嵌套),
 * [List] 会被用 [TextKey.ListSeparator] 连接(如覆盖同名 CBZ 的多条路径)。
 */
@Composable
fun textOf(key: TextKey, vararg args: Any): String {
    val resId = key.resId()
    // 传 lambda 而不是 `::textOf` 方法引用:`textOf` 是 vararg + @Composable,
    // 方法引用匹配不上 `(Msg) -> String` 这个形状。
    val resolved: List<Any> = args.map { resolveArgWith(key, it, { m -> textOf(m) }, { k -> textOf(k) }) }
    val quantityIndex = key.pluralQuantityIndex()
    return if (quantityIndex == null) {
        stringResource(resId, *resolved.toTypedArray())
    } else {
        // 复数键:数量单独取出来交给 pluralStringResource 选变格,其余参数按位传入。
        val quantity = (resolved.getOrNull(quantityIndex) as? Int) ?: 0
        pluralStringResource(resId, quantity, *resolved.toTypedArray())
    }
}

/**
 * 解析**参数树**里的一个参数 —— 界面入口与 [resolveText] **共用这一份**遍历逻辑。
 *
 * 共用是刻意的:测试若与界面各拼一份参数解析,就成了"把实现复制一遍",
 * 两边同时写错时测试照样绿。
 *
 * `inline` 也是必须的:界面侧传进来的 `resolveKey` 里要调用 `@Composable`
 * 的取资源函数,而只有内联 lambda 才允许这种"经普通函数体承接 composable 调用"
 * (与直接用 `forEach` 包住 `textOf` 同理)。
 */
private inline fun resolveArgWith(
    key: TextKey,
    arg: Any,
    resolveMsg: (Msg) -> String,
    resolveKey: (TextKey) -> String,
): Any = when (arg) {
    is Msg -> resolveMsg(arg)
    // joinToString 的 transform 是普通 lambda,故 map 放在外面(它才是内联的)
    is List<*> -> arg.map { if (it is Msg) resolveMsg(it) else it.toString() }
        .joinToString(resolveKey(separatorKeyOf(key)))

    else -> arg
}

/**
 * 列表参数用哪个连接符 —— **由键决定,因为连接符本身也是语言相关的**
 * (中文用「、」,英文用「, 」;而非法字符列表原文用的是空格)。
 */
private fun separatorKeyOf(key: TextKey): TextKey = when (key) {
    TextKey.WarnIllegalChar -> TextKey.WarnIllegalCharSeparator
    else -> TextKey.ListSeparator
}

/**
 * 复数键里**哪个参数是数量**。
 *
 * 返回 null = 普通 `<string>`;返回下标 = 该键是 `<plurals>`,该位是 `count`。
 * 中文没有复数变化(资源里 one/other 文本相同),但英文需要正确变格,
 * 故资源类型统一为 `<plurals>` —— 资源类型不能跨语言不同。
 */
private fun TextKey.pluralQuantityIndex(): Int? = when (this) {
    TextKey.SummaryProcessingVolumes,
    TextKey.PackPackPages,
    TextKey.ExecSummary,
    TextKey.PackFailMissingPages,
    TextKey.UndoRecorded,
    TextKey.WarnCaseCollision,
    TextKey.WarnDuplicate,
    TextKey.WarnTooLong,
    TextKey.NamingSummaryEnabled,
    TextKey.NamingSummaryManual,
    TextKey.RenameSummary,
    -> 0

    // 形态是 `<首个建议名> · <N 个组件启用>`,数量在第 2 位
    TextKey.NamingSummaryWithHead -> 1

    else -> null
}

/**
 * 键 → 资源 id 的**唯一映射**。
 *
 * 集中在一处(而不是散在 UI 里)有三个好处:①新增键时漏配会在此处**编译失败**
 * (`when` 是穷尽的,没有 `else`);②`grep` 一个键就能看到它的中文与英文资源名;
 * ③资源文件的重命名只需改这一处。
 *
 * 与 `SettingsScreen.kt` 里「枚举 -> 文案」的做法一致。
 *
 * 可见性为 `internal`(而非 `private`):[warnIcon] 需要用它把 `TextKey` 折进
 * 「资源 id -> 警示图标」那张表,好让 `TextKey` 与纯资源键**共用同一份**判定。
 */
internal fun TextKey.resId(): Int = when (this) {
    // 排序字段
    TextKey.SortNaturalName -> R.string.domain_sort_natural_name
    TextKey.SortFileName -> R.string.domain_sort_file_name
    TextKey.SortDirName -> R.string.domain_sort_dir_name
    TextKey.SortModified -> R.string.domain_sort_modified
    TextKey.SortCreated -> R.string.domain_sort_created
    TextKey.SortSize -> R.string.domain_sort_size

    // 命名组件类型
    TextKey.NameKindPrefix -> R.string.domain_name_kind_prefix
    TextKey.NameKindRootDir -> R.string.domain_name_kind_root_dir
    TextKey.NameKindRelDir -> R.string.domain_name_kind_rel_dir
    TextKey.NameKindDirIndex -> R.string.domain_name_kind_dir_index
    TextKey.NameKindImageIndex -> R.string.domain_name_kind_image_index
    TextKey.NameKindOrigName -> R.string.domain_name_kind_orig_name
    TextKey.NameKindCustomText -> R.string.domain_name_kind_custom_text

    // 大小写策略
    TextKey.CasePolicyKeep -> R.string.domain_case_policy_keep
    TextKey.CasePolicyLower -> R.string.domain_case_policy_lower
    TextKey.CasePolicyUpper -> R.string.domain_case_policy_upper

    // 扩展名策略
    TextKey.ExtPolicyKeep -> R.string.domain_ext_policy_keep
    TextKey.ExtPolicyLower -> R.string.domain_ext_policy_lower
    TextKey.ExtPolicyUpper -> R.string.domain_ext_policy_upper
    TextKey.ExtPolicyStrip -> R.string.domain_ext_policy_strip

    // 警告类型标签
    TextKey.WarnTypeDuplicate -> R.string.domain_warn_type_duplicate
    TextKey.WarnTypeIllegalChar -> R.string.domain_warn_type_illegal_char
    TextKey.WarnTypeCaseCollision -> R.string.domain_warn_type_case_collision
    TextKey.WarnTypeTooLong -> R.string.domain_warn_type_too_long
    TextKey.WarnTypeOverridden -> R.string.domain_warn_type_overridden

    // 语言选择
    TextKey.LangUnset -> R.string.domain_lang_unset
    TextKey.LangZh -> R.string.domain_lang_zh
    TextKey.LangJa -> R.string.domain_lang_ja
    TextKey.LangOther -> R.string.domain_lang_other
    TextKey.LangUnknown -> R.string.domain_lang_unknown
    TextKey.LangSkip -> R.string.domain_lang_skip

    // 元数据字段
    TextKey.MetaFieldTitle -> R.string.domain_meta_field_title
    TextKey.MetaFieldSeries -> R.string.domain_meta_field_series
    TextKey.MetaFieldWriter -> R.string.domain_meta_field_writer
    TextKey.MetaFieldVolume -> R.string.domain_meta_field_volume
    TextKey.MetaFieldLanguage -> R.string.domain_meta_field_language
    TextKey.MetaFieldCbzName -> R.string.domain_meta_field_cbz_name
    TextKey.MetaFieldOutputDir -> R.string.domain_meta_field_output_dir

    // 重命名计划条目状态
    TextKey.OpStatusRename -> R.string.domain_op_status_rename
    TextKey.OpStatusSkip -> R.string.domain_op_status_skip

    // 改名计划行
    TextKey.RenamePlanRename -> R.string.domain_rename_plan_rename

    // 执行结果
    TextKey.OutcomeSucceeded -> R.string.domain_outcome_succeeded
    TextKey.OutcomeSkipped -> R.string.domain_outcome_skipped
    TextKey.OutcomeFailed -> R.string.domain_outcome_failed

    // 执行报告标题
    TextKey.ExecKindRename -> R.string.domain_exec_kind_rename
    TextKey.ExecKindPackage -> R.string.domain_exec_kind_package

    // 输出冲突策略
    TextKey.ConflictAutoRename -> R.string.domain_conflict_auto_rename
    TextKey.ConflictSkipVolume -> R.string.domain_conflict_skip_volume
    TextKey.ConflictOverwrite -> R.string.domain_conflict_overwrite

    // 源目录处置
    TextKey.SourceKeep -> R.string.domain_source_keep
    TextKey.SourceDelete -> R.string.domain_source_delete

    // 危险动作
    TextKey.DangerDeleteSourceDirLabel -> R.string.domain_danger_delete_source_dir_label
    TextKey.DangerDeleteSourceDirBody -> R.string.domain_danger_delete_source_dir_body
    TextKey.DangerDeleteSourceDirButton -> R.string.domain_danger_delete_source_dir_button
    TextKey.DangerCleanEmptyDirsLabel -> R.string.domain_danger_clean_empty_dirs_label
    TextKey.DangerCleanEmptyDirsBody -> R.string.domain_danger_clean_empty_dirs_body
    TextKey.DangerCleanEmptyDirsButton -> R.string.domain_danger_clean_empty_dirs_button
    TextKey.DangerOverwriteExistingLabel -> R.string.domain_danger_overwrite_existing_label
    TextKey.DangerOverwriteExistingBody -> R.string.domain_danger_overwrite_existing_body
    TextKey.DangerOverwriteExistingButton -> R.string.domain_danger_overwrite_existing_button

    // 独立确认对话框
    TextKey.DangerDialogTitle -> R.string.domain_danger_dialog_title
    TextKey.GeneralConfirmTitle -> R.string.domain_general_confirm_title
    TextKey.GeneralConfirmCancel -> R.string.domain_general_confirm_cancel
    TextKey.GeneralConfirmOk -> R.string.domain_general_confirm_ok

    // 待确认标记 / 未命名系列
    TextKey.PendingLanguage -> R.string.domain_pending_language
    TextKey.PendingVolume -> R.string.domain_pending_volume
    TextKey.UnnamedSeries -> R.string.domain_unnamed_series

    // 影响范围摘要
    TextKey.SummaryProcessingVolumes -> R.plurals.domain_summary_processing_volumes
    TextKey.SummarySourceDirPrefix -> R.string.domain_summary_source_dir_prefix
    TextKey.SummarySourceDirDelete -> R.string.domain_summary_source_dir_delete
    TextKey.SummarySourceDirKeep -> R.string.domain_summary_source_dir_keep
    TextKey.SummaryCleanEmptyDirsWarning -> R.string.domain_summary_clean_empty_dirs_warning
    TextKey.SummaryOverwriteCbz -> R.string.domain_summary_overwrite_cbz
    TextKey.SummaryDeleteSourceDirWarning -> R.string.domain_summary_delete_source_dir_warning
    TextKey.SummaryWriteOnly -> R.string.domain_summary_write_only
    TextKey.ListSeparator -> R.string.domain_list_separator

    // 打包计划行
    TextKey.PackCreateCbz -> R.string.domain_pack_create_cbz
    TextKey.PackWriteComicInfo -> R.string.domain_pack_write_comic_info
    TextKey.PackPackPages -> R.plurals.domain_pack_pack_pages
    TextKey.PackDeleteSourceDir -> R.string.domain_pack_delete_source_dir
    TextKey.PackValueUnset -> R.string.domain_pack_value_unset
    TextKey.PackValueEmpty -> R.string.domain_pack_value_empty
    TextKey.PackValueNotWritten -> R.string.domain_pack_value_not_written
    TextKey.PackSkipOutputExists -> R.string.domain_pack_skip_output_exists

    // 执行报告文本
    TextKey.ExecSummary -> R.plurals.domain_exec_summary
    TextKey.PackFailNoPages -> R.string.domain_pack_fail_no_pages
    TextKey.PackFailEmptyOutputDir -> R.string.domain_pack_fail_empty_output_dir
    TextKey.PackFailMissingPages -> R.plurals.domain_pack_fail_missing_pages
    TextKey.PackFailWrite -> R.string.domain_pack_fail_write
    TextKey.PackFailNotStarted -> R.string.domain_pack_fail_not_started
    TextKey.RenameFailEmptyName -> R.string.domain_rename_fail_empty_name
    TextKey.UndoNothing -> R.string.domain_undo_nothing
    TextKey.UndoRecorded -> R.plurals.domain_undo_recorded

    // 重命名警告
    TextKey.WarnDuplicate -> R.plurals.domain_warn_duplicate
    TextKey.WarnIllegalCharControl -> R.string.domain_warn_illegal_char_control
    TextKey.WarnIllegalCharSeparator -> R.string.domain_warn_illegal_char_separator
    TextKey.WarnIllegalChar -> R.string.domain_warn_illegal_char
    TextKey.WarnCaseCollision -> R.plurals.domain_warn_case_collision
    TextKey.WarnTooLong -> R.plurals.domain_warn_too_long
    TextKey.WarnOverridden -> R.string.domain_warn_overridden

    // 命名结构摘要
    TextKey.NamingSummaryEnabled -> R.plurals.domain_naming_summary_enabled
    TextKey.NamingSummaryWithHead -> R.plurals.domain_naming_summary_with_head
    TextKey.NamingSummaryManual -> R.plurals.domain_naming_summary_manual

    // 重命名影响范围
    TextKey.RenameSummary -> R.plurals.domain_rename_summary
    TextKey.RenameSummarySourceKept -> R.string.domain_rename_summary_source_kept
    TextKey.RenameSummaryCleanEmptyDirs -> R.string.domain_rename_summary_clean_empty_dirs

    // 确认流程进度
    TextKey.ConfirmProgress -> R.string.domain_confirm_progress

    // 页码一致性自检
    TextKey.PageIssueCountMismatch -> R.string.domain_page_issue_count_mismatch
    TextKey.PageIssueMissingFromOrder -> R.string.domain_page_issue_missing_from_order
    TextKey.PageIssueWrongIndex -> R.string.domain_page_issue_wrong_index
}
