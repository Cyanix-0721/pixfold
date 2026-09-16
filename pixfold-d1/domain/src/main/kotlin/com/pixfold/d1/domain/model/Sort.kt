package com.pixfold.d1.domain.model

/** 枚举声明顺序 = UI 下拉顺序(HANGOFF §4 排序键定位,2026-09-10 用户定序),不得重排。 */
enum class SortField(val label: String) {
    NaturalName("文件名(自然)"),
    FileName("文件名(字典)"),
    DirName("目录名"),
    Modified("修改时间"),
    Created("创建时间"),
    Size("文件大小"),
}

data class SortKey(val field: SortField, val ascending: Boolean)

/** keys.size 必须在 1..4 之间(至少 1 级、最多 4 级)。 */
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
