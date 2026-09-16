package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.defaultRule

/**
 * 页序三重状态(规格 §5.3):
 * - 自动层: rule + order(由 sortItems 算出)
 * - 人工层: manualIds —— 标记用户拖拽过的项;applyRule 时清空
 * - 固定层: pinnedIds —— 图钉;applyRule 时保留
 */
data class PageOrderState(
    val images: List<SourceItem>,
    val rule: SortRule,
    val order: List<SourceItem>,
    val manualIds: Set<String> = emptySet(),
    val pinnedIds: Set<String> = emptySet(),
) {
    val hasManual: Boolean get() = manualIds.isNotEmpty()
    val manualCount: Int get() = manualIds.size
    val pinnedCount: Int get() = pinnedIds.size
    fun isManual(id: String): Boolean = id in manualIds
    fun isPinned(id: String): Boolean = id in pinnedIds
}

fun pageOrderOf(images: List<SourceItem>, rule: SortRule = defaultRule()): PageOrderState =
    PageOrderState(images = images, rule = rule, order = sortItems(images, rule))

/**
 * 唯一重排入口(规格 §5.3)。targetIndex 是"移除源项之后"的插入位。
 * 边界:不存在的 id / 拖到自己格 -> 原样返回;越界 -> 夹取。
 */
fun moveItemTo(state: PageOrderState, id: String, targetIndex: Int): PageOrderState {
    val from = state.order.indexOfFirst { it.id == id }
    if (from < 0) return state
    val target = targetIndex.coerceIn(0, state.order.size - 1)
    if (from == target) return state
    val next = state.order.toMutableList()
    val item = next.removeAt(from)
    next.add(target, item)
    return state.copy(order = next, manualIds = state.manualIds + id)
}

fun togglePin(state: PageOrderState, id: String): PageOrderState {
    if (state.order.none { it.id == id }) return state
    val next = if (id in state.pinnedIds) state.pinnedIds - id else state.pinnedIds + id
    return state.copy(pinnedIds = next)
}

/**
 * 应用排序规则(规格 §5.3):固定项按"记录下标优先、冲突向左找空位"落位,
 * 其余项按新规则填满空位;manualIds 清空,pinnedIds 保留。
 */
fun applyRule(state: PageOrderState, rule: SortRule): PageOrderState {
    // 1) 记录固定项当前下标(按下标升序处理 -> 结果确定)
    val pinnedAt = state.order.withIndex()
        .filter { (_, item) -> item.id in state.pinnedIds }
        .associate { (i, item) -> i to item }

    // 2) 按新规则排序,剔除固定项
    val sorted = sortItems(state.images, rule)
    val rest = sorted.filter { it.id !in state.pinnedIds }

    // 3) 固定项优先落位,被占则向左找空位
    val slots = arrayOfNulls<SourceItem>(state.images.size)
    for ((recorded, item) in pinnedAt.entries.sortedBy { it.key }) {
        var idx = minOf(recorded, slots.size - 1)
        while (idx >= 0 && slots[idx] != null) idx--
        if (idx < 0) continue
        slots[idx] = item
    }

    // 4) 其余项按新规则顺序填洞
    var ri = 0
    for (i in slots.indices) {
        if (slots[i] == null) slots[i] = rest[ri++]
    }

    return state.copy(
        rule = rule,
        order = slots.filterNotNull(),
        manualIds = emptySet(),
        pinnedIds = state.pinnedIds,
    )
}

/** 放弃人工调整但保留固定项位置(走同一条 applyRule 路径)。 */
fun resetToAuto(state: PageOrderState): PageOrderState = applyRule(state, state.rule)
