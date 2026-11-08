package com.cyanix.pixfold.data.saf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 清理临时文件时**该怎么记日志**的判定。
 *
 * ## 为什么需要它
 *
 * 真机（2026-10-27 起每轮必现）：更新成功后清理 `.updating` 临时文件，
 * provider 抛 `IllegalStateException: Failed to delete …`，但三个时间点实测文件都已不在。
 *
 * AOSP 的 `com.android.internal.content.FileSystemProvider.deleteDocument` 顺序是
 * 「`file.delete()` → `onDocIdChanged` → `updateMediaStore`」，抛点可以晚于真正的删除，
 * 所以**异常类型不能当判据**。这里改用结果当判据：事后查一次目标还在不在。
 *
 * - 已经不在 ⇒ 目标达成，记一行 notice（**不带堆栈**，否则每次更新都刷一段红字）
 * - 还在 ⇒ 真的没删掉，记 warning（带堆栈，这是真缺陷）
 *
 * 抽成纯函数是为了离线钉死：Robolectric 没有真实 `DocumentsProvider`，
 * 走不到真实的删除路径。
 */
class SafCleanupLogTest {

    @Test
    fun `删掉了就什么都不记 正常路径不该有日志噪音`() {
        assertEquals(CleanupLog.Silent, cleanupLogFor(outcome = true, stillExists = false))
        // provider 返回 true 但查询说还在（不一致）：仍按成功处理，不打扰用户
        assertEquals(CleanupLog.Silent, cleanupLogFor(outcome = true, stillExists = true))
    }

    @Test
    fun `抛异常但文件已经不在 只记 notice 不带堆栈`() {
        assertEquals(CleanupLog.Notice, cleanupLogFor(outcome = null, stillExists = false))
    }

    @Test
    fun `返回 false 但文件已经不在 同样只记 notice`() {
        assertEquals(CleanupLog.Notice, cleanupLogFor(outcome = false, stillExists = false))
    }

    @Test
    fun `文件还在就是真失败 记 warning`() {
        assertEquals(CleanupLog.Warning, cleanupLogFor(outcome = null, stillExists = true))
        assertEquals(CleanupLog.Warning, cleanupLogFor(outcome = false, stillExists = true))
    }

    @Test
    fun `查不出来时保守当还在 别把真失败藏成已忽略`() {
        assertEquals(CleanupLog.Warning, cleanupLogFor(outcome = null, stillExists = null))
        assertEquals(CleanupLog.Warning, cleanupLogFor(outcome = false, stillExists = null))
    }
}