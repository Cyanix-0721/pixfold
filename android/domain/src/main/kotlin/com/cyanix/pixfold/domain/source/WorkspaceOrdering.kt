package com.cyanix.pixfold.domain.source

/**
 * 一个分区内部按 [WorkspaceEntry.pinned] **稳定**排序：钉住的排最前，等键项保持原相对顺序。
 *
 * 用 `sortedByDescending` 而非自己写 partition+concat：Kotlin 的排序是稳定的，
 * 手写版本一旦写成 `sortedBy { !it.pinned }` 之外的任何变体都容易丢掉稳定性，
 * 而稳定性正是「多个图钉之间仍可互相拖拽互换」的唯一依据。
 */
fun orderedForDisplay(entries: List<WorkspaceListing>): List<WorkspaceListing> =
    entries.sortedByDescending { it.entry.pinned }

/**
 * 拖拽提交：把 [fromUri] 这条移到 [toUri] 当前所在的位置。
 *
 * 返回 `null` 表示这次拖拽应被丢弃（同一位置 / URI 不在序列里）。
 * **跨分区的拒绝由调用方做** —— 纯函数看不见 `granted`，把分区判断塞进来会让这里
 * 同时依赖存储与运行时两类事实。
 *
 * ## 为什么入参是 treeUri 而不是下标
 * 渲染顺序经过 pinned 稳定排序，与存储下标没有任何线性对应关系。
 *
 * ## 为什么「先删后插」且目标下标不减一
 * 设 `si < sj`：移除后原 `sj` 的元素前移到 `sj - 1`，而「插到它前面」要求的新下标
 * 正好是 `sj - 1 + 1 = sj`；`si > sj` 时 `sj` 不受影响。两支同一个表达式，
 * 加特判反而会在其中一支上错位。
 */
fun movedOrder(
    store: List<WorkspaceEntry>,
    fromUri: String,
    toUri: String,
): List<WorkspaceEntry>? {
    if (fromUri == toUri) return null
    val si = store.indexOfFirst { it.handle.treeUri == fromUri }
    val sj = store.indexOfFirst { it.handle.treeUri == toUri }
    if (si < 0 || sj < 0) return null

    val out = store.toMutableList()
    val item = out.removeAt(si)
    out.add(sj.coerceIn(0, out.size), item)
    return out
}
