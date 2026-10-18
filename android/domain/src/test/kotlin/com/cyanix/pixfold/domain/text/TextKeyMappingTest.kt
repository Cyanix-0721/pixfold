package com.cyanix.pixfold.domain.text

import com.cyanix.pixfold.domain.model.CasePolicy
import com.cyanix.pixfold.domain.model.ExtPolicy
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.NameComponentKind
import com.cyanix.pixfold.domain.model.ProposalWarningType
import com.cyanix.pixfold.domain.model.SortField
import com.cyanix.pixfold.domain.naming.OpStatus
import com.cyanix.pixfold.domain.plan.DangerAction
import com.cyanix.pixfold.domain.plan.ExecKind
import com.cyanix.pixfold.domain.plan.ExecOutcome
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.SourcePolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 领域枚举 → 文案键的映射表(W16 补)。
 *
 * ## 为什么需要单独一个文件
 *
 * 这 12 张映射表此前在**全部 31 个领域测试文件里的引用计数都是 0** ——
 * 领域层只持有键、不持有译文(见 `model/Sort.kt` 的 KDoc),译文在 app 侧
 * `ui/text/PixFoldText.kt` 的 `when` 里补,于是"表里的映射对不对"在领域侧
 * 没有任何读数。W16 广扫荡里 17 个"把某个枚举项指到**别的项**的键"的变异体
 * 因此**全部存活**。
 *
 * 这类错误的后果不是崩溃,而是**静默串味**:下拉框里选「大小」，列表显示的却是
 * 「文件名」那一项的文字;选了「日语」却写成「未设置」。用户看到的是自洽的界面,
 * 没有任何报错。故这里把每张表**逐项**钉死 —— 断言的是"这一项对应这一键",
 * 而不是"键各不相同"(仅"互不相同"挡不住整体位移或两两对调)。
 *
 * 译文不需要在此断言:领域层的契约只到键为止,键 → 译文的对应由 app 侧负责。
 */
class TextKeyMappingTest {

    @Test
    fun `sort field maps each option to its own key`() {
        // 顺序同时是 UI 下拉框顺序(SortRuleEditor 直接遍历 entries),故逐项列出而不排序。
        assertEquals(
            listOf(
                TextKey.SortNaturalName,
                TextKey.SortFileName,
                TextKey.SortDirName,
                TextKey.SortModified,
                TextKey.SortCreated,
                TextKey.SortSize,
            ),
            SortField.entries.map { it.textKey },
        )
    }

    @Test
    fun `language choice maps each option to its own key`() {
        // Unset 与 Skip 都是"不写语言",但语义不同(待确认 / 明确跳过),
        // 若两者共用一键,UI 上就分不出"还没填"和"决定不填"。
        assertEquals(
            listOf(
                TextKey.LangUnset,
                TextKey.LangZh,
                TextKey.LangJa,
                TextKey.LangOther,
                TextKey.LangUnknown,
                TextKey.LangSkip,
            ),
            LangChoice.entries.map { it.textKey },
        )
    }

    @Test
    fun `metadata field maps each option to its own key`() {
        // 这七项是批量设置的键(逐项例外也以它为键),指错就等于批量改错字段。
        assertEquals(
            listOf(
                TextKey.MetaFieldTitle,
                TextKey.MetaFieldSeries,
                TextKey.MetaFieldWriter,
                TextKey.MetaFieldVolume,
                TextKey.MetaFieldLanguage,
                TextKey.MetaFieldCbzName,
                TextKey.MetaFieldOutputDir,
            ),
            MetaField.entries.map { it.textKey },
        )
    }

    @Test
    fun `name component kind maps each option to its own key`() {
        // 命名结构的组件类型;RelDir(子目录名)与 DirIndex(目录序号)是两种东西,
        // 指错会让用户在结构编辑器里看到"子目录名"实际插入的是序号。
        assertEquals(
            listOf(
                TextKey.NameKindPrefix,
                TextKey.NameKindRootDir,
                TextKey.NameKindRelDir,
                TextKey.NameKindDirIndex,
                TextKey.NameKindImageIndex,
                TextKey.NameKindOrigName,
                TextKey.NameKindCustomText,
            ),
            NameComponentKind.entries.map { it.textKey },
        )
    }

    @Test
    fun `case policy maps each option to its own key`() {
        assertEquals(
            listOf(TextKey.CasePolicyKeep, TextKey.CasePolicyLower, TextKey.CasePolicyUpper),
            CasePolicy.entries.map { it.textKey },
        )
    }

    @Test
    fun `extension policy maps each option to its own key`() {
        // Strip(去掉扩展名)与 Keep(保留原样)在 UI 上是相邻选项,指错会静默改掉输出名。
        assertEquals(
            listOf(
                TextKey.ExtPolicyKeep,
                TextKey.ExtPolicyLower,
                TextKey.ExtPolicyUpper,
                TextKey.ExtPolicyStrip,
            ),
            ExtPolicy.entries.map { it.textKey },
        )
    }

    @Test
    fun `proposal warning type maps each option to its own key`() {
        // 警告类型是全库改名时用户唯一能据以判断"要不要处理"的信息。
        assertEquals(
            listOf(
                TextKey.WarnTypeDuplicate,
                TextKey.WarnTypeIllegalChar,
                TextKey.WarnTypeCaseCollision,
                TextKey.WarnTypeTooLong,
                TextKey.WarnTypeOverridden,
            ),
            ProposalWarningType.entries.map { it.textKey },
        )
    }

    @Test
    fun `rename op status maps each option to its own key`() {
        // 两个状态必须在计划页上可分辨:一个说"会改",一个说"被跳过"。
        assertEquals(
            listOf(TextKey.OpStatusRename, TextKey.OpStatusSkip),
            OpStatus.entries.map { it.textKey },
        )
    }

    @Test
    fun `execution outcome maps each option to its own key`() {
        // 报告页每一行都带一个结论词;Succeeded 与 Failed 指错会把失败显示成成功。
        assertEquals(
            listOf(TextKey.OutcomeSucceeded, TextKey.OutcomeSkipped, TextKey.OutcomeFailed),
            ExecOutcome.entries.map { it.textKey },
        )
    }

    @Test
    fun `exec kind maps each option to its own title key`() {
        // 字段名是 titleKey(不是 textKey):报告页标题与条目状态词不是一套。
        assertEquals(
            listOf(TextKey.ExecKindRename, TextKey.ExecKindPackage),
            ExecKind.entries.map { it.titleKey },
        )
    }

    @Test
    fun `output conflict policy maps each option to its own key`() {
        // 三种策略对应三种**不可互换**的后果(改名 / 跳过 / 覆盖),指错直接动用户文件。
        assertEquals(
            listOf(
                TextKey.ConflictAutoRename,
                TextKey.ConflictSkipVolume,
                TextKey.ConflictOverwrite,
            ),
            OutputConflictPolicy.entries.map { it.textKey },
        )
    }

    @Test
    fun `source policy maps each option to its own key`() {
        // Delete 是危险动作;与 Keep 共用一键会让确认框读起来像"什么都不会删"。
        assertEquals(
            listOf(TextKey.SourceKeep, TextKey.SourceDelete),
            SourcePolicy.entries.map { it.textKey },
        )
    }

    @Test
    fun `each danger action carries its own label body and button key`() {
        // 三份文案分工不同(label 标题 / body 正文 / button 按钮),故逐项列出三元组。
        // DangerActionTest 只断言"三个动作的键两两不同",挡不住"某个动作的三份文案
        // 整体错位到另一个动作"—— 那正是本用例要拦的。
        assertEquals(
            listOf(
                Triple(
                    TextKey.DangerDeleteSourceDirLabel,
                    TextKey.DangerDeleteSourceDirBody,
                    TextKey.DangerDeleteSourceDirButton,
                ),
                Triple(
                    TextKey.DangerCleanEmptyDirsLabel,
                    TextKey.DangerCleanEmptyDirsBody,
                    TextKey.DangerCleanEmptyDirsButton,
                ),
                Triple(
                    TextKey.DangerOverwriteExistingLabel,
                    TextKey.DangerOverwriteExistingBody,
                    TextKey.DangerOverwriteExistingButton,
                ),
            ),
            DangerAction.entries.map { Triple(it.labelKey, it.bodyKey, it.buttonKey) },
        )
    }
}
