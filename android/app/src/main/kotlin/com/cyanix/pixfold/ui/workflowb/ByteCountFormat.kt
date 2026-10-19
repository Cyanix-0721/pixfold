package com.cyanix.pixfold.ui.workflowb

import kotlin.math.round
import kotlin.math.roundToLong

/**
 * 字节数 → 给用户看的读数（1024 进制，最多保留一位小数）。
 *
 * 为什么不复用某个已有的格式化函数：仓库里没有。`SourceItem.sizeLabel` 是另一套
 * 「单张图片多大」的文案，语义不同且不带单位换算；`android.text.format.Formatter`
 * 按系统 locale 输出，测试断言会随环境漂移。
 *
 * 两个边界是踩过的：
 * - **先舍入再判进位**。`1048535 B` = 1023.96 KB，若按原始值判「还没到 1024」就不进位，
 *   再舍入得到 `1024.0 KB` —— 一个比上一级单位还大的读数。故进位判据用舍入后的值。
 * - **负值归零**。平台层重复回调或 `StatFs` 的极端值可能给出负数，界面上不能出现 `-1 B`。
 *
 * 小数分隔符固定 `.`，不受 locale 影响（中文/英文都用点号）。
 */
internal fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (unitIndex < units.lastIndex && (value * 10).roundToLong() >= 1024 * 10) {
        value /= 1024
        unitIndex++
    }
    val rounded = round(value * 10) / 10.0
    val text = if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
    return "$text ${units[unitIndex]}"
}
