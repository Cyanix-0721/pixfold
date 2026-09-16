package com.pixfold.d1.domain.workflowa

import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.defaultRule
import com.pixfold.d1.domain.sort.PageOrderState
import com.pixfold.d1.domain.sort.applyRule
import com.pixfold.d1.domain.sort.pageOrderOf

/**
 * 工作流 A 的批次级状态(规格 §5.4 / 验收清单第 7 项)。
 *
 * "批次统一 + 逐项例外"在这里的落法:
 *  - [batchRule] 是批次默认排序规则,作用于**所有非独立**集合;
 *  - [customRuleCollections] 记录被标记为"本组独立"的集合 id;
 *  - 对非独立集合应用规则 -> 更新 [batchRule] 并重排**所有非独立**集合;
 *  - 对独立集合应用规则 -> 只重排该集合,[batchRule] 不受影响。
 *
 * 不可变数据结构 + 纯函数(与 §5 领域层风格一致)。
 */
data class WorkflowAState(
    val collections: List<ImageCollection>,
    val orders: Map<String, PageOrderState>,
    val batchRule: SortRule,
    val customRuleCollections: Set<String>,
) {
    val hasManualAnywhere: Boolean get() = orders.values.any { it.hasManual }
    val manualCountTotal: Int get() = orders.values.sumOf { it.manualCount }
    val pinnedCountTotal: Int get() = orders.values.sumOf { it.pinnedCount }

    /** 某集合当前生效的规则:独立集合用自己的,否则用批次默认。 */
    fun effectiveRule(collectionId: String): SortRule =
        if (collectionId in customRuleCollections) {
            orders[collectionId]?.rule ?: batchRule
        } else {
            batchRule
        }
}

fun workflowAStateOf(
    collections: List<ImageCollection>,
    batchRule: SortRule = defaultRule(),
): WorkflowAState = WorkflowAState(
    collections = collections,
    orders = collections.associate { it.id to pageOrderOf(it.images, batchRule) },
    batchRule = batchRule,
    customRuleCollections = emptySet(),
)

/**
 * 对某集合应用排序规则。
 * 非独立 -> 更新批次默认并重排所有非独立集合;独立 -> 只重排该集合。
 */
fun applyRuleToCollection(
    state: WorkflowAState,
    collectionId: String,
    rule: SortRule,
): WorkflowAState {
    val collection = state.collections.firstOrNull { it.id == collectionId } ?: return state

    return if (collectionId in state.customRuleCollections) {
        val current = state.orders[collectionId] ?: pageOrderOf(collection.images, rule)
        state.copy(orders = state.orders + (collectionId to applyRule(current, rule)))
    } else {
        val nextBatch = rule
        val nextOrders = state.orders.mapValues { (id, order) ->
            if (id in state.customRuleCollections) {
                order // 独立集合保持自己的规则与顺序
            } else {
                val col = state.collections.firstOrNull { it.id == id } ?: return@mapValues order
                applyRule(pageOrderOf(col.images, nextBatch), nextBatch)
            }
        }
        state.copy(batchRule = nextBatch, orders = nextOrders)
    }
}

/** 切换"本组独立":加入时保留当前顺序;移除时立刻用批次规则重排该集合。 */
fun toggleCustomRule(state: WorkflowAState, collectionId: String): WorkflowAState {
    val collection = state.collections.firstOrNull { it.id == collectionId } ?: return state
    val current = state.orders[collectionId] ?: pageOrderOf(collection.images, state.batchRule)

    return if (collectionId in state.customRuleCollections) {
        // 取消独立 -> 用批次规则重排
        state.copy(
            customRuleCollections = state.customRuleCollections - collectionId,
            orders = state.orders + (collectionId to applyRule(current, state.batchRule)),
        )
    } else {
        // 转为独立 -> 保留当前顺序(仅把 rule 记为该集合自己的规则)
        state.copy(
            customRuleCollections = state.customRuleCollections + collectionId,
            orders = state.orders + (collectionId to current.copy(rule = current.rule)),
        )
    }
}

/** 重排单个集合的页序(拖拽/图钉入口),不改规则。 */
fun updatePageOrder(
    state: WorkflowAState,
    collectionId: String,
    next: PageOrderState,
): WorkflowAState = state.copy(orders = state.orders + (collectionId to next))
