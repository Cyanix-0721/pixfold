package com.cyanix.pixfold.domain.plan

import com.cyanix.pixfold.domain.model.NameProposal
import com.cyanix.pixfold.domain.model.effectiveScheme
import com.cyanix.pixfold.domain.naming.RenameOp
import com.cyanix.pixfold.domain.naming.buildRenamePlan
import com.cyanix.pixfold.domain.naming.buildProposals
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.sort.PageOrderState
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey

/**
 * 工作流 A 的整库改名计划(规格 §6.4 末条:「工作流 A 的计划遍历**所有集合**拼接成整库计划」)。
 *
 * **为什么必须整库**:命名结构与覆盖是**批次级**(§6.3),一个根目录名下可能对应多个集合;
 * 若计划只算当前集合,用户在当前集合看不到其它集合的重名冲突,执行后才发现被跳过。
 *
 * 顺序 = [collections] 顺序、集合内 = 该集合当前页序。计划行序与报告行序一致,
 * 用户才能拿报告逐行核对。
 */
data class LibraryRenamePlan(
    val ops: List<RenameOp>,
    val collectionNames: Map<String, String>,
) {
    val total: Int get() = ops.size
    val skipped: Int get() = ops.count { it.isSkipped }
    val runnable: Int get() = total - skipped
    val counts: PlanCounts get() = PlanCounts(total, skipped)
}

/**
 * 构建整库改名计划。
 *
 * 每个集合用**同一份** [namingState](批次级),但根目录名按 §6.3 的"逐项例外"
 * 语义**各自跟随所属集合**(见 `effectiveScheme`)—— 这正是 P4 定下的规则,
 * 计划页必须遵守,否则计划上的名字会与实际执行的不一致。
 *
 * @param orders 集合 id → 页序;缺失的集合用其原始顺序兜底(不静默丢集合)
 */
fun buildLibraryRenamePlan(
    collections: List<ImageCollection>,
    orders: Map<String, PageOrderState>,
    namingState: NamingState,
): LibraryRenamePlan {
    val allOps = mutableListOf<RenameOp>()
    val names = mutableMapOf<String, String>()
    collections.forEach { collection ->
        names[collection.id] = collection.name
        val order = orders[collection.id]?.order ?: collection.images
        val proposals: List<NameProposal> =
            buildProposals(order, effectiveScheme(namingState, collection), overrides = namingState.overrides)
        allOps += buildRenamePlan(proposals)
    }
    return LibraryRenamePlan(allOps, names)
}

/**
 * 工作流 A 的危险动作(规格 §9.2:清理空目录,默认 `false`)。
 *
 * A 侧只有这一项危险动作 —— 与 B 侧的删除源目录/覆盖区分开,
 * 避免 UI 把"该工作流根本没有的动作"也列出来让用户确认。
 */
fun workflowADangerActions(cleanEmptyDirs: Boolean): Set<DangerAction> =
    if (cleanEmptyDirs) setOf(DangerAction.CleanEmptyDirs) else emptySet()

/** 工作流 A 通用确认框要列出的影响范围(键 + 参数,见 `text/Msg.kt`)。 */
fun renameSummaryLines(plan: LibraryRenamePlan, dangers: Set<DangerAction>): List<Msg> = buildList {
    add(Msg(TextKey.RenameSummary, listOf(plan.runnable, plan.skipped)))
    add(Msg(TextKey.RenameSummarySourceKept))
    if (DangerAction.CleanEmptyDirs in dangers) {
        add(Msg(TextKey.RenameSummaryCleanEmptyDirs))
    }
}
