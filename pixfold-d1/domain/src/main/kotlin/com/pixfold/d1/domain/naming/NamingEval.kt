package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.CasePolicy
import com.pixfold.d1.domain.model.ExtPolicy
import com.pixfold.d1.domain.model.ILLEGAL_NAME_CHARS
import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NameProposal
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.SourceItem

/**
 * 命名求值(规格 §6.1 / §6.2)。
 *
 * 顺序严格照规格:组件取值 → `part.isEmpty()` 整体跳过 → 拼接 → 扩展名策略 →
 * 清洗(作用于完整文件名) → 覆盖 → 统一挂警告。
 *
 * 纯函数:不改入参,不依赖 UI。
 */
fun buildProposals(
    order: List<SourceItem>,
    scheme: NamingScheme,
    dirIndexMap: Map<String, Int>? = null,
    overrides: Map<String, String>? = null,
): List<NameProposal> {
    // 1) 目录序号表:按目录名字典序 1-based;显式传入的 dirIndexMap 覆盖默认值。
    //    因为基于 `dir` 本身(而非页序位置)计算,**换序不会改变目录编号**(§6.1)。
    val dirs = order.map { it.dir }.distinct().sorted()
    val effectiveDirIndex = dirs.withIndex().associate { (i, dir) -> dir to i + 1 } +
        (dirIndexMap ?: emptyMap())

    val proposals = order.mapIndexed { position, item ->
        val parts = mutableListOf<String>()
        val seps = mutableListOf<String>()

        for (component in scheme.components) {
            if (!component.enabled) continue
            val raw = componentText(component, item, scheme, effectiveDirIndex, position)
            val cased = applyCase(raw, component.casePolicy)
            val part = if (component.trimSpaces) cased.trim() else cased
            // 4) 空组件**整体跳过**——连分隔符一起丢,不留多余分隔符
            if (part.isEmpty()) continue
            parts += part
            seps += component.separatorBefore
        }

        // 5) 拼接:首个实际输出的组件不输出分隔符
        val stem = buildString {
            parts.forEachIndexed { i, part ->
                if (i > 0) append(seps[i])
                append(part)
            }
        }

        // 6) 扩展名策略
        val withExt = stem + extensionSuffix(scheme.extPolicy, item.ext)

        // 7) 清洗作用于**完整文件名(含扩展名)**
        val sanitized = if (scheme.sanitize) sanitizeName(withExt, scheme.replacement) else withExt

        // 8) 覆盖:按 id 取,覆盖项逐字不变
        NameProposal(
            image = item,
            proposedName = sanitized,
            overrideName = overrides?.get(item.id),
        )
    }

    // 9) 统一挂警告
    return attachWarnings(proposals, sanitize = scheme.sanitize)
}

/** 组件取值 + 补零(规格 §6.2 表)。 */
private fun componentText(
    component: NameComponent,
    item: SourceItem,
    scheme: NamingScheme,
    dirIndexMap: Map<String, Int>,
    position: Int,
): String = when (component.kind) {
    NameComponentKind.Prefix, NameComponentKind.CustomText -> component.text
    NameComponentKind.RootDir -> scheme.rootDirName
    NameComponentKind.RelDir -> item.dir
    NameComponentKind.DirIndex ->
        (dirIndexMap[item.dir] ?: 1).toString().padStart(scheme.indexPadding, '0')
    // position 是**页序下标(0-based)**;序号 = indexStart + position
    NameComponentKind.ImageIndex ->
        (scheme.indexStart + position).toString().padStart(scheme.indexPadding, '0')
    NameComponentKind.OrigName -> item.baseName
}

/** 后处理第一步:大小写策略(逐组件)。 */
private fun applyCase(text: String, policy: CasePolicy): String = when (policy) {
    CasePolicy.Keep -> text
    CasePolicy.Lower -> text.lowercase()
    CasePolicy.Upper -> text.uppercase()
}

private fun extensionSuffix(policy: ExtPolicy, ext: String): String = when (policy) {
    ExtPolicy.Keep -> ".$ext"
    ExtPolicy.Lower -> ".${ext.lowercase()}"
    ExtPolicy.Upper -> ".${ext.uppercase()}"
    ExtPolicy.Strip -> ""
}

/** 是否含非法字符:9 个保留字符 + 控制字符(码点 < 0x20)。 */
fun containsIllegalChar(name: String): Boolean =
    name.any { it in ILLEGAL_NAME_CHARS || it.code < 0x20 }

/** 把非法字符全部替换为 [replacement](规格 §7)。 */
fun sanitizeName(name: String, replacement: String = "_"): String {
    if (!containsIllegalChar(name)) return name
    return buildString {
        for (ch in name) {
            if (ch in ILLEGAL_NAME_CHARS || ch.code < 0x20) append(replacement) else append(ch)
        }
    }
}
