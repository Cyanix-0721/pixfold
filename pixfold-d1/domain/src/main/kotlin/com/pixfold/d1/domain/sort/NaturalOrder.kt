package com.pixfold.d1.domain.sort

/**
 * 记号扫描:阿拉伯数字段,或**中文数字段**(`一`~`九` 与 `十`)。
 *
 * 中文数字只认这两类字符(用户裁决 2026-09-25):`百`/`千`/`万`/`〇` 等**不**归一化,
 * 它们会退化成普通文本记号,按码点参与比较。
 */
private val NUMERAL_RUN = Regex("""\d+|[一二三四五六七八九十]+""")

private val CHINESE_DIGITS = mapOf(
    '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
    '六' to 6, '七' to 7, '八' to 8, '九' to 9,
)

/**
 * 把一段中文数字解析成数值(仅 `1..99`),无法解析时返回 `null`(调用方保留原字面量)。
 *
 * 支持的形态:`十`(10)/ `十X`(11..19)/ `X十`(20,30,…,90)/ `X十Y`(21..99)/ `X`(1..9)。
 * 连续罗列(如 `一二`、`十十`)不是合法数字,一律不归一化 —— 宁可不排序也不要猜。
 */
private fun parseChineseNumeral(s: String): Int? {
    val ten = s.indexOf('十')
    if (ten < 0) return if (s.length == 1) CHINESE_DIGITS[s[0]] else null
    val head = s.substring(0, ten)
    val tail = s.substring(ten + 1)
    if (head.length > 1 || tail.length > 1) return null
    val tens = if (head.isEmpty()) 1 else CHINESE_DIGITS[head[0]] ?: return null
    val ones = if (tail.isEmpty()) 0 else CHINESE_DIGITS[tail[0]] ?: return null
    return tens * 10 + ones
}

/** 数字段归一化成阿拉伯数字串;无法解析(连续中文数字罗列)时原样返回。 */
private fun normalizeNumeral(token: String): String =
    if (token[0] in '0'..'9') token else parseChineseNumeral(token)?.toString() ?: token

/**
 * 把字符串切成「非数字段 / 数字段」交替的记号序列。
 *
 * 数字段含**阿拉伯数字**与**中文数字**(归一化为阿拉伯数字,故 `第十话` 与 `第10话` 相等)。
 * 归一化必须发生在**分词阶段**而不是比较阶段:`compareNaturalTokens` 还会被预计算过的
 * 记号序列调用(`SortItems`),分词是两者唯一的共同入口。
 *
 * 注意:必须用 findAll 手动扫描,不能用 Regex.split —— Kotlin 的 split 会丢弃捕获组,
 * 导致数字段丢失、比较恒为 0(排序静默返回原序)。见规格 §13 与 toolchain-evidence。
 */
fun tokenizeNatural(s: String): List<String> {
    val tokens = mutableListOf<String>()
    var last = 0
    for (m in NUMERAL_RUN.findAll(s)) {
        if (m.range.first > last) tokens += s.substring(last, m.range.first)
        tokens += normalizeNumeral(m.value)
        last = m.range.last + 1
    }
    if (last < s.length) tokens += s.substring(last)
    return tokens
}

private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

/** 去前导零;全零退化为 "0"。 */
private fun stripLeadingZeros(s: String): String {
    val trimmed = s.trimStart('0')
    return if (trimmed.isEmpty()) "0" else trimmed
}

/**
 * 自然排序:数字段按数值比较,非数字段按单字符码点比较。
 * 语义对齐归档原型 naturalCompare,但对超长数字串不溢出。
 */
fun naturalCompare(a: String, b: String): Int =
    compareNaturalTokens(tokenizeNatural(a), tokenizeNatural(b))

/**
 * 比较两个**已分词**的记号序列。
 *
 * 抽出来是为了让调用方能**预计算分词**:排序时每次比较都重新分词是纯浪费
 * (n=3000 时约 1.7 万次比较 → 本可只分词 3000 次;见 2026-09-17 性能实测)。
 * 比较语义**只有这一份实现**,`naturalCompare` 与 `sortItems` 共用,避免两处逻辑漂移。
 */
fun compareNaturalTokens(ta: List<String>, tb: List<String>): Int {
    val n = minOf(ta.size, tb.size)
    for (i in 0 until n) {
        val x = ta[i]
        val y = tb[i]
        if (isDigits(x) && isDigits(y)) {
            val nx = stripLeadingZeros(x)
            val ny = stripLeadingZeros(y)
            // 1) 数值大小:先比有效长度,再比字典序
            val c = if (nx.length != ny.length) {
                nx.length.compareTo(ny.length)
            } else {
                nx.compareTo(ny)
            }
            if (c != 0) return c
            // 2) 数值相等 -> 原始位数少者在前
            if (x.length != y.length) return x.length.compareTo(y.length)
        } else {
            // 非数字(或一边数字一边非数字):逐字符码点比较
            val m = minOf(x.length, y.length)
            for (k in 0 until m) {
                val c = x[k].code.compareTo(y[k].code)
                if (c != 0) return c
            }
            if (x.length != y.length) return x.length.compareTo(y.length)
        }
    }
    return ta.size.compareTo(tb.size)
}
