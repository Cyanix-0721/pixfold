package com.cyanix.pixfold.domain.source

/**
 * 工作区列表里的一条**存储事实**。
 *
 * [pinned] 只影响渲染顺序（见 [orderedForDisplay]），**不改变存储位置** ——
 * 拖拽时若为钉住与非钉住维护两条序列，两者迟早漂移。
 */
data class WorkspaceEntry(
    val handle: WorkspaceHandle,
    val pinned: Boolean = false,
)
