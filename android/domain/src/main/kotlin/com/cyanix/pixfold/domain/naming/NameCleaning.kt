package com.cyanix.pixfold.domain.naming

/**
 * 从目录名清洗出标题类文字的规则。
 *
 * **权威来源 = `scripts/batch_pack_cbz.py`**(旧脚本的现有行为参考)。本项目在
 * 2026-10-19 之前完全没有清洗,直接把目录名整串当 title —— 真机实测导致 CBZ 文件名
 * 里留着 `[DL版]` 这类发行标注。用户 m06244 裁决「照搬旧脚本规则」。
 *
 * 三条规则的**边界**刻意与脚本逐字对齐,不要"顺手优化":
 *
 * 1. **只剥末尾的 `[]` 标注**。中间的 `[]` 是标题的一部分(`真正的[漫画]标题`),
 *    剥掉会毁标题;
 * 2. **方括号内部的括号不动**。`[Cior (Ken-1)]` 里的 `(Ken-1)` 属于作者名;
 * 3. **`writerRaw` 保留原始形态**(带方括号与括号),`cleanWriterName` 才清理。
 *    UI 的「一键清理作者」按钮靠这个差异才有意义。
 *
 * 无 Android 依赖:纯字符串处理,可被领域层任意调用。
 */

/** 全角/半角括号(内容通常是"原作")。 */
private val PAREN_RE = Regex("[（(][^（）()]*[）)]")

/**
 * 开头的 `()` 前缀(如 `(C108) [作者] 标题` 里的 `(C108)`)。
 *
 * 通常为展会/社团代码,与末尾 `[DL]` 等标注一样忽略。
 */
private val LEADING_TAG_PAREN_RE = Regex("""^\s*[（(][^（）()]*[）)]\s*""")

/**
 * 末尾标注:`[DL]`、`[中文翻译]` 等(0 个或多个,可选空格隔开)。
 *
 * ⚠️ **只匹配串尾**。写成不带 `$` 的版本会把标题中间的 `[漫画]` 一起剥掉。
 */
private val TRAILING_TAG_RE = Regex("""(?:\s*\[[^\[\]]*\])+$""")

/** 连续空白(含全角空格)。 */
private val WHITESPACE_RE = Regex("""\s+""")

/**
 * 剥掉括号原作与末尾 `[]` 标注,并规整空白。
 *
 * 例(全部是脚本 docstring 的原文样例):
 * ```
 * "作品A（原作：X）"        -> "作品A"
 * "作品A (原作X)"           -> "作品A"
 * "作品A（原作：X） 1"      -> "作品A 1"
 * "作品A [DL]"              -> "作品A"
 * "作品A[DL] [中文翻译]"    -> "作品A"
 * "真正的[漫画]标题 [DL]"   -> "真正的[漫画]标题"   // 中间 [] 保留
 * "作品A [DL] 副标题"       -> "作品A [DL] 副标题"  // 非末尾,保留
 * ```
 */
fun stripOriginalWork(name: String): String {
    val withoutParen = PAREN_RE.replace(name, "")
    val withoutTrailingTag = TRAILING_TAG_RE.replace(withoutParen, "")
    return WHITESPACE_RE.replace(withoutTrailingTag, " ").trim()
}

/** 目录名解析结果。 */
data class ParsedName(
    /** 原始作者形态,**保留**方括号与括号;无作者前缀时为空串。 */
    val writerRaw: String,
    /** 清理后的标题(已剥括号原作与末尾标注)。 */
    val title: String,
)

/**
 * 从目录名解析 `(writerRaw, title)`。
 *
 * 例(脚本 docstring 原文):
 * ```
 * "(C108) [作者A] 作品A 副标题（原作：X）"  -> ("作者A", "作品A 副标题")
 * "作品B（原作：Y）"                        -> ("", "作品B")
 * ```
 *
 * ⚠️ 本函数的 `writerRaw` **保留方括号**(`"[作者A]"`),与脚本 `parse_name` 返回
 * 不带括号的 `"作者A"` 不同 —— 因为本项目的 `ComicVolume.writerSug` 语义是
 * 「原始形态,供一键清理」,清理后的值走 [cleanWriterName]。要剥括号用后者。
 */
fun parseCollectionName(name: String): ParsedName {
    val stripped = name.trim()
    // 先剥开头的展会代码,再解析 [作者] —— 否则 "(C108) [作者] ..." 的开头不是 '['。
    val withoutLeadingTag = LEADING_TAG_PAREN_RE.replace(stripped, "")
    if (!withoutLeadingTag.startsWith("[")) {
        return ParsedName(writerRaw = "", title = stripOriginalWork(withoutLeadingTag))
    }
    val close = withoutLeadingTag.indexOf(']')
    if (close < 0) {
        // 只有左方括号没有右方括号:当普通文字处理,不当作者。
        return ParsedName(writerRaw = "", title = stripOriginalWork(withoutLeadingTag))
    }
    val writerRaw = withoutLeadingTag.substring(0, close + 1).trim()
    val rest = withoutLeadingTag.substring(close + 1).trim()
    return ParsedName(writerRaw = writerRaw, title = stripOriginalWork(rest))
}

/**
 * 生成 CBZ 文件名用的干净基名:**保留**开头的 `[作者]` 前缀,剥掉其余。
 *
 * 例(脚本 docstring 原文):
 * ```
 * "(C108) [作者] 标题（原作：X）[DL]"     -> "[作者] 标题"
 * "[作者] 真正的[漫画]标题 [中文翻译]"    -> "[作者] 真正的[漫画]标题"
 * "[作者] 标题"                          -> "[作者] 标题"
 * ```
 *
 * 为什么 CBZ 名保留作者前缀而 `<Title>` 不保留:文件名用于在文件管理器里辨认,
 * 作者是有用信息;`<Title>` 是阅读器元数据,作者另写 `<Writer>`,重复会污染。
 */
fun cleanCbzBaseName(raw: String): String {
    val stripped = raw.trim()
    val withoutLeadingTag = LEADING_TAG_PAREN_RE.replace(stripped, "")
    if (!withoutLeadingTag.startsWith("[")) {
        return stripOriginalWork(withoutLeadingTag)
    }
    val close = withoutLeadingTag.indexOf(']')
    if (close < 0) {
        return stripOriginalWork(withoutLeadingTag)
    }
    val writerPart = withoutLeadingTag.substring(0, close + 1).trim()
    val rest = withoutLeadingTag.substring(close + 1).trim()
    val clean = stripOriginalWork(rest)
    return "$writerPart $clean".trim()
}

/**
 * 清理作者名:剥掉包裹的方括号、括号原作,以及可能残留的末尾标注。
 *
 * 例:
 * ```
 * "[作者]谏山创(原作:某人)(电子版)"  -> "谏山创"
 * "[Cior (Ken-1)]"                   -> "Cior (Ken-1)"
 * "尾田荣一郎"                       -> "尾田荣一郎"
 * ""                                 -> ""
 * ```
 *
 * ## 两种方括号形态的处理**刻意不同**
 *
 * - **方括号只是标记前缀**(`[作者]谏山创(原作:某人)`)⇒ 剥掉整段标记,再把剩下的
 *   括号附注全部剥掉。这里的括号在署名**之外**,是「原作/电子版」这类附注。
 * - **整体被方括号包裹**(`[Cior (Ken-1)]`)⇒ 只去掉两侧方括号,**保留内部括号**。
 *   同人本的主流体例 `[社团名 (画师名)]` 里,括号内就是署名的一部分,剥掉只剩社团名,
 *   作者信息反而丢失。
 *
 * 曾经两种形态都走 `stripOriginalWork`,结果 `[Cior (Ken-1)]` 被清成 `Cior` ——
 * 由 `DerivedNamesTest.真实素材的四种形态互相不同` 抓出。
 */
fun cleanWriterName(raw: String): String {
    val stripped = raw.trim()
    if (stripped.isEmpty()) return ""
    // 整体被方括号包裹(如 "[Cior (Ken-1)]"):只去掉两侧方括号,**内部括号保留** ——
    // 那是署名的一部分(`[社团 (画师)]` 是主体例),不是附注。
    if (stripped.startsWith("[") && stripped.endsWith("]") && stripped.length >= 2) {
        return stripped.substring(1, stripped.length - 1).trim()
    }
    val withoutBrackets = when {
        // 方括号只是一段标记前缀(如 "[作者]谏山创(原作:某人)"):剥掉整段标记。
        // 曾写成 removePrefix("["),那样会留下 "作者]谏山创" 的残渣。
        stripped.startsWith("[") && stripped.indexOf(']') >= 0 ->
            stripped.substring(stripped.indexOf(']') + 1).trim()
        else -> stripped
    }
    // 标记前缀之后的括号在署名**之外**,是「原作/电子版」这类附注 ⇒ 全剥。
    return stripOriginalWork(withoutBrackets)
}
