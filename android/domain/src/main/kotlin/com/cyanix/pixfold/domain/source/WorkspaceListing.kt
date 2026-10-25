package com.cyanix.pixfold.domain.source

/**
 * 列表项在 UI 层的完整事实：存储事实（[entry]）+ 运行时事实（[granted]）。
 *
 * **为什么 [granted] 不存进 [WorkspaceEntry]**：持久授权是系统状态，用户在系统设置或
 * 别的应用里撤销它时我们收不到通知；把它持久化等于存一个随时会过期的判断。
 */
data class WorkspaceListing(
    val entry: WorkspaceEntry,
    val granted: Boolean,
)
