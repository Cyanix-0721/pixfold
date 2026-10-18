package com.cyanix.pixfold.domain.naming

import com.cyanix.pixfold.domain.model.NameProposal
import com.cyanix.pixfold.domain.model.ProposalWarning
import com.cyanix.pixfold.domain.model.ProposalWarningType
import com.cyanix.pixfold.domain.model.TOO_LONG_THRESHOLD
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey

/**
 * 冲突与警告检测(规格 §7)。
 *
 * 一次遍历检测 5 类;判定一律基于 **finalName**(覆盖优先),因为用户看到并会写盘的是 finalName。
 *
 * 跳过语义(§6.4):**只有 [ProposalWarningType.Duplicate] 与 [ProposalWarningType.IllegalChar]
 * 会导致改名计划跳过**;CaseCollision / TooLong 仅警告,Overridden 纯信息性。
 *
 * **文案**:每条警告产出 [Msg](键 + 参数),不在此处解析成字符串 —— 见 `text/Msg.kt`。
 * 参数里的数值都保持 `Int`(而非预先 `toString()`),以便英文侧用 `<plurals>` 正确变格。
 *
 * @param sanitize 为 true 时不会产生 IllegalChar 警告(已被清洗),
 *   但清洗后的名字**仍可能撞 Duplicate** —— 两者互补而非重复(§7)。
 */
fun attachWarnings(
    proposals: List<NameProposal>,
    sanitize: Boolean,
): List<NameProposal> {
    if (proposals.isEmpty()) return proposals

    // 精确同名(大小写敏感)分组 —— 用于 Duplicate
    val byExact = proposals.groupBy { it.finalName }
    // 小写同名分组 —— 用于 CaseCollision
    val byLower = proposals.groupBy { it.finalName.lowercase() }

    return proposals.map { p ->
        val name = p.finalName
        val warnings = mutableListOf<ProposalWarning>()

        // 1) Duplicate:同名项数 > 1
        if ((byExact[name]?.size ?: 0) > 1) {
            warnings += ProposalWarning(
                ProposalWarningType.Duplicate,
                Msg(TextKey.WarnDuplicate, listOf(byExact.getValue(name).size, name)),
            )
        }

        // 2) IllegalChar:仅当未开启自动清洗时产生
        if (!sanitize && containsIllegalChar(name)) {
            // 控制字符用 Msg(它是一句要翻译的说明),非法字符本身是字面字符。
            // 收集成 List<Any> 后由渲染侧用空格 join —— 分隔符不属于文案,故不建键。
            val parts: List<Any> = name.filter { it.code < 0x20 }
                .map { Msg(TextKey.WarnIllegalCharControl) }
                .plus(name.filter { it in com.cyanix.pixfold.domain.model.ILLEGAL_NAME_CHARS }.map { it.toString() })
                .distinct()
            warnings += ProposalWarning(
                ProposalWarningType.IllegalChar,
                Msg(TextKey.WarnIllegalChar, listOf(parts)),
            )
        }

        // 3) CaseCollision:小写同名但原始名不全同。
        //    组内若存在**完全同名**则不报(已由 Duplicate 覆盖,避免重复噪音)。
        val lowerGroup = byLower[name.lowercase()].orEmpty()
        if (lowerGroup.size > 1 && lowerGroup.map { it.finalName }.distinct().size == lowerGroup.size) {
            warnings += ProposalWarning(
                ProposalWarningType.CaseCollision,
                Msg(TextKey.WarnCaseCollision, listOf(lowerGroup.size - 1)),
            )
        }

        // 4) TooLong:仅警告,不跳过
        if (name.length > TOO_LONG_THRESHOLD) {
            warnings += ProposalWarning(
                ProposalWarningType.TooLong,
                Msg(TextKey.WarnTooLong, listOf(name.length, TOO_LONG_THRESHOLD)),
            )
        }

        // 5) Overridden:信息性
        if (p.isOverridden) {
            warnings += ProposalWarning(ProposalWarningType.Overridden, Msg(TextKey.WarnOverridden))
        }

        if (warnings.isEmpty()) p else p.copy(warnings = warnings)
    }
}
