package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.NameProposal
import com.pixfold.d1.domain.model.ProposalWarning
import com.pixfold.d1.domain.model.ProposalWarningType
import com.pixfold.d1.domain.model.TOO_LONG_THRESHOLD

/**
 * 冲突与警告检测(规格 §7)。
 *
 * 一次遍历检测 5 类;判定一律基于 **finalName**(覆盖优先),因为用户看到并会写盘的是 finalName。
 *
 * 跳过语义(§6.4):**只有 [ProposalWarningType.Duplicate] 与 [ProposalWarningType.IllegalChar]
 * 会导致改名计划跳过**;CaseCollision / TooLong 仅警告,Overridden 纯信息性。
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
                "重名：有 ${byExact.getValue(name).size} 项同名「$name」，执行时将跳过",
            )
        }

        // 2) IllegalChar:仅当未开启自动清洗时产生
        if (!sanitize && containsIllegalChar(name)) {
            val bad = name.filter { it.code < 0x20 }
                .map { "控制字符" }
                .plus(name.filter { it in com.pixfold.d1.domain.model.ILLEGAL_NAME_CHARS }.map { it.toString() })
                .distinct()
                .joinToString(" ")
            warnings += ProposalWarning(
                ProposalWarningType.IllegalChar,
                "非法字符：$bad（执行时将跳过；可开启自动清洗）",
            )
        }

        // 3) CaseCollision:小写同名但原始名不全同。
        //    组内若存在**完全同名**则不报(已由 Duplicate 覆盖,避免重复噪音)。
        val lowerGroup = byLower[name.lowercase()].orEmpty()
        if (lowerGroup.size > 1 && lowerGroup.map { it.finalName }.distinct().size == lowerGroup.size) {
            warnings += ProposalWarning(
                ProposalWarningType.CaseCollision,
                "大小写冲突：与 ${lowerGroup.size - 1} 项仅大小写不同，在部分系统上会互相覆盖",
            )
        }

        // 4) TooLong:仅警告,不跳过
        if (name.length > TOO_LONG_THRESHOLD) {
            warnings += ProposalWarning(
                ProposalWarningType.TooLong,
                "超长：${name.length} 字符，超过 $TOO_LONG_THRESHOLD 上限",
            )
        }

        // 5) Overridden:信息性
        if (p.isOverridden) {
            warnings += ProposalWarning(ProposalWarningType.Overridden, "人工覆盖：已手动指定名称")
        }

        if (warnings.isEmpty()) p else p.copy(warnings = warnings)
    }
}
