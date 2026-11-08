package com.cyanix.pixfold.data.saf

/**
 * 清理临时文件失败时该记哪一级日志。
 *
 * ## 为什么需要这个判定（真机缺陷，2026-10-27 起每轮必现）
 *
 * 更新成功后删 `原名.cbz.updating`，日志里必然出现
 * `W SafMetaUpdater: 清理临时文件失败` + `IllegalStateException: Failed to delete …`，
 * 但三个时间点实测文件**都已不在**。
 *
 * AOSP `com.android.internal.content.FileSystemProvider.deleteDocument` 的顺序是：
 *
 * ```
 * if (file.exists() && !file.delete()) throw new IllegalStateException("Failed to delete " + file);
 * onDocIdChanged(docId);
 * onDocIdDeleted(docId);
 * updateMediaStore(getContext(), visibleFile);   // ← 这一步抛，文件其实已经没了
 * ```
 *
 * 也就是说**抛点晚于真正的删除**，异常类型不能当判据（抛的还是 RuntimeException，
 * `DocumentsContract.rethrowIfNecessary` 会原样透给客户端）。
 *
 * 判据改用结果：事后查一次目标还在不在。
 * - 已经不在 ⇒ 目标达成，记一行 notice，**不带堆栈**（否则每次更新都刷一段红字）
 * - 还在 ⇒ 真的没删掉，记 warning 带堆栈，这是真缺陷
 *
 * 抽成纯函数只为离线可测：Robolectric 没有真实 `DocumentsProvider`。
 */
internal enum class CleanupLog { Silent, Notice, Warning }

/**
 * @param outcome [DocumentsContract.deleteDocument] 的返回值；抛异常时传 null
 * @param stillExists 事后查询目标是否仍在；**null = 查不出来**
 */
internal fun cleanupLogFor(outcome: Boolean?, stillExists: Boolean?): CleanupLog = when {
    outcome == true -> CleanupLog.Silent
    // 查不出来时保守当「还在」：宁可多一条 warning，也不能把真失败藏成「已忽略」。
    stillExists == false -> CleanupLog.Notice
    else -> CleanupLog.Warning
}