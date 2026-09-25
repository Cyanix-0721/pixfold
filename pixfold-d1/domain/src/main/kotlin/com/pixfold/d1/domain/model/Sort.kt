package com.pixfold.d1.domain.model

import com.pixfold.d1.domain.text.TextKey

/**
 * 排序键。
 *
 * 枚举声明顺序 = UI 下拉顺序(HANGOFF §4 排序键定位,2026-09-10 用户定序),不得重排。
 *
 * [textKey] 指向 app 侧资源表的键 —— 领域层不持有译文,故语言不会在建计划时被冻住。
 */
enum class SortField(val textKey: TextKey) {
    NaturalName(TextKey.SortNaturalName),
    FileName(TextKey.SortFileName),
    DirName(TextKey.SortDirName),
    Modified(TextKey.SortModified),
    Created(TextKey.SortCreated),
    Size(TextKey.SortSize),
}

data class SortKey(val field: SortField, val ascending: Boolean)

/**
 * keys.size 必须在 1..4 之间(至少 1 级、最多 4 级)。
 *
 * `require` 的消息是**开发者诊断**(面向日志与堆栈),不是用户可见文案,故保持中文常量。
 */
data class SortRule(val keys: List<SortKey>) {
    init {
        require(keys.isNotEmpty()) { "排序规则至少需要 1 级" }
        require(keys.size <= MAX_LEVELS) { "排序规则最多 $MAX_LEVELS 级" }
    }

    companion object {
        const val MAX_LEVELS = 4
    }
}

fun defaultRule(): SortRule = SortRule(
    listOf(SortKey(SortField.DirName, true), SortKey(SortField.NaturalName, true)),
)
