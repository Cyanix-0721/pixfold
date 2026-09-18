package com.pixfold.d1.domain.naming

import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NameProposal
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.model.ProposalWarningType

/** 改名操作状态(规格 §4.6)。 */
enum class OpStatus(val label: String) {
    Ok("将重命名"),
    Skipped("将跳过"),
}

data class RenameOp(val proposal: NameProposal, val status: OpStatus, val reason: String?) {
    val id: String get() = proposal.image.id
    val finalName: String get() = proposal.finalName
    val isSkipped: Boolean get() = status == OpStatus.Skipped
}

/**
 * 改名计划(规格 §6.4)。
 *
 * - 含 Duplicate 或 IllegalChar → `Skipped`,**reason 取第一个**冲突警告的 message;
 * - 其余 `Ok`;
 * - CaseCollision / TooLong **不导致跳过**,仅警告。
 */
fun buildRenamePlan(proposals: List<NameProposal>): List<RenameOp> = proposals.map { p ->
    if (p.hasConflict) {
        val first = p.warnings.first {
            it.type == ProposalWarningType.Duplicate || it.type == ProposalWarningType.IllegalChar
        }
        RenameOp(p, OpStatus.Skipped, first.message)
    } else {
        RenameOp(p, OpStatus.Ok, null)
    }
}

// ---- 覆盖(override)的三个纯函数入口(规格 §6.3) ----

/**
 * 设置/撤销逐项覆盖。
 * [name] 为 null 或**空** → 移除覆盖(清空输入框 = 撤销覆盖,**不是**"改成空名")。
 */
fun setOverride(state: NamingState, id: String, name: String?): NamingState {
    val next = if (name.isNullOrEmpty()) {
        state.overrides - id
    } else {
        state.overrides + (id to name)
    }
    return state.copy(overrides = next)
}

/**
 * 改命名结构。**覆盖不受影响** —— 覆盖独立于 scheme 存储,
 * 这正是"改结构后覆盖不丢失"的机制(§6.3);无需在 UI 小心维护。
 */
fun updateScheme(state: NamingState, scheme: NamingScheme): NamingState = state.copy(scheme = scheme)

/**
 * 用户显式指定根目录名 -> 记为"逐项例外",不再跟随集合。
 * 传入 null/空 -> **撤销覆盖,恢复跟随集合**(清空输入框 = 回到默认,与 override 同一约定)。
 */
fun setRootDirName(state: NamingState, name: String?): NamingState =
    state.copy(rootDirNameOverride = name?.takeIf { it.isNotEmpty() })

/** 恢复"根目录名跟随集合"(清除逐项例外)。 */
fun clearRootDirNameOverride(state: NamingState): NamingState = state.copy(rootDirNameOverride = null)

/** 在**已生效**的 scheme 上改根目录名:同时写进 scheme 与 override,保证立刻可见且后续跟随被关闭。 */
fun setRootDirNameOnScheme(state: NamingState, name: String): NamingState =
    state.copy(
        scheme = state.scheme.copy(rootDirName = name),
        rootDirNameOverride = name.takeIf { it.isNotEmpty() },
    )

/** 改单个组件(按下标);越界则原样返回。 */
fun updateComponent(state: NamingState, index: Int, transform: (NameComponent) -> NameComponent): NamingState {
    val components = state.scheme.components
    if (index !in components.indices) return state
    val next = components.toMutableList().also { it[index] = transform(it[index]) }
    return state.copy(scheme = state.scheme.copy(components = next))
}

/** 开关某组件(启用/停用)。 */
fun toggleComponent(state: NamingState, index: Int): NamingState =
    updateComponent(state, index) { it.copy(enabled = !it.enabled) }

/** 追加一个组件(规格:组件数量无上限,由 UI 决定呈现)。 */
fun addComponent(state: NamingState, kind: NameComponentKind, text: String = ""): NamingState =
    state.copy(scheme = state.scheme.copy(components = state.scheme.components + NameComponent(kind, text = text)))

/** 移除某组件(至少保留 1 个,避免空结构)。 */
fun removeComponent(state: NamingState, index: Int): NamingState {
    if (state.scheme.components.size <= 1) return state
    if (index !in state.scheme.components.indices) return state
    val next = state.scheme.components.toMutableList().also { it.removeAt(index) }
    return state.copy(scheme = state.scheme.copy(components = next))
}

/**
 * 移动组件(验收第 8 项的"顺序可调")。[delta] 为 -1 上移 / +1 下移。
 * 越界时**夹取后原样返回**(不循环、不报错)。
 */
fun moveComponent(state: NamingState, index: Int, delta: Int): NamingState {
    val components = state.scheme.components
    val target = index + delta
    if (index !in components.indices || target !in components.indices) return state
    val next = components.toMutableList().also { list ->
        val item = list.removeAt(index)
        list.add(target, item)
    }
    return state.copy(scheme = state.scheme.copy(components = next))
}

/** 恢复建议值:清除该 id 的覆盖,输入框回到 proposedName。 */
fun clearOverride(state: NamingState, id: String): NamingState = state.copy(overrides = state.overrides - id)
