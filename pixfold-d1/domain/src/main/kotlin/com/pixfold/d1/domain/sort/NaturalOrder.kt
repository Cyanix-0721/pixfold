package com.pixfold.d1.domain.sort

private val DIGIT_RUN = Regex("""\d+""")

/**
 * 把字符串切成「非数字段 / 数字段」交替的记号序列。
 *
 * 注意:必须用 findAll 手动扫描,不能用 Regex.split —— Kotlin 的 split 会丢弃捕获组,
 * 导致数字段丢失、比较恒为 0(排序静默返回原序)。见规格 §13 与 toolchain-evidence。
 */
fun tokenizeNatural(s: String): List<String> {
    val tokens = mutableListOf<String>()
    var last = 0
    for (m in DIGIT_RUN.findAll(s)) {
        if (m.range.first > last) tokens += s.substring(last, m.range.first)
        tokens += m.value
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
fun naturalCompare(a: String, b: String): Int {
    val ta = tokenizeNatural(a)
    val tb = tokenizeNatural(b)
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
